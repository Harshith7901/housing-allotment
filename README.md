# Housing allotment backend

Six hundred flats, about four thousand applications, and a final list that will be
questioned by an applicant, by a newspaper, and possibly in court.

The brief asks for the backend. The thing that makes this problem hard is not the lottery —
that is twenty lines — it is that **the output is not a list of 600 names. The output is a
decision that survives being disbelieved.** Every design choice below follows from that.

Three audiences, three different questions:

| Who | Asks | Answered by |
|---|---|---|
| An applicant | "Why not me?" | `GET /api/public/applications/{id}/explanation` — their ticket, pool, rank, the seat count, a reason code, and the arithmetic to recompute their own ticket |
| A newspaper | "Show me the process" | `GET /api/public/draws/{id}/verification-bundle` — every input to the decision, in the exact bytes that were hashed |
| A court | "Prove nothing was changed" | A hash-chained audit log, a frozen and hashed roll, a commit–reveal seed, and a result recomputed from first principles on every read |

---

## Running it

### The fastest look (no Docker, no MySQL, no network after the build)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

In-memory H2. On startup it loads ~4,020 applications (including 420 planted
re-submissions with realistic transcription errors) and 600 flats, then runs the entire
lifecycle — deduplicate, review, verify, freeze, commit, draw, publish, allot — and logs
the URLs to try. Roughly 20 seconds. Shrink it if you are impatient:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--housing.demo.households=400 --housing.demo.units=80"
```

### The production shape

```bash
docker compose up --build
```

MySQL 8, Flyway migrations, the same demo scheme, on <http://localhost:8080>.
Swagger UI at `/swagger-ui.html`.

### The core, with nothing but a JDK

The whole decision core is framework-free plain Java. It compiles and runs an end-to-end
simulation over the brief's population without Maven, Spring, a database, or a network:

```bash
javac -d out $(find src/main/java/dev/harshith/housing/core -name '*.java')
java -cp out dev.harshith.housing.core.sim.Simulation
```

43 assertions over ~4,000 synthetic applications: apportionment, deduplication precision
and recall, commit–reveal, determinism, tamper detection, unit assignment, waitlist
promotion, the audit chain. It exits non-zero on any failure, so it works as a CI gate on
its own. It also writes a verification bundle to `target/verification-bundle`.

### Verifying a draw the way an outsider would

```bash
python3 verify/verify.py target/verification-bundle
```

`verify.py` is a **second, independent implementation** of the published rules. It shares
no code with the service, imports nothing beyond the Python standard library, and reads
only the bundle. It recomputes the seed, every ticket, the quota arithmetic, the pool
rankings and every applicant's outcome, and reports the first line where its answer differs
from the published one.

Both implementations agree, line for line, across all 3,419 applicants on the demo roll.
That agreement is the single most useful test in the repository: a bug I put in the Java
would have to be reproduced identically in the Python to go unnoticed.

Try breaking it:

```bash
cp -r target/verification-bundle /tmp/forged
sed -i '0,/|NOT_SELECTED|/s//|SELECTED|/' /tmp/forged/result.txt   # forge one winner
python3 verify/verify.py /tmp/forged
```

It fails four different ways and names the application id.

### Tests

```bash
mvn verify
```

Core invariants (apportionment, allocation, deduplication, hash chain, commit–reveal,
canonical encodings) plus `SchemeLifecycleIT`, which drives the whole scheme through HTTP
and asserts that every gate holds: a correction after the freeze is refused, a draw runs
once, the officer who commits the seed cannot execute it, a clerk cannot verify their own
data entry, an auditor can read everything and write nothing.

---

## The four ideas that matter

### 1. The rules are versioned data, not code

A rule set is a published, immutable record with a hash, encoded as human-readable text
that reads like the gazette notification it mirrors:

```
version=2026-PHASE-1
totalUnits=600
openCode=OPEN
reserved=CAT_A|Reserved category A|15.0000
horizontal=WOMEN|Women applicants|30.0000
residency=PRIORITY_TIER|3
lapse=LAPSE_TO_OPEN
waitlistSize=150
```

`sha256sum rules.txt` is the `ruleSetHash` quoted in the notification. Every roll records
the version and hash it was frozen under, so *"you changed the rules half way through"* is
answered with a hash rather than a git blame. Amending a quota is a new published version,
not a deploy.

Text, not JSON, because a hash is only useful if a human can read exactly the bytes that
were hashed. JSON leaves key order, whitespace and number formatting free, so a
canonicalising serialiser would become load-bearing legal infrastructure.

### 2. The seed is a commit–reveal, so nobody chose the winners

The hard problem in a public lottery is not randomness, it is convincing a hostile audience
that nobody picked the outcome. `SecureRandom` proves nothing after the fact: whoever
chooses the seed chooses the winners.

```
commitment = SHA256("commit/1|" + nonce)                        published before the draw
seed       = SHA256("seed/1|" + rollHash + "|" + publicEntropy + "|" + nonce)
ticket(a)  = SHA256("ticket/1|" + seed + "|" + applicationId)
```

- The **roll hash** fixes who is in the draw. Edit the roll and the seed changes.
- The **nonce** is committed before the entropy value exists, so it cannot be tuned to a
  known outcome — and cannot be swapped afterwards without breaking the published
  commitment.
- The **public entropy** is a value the authority does not control, named in advance in the
  notification (a specified state lottery number, an index close, a named block hash).

Neither party can bias the result, and anyone can recompute it in one line of shell:

```bash
printf '%s' 'ticket/1|<seed>|<yourApplicationId>' | sha256sum
```

Ranking by SHA-256 ticket rather than shuffling a list buys three things: it is
reproducible in any language (a shuffle is reproducible only in this JDK), it is
per-applicant so a late arrival does not reshuffle everybody, and each person can check
their own ticket without trusting the whole result.

### 3. Every applicant gets an outcome, not just the winners

The draw writes ~3,400 rows per draw where 600 would do. That is the price of answering
*"why not me?"* in one indexed lookup, months later, with a ticket, a pool, a rank, a seat
count, a machine-readable reason code and a sentence of English.

```
Ranked 892 of 3419 in pool OPEN, which had 303 seats. The ticket ranked below the
number of available seats, and below the published waitlist length.
```

Displacement — the only act in the pipeline that removes somebody who was provisionally
selected — is recorded by name, rank and the quota that caused it, and puts that applicant
at the front of the waitlist.

### 4. Duplicates are linked, never deleted

Roughly a tenth of the applications are the same family applying twice "because they were
not sure the first one went through". The two possible mistakes are wildly asymmetric:
missing a duplicate gives one household two tickets, which is unfair and detectable later;
wrongly merging two households removes a real family from the draw, and they find out when
the list is published.

So the matcher links what is certain, queues what is plausible for a human, and records the
arithmetic for both:

- **Deterministic**: same government identifier, or same phone and date of birth → linked.
- **Deterministic veto**: two records that both carry an identifier and disagree can *never*
  be auto-linked, however high the fuzzy score. A father and son at one address share a
  name, a birthday and a phone; merging them drops a household.
- **Probabilistic**: weighted field agreement (Jaro–Winkler on names, day/month
  transposition tolerance on dates, edit distance on phones, token overlap on addresses),
  with the weight of any missing field **redistributed** rather than counted as
  disagreement. Treating blanks as disagreement is the classic record-linkage bug, and it
  under-links exactly the paper channel that needs help most.
- **Blocking** on five keys (identifier, phone, soundex+birth year, name prefix+birth date,
  ward+surname soundex) cuts 8,078,190 candidate pairs to 46,527 — a 99.4% reduction —
  without losing duplicates.
- **Transitive closure** by union–find, because a family that applied three times usually
  produces A–B and B–C but no direct A–C link.
- Duplicates are marked `SUPERSEDED`, keep their row and their id, and point at the
  application that carries the ticket. The **earliest submission survives**, because "the
  first one went through" is what the applicant believed when they applied again.

On the demo population: **98.6% of planted duplicates caught automatically, 7 pairs queued
for a human, and zero false merges** — verified against ground truth the matcher never
sees.

---

## Architecture

```
src/main/java/dev/harshith/housing/
  core/            zero framework imports, zero clock, zero I/O
    model/         Rules, Roll, Draw, RulesCodec  (canonical text encodings)
    dedup/         Normalizer, StringMetrics, Soundex, BlockingKeys, MatchScorer,
                   UnionFind, Deduplicator
    draw/          QuotaCalculator, SeedDeriver, TicketGenerator, AllocationEngine,
                   RollHasher, ResultHasher, WaitlistPromoter
    units/         UnitAssigner
    audit/         HashChain
    bundle/        VerificationBundle
    sim/           SyntheticData, Simulation
  persistence/     JPA entities + Spring Data repositories
  service/         orchestration, phase gates, authorisation, audit
  web/             controllers, DTOs, problem-detail error mapping
  demo/            dev-profile fixture loader
