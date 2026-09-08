# Housing allotment backend — architecture and working

600 flats · ~4,000 applications · one list that must survive being disbelieved.

This document traces the system as it is actually built: every layer, every algorithm, every
hash formula, and the exact order in which things happen. `README.md` is the tour and
`DESIGN.md` is the reasoning; this is the map.

Stack: Java 21 · Spring Boot 3.3.5 · MySQL 8 + Flyway (prod) · H2 + Hibernate DDL (dev/test) ·
springdoc OpenAPI · Lombok on entities only · **no Spring Security** (deliberate — see §9).

---

## 1. The shape of the system

```
                     HTTP  (X-Actor-Id / X-Actor-Role headers)
                              │
   ┌──────────────────────────▼───────────────────────────────────────┐
   │ web/          controllers, DTOs, RFC-7807 ProblemDetail mapping  │
   │               AdminScheme · AdminDraw · Application · Public ·   │
   │               Audit · CallerActor · GlobalExceptionHandler       │
   └──────────────────────────┬───────────────────────────────────────┘
                              │  Actor(id, role)
   ┌──────────────────────────▼───────────────────────────────────────┐
   │ service/      orchestration · phase gates · role + maker-checker │
   │               · audit append · recompute-on-read                 │
   │   Scheme RuleSet Intake Eligibility Deduplication Roll Draw      │
   │   Allotment Explanation Bundle Audit                             │
   └───────────┬──────────────────────────────┬───────────────────────┘
               │                              │
   ┌───────────▼───────────────┐   ┌──────────▼────────────────────────┐
   │ core/   PURE JAVA         │   │ persistence/  JPA entities +      │
   │  no Spring, no clock,     │   │   Spring Data repositories        │
   │  no I/O, no randomness    │   │   12 tables, no delete path       │
   │  (except nonce generation)│   └──────────┬────────────────────────┘
   │  model dedup draw units   │              │
   │  audit bundle sim util    │        MySQL 8 / H2
   └───────────────────────────┘
               ▲
               │ same published rules, re-implemented independently
   ┌───────────┴───────────────────────────────────────────────────────┐
   │ verify/verify.py   second implementation · Python stdlib only ·   │
   │                    shares no code · reads only the bundle         │
   └───────────────────────────────────────────────────────────────────┘
```

### The load-bearing rule

`core/` is framework-free. `AllocationEngine.execute(drawId, roll, rules, seed, executedAt)`
is a **pure function**: no clock read, no database, no config, no `SecureRandom`. Same three
inputs → bit-identical output on any machine, any JVM, any time zone. That is what makes the
draw recomputable by a third party in another language a year later — and it cannot hold if
the domain layer reaches for a framework, a `now()`, or a database ordering.

Everything in `service/` is allowed to know about clocks, transactions and actors.
Everything in `core/` is not. The boundary is enforced by import discipline: `core/` imports
nothing from `service/`, `web/`, `persistence/`, or Spring.

### Package map (9,703 lines of Java)

| Package | Contents |
|---|---|
| `core/model` | `Rules` (RuleSet, ReservedQuota, HorizontalQuota, ResidencyRule, LapsePolicy), `Roll` (RollEntry, DrawRoll), `Draw` (SeatPlan, SeedCommitment, DrawSeed, PoolResult, Selection, DrawOutcome, TopUpNote), `RulesCodec`, `Channel` |
| `core/dedup` | `Normalizer`, `StringMetrics` (Jaro–Winkler, Levenshtein, Jaccard), `Soundex`, `BlockingKeys`, `MatchScorer`, `UnionFind`, `Deduplicator`, `Dedup` (value types + weights) |
| `core/draw` | `QuotaCalculator`, `SeedDeriver`, `TicketGenerator`, `AllocationEngine`, `RollHasher`, `ResultHasher`, `WaitlistPromoter` |
| `core/units` | `UnitAssigner`, `Units` |
| `core/audit` | `HashChain`, `Audit` |
| `core/bundle` | `VerificationBundle` |
| `core/sim` | `SyntheticData`, `Simulation` — 43 assertions, runs with a bare JDK |
| `core/util` | `Hashing` (SHA-256 hex, chain, GENESIS = 64 zeros), `Text` |
| `persistence` | 12 entities + 12 repositories + 5 enums |
| `service` | 11 services |
| `web` | 5 controllers + DTOs + `CallerActor` + `GlobalExceptionHandler` |
| `support` | `Csv`, `GovernmentIdHasher` |
| `demo` | `DemoDataLoader`, `DemoDataService` — dev-profile fixture + full pipeline run |

---

## 2. The lifecycle state machine

Most of the integrity lives here. `SchemePhase` is a strict one-way chain
(`allowedNext()` returns exactly one successor per phase):

```
INTAKE_OPEN → INTAKE_CLOSED → DEDUPLICATION → VERIFICATION → ROLL_FROZEN
  → SEED_COMMITTED → DRAW_EXECUTED → RESULT_PUBLISHED → ALLOTMENT → FINALISED
```

`SchemeService.advance()` refuses any other transition (`PhaseViolationException` → HTTP 409).
There is **no path back** from `ROLL_FROZEN` to `VERIFICATION` — that is precisely the
manoeuvre by which a published list gets quietly edited. A late correction produces a *new*
roll, a new commitment, and a separately authorised draw, with both rolls permanently on
record.

