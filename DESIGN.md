# Design notes

The README says what this does and how to run it. This is the reasoning underneath:
the threat model, the alternatives I considered and rejected, and where it would break at
a hundred times the size.

---

## 1. Threat model

Not "what could a hacker do" — that is the ordinary security work any service needs. The
interesting adversaries here have legitimate access.

| Adversary | What they want | What stops them |
|---|---|---|
| A clerk at intake | Enter a friend's form twice, or a rival's form wrongly | Idempotency keys; deduplication with recorded arithmetic; maker–checker (a clerk cannot verify what they keyed); every correction carries a reason, an actor and a before/after in the audit chain |
| A verifying officer | Reject an inconvenient applicant | Rejection requires a named published condition and an evidence reference; the reason is shown verbatim to the applicant through the public explanation endpoint; status is *derived* from checks, never settable directly |
| The scheme authority | Choose the winners | Commit–reveal: the nonce is committed before the public entropy value exists, and the entropy source is named in advance in the notification. The authority cannot tune the nonce to a known outcome, and cannot swap it afterwards without breaking a published commitment |
| The entropy source | Choose the winners | It does not know the nonce |
| Whoever runs the draw | Edit the roll to add or drop somebody | The roll is hashed at freeze; the seed is derived from the roll hash, so editing the roll changes the seed; every read re-verifies the roll against its stored hash and refuses to draw from a roll that no longer matches |
| A DBA | Change the result after publication | The stored result is not the authority. Every read reloads the roll, re-runs the engine, and refuses unless the output hashes to the announced `resultHash` |
| A DBA | Rewrite the audit log | Hash chaining makes any edit, deletion or reordering break every subsequent link; a unique index on `previous_hash` makes a forked chain a constraint violation. **Honest limit:** somebody with write access to the whole table can recompute the chain. What defeats that is publishing the head hash outside the system — read out at the draw, printed in the notification, archived by a journalist — which is why `/api/public/audit/head` needs no credentials |
| An official, months later | Quietly reallocate forfeited flats | Forfeiture demands a reason and an officer; promotion has no discretion at all and comes from the same pool's published waitlist; a unique index permits only one live offer per flat |
| An applicant | Game their own odds | Tickets derive from the seed, which does not exist when ids are issued; application ids are random, so arrival order leaks nothing; unit assignment is strategy-proof, so misreporting preferences cannot help |

The recurring pattern: **make the honest path the only cheap path.** Every shortcut a
tired operator might reach for — set a status directly, edit a roll, re-run a draw, hand a
flat to a name — either does not exist as an operation or fails loudly.

---

## 2. Alternatives considered and rejected

### Seeding with `SecureRandom`, or with a fixed seed in config
Simplest, and worthless. Whoever picks the seed picks the winners, and "we used
`SecureRandom`" is unfalsifiable after the fact. Commit–reveal costs one extra column and
one extra published value, and converts "trust us" into "check us".

### Shuffling the applicant list instead of ranking by hash
A seeded `Collections.shuffle` is reproducible only in this JDK, only while it keeps that
algorithm, and only for the exact list contents and order. Per-applicant SHA-256 tickets
are reproducible in five lines of any language, stable when a late applicant is added, and
individually checkable by the one person who cares most.

### Storing the rules as normalised quota tables
The natural relational shape, and it makes the hash a hash of a *re-serialisation*. Any
future change to that serialiser — a column added, a default changed — silently invalidates
the hash of every historical rule set. Storing the canonical text as the record and parsing
it on read inverts that: the bytes are the record, the object is derived.

### Canonical JSON instead of canonical text
Key order, whitespace and number formatting are all free variables in JSON, so a
canonicalising serialiser becomes load-bearing legal infrastructure. A fixed-order
line-delimited document is trivially canonical, and a magistrate can read the exact bytes
that were hashed. The cost is a hand-written codec of about 100 lines, with a round-trip
test.

### Persisting the per-pool rankings
About 4,000 rows per pool per draw. They are a pure function of the roll and the seed, both
of which are stored and hashed, so the table's only possible contribution is to *disagree*
with the recomputation. Recomputing takes milliseconds. Not stored.

### Trusting the stored result on read
The obvious design, and it makes a single `UPDATE` sufficient to change who got a flat.
Recomputing on every read means that `UPDATE` produces a named, logged failure instead of a
wrong answer.

### Auto-rejecting probable duplicates
The costs are asymmetric: a missed duplicate gives one household two tickets, which is
unfair and detectable later; a wrong merge removes a real family from the draw, and they
find out when the list is published. So the auto-link bar is 0.93, there is a hard veto on
conflicting government identifiers, and the middle band goes to a human who cannot be
bypassed — a pending review blocks the roll freeze.

### A trained model for record linkage
Would likely score better on F1. Could not tell an applicant *why* their form was merged,
and could not have its threshold printed in a notification before the scheme opened. For a
decision that removes a family from a housing list, explainability wins.

### Deleting duplicate rows, or a soft-delete flag
Duplicates are `SUPERSEDED` and point at the application that carries the ticket. The row,
its id and its history survive, because "you struck out my application" is a question that
has to be answerable years later with the actual arithmetic.

### Reserved pools before the open pool
Would convert every quota into a ceiling: a reserved-category applicant who ranks in the
open merit list would consume their own category's seat. Open first is both the correct
reading of how reservation works and the more generous one — 151 of 303 open seats go to
reserved-category applicants on merit here, *and* every quota is still filled.