verify/verify.py   independent verifier, standard library only
```

**The core is deliberately framework-free.** `AllocationEngine.execute(roll, rules, seed)`
is a pure function: no clock, no database, no configuration read from the environment.
Given the same three inputs it returns bit-identical output on any machine, in any time
zone. That is not architectural tidiness — it is the property that makes the draw
reproducible by a third party in another language a year from now, and it cannot hold if
the domain reaches for a framework, a `now()`, or a database ordering.

It is also why the pure core is testable and tested to a much higher standard than the
plumbing around it.

### The lifecycle is a state machine, and that is where most of the integrity lives

```
INTAKE_OPEN → INTAKE_CLOSED → DEDUPLICATION → VERIFICATION → ROLL_FROZEN
            → SEED_COMMITTED → DRAW_EXECUTED → RESULT_PUBLISHED → ALLOTMENT → FINALISED
```

The hash chain proves a record was not *edited*. The phase machine is what stops the edit
being *accepted*. Applications become read-only at `ROLL_FROZEN`. A seed cannot be
committed before a roll exists. A draw cannot run before the seed is committed, or twice
after. Transitions are one-way: there is no path back from `ROLL_FROZEN` to
`VERIFICATION`, because that is precisely the manoeuvre by which a published list gets
quietly edited. A late correction produces a *new* roll, a new commitment and a separately
authorised draw, with both rolls permanently on record.

The roll freeze is gated on things that have actually gone wrong in real schemes:

- no duplicate reviews still pending (an open review is an unanswered question about
  whether a household holds two tickets);
- no application without a recorded eligibility decision (freezing with people still
  unverified is indistinguishable from excluding them deliberately);
- the rule set's unit count must equal the units actually available — withdraw a block for
  a construction defect and the freeze refuses until an amended rule set is published,
  rather than issuing six allotment letters that cannot be honoured.

### The stored result is not the authority; the recomputation is

`GET` on a draw does not read the result from the database. It reloads the frozen roll and
the published rule set, re-runs the engine with the recorded seed, and checks that the
output hashes to the `resultHash` recorded at execution. Only then does it answer.

A row edited from `NOT_SELECTED` to `SELECTED` therefore does not produce a wrong answer.
It produces a loud failure naming the draw. The selection rows exist for fast individual
lookup; the recomputation is what is trusted. It costs about 3,400 hashes and a few sorts —
single-digit milliseconds.

The per-pool rankings are **not persisted** at all. They are a pure function of the roll and
the seed, both of which are stored and hashed, so a rankings table could only ever
disagree with the recomputation.

### Full public verifiability *and* applicant privacy

These are usually presented as a trade-off. They are not, provided the separation is made
early. The roll carries only the attributes the rules act on:

```
e=APP-2026-A3F19B2C|CL-APP-2026-A3F19B2C|CAT_C|WOMEN|12|TWO_BHK,ONE_BHK
```

No name, no phone number, no address, no identifier. So the roll can be published in full,
anyone can recompute the entire draw from it, and an applicant can find their own line
because they know their own application id — while the link from id to human stays behind
authorisation. Government identifiers are stored only as a **peppered SHA-256 digest** plus
the last four digits: duplicate detection needs to know whether two forms carry the *same*
identifier, never what it is, so a leak of the database does not leak identity numbers.

### Separation of duties is a domain rule, not a framework concern

A clerk who keys a form cannot verify it. The officer who commits the seed cannot execute
the draw. An auditor reads everything, including the raw chain, and writes nothing. These
are enforced in the services and covered by tests, because they are rules of the scheme's
own procedure rather than access-control configuration.

### The post-draw period, which is where schemes are actually corrupted

The lottery is public and watched. The quiet reallocation of thirty flats over the
following six months is not. So:

- **Unit assignment** is serial dictatorship in ticket order — each winner takes the best
  still-available flat from their own stated preference list. Strategy-proof (nobody gains
  by misreporting preferences) and explainable in one sentence to the person who lost the
  flat they wanted: *"three households with lower ticket numbers also asked for a
  ground-floor two-bedroom, and there were two left."* A welfare-maximising assignment
  might house more people in a preferred flat, but it cannot be explained to the individual
  who was moved, and unexplainable is indefensible. 512 of 600 get their first choice.
- **Forfeiture** is the only discretionary act, and it demands a reason and an officer.
- **Promotion** has no discretion at all: the next person on the *same pool's* published
  waitlist. A vacated reserved seat stays a reserved seat; sending it to an open waitlist
  would erode the quota one forfeiture at a time. Idempotent, so it can be called after
  every batch of forfeitures.

---

## The arithmetic, spelled out

Because this is where somebody loses a flat to a rounding rule.

**Apportionment** uses largest remainder (Hare) over a partition that sums to exactly 100%,
with the open category treated as a participant at `100 − Σreserved` rather than as
"whatever is left". Every category takes the floor of its exact entitlement and the
leftover seats go one each to the largest fractional remainders. Ties are broken by
**declared order in the published rule set** — arbitrary, but fixed in advance, printed in
the notification, and identical on every run. A tie broken by hash-map iteration order is
the kind of defect that only surfaces in court.

Naive independent rounding does not have these properties: rounding 15%, 7.5% and 27% of
600 half-up and subtracting can leave the open category short or over by several flats.
Tested at 12 inventory sizes from 1 to 4,001; the seats always sum exactly and no category
is ever more than one seat from its exact entitlement.

```
600 units, reserved 15% / 7.5% / 27%, open takes the residual 50.5%
  OPEN  303   CAT_A  90   CAT_B  45   CAT_C  162     total 600