`SchemePhase.isFrozen()` (`ordinal() >= ROLL_FROZEN`) is the single read-only switch:
`RuleSetService.assertMutable(phase)` uses it to reject application corrections and rule-set
publication after the freeze.

Every service action re-asserts its phase with `SchemeService.requirePhase(schemeCode, …)`,
so the gate holds even if a caller reaches an endpoint out of order.

| Action | Required phase | Required role(s) | Extra gate |
|---|---|---|---|
| create scheme | — | SCHEME_ADMIN | code must not exist |
| publish rule set | any not-frozen | SCHEME_ADMIN | version immutable; `QuotaCalculator.plan()` must succeed |
| register / withdraw units | pre-freeze | SCHEME_ADMIN | withdrawal needs a reason |
| submit application | INTAKE_OPEN | APPLICANT, DATA_ENTRY, SCHEME_ADMIN | idempotency key |
| correct application | not frozen | DATA_ENTRY, VERIFIER, SCHEME_ADMIN | reason required; before/after diff audited |
| run deduplication | DEDUPLICATION | VERIFIER, SCHEME_ADMIN | — |
| review a duplicate link | DEDUPLICATION or VERIFICATION | VERIFIER, SCHEME_ADMIN | link must still be PENDING |
| record eligibility check | DEDUPLICATION or VERIFICATION | VERIFIER, SCHEME_ADMIN | **maker–checker**: actor ≠ `receivedBy`; a fail needs a reason |
| freeze roll | VERIFICATION | SCHEME_ADMIN | three gates, §4 |
| commit seed | ROLL_FROZEN | SCHEME_ADMIN | entropy source must be *named* first |
| execute draw | SEED_COMMITTED | SCHEME_ADMIN | draw must be COMMITTED; **actor ≠ `committedBy`** |
| publish result | — (draw EXECUTED) | SCHEME_ADMIN | recomputes and verifies before announcing |
| assign units | RESULT_PUBLISHED | SCHEME_ADMIN | none already assigned |
| accept / forfeit | ALLOTMENT | SCHEME_ADMIN, VERIFIER | forfeit needs a reason; row must be live |
| promote waitlist | ALLOTMENT | SCHEME_ADMIN | idempotent, no discretion |
| read audit / verify chain | any | AUDITOR (+admin) | read-only |
| public endpoints | draw PUBLISHED | none | — |

---

## 3. Intake and identity

`IntakeService.submit()`:

1. Role check, phase check (`INTAKE_OPEN`).
2. **Idempotency** — if `idempotencyKey` was seen before, return the existing application with
   `created=false`. Not an error; the applicant's browser hung and they pressed submit again.
   Backed by `UNIQUE KEY ux_application_idempotency` so it holds under a race, not just in
   application code.
3. Validate `verticalCode` and every `horizontalCode` against the **active rule set** — codes
   are data, never hard-coded enums.
4. Mint a **random** id: `APP-<year>-<8 hex>`, retried up to 10 times against the table.
   Sequential ids would leak arrival order, and the ticket is derived from the id.
5. Hash the government identifier at the boundary (see below) — the raw value is never stored.
6. `submittedAt` is **caller-supplied** for paper forms (a form keyed on 3 May may have been
   submitted 12 April) and defaults to `clock.instant()` otherwise. This date decides which
   of a household's forms carries the ticket.
7. Status `RECEIVED`; audit `APPLICATION_RECEIVED` with channel, batch, codes, ward, timestamp.

`GovernmentIdHasher`: `SHA256("gid/1|" + pepper + "|" + digitsOnly(id))`, plus the last four
digits stored separately for human matching. Deduplication only ever needs to know whether two
forms carry the *same* identifier, never what it is, so a database leak leaks no identity
numbers. The pepper **must** be overridden per deployment (`HOUSING_GOVERNMENT_ID_PEPPER`);
an unpeppered digest of a 12-digit number is brute-forceable in minutes.

`IntakeService.correct()` requires a stated reason, computes a field-by-field
`before->after` diff, and writes it into the audit payload. Status is never settable directly.

### Application status is derived, never assigned

`ApplicationStatus`: `RECEIVED → PENDING_DUPLICATE_REVIEW | SUPERSEDED | INELIGIBLE | ELIGIBLE`.

`EligibilityService.recomputeStatus()` recomputes from the recorded `eligibility_check` rows:
any failed check ⇒ `INELIGIBLE` with `checkCode: reason`; ≥1 check and none failed ⇒ `ELIGIBLE`.
A `SUPERSEDED` application is left alone. So no officer can set a status; they can only record
an evidenced decision about a named published condition, and the status follows.

---

## 4. Deduplication — link, never delete

Roughly a tenth of the applications are the same family applying twice. The two errors are
wildly asymmetric: a missed duplicate gives one household two tickets (unfair, detectable
later); a wrong merge removes a real family from the draw (they find out when the list is
published). So the matcher **links what is certain, queues what is plausible, and records the
arithmetic for both.**

### Blocking (`BlockingKeys`)

Five keys per record, so only plausible pairs are ever scored:

| Key | Form |
|---|---|
| `GID:` | normalised government-id hash |
| `PHN:` | 10-digit phone |
| `SDX:` | Soundex(first name token) + birth year |
| `NDB:` | first 4 alphanumeric chars of the name key + full date of birth |
| `WRD:` | ward code + Soundex(last name token) |

Any block larger than `MAX_BLOCK_SIZE = 250` is skipped as non-discriminating and the skip is
**recorded as a note** rather than silently dropped. On the demo population this cuts
8,078,190 candidate pairs to 46,527 — a 99.4% reduction — without losing duplicates.