### Horizontal quotas as separate pools
They overlap — one applicant can satisfy several — so carving seats out of the inventory for
each would double-count and over-allocate. Minimum guarantees applied after ranking, with
recorded promotions and displacements, is both arithmetically sound and explainable.

### Per-category rounding, half-up
Rounding 15%, 7.5% and 27% of 600 independently and subtracting can leave the open
category several flats short or over. Largest remainder over a partition summing to exactly
100% guarantees the seats add up and no category is more than one seat from its exact
entitlement.

### Welfare-maximising flat assignment
A matching that maximises the number of people in a preferred flat would house more people
well. It cannot be explained to the individual who was moved to make it work, and
unexplainable is indefensible. Serial dictatorship in ticket order is strategy-proof, is
one sentence to explain, and still gets 512 of 600 their first choice.

### Spring Security
The starter would put a filter chain and CSRF in front of every endpoint while
authentication is still a request header — the appearance of security without any. The
authorisation rules that matter here are domain rules (which role may freeze a roll; the
officer who commits the seed may not execute the draw), so they live in the services with
tests. The starter arrives with real authentication, not before it.

### A second hand-written schema dialect for H2
Two hand-maintained dialects drift apart silently. One reviewable MySQL migration plus
Hibernate generation for dev and test means the dev schema always matches the entities, and
there is exactly one schema of record.

---

## 3. Data model

Twelve tables. The shape follows one rule: **anything a decision depended on is snapshotted
at the moment of the decision, never read live afterwards.**

```
scheme ──────────── phase, active rule set, active roll        (optimistic-locked)
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

Choices worth defending:

- **`roll_entry` duplicates columns from `application`.** That is the point. The
  application row is a living record that can be corrected before the freeze; the roll
  entry is the snapshot the draw used and is covered by the roll hash. Reading the category
  from the application at draw time would let a later correction silently rewrite history.
- **`draw_selection` holds every applicant.** ~3,400 rows per draw where 600 would do, so
  that *"why not me?"* is one indexed lookup rather than a recomputation with a spreadsheet.
- **No draw outcome column on `application`.** A scheme can hold more than one draw against
  the same applicants (a lapsed-seat draw, a re-draw ordered on appeal); a mutable "current
  outcome" would destroy the first draw's record the moment a second ran.
- **`live_unit_key`** is `<drawId>:<unitId>` while an offer is live and NULL once forfeited.
  A unique index over a nullable column permits many NULLs, so this enforces "one live
  offer per flat per draw" in the database while keeping the whole forfeiture history. A
  unique index on `(draw_id, unit_id)` could not: a forfeited row and its replacement
  legitimately share both.
- **A unique index on `audit_event.previous_hash`**, because each hash can be the
  predecessor of at most one event. A forked chain — the failure mode of two concurrent
  appends — becomes a constraint violation instead of silent damage.
- **No `ON DELETE CASCADE` anywhere**, and no delete path in the application. A cascade is a
  loaded gun pointed at an audit trail.
- **Single-column string primary keys** derived where composite keys would be natural
  (`<rollId>:<applicationId>`). Slightly larger indexes, trivial JPA mapping, and ids that
  are readable in a log line.

---

## 4. Where this breaks, and what I would do about it

**4,000 applications is small.** The whole draw is a few thousand SHA-256 hashes and some
sorts: milliseconds. Deduplication does ~46,000 scored comparisons after blocking. One
instance is comfortably the right answer, and choosing anything more elaborate would be
architecture for its own sake.

**At 400,000 applications** (a city-wide scheme):

- *Deduplication* is the only quadratic risk and blocking already keeps it near-linear. The
  degenerate-block guard (250 members) would need lifting into a proper strategy — split
  oversized blocks on a secondary key rather than skipping them, because skipping trades
  recall for time silently. Scoring is embarrassingly parallel over blocks.
- *The draw* stays trivial. Ticket generation is per-applicant and stateless; each pool
  sorts independently.
- *`draw_selection`* becomes ~400,000 rows per draw. Fine, but the "recompute on every
  read" policy would want a short-lived cache of the recomputed outcome keyed by
  `resultHash`, invalidated by nothing because the inputs are immutable.
- *Roll canonicalisation* would need streaming rather than a `StringBuilder`. The encoding
  is line-oriented precisely so that it can be streamed and hashed incrementally.

**The three things I would fix before this went anywhere near production**, in order:

1. **Real authentication and authorisation.** Replace `CallerActor` with OIDC and map
   claims onto the existing `Role`. Everything downstream already takes an `Actor`.
2. **The nonce out of the database, and the audit table locked down.** A key vault or a
   sealed envelope with a custodian separate from whoever runs the draw; `INSERT` and
   `SELECT` only on `audit_event`; and a scheduled export of the head hash to somewhere
   outside the system's control.
3. **A database-level lock for audit appends.** `SELECT ... FOR UPDATE` on a chain-head row
   inside the insert's transaction, replacing the in-process lock, so the service can run
   more than one instance.

**Two things I would want before trusting it with real people's housing:** an adversarial
review of the horizontal top-up algorithm by somebody who knows the relevant reservation
jurisprudence — the displacement rule is defensible but it is a *policy* choice wearing an
algorithm's clothes — and a dry run of the whole pipeline against a real prior scheme's
data, because every assumption in the README about how forms are actually filled in is a
guess until it meets a real batch of paper.