```

**Order of operations.** The open pool runs **first, over every applicant regardless of
category**. A reserved-category applicant who ranks inside the open list takes an open seat
and does *not* consume their category's quota, which then goes to the next person in that
category. Running the reserved pools first would quietly convert a quota into a ceiling —
the opposite of its purpose, and the kind of thing that gets a scheme struck down. On the
demo population, 151 of the 303 open seats go to reserved-category applicants on merit,
*and* every reserved quota is still filled in full.

**Horizontal quotas** (women, persons with disability, ex-servicemen, senior citizens) are
minimum guarantees applied *inside* each vertical category after ranking, not separate
pools — because they overlap, and one person can satisfy several. When a minimum is short,
the highest-ranked qualifying candidate outside the cut is promoted and the lowest-ranked
selected candidate is displaced, provided that candidate does not satisfy the quota being
filled and is not the last person holding up an already-satisfied minimum. Every promotion
and displacement is recorded by name. If no displaceable candidate exists the shortfall is
recorded rather than forced, because forcing it would break a different published guarantee
and a recorded, explained shortfall is far more defensible than a silent one.

**Residency preference** is a priority tier: applicants resident for at least the published
number of years rank ahead of everyone else inside a pool. Tier dominates the ticket,
because a published preference is a rule and not a tie-break.

**Lapsed seats.** A reserved seat that no eligible applicant in its category claimed is
re-offered in a clearly labelled supplementary pool (`OPEN_LAPSED`) whose winners carry the
reason code `SELECTED_IN_LAPSED_OPEN_POOL` — or withheld for the next draw, according to
the rule set. It is never passed off as an ordinary open win.

---

## Assumptions

Places where the brief was silent and I chose, rather than pretending the choice was
obvious.

1. **Categories are generic codes, not real reservation categories.** `CAT_A`, `CAT_B`,
   `CAT_C`, `WOMEN`, `PWD`, `EX_SERVICE`, `SENIOR`. The engine never hard-codes a category;
   they are data in the rule set, validated on intake. Nothing needs recompiling to run a
   scheme with a different reservation structure.
2. **Eligibility conditions are opaque check codes.** The system records *that* a named
   officer decided a specific published condition against a specific piece of evidence. It
   does not compute income ceilings or property ownership; those are decisions about
   documents, and inventing the criteria would have been fiction.
3. **One ticket per deduplicated household**, and the earliest submission carries it.
4. **The number of flats is part of the published rules.** Withdrawing a unit therefore
   requires an amended rule set version; the freeze refuses to draw against a different
   inventory.
5. **Quotas are on the total inventory, not per flat type.** Selection happens first, then
   flats are assigned by preference in ticket order. Many real schemes draw separately per
   type; that is a rule-set extension, not a code change, but it is not implemented.
6. **The waitlist has a published length** (`waitlistSize`). Everyone below it is
   `NOT_SELECTED` with a rank and a reason rather than being told they are number 2,847.
7. **Residency preference is a hard priority tier.** A sub-quota model — reserving a share
   of seats for locals rather than ranking all locals first — is gentler on non-locals and
   is the documented extension point (`ResidencyMode`), but only `NONE` and
   `PRIORITY_TIER` are implemented.
8. **`submittedAt` is supplied by the caller for paper applications.** A form keyed in on
   3 May was submitted on 12 April, and the earlier date is the one that decides which of a
   household's forms carries the ticket.
9. **Application ids are random, not sequential.** A sequential id leaks arrival order, and
   the ticket is derived from the id.
10. **Government identifiers arrive in the clear over TLS and are hashed at the boundary.**
    Only the digest and the last four digits are stored.
11. **UTC throughout**, and every hashed timestamp is truncated to whole seconds so that a
    column with different fractional-second precision cannot invalidate a hash.
12. **Horizontal quota codes and unit type preferences are stored comma-separated** rather
    than in child tables. Both are short, closed sets of codes validated against the rule
    set, never queried individually, and always read and written whole; two extra tables
    and two joins would buy the appearance of normalisation and nothing else.

---

## Left out, and why

Cut deliberately, with the reasoning, rather than left as an unmarked gap.

**Authentication.** The caller asserts an identity and a role in `X-Actor-Id` /
`X-Actor-Role` headers and the service believes them. This is **not production safe** and
it is the first thing I would replace. It is isolated in one class (`CallerActor`) because
every service method already takes an `Actor` and every recorded act already carries one,
so swapping in OIDC changes that class and nothing else. What the headers do buy even in
this form is that the audit chain names a person for every act and the maker–checker rules
actually bite.

**Serialised audit appends behind a load balancer.** Each event's hash covers its own
sequence number and its predecessor's hash, so two concurrent appends that both read the
same head would fork the chain. Appends are serialised with a `synchronized` block, which
is correct for one instance and wrong for several. The production form is a row lock
(`SELECT … FOR UPDATE` on a chain-head row) inside the same transaction as the insert. The
mitigation meanwhile is that the schema has a unique index on `previous_hash`, so a fork is
a constraint violation rather than silent damage, and the chain verifier detects it.

**A database column is not a secret.** The pre-draw nonce lives in the `draw` table. The
API refuses to disclose it before execution, but a DBA can read it and could compute
outcomes in advance. It belongs in a key vault, or in a sealed envelope with a custodian
separate from whoever runs the draw. Similarly, the audit table should be granted `INSERT`
and `SELECT` only — a role that can `UPDATE` an audit log makes the chain a formality — and
the head hash should be exported outside the system's control (read out at the draw,
printed in the notification, notarised) so that a wholesale rewrite no longer matches a
value already in the world. Chaining is tamper-*evident*, not tamper-*proof*, and I would
rather say so than oversell it.

**Document storage.** Eligibility checks carry an `evidenceRef` — a file-store key or a
physical register entry — not the document. Object storage, retention and access logging are
a subsystem of their own.

**Notifications, payments, appeals adjudication.** Result publication emits no SMS or email;
allotment records no payment or possession; a rejected applicant's appeal can be *recorded*
(a new eligibility check with a reason, or an annulled draw) but there is no hearing
workflow. All three are large and none of them change the integrity story.

**Multi-tenancy and horizontal scale.** One scheme at a time is modelled, though the schema
keys everything by `schemeCode`. At 4,000 applications the whole draw is milliseconds and a
single instance is the right answer; at a city-wide 400,000 the blocking already keeps
deduplication near-linear, and the draw is embarrassingly parallel per pool.

**A second hand-written schema dialect.** Only the MySQL migration is maintained. The `dev`
and `test` profiles run on H2 with Hibernate generating the schema from the same entities,
which keeps the dev database matching the entities and leaves the MySQL migration as the
single reviewable schema of record. Two hand-written dialects would drift apart silently,
which is worse than one plus generation.

**A UI.** Backend only, as asked. The public endpoints return plain text where a human is
meant to hash the bytes (`/result`, `/roll`, `/rolls/{id}/canonical`) and JSON where a
client is meant to render it.

**Machine-learned record linkage.** The matcher is explainable weighted-field agreement with
published thresholds. A trained model would probably score better, and could not tell an
applicant *why* their form was merged. For a decision that removes a family from a housing
list, explainability beats a few points of F1.

---

## API tour

Admin endpoints need `-H 'X-Actor-Id: registrar@scheme' -H 'X-Actor-Role: SCHEME_ADMIN'`.
Public endpoints need nothing.

```
# lifecycle
POST /api/admin/schemes                                  create a scheme
GET  /api/admin/schemes/{s}/status                       counts by status + what is blocking the freeze
POST /api/admin/rule-sets                                publish rules (returns the hash + workings)
POST /api/admin/schemes/{s}/units                        register the inventory
POST /api/applications                                   intake, idempotent
PUT  /api/applications/{a}                               correct a keying error, with a reason
POST /api/applications/{a}/eligibility-checks            record an evidenced decision
POST /api/admin/schemes/{s}/phase                        advance the state machine
POST /api/admin/schemes/{s}/deduplication                run the matcher
GET  /api/admin/schemes/{s}/duplicate-review-queue       what a human must decide
POST /api/admin/duplicate-links/{l}/review               decide one pair
POST /api/admin/schemes/{s}/rolls                        freeze the roll  (gated)
POST /api/admin/schemes/{s}/draws                        publish the seed commitment
POST /api/admin/draws/{d}/execute                        reveal, derive, draw  (different officer)
POST /api/admin/draws/{d}/publish                        announce  (recomputes first)
POST /api/admin/draws/{d}/allotments                     assign flats
POST /api/admin/allotments/{x}/forfeit                   declare a seat vacant, with a reason
POST /api/admin/draws/{d}/waitlist-promotions            refill, no discretion, idempotent