### Scoring (`MatchScorer`)

Three tiers, checked in order:

1. **Deterministic — same government identifier** → score 1.0, `AUTO_LINKED`.
2. **Deterministic — same 10-digit phone + identical date of birth**, and *neither* side has
   a conflicting identifier → 0.99, `AUTO_LINKED`.
3. **Probabilistic** — weighted field agreement:

   | Field | Weight | Metric |
   |---|---|---|
   | name | 0.32 | Jaro–Winkler on the normalised name key |
   | dateOfBirth | 0.22 | exact 1.0 · day/month transposed 0.85 · same year+month 0.60 · same year 0.30 · adjacent years 0.15 |
   | phone | 0.18 | exact 1.0 · edit distance 1 → 0.70 · 2 → 0.35 |
   | address | 0.12 | token Jaccard |
   | relativeName | 0.10 | Jaro–Winkler |
   | ward | 0.06 | exact match |

   **Missing fields have their weight redistributed**, not counted as disagreement:
   `score = Σ(w·s over available fields) / Σ(w over available fields)`. Treating blanks as
   disagreement is the classic record-linkage bug and it under-links exactly the paper channel
   that needs help most.

   Thresholds: **≥ 0.93 auto-link · ≥ 0.80 human review · below that DISTINCT** (not stored —
   tens of thousands of confidently-distinct pairs would bury the ones a human must look at).

4. **Deterministic veto** — if both records carry an identifier and the identifiers *differ*,
   an auto-link is downgraded to `NEEDS_REVIEW` however high the fuzzy score. A father and son
   at one address share a name, a birthday and a phone; merging them drops a household.

Every pair stores its per-field breakdown, its rationale in English, and the shared blocking
keys that made it a candidate.

### Clustering and supersession

`UnionFind` takes the transitive closure — a family that applied three times usually produces
A–B and B–C but no direct A–C. Within a cluster the **earliest `submittedAt`** wins the ticket
(ties by application id); the rest become `SUPERSEDED` and point at the survivor via
`supersededByApplicationId`. Rows, ids and history all survive: "you struck out my
application" must be answerable years later with the actual arithmetic.

Anything still queued marks both applications `PENDING_DUPLICATE_REVIEW`, which the freeze gate
can see.