# public
GET  /api/public/applications/{a}/explanation            why not me
GET  /api/public/draws/{d}                               seed, hashes, how to check them
GET  /api/public/draws/{d}/result                        canonical text; sha256 = resultHash
GET  /api/public/draws/{d}/roll                          canonical text; sha256 = rollHash
GET  /api/public/draws/{d}/verification-bundle           zip: everything above + README
GET  /api/public/audit/head                              head hash, meant to be quoted elsewhere

# audit
GET  /api/audit/verify                                   replay the chain from genesis
GET  /api/audit/trail/{type}/{id}                        everything ever recorded against one entity
```

`scripts/walkthrough.sh` runs the public half of this against a running instance.

---

## Notes

Java 21, Spring Boot 3.3, MySQL 8 + Flyway, H2 for dev and tests. **Spring Security is
deliberately absent** — see "Left out" above. Lombok on the JPA entities only; the core has
no annotations of any kind. Jaro–Winkler, Levenshtein, Soundex and union–find are
implemented in-repo rather than pulled from a library — they are the numeric core of a
decision that displaces families, every threshold is calibrated against these exact
definitions, and the arithmetic behind a contested match should be readable and re-runnable
by whoever is reviewing the scheme.

`DESIGN.md` has the reasoning underneath: the threat model (who the adversaries are, and
they mostly have legitimate access), the alternatives I considered and rejected with the
reasons, the data model, and where this breaks at a hundred times the size.

Written for the Zenalyst AI backend assignment.