`DeduplicationService.decide()` has one subtlety worth knowing: on `SAME_HOUSEHOLD` the
primary is chosen as the earliest submission **across both merged clusters**, not merely the
earlier of the two applications named on the link. (Cluster {X,Y} with X earliest, plus a
queued link Y~Z where Z precedes Y but follows X — deciding between Y and Z alone would
supersede X, the current ticket holder, and quietly move the household's ticket.) The verdict
is also flushed *before* the "any links still pending?" sweep, or an application would stay
stuck in `PENDING_DUPLICATE_REVIEW` because of the very link just resolved.

Result on the demo population: **98.6% of planted duplicates caught automatically, 7 pairs
queued for a human, zero false merges** — checked against ground truth the matcher never sees.

### The roll freeze gates (`RollService.freeze`)

Refuses, with a specific message, if:

1. **any duplicate review is still PENDING** — an open review is an unanswered question about
   whether a household holds two tickets;
2. **any application lacks a recorded eligibility decision** (`RECEIVED` or
   `PENDING_DUPLICATE_REVIEW` count > 0) — freezing with people still unverified is
   indistinguishable from excluding them deliberately;
3. **the rule set's `totalUnits` ≠ the count of non-withdrawn units** — withdraw a block for a
   construction defect and the freeze refuses until an amended rule set is published, rather
   than issuing six allotment letters that cannot be honoured.

Also: the roll id must be unused (rolls are immutable), inventory > 0, and at least one
`ELIGIBLE` application.

---

## 5. The roll — the hinge of the system

Only `ELIGIBLE` applications, sorted by application id, snapshotted into `roll_entry`.
A roll entry carries **only what the rules act on**:

```
e=APP-2026-A3F19B2C|CL-APP-2026-A3F19B2C|CAT_C|WOMEN|12|TWO_BHK,ONE_BHK
  applicationId | clusterId | verticalCode | horizontalCodes | residencyYears | unitTypePrefs
```

No name, no phone, no address, no identifier. So the roll can be **published in full** —
anyone can recompute the entire draw from it, and an applicant can find their own line because
they know their own application id — while the link from id to human stays behind
authorisation. Full public verifiability and applicant privacy are not a trade-off if the
separation is made this early.

`roll_entry` deliberately duplicates columns from `application`. The application row is a
living record that can be corrected before the freeze; the roll entry is the frozen snapshot
the draw used, and it is what the roll hash covers. Reading the category from `application` at
draw time would let a later correction silently rewrite history.

Canonical encoding (`RollHasher.encode`) — line-oriented, fixed field order, `frozenAt`
truncated to whole seconds so a column with different fractional-second precision cannot
invalidate the hash:

```
roll/1
schemeCode=… ruleSetVersion=… ruleSetHash=… frozenAt=… entryCount=…
e=…  (one line per entry, sorted by application id)
```

`rollHash = SHA256(that text)`.

**Every read re-verifies.** `RollService.load()` rebuilds the roll from the rows, recomputes the
hash, and throws if it no longer matches what was stored at freeze time. `AllocationEngine`
independently calls `RollHasher.verify(roll)` before doing anything. Editing the roll therefore
cannot produce a wrong answer — it produces a loud, named failure.

---

## 6. The seed — commit–reveal, so nobody chose the winners

The hard problem in a public lottery is not randomness; it is convincing a hostile audience
that nobody picked the outcome. `SecureRandom` proves nothing after the fact — whoever chooses
the seed chooses the winners.

```
nonce      = 32 random bytes, hex                      (secret until the reveal)
commitment = SHA256("commit/1|" + nonce)               published BEFORE the entropy exists
seed       = SHA256("seed/1|" + rollHash + "|" + publicEntropy + "|" + nonce)
ticket(a)  = SHA256("ticket/1|" + seed + "|" + applicationId)
```

- **`rollHash`** fixes who is in the draw. Edit the roll and the seed changes.
- **The nonce** is committed before the public entropy value exists, so it cannot be tuned to
  a known outcome — and cannot be swapped afterwards without breaking a published commitment.
- **The public entropy** is a value the authority does not control, and its source must be
  *named at commit time* (`commitSeed` rejects a blank `entropySourceDescription`): a specified
  state lottery number, an index close, a named block hash.
- Neither party can bias the result. The entropy source does not know the nonce; the authority
  cannot change the nonce.

The nonce is **excluded from the `SEED_COMMITTED` audit payload** on purpose — auditors can
read the audit log before the draw, and a leaked nonce makes the commitment worthless. It is
disclosed in the `DRAW_EXECUTED` payload and in the public draw JSON afterwards.

`SeedDeriver.reveal()` refuses to proceed unless `SHA256("commit/1|" + nonce)` equals the
published commitment.

Anyone can check their own ticket in one line:

```bash
printf '%s' 'ticket/1|<seed>|<yourApplicationId>' | sha256sum
```

Ranking by SHA-256 ticket rather than shuffling a list buys three things: reproducibility in
any language (a seeded `Collections.shuffle` is reproducible only in this JDK, only while it
keeps that algorithm); per-applicant stability (a late arrival does not reshuffle everybody);
and individual checkability by the one person who cares most.

---

## 7. The draw

`AllocationEngine.execute(drawId, roll, rules, seed, executedAt)`.

**Validation first**: roll matches its own hash; the roll's rule-set version equals the rule
set supplied; the roll hash equals the hash the seed was committed against; every vertical and
horizontal code on the roll is defined by that rule set.

### 7.1 Apportionment — `QuotaCalculator.plan()`

Largest remainder (Hare) over a partition summing to exactly 100%, with the **open category
treated as a participant at `100 − Σreserved`**, not as "whatever is left":

1. each vertical takes `floor(percent × total / 100)`;
2. leftover seats go one each to the largest fractional remainders;
3. **ties are broken by declared order in the published rule set** — arbitrary, but fixed in
   advance, printed in the notification, identical on every run. A tie broken by hash-map
   iteration order is the kind of defect that only surfaces in court.

```
600 units, reserved 15% / 7.5% / 27%, open takes the residual 50.5%
  OPEN 303   CAT_A 90   CAT_B 45   CAT_C 162     total 600  ✓
```

The seats are asserted to sum to the inventory exactly, and every step is written into a
human-readable `workings` trace stored on the draw and published in the seat plan.

Horizontal minima are computed **independently per vertical** (`round(percent × seats)`,
HALF_UP) because they overlap and are not a partition. If they cannot jointly fit inside a
vertical, the plan is rejected — at publish time, via `RuleSetService.publish()`, which runs
`QuotaCalculator.plan()` before accepting any rule set.

### 7.2 Ranking

Within a pool, order is: **residency tier, then ticket hex, then application id**
(`TicketGenerator.order()`). Tier dominates the ticket because a published preference is a
*rule*, not a tie-break: with `PRIORITY_TIER`, `residencyYears >= minYears` → tier 0, everyone
else tier 1, so every qualifying local outranks every non-local inside a pool.

### 7.3 Pool order — open first, over everybody

1. **The open pool runs first, over every applicant regardless of category.** A
   reserved-category applicant who ranks inside the open list takes an open seat and does *not*
   consume their category's quota, which then goes to the next person in that category.
   Running reserved pools first would quietly convert every quota into a **ceiling** — the
   opposite of its purpose. On the demo population 151 of the 303 open seats go to
   reserved-category applicants on merit, *and* every reserved quota is still filled in full.
2. **Each reserved pool**, in published order, over that category's applicants not already
   selected.
3. **Lapsed seats**: reserved seats no eligible applicant in the category claimed are
   re-offered in a clearly labelled supplementary pool `OPEN_LAPSED` (its own horizontal minima
   computed against its own seat count), whose winners carry the reason code
   `SELECTED_IN_LAPSED_OPEN_POOL` — never passed off as an ordinary open win. Or withheld, if
   the rule set says `CARRY_FORWARD`.

### 7.4 Horizontal minima — the one contestable act

Horizontal quotas (women, PwD, ex-servicemen, senior citizens) are **minimum guarantees applied
inside each pool after ranking**, not separate pools, because they overlap — one person can
satisfy several, so carving seats per quota would double-count.

When a minimum is short by *n*, for each of the *n* slots the engine takes the
highest-ranked qualifying candidate from outside the cut and displaces the **lowest-ranked
selected candidate** who

- does **not** himself satisfy the quota being filled (displacing him would not help), and
- is **not** the last person holding up an already-processed minimum (`neededForMinimum`).

If no displaceable candidate exists, the shortfall is **recorded as a note rather than
forced** — forcing it would break a different published guarantee, and a recorded, explained
shortfall is far more defensible than a silent one.

Every promotion and displacement is recorded by application id in a `TopUpNote`
(`horizontalCode`, `requiredSeats`, `satisfiedBeforeTopUp`, promoted ids, displaced ids, note).
A displaced applicant gets reason code `DISPLACED_BY_HORIZONTAL_MINIMUM` and sits at the front
of the waitlist.

### 7.5 An outcome for everybody

The draw writes ~3,400 rows where 600 would do. Waitlist position is the **best position across
every pool the applicant appeared in**, counted over candidates not selected anywhere; anyone
at position ≤ `waitlistSize` is `WAITLISTED`, the rest `NOT_SELECTED` with a rank and a reason
(nobody is told they are number 2,847).

`Outcome`: `SELECTED | WAITLISTED | NOT_SELECTED`.
`ReasonCode`: `SELECTED_ON_MERIT`, `SELECTED_VIA_HORIZONTAL_MINIMUM`,
`SELECTED_IN_LAPSED_OPEN_POOL`, `DISPLACED_BY_HORIZONTAL_MINIMUM`,
`WAITLISTED_BEHIND_SELECTED`, `NOT_SELECTED_RANK_BELOW_SEATS`, `NOT_SELECTED_NO_SEATS_IN_POOL`
— each with a generated sentence of English:

```
Ranked 892 of 3419 in pool OPEN, which had 303 seats. The ticket ranked below the
number of available seats, and below the published waitlist length.
```

Selections are sorted by application id, then hashed.

### 7.6 The result hash, and why the stored result is not the authority

`ResultHasher.encode()` produces a canonical text of `result/1` header, draw/roll/rule-set ids
and hashes, seed, `executedAt` (whole seconds), the seat plan, one `pool=` line per pool, and
one `sel=` line per applicant. It **asserts the selections are sorted** before hashing.
`resultHash = SHA256(that text)`.

`DrawService.outcome(drawId)` — used by *every* read, including `publish` — does **not** read the
result from the database. It:

1. reloads the frozen roll (re-verifying its hash),
2. reloads the published rule set and decodes it from its canonical text,
3. re-derives the seed from the commitment, entropy and nonce (re-checking the commitment),
4. **re-runs `AllocationEngine`**,
5. refuses unless the recomputed `resultHash` equals the one recorded at execution, and unless
   the outcome passes its own `ResultHasher.verify`.

So a row edited from `NOT_SELECTED` to `SELECTED` does not produce a wrong answer — it produces
an `IllegalStateException` naming the draw, rendered as HTTP 500 "Integrity check failed".
`draw_selection` exists for fast individual lookup; the recomputation is what is trusted. Cost:
~3,400 hashes and a few sorts, single-digit milliseconds.

**Per-pool rankings are not persisted at all.** They are a pure function of the roll and the
seed, both stored and hashed, so a rankings table could only ever *disagree* with the
recomputation.

---

## 8. After the draw — where schemes are actually corrupted

The lottery is public and watched. The quiet reallocation of thirty flats over the following
six months is not.

### Unit assignment — `UnitAssigner.assign()`

**Serial dictatorship in ascending ticket order** (one published order across all pools, so a
reserved-category winner is not systematically served before or after an open winner). Each
winner in turn takes the lowest-numbered available flat from their own stated preference list;
if all their preferences are exhausted, the lowest-numbered remaining flat of any type; the
`basis` string records which preference rank was honoured (0 = none).

Strategy-proof — nobody gains by misreporting preferences — and explainable in one sentence to
the person who lost the flat they wanted: *"three households with lower ticket numbers also
asked for a ground-floor two-bedroom, and there were two left."* A welfare-maximising matching
might house more people in a preferred flat, but it cannot be explained to the individual who
was moved, and unexplainable is indefensible. 512 of 600 get their first choice.

The plan is itself hashed (`allotment/1` canonical text → `allotmentHash`) and recorded in the
audit payload.

### Forfeiture and promotion

- **Forfeiture** is the only discretionary act post-draw and demands a reason and an officer.
  It sets `liveUnitKey = NULL`, releasing the flat's unique live-offer claim while the forfeited
  row stays permanently on record.
- **Promotion** (`WaitlistPromoter.promote`) has *no discretion*: the next eligible applicant on
  the **same pool's** published waitlist. A vacated reserved seat stays a reserved seat —
  sending it to an open waitlist would erode the quota one forfeiture at a time. Vacancies are
  processed in ticket order, so the sequence of promotions does not depend on the order
  forfeitures happened to be keyed in. Idempotent via `fillsVacancyOf`, so it can be called
  after every batch. If a pool's waitlist is exhausted, `PROMOTION_EXHAUSTED` is audited and the
  seat is carried to the next draw.
- A promotion row's id is suffixed with the vacancy it fills
  (`<draw>:<applicant>:P<pickOrder>`), because an applicant who forfeited an earlier offer
  already owns the plain id and overwriting it would erase the record of that forfeiture.

---

## 9. Authorisation, separation of duties, audit

### Actor

Every service method takes `Actor(id, role)`. `Role`: `PUBLIC, APPLICANT, DATA_ENTRY, VERIFIER,
SCHEME_ADMIN, AUDITOR`. `actor.require(…)` throws `ForbiddenException` (403);
`actor.requireDifferentFrom(earlierActorId, what)` is the maker–checker primitive.

Two maker–checker rules are enforced and tested:

- a clerk who **keyed** a form cannot **verify** it (`actor ≠ application.receivedBy`);
- the officer who **commits the seed** cannot **execute the draw** (`actor ≠ draw.committedBy`).

These are rules of the scheme's own procedure, not access-control configuration, so they live in
the services. **Spring Security is deliberately absent**: the starter would put a filter chain
and CSRF in front of every endpoint while authentication is still an HTTP header — the
appearance of security without any. `CallerActor` reads `X-Actor-Id` / `X-Actor-Role` and
believes them. **This is not production safe** and is the first thing to replace; because every
service method already takes an `Actor` and every recorded act already carries one, swapping in
OIDC changes that one class and nothing else.

### The hash-chained audit log

```
canonical(e) = "event/1|<seq>|<occurredAt sec>|<actor>|<role>|<action>|<entityType>|<entityId>|<payload>"
hash(e)      = SHA256(previousHash + "|" + canonical(e))
genesis      = "0" × 64
```

Dense sequence from 1, append-only, no delete path anywhere in the application and no
`ON DELETE CASCADE` in the schema. `HashChain.verify()` replays from genesis and reports the
first divergence, distinguishing three failures: a **sequence jump** (an event deleted or
inserted), a **predecessor mismatch** (reordering), and a **content change** (the recomputed
hash differs from the stored one).

`UNIQUE KEY ux_audit_previous (previous_hash)` makes a **forked chain** — the failure mode of
two concurrent appends — a constraint violation rather than silent damage. Appends are
serialised in-process with a `ReentrantLock` inside a `Propagation.MANDATORY` transaction:
correct for one instance, wrong for several. The production form is `SELECT … FOR UPDATE` on a
chain-head row in the same transaction.

Actions recorded: `SCHEME_CREATED`, `PHASE_ADVANCED`, `RULESET_PUBLISHED`,
`APPLICATION_RECEIVED`, `APPLICATION_CORRECTED`, `ELIGIBILITY_RECORDED`,
`DEDUPLICATION_COMPLETED`, `DUPLICATE_REVIEWED`, `ROLL_FROZEN`, `SEED_COMMITTED`,
`DRAW_EXECUTED`, `RESULT_PUBLISHED`, `DRAW_ANNULLED`, `UNITS_ASSIGNED`, `ALLOTMENT_ACCEPTED`,
`ALLOTMENT_FORFEITED`, `WAITLIST_PROMOTED`, `PROMOTION_EXHAUSTED`.

**Honest limit**: chaining is tamper-*evident*, not tamper-*proof*. Somebody with write access
to the whole table can recompute the chain. What defeats that is publishing the head hash
outside the system — read out at the draw, printed in the notification, archived by a
journalist — which is exactly why `GET /api/public/audit/head` needs no credentials.

---

## 10. Data model — 12 tables

One rule throughout: **anything a decision depended on is snapshotted at the moment of the
decision, never read live afterwards.**

```
scheme ──────────── phase, active rule set, active roll        (optimistic-locked, @Version)
rule_set ────────── canonical text + hash, immutable, versioned
flat_unit ───────── inventory, withdrawable with a reason

application ─────── identity + declared attributes + derived status
  ├─ eligibility_check   one evidenced decision per condition per officer
  └─ duplicate_link      one scored pair, its per-field breakdown, and its verdict

draw_roll ───────── frozen, hashed, references the rule set version and hash
  └─ roll_entry        the attributes each application was DRAWN under (snapshot)

draw ────────────── commitment → entropy + nonce → seed → result hash → published
  ├─ draw_selection    one row per applicant per draw, not per winner
  └─ unit_allotment    offers, forfeitures, promotions; live-offer uniqueness

audit_event ─────── hash-chained, dense sequence, append-only
```

Choices worth knowing:

- **No draw-outcome column on `application`.** A scheme can hold more than one draw against the
  same applicants (a lapsed-seat draw, a re-draw ordered on appeal); a mutable "current
  outcome" would destroy the first draw's record the moment a second ran.
- **`live_unit_key`** = `<drawId>:<unitId>` while an offer is live, `NULL` once forfeited. A
  unique index over a nullable column permits many NULLs, so this enforces "one live offer per
  flat per draw" *in the database* while keeping the whole forfeiture history. A unique index on
  `(draw_id, unit_id)` could not: a forfeited row and its replacement legitimately share both.
- **Single-column string primary keys** derived where composite keys would be natural
  (`<rollId>:<applicationId>`): slightly larger indexes, trivial JPA mapping, ids readable in a
  log line.
- **Horizontal codes and unit-type preferences are comma-separated** (`support/Csv`), not child
  tables. Both are short closed sets of codes validated against the rule set, never queried
  individually, always read and written whole.
- Hashes are `VARCHAR(64)`, timestamps `DATETIME(6)` in UTC throughout, and every hashed
  timestamp is truncated to whole seconds so differing fractional-second precision cannot
  invalidate a hash.

### Rules as versioned data, not code

A rule set is an immutable published record whose **canonical text is the record** and whose
object is derived (`RulesCodec.decode` on read):

```
version=2026-PHASE-1
schemeCode=DEMO-2026
totalUnits=600
openCode=OPEN
reserved=CAT_A|Reserved category A|15.0000
horizontal=WOMEN|Women applicants|30.0000
residency=PRIORITY_TIER|3
lapse=LAPSE_TO_OPEN
waitlistSize=150
rulesUri=…
```

`sha256sum rules.txt` **is** the `ruleSetHash` quoted in the notification. Text, not JSON,
because key order, whitespace and number formatting are free variables in JSON, which would
make a canonicalising serialiser load-bearing legal infrastructure; a magistrate can read the
exact bytes that were hashed. Percentages carry at most 4 decimals and are **refused, not
rounded**, if more precise; pipes and newlines are rejected at construction (they would encode
fine and fail to decode later, bricking the scheme with no repair path). Amending a quota is a
new published version, never a deploy.

---

## 11. The public API

Admin endpoints need `-H 'X-Actor-Id: registrar@scheme' -H 'X-Actor-Role: SCHEME_ADMIN'`.
Public endpoints need nothing. Errors are RFC-7807 `ProblemDetail`:
404 not found · 403 role · **409 phase violation / uniqueness conflict** · 400 validation ·
500 integrity-check failure.

```
# lifecycle  (/api/admin)
POST /schemes                                  create a scheme
GET  /schemes/{s}                              scheme + active rule set + active roll
GET  /schemes/{s}/status                       counts by status + what is blocking the freeze
POST /schemes/{s}/phase                        advance the state machine
POST /rule-sets                                publish rules (returns hash + seat-plan workings)
GET  /rule-sets/{v} · GET /schemes/{s}/rule-sets
POST /schemes/{s}/units · POST /units/{u}/withdraw
POST /schemes/{s}/deduplication                run the matcher
GET  /schemes/{s}/duplicate-review-queue       what a human must decide
POST /duplicate-links/{l}/review               decide one pair
POST /schemes/{s}/rolls                        freeze the roll  (gated, §4)
GET  /rolls/{r} · GET /rolls/{r}/canonical     (text/plain; sha256 = rollHash)
POST /schemes/{s}/draws                        publish the seed commitment
POST /draws/{d}/execute                        reveal, derive, draw  (different officer)
POST /draws/{d}/publish                        announce  (recomputes first)
POST /draws/{d}/annul                          with a reason
GET  /draws/{d} · GET /schemes/{s}/draws · GET /draws/{d}/waitlist
POST /draws/{d}/allotments                     assign flats
GET  /draws/{d}/allotments
POST /allotments/{x}/accept · POST /allotments/{x}/forfeit
POST /draws/{d}/waitlist-promotions            refill, no discretion, idempotent

# applications  (/api/applications)
POST /                                         intake, idempotent
GET  /{a}
PUT  /{a}                                      correct a keying error, with a reason
POST /{a}/eligibility-checks                   record an evidenced decision
GET  /{a}/eligibility-checks

# public  (/api/public)
GET  /applications/{a}/explanation             why not me
GET  /draws/{d}                                seed, hashes, and how to check them
GET  /draws/{d}/result                         canonical text; sha256 = resultHash
GET  /draws/{d}/roll                           canonical text; sha256 = rollHash
GET  /draws/{d}/verification-bundle            zip of everything above + README
GET  /draws/{d}/verification-bundle/manifest   the manifest inline
GET  /audit/head                               head hash, meant to be quoted elsewhere

# audit  (/api)
GET  /audit/verify                             replay the chain from genesis
GET  /audit/trail/{type}/{id}                  everything ever recorded against one entity
GET  /audit/events?from=&to=                   raw range
```

Public draw and result endpoints refuse anything not `PUBLISHED`, and
`ExplanationService` **skips unpublished draws entirely** — telling one applicant before the
announcement is exactly the leak that makes a draw contestable.

### Three audiences, three answers

| Who | Asks | Answered by |
|---|---|---|
| An applicant | "Why not me?" | `/explanation` — status, every eligibility check with its evidence ref and officer, ticket, pool, rank, seats, candidates, waitlist position, reason code, a sentence of English, **and the shell command to recompute their own ticket** |
| A newspaper | "Show me the process" | `/verification-bundle` — `rules.txt`, `roll.txt`, `seed.txt`, `result.txt`, `MANIFEST.txt` with all four hashes + audit head, and a `README.txt` explaining how to check each one, zipped with a fixed timestamp so the archive itself is byte-identical and hashable |
| A court | "Prove nothing was changed" | hash-chained audit log + frozen hashed roll + commit–reveal seed + a result recomputed from first principles on every single read |

`ExplanationService` also self-checks: it recomputes the ticket from the quoted formula and
throws rather than hand an applicant an instruction that will not verify.

---

## 12. Independent verification

`verify/verify.py` is a **second implementation of the published rules**: Python standard
library only, no shared code with the service, reads only the bundle. It recomputes the seed,
every ticket, the quota arithmetic, the pool rankings and every applicant's outcome, and
reports the first line where its answer differs from the published one.

```bash
python3 verify/verify.py target/verification-bundle
```

Both implementations agree line for line across all 3,419 applicants on the demo roll. That
agreement is the single most useful test in the repository: a bug in the Java would have to be
reproduced identically in the Python to go unnoticed.

Tamper test:

```bash
cp -r target/verification-bundle /tmp/forged
sed -i '0,/|NOT_SELECTED|/s//|SELECTED|/' /tmp/forged/result.txt
python3 verify/verify.py /tmp/forged      # fails four ways and names the application id
```

---

## 13. Running it, and the test story

```bash
# zero dependencies: in-memory H2, ~4,020 applications (420 planted duplicates), 600 flats,
# whole lifecycle run at startup, ~20 seconds
mvn spring-boot:run -Dspring-boot.run.profiles=dev
#   shrink:  -Dspring-boot.run.arguments="--housing.demo.households=400 --housing.demo.units=80"

# production shape: MySQL 8 + Flyway, Swagger at /swagger-ui.html
docker compose up --build

# the decision core with nothing but a JDK — 43 assertions, exits non-zero on failure,
# and writes a verification bundle to target/verification-bundle
javac -d out $(find src/main/java/dev/harshith/housing/core -name '*.java')
java -cp out dev.harshith.housing.core.sim.Simulation

mvn verify        # unit + integration tests
```

- `core/sim/Simulation` — 43 assertions over ~4,000 synthetic applications: apportionment,
  dedup precision and recall against ground truth, commit–reveal, determinism, tamper
  detection, unit assignment, waitlist promotion, the audit chain. Usable as a CI gate on its
  own.
- `QuotaCalculatorTest` (8) — apportionment at 12 inventory sizes from 1 to 4,001: seats always
  sum exactly, no category ever more than one seat from its exact entitlement.
- `AllocationEngineTest` (16) — pool order, horizontal top-ups, displacement, lapsed pools,
  determinism.
- `DeduplicatorTest` (16) — blocking, weights, missing-field redistribution, the identifier veto,
  transitive closure.
- `IntegrityTest` (24) — canonical encodings, roll and result hashes, hash chain, commit–reveal.
- `SchemeLifecycleIT` — drives the whole scheme through HTTP and asserts every gate: a
  correction after the freeze is refused, a draw runs once, the officer who commits the seed
  cannot execute it, a clerk cannot verify their own data entry, an auditor reads everything
  and writes nothing.

Config worth noting: `spring.jpa.hibernate.ddl-auto=none` and Flyway owns the MySQL schema —
Hibernate never touches DDL in production, because a schema that drifts with the entity classes
is not something an auditor can review. The `dev`/`test` profiles run H2 with
`ddl-auto=create-drop` from the same entities, so the dev database always matches the entities
and the MySQL migration stays the single reviewable schema of record. Virtual threads are on;
`open-in-view` is off; Jackson writes ISO-8601, not epoch numbers, because these timestamps are
quoted in explanations given to applicants.

---

## 14. Known limits, in the order they should be fixed

1. **Real authentication and authorisation.** Replace `CallerActor` with OIDC and map claims
   onto the existing `Role`. Everything downstream already takes an `Actor`.
2. **The nonce out of the database, and the audit table locked down.** A key vault or a sealed
   envelope with a custodian separate from whoever runs the draw; `INSERT`/`SELECT` only on
   `audit_event`; a scheduled export of the head hash to somewhere outside the system's control.
3. **A database-level lock for audit appends.** `SELECT … FOR UPDATE` on a chain-head row inside
   the insert's transaction, replacing the in-process `ReentrantLock`, so the service can run
   more than one instance.

Deliberately out of scope: document storage (eligibility checks carry an `evidenceRef`, not the
document), notifications, payments, appeals adjudication (an appeal can be *recorded* — a new
eligibility check, or an annulled draw — but there is no hearing workflow), multi-tenancy, a
second hand-written H2 schema dialect, a UI, and machine-learned record linkage (a trained
model would probably score better and could not tell an applicant *why* their form was merged).

At **400,000 applications**, deduplication is the only quadratic risk and blocking already keeps
it near-linear — the 250-member degenerate-block guard would need to split oversized blocks on a
secondary key rather than skip them, since skipping trades recall for time silently. The draw
stays trivial (per-applicant, stateless, pools sort independently); `draw_selection` becomes
~400,000 rows per draw, which would want a short-lived cache of the recomputed outcome keyed by
`resultHash` (invalidated by nothing, because the inputs are immutable); roll canonicalisation
would stream rather than build a `StringBuilder`, which is why the encoding is line-oriented.

Two things worth having before trusting it with real people's housing: an adversarial review of
the horizontal top-up algorithm by somebody who knows the relevant reservation jurisprudence —
the displacement rule is defensible but it is a *policy* choice wearing an algorithm's
clothes — and a dry run against a real prior scheme's data, because every assumption about how
forms are actually filled in is a guess until it meets a real batch of paper.

---

## Appendix — every hash in one place

```
ruleSetHash   = SHA256( RulesCodec.encode(ruleSet) )              # rules.txt
rollHash      = SHA256( RollHasher.encode(roll) )                 # roll.txt
commitment    = SHA256( "commit/1|" + nonce )
seedHex       = SHA256( "seed/1|"   + rollHash + "|" + publicEntropy + "|" + nonce )
ticketHex     = SHA256( "ticket/1|" + seedHex  + "|" + applicationId )
resultHash    = SHA256( ResultHasher.encode(outcome) )            # result.txt
allotmentHash = SHA256( "allotment/1\ndrawId=…\na=…" )
governmentId  = SHA256( "gid/1|" + pepper + "|" + digitsOnly(id) )
auditHash(e)  = SHA256( previousHash + "|" + "event/1|<seq>|<ts>|<actor>|<role>|<action>|<type>|<id>|<payload>" )
genesis       = "0" × 64
```

All SHA-256, all lowercase hex, all over UTF-8 bytes, all timestamps truncated to whole
seconds.
