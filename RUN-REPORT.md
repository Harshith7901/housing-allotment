# Run report — 8 September 2026

Everything below was executed on this machine (Windows host, Linux side of the Cowork
workspace) against a JDK 21 and the Maven jars already cached in `C:\Users\test\.m2`.

## 1. Framework-free core — `dev.harshith.housing.core.sim.Simulation`

`javac -Xlint:all` over the 29 `core/` classes: **clean, zero warnings.**
Simulation: **43 / 43 checks, exit 0, ~4 s.**

| Stage | Observed |
|---|---|
| Apportionment | 600 = OPEN 303 · CAT_A 90 · CAT_B 45 · CAT_C 162, sums exactly |
| Intake | 4,020 applications · 3,600 households · 420 planted re-submissions |
| Deduplication | 46,527 pairs vs 8,078,190 exhaustive (99.4% cut) · 432 auto-linked · 7 queued · **98.6% recall, zero false merges** |
| Roll freeze | 3,419 entries, 183 ineligible, verifies against its own hash |
| Commit–reveal | commitment holds; substituted nonce refused; seed recomputable from published values |
| Draw | 600 selected, every pool filled, all horizontal minima met by recorded promotions |
| Reproducibility | re-run bit-identical; different entropy → different winners (289/600 overlap); altered roll refused; mismatched seed refused |
| Units | 600/600 housed, no flat twice, 512 first choices |
| Forfeiture | 5 vacated seats, all refilled from the correct pool, deterministic |
| Audit chain | verifies from genesis; edited event and deleted event both detected and localised |

## 2. Independent verifier — `verify/verify.py`

**47 / 47 checks, exit 0**, agreeing line for line with the Java across all 3,419
applicants. Run twice: once on the simulation's bundle, once on the bundle the **live
service** served over HTTP.

Tamper tests, all detected and named:

| Forgery | Result |
|---|---|
| one `NOT_SELECTED` → `SELECTED` in result.txt | 4 failures (digest, byte count, resultHash, per-applicant comparison) |
| nonce altered in seed.txt | commitment no longer holds; seed no longer derives |
| one line deleted from roll.txt | 10 failures incl. rollHash, entry count, seed-vs-roll binding, pool counts |
| untouched bundle | VERIFIED |

## 3. Unit tests — 75 / 75

`QuotaCalculatorTest` 19 (incl. 12 parameterised inventory sizes) · `AllocationEngineTest` 16 ·
`DeduplicatorTest` 16 · `IntegrityTest` 24. **Zero failures.**

Run through a small reflective driver rather than Surefire: `junit-platform-launcher` is not
in the local repository (Eclipse supplies its own), and Maven Central is not reachable from
this sandbox. On a networked machine `mvn verify` runs them normally.

## 4. The service, dev profile, driven over HTTP

Started with `--spring.profiles.active=dev`: profile active, Tomcat on 8080, **started in
7.1 s**, whole demo pipeline (1,340 applications → 200 flats) complete in **15 s**, audit
chain 22 events intact.

Checks against the running service:

- `GET /api/public/audit/head` — served without credentials, as intended
- `GET /api/audit/verify` — `intact: true`, 22 events verify from genesis
- `GET /api/admin/schemes/{s}/status` — phase `ALLOTMENT`, 1,340 applications
  (140 superseded · 58 ineligible · 1,142 eligible), `rollFreezeBlockers: []`
- `sha256` of the served `/result` and `/roll` text **equals** the published `resultHash`
  and `rollHash`
- an applicant's `/explanation` returns pool, seats, rank, ticket, reason and the shell
  command to check it — and `printf 'ticket/1|<seed>|<id>' | sha256sum` **reproduced that
  applicant's ticket exactly**
- `/verification-bundle` — 200, 68,648 bytes, 6 files, manifest digests all correct

Negative tests (the gates):

| Attempt | Expected | Got |
|---|---|---|
| `DATA_ENTRY` freezes a roll | 403 | **403** "role DATA_ENTRY may not perform this action" |
| `AUDITOR` publishes a draw | 403 | **403** "requires one of [SCHEME_ADMIN]" |
| intake after the freeze | 409 | **409** "requires the scheme to be in one of [INTAKE_OPEN]" |
| correct an application after the freeze | 409 | **409** "the roll is frozen … applications are read-only" |
| execute the draw a second time | 409 | **409** "is PUBLISHED and can only be executed once, from COMMITTED" |
| malformed rule set / phase body | 400 | **400** with per-field messages |

## 5. Maker–checker, on a clean scheme driven by hand

Demo data disabled; scheme `SCH-MK-01` created through the API with distinct actors.

| Step | Result |
|---|---|
| create scheme · publish rules (4 units) · register 4 flats | 201 · 201 · 201 |
| 6 applications keyed by `officer.x` | 201 each |
| same `idempotencyKey` resubmitted | **200, same application id returned** |
| deduplication | 15 comparisons, 0 linked, 6 clusters |
| **`officer.x` verifies a form `officer.x` keyed** | **403** "eligibility verification must be performed by somebody other than officer.x@scheme, who performed the preceding step" |
| `officer.y` verifies it | 201, status `ELIGIBLE` |
| freeze roll | 201, 6 entries, rollHash recorded |
| `officer.x` commits the seed | 201 |
| **`officer.x` executes the draw** | **403** "executing the draw must be performed by somebody other than officer.x@scheme" |
| `officer.y` executes it | 200, 4 of 6 selected, seed + resultHash returned |
| publish · assign flats | 200 · 201, 4 assigned, 0 unhoused, allotmentHash recorded |
| audit trail for the draw | `SEED_COMMITTED` by officer.x, `DRAW_EXECUTED` by officer.y, chained |
| chain verify | `intact: true`, 29 events |

## 6. Changes made

1. **`Deduplicator.java:79`** — the pair-key separator was a *raw NUL byte* inside the
   string literal, which made every `grep`, diff and code-review tool treat the file as
   binary. Replaced with the escape `"\u0000"`: same character, same behaviour, file is
   now plain text. Re-verified: 75/75 unit tests and 43/43 simulation checks still pass.
2. **`pom.xml`** — added a Surefire `includes` block for `**/*IT.java`. Surefire's defaults
   are `Test*` / `*Test` / `*Tests` / `*TestCase`, so `SchemeLifecycleIT` was **silently
   never run** by `mvn verify`, despite being the test that drives the scheme through HTTP
   and asserts the phase gates and maker–checker rules. `**/*Test.java` is listed alongside
   it so the existing tests keep running.

3. **`ExplanationService` + `DrawService`** — a real defect, found by `SchemeLifecycleIT`
   the moment change 2 let it run. `explain()` is `@Transactional(readOnly = true)` and
   called `draws.selection(...)`, itself `@Transactional`, inside a `try/catch` for
   `NotFoundException` — the ordinary case of an applicant who is not on a draw's roll.
   Spring marks the surrounding transaction **rollback-only** when the inner transactional
   method throws, so catching the exception did not help: the request died at commit with
   `UnexpectedRollbackException`, surfacing as **HTTP 500 on
   `GET /api/public/applications/{id}/explanation`** for every applicant excluded from the
   roll — the ineligible and the duplicate-superseded. Precisely the people most likely to
   ask "why not me?", and the endpoint the README offers them by name.

   Fixed by asking instead of catching: `DrawService.findSelection(...)` returns
   `Optional<DrawSelectionEntity>`, `selection(...)` now delegates to it and keeps
   throwing for callers that want that, and `ExplanationService` takes the Optional. No
   exception crosses a transaction boundary.

   Verified end to end over HTTP on a scheme built for the case — one applicant refused on
   `INCOME_CEILING`, two eligible, draw published:

   ```
   GET /api/public/applications/<ineligible-id>/explanation   ->  HTTP 200
   {"status":"INELIGIBLE",
    "statusReason":"INCOME_CEILING: Declared income above the published ceiling",
    "eligibilityChecks":[{"checkCode":"INCOME_CEILING","passed":false,
      "reason":"Declared income above the published ceiling","evidenceRef":"REGISTER/17",
      "decidedBy":"officer.y@s"}],
    "draws":[],"allotments":[]}
   ```

   The applicant now gets the published condition they failed, the evidence reference and
   the officer who decided it — read back verbatim, which is what the design promises.
   After the fix: `SchemeLifecycleIT` passes, 75/75 unit tests pass, 43/43 simulation
   checks pass.

   Worth stating plainly: the test that catches this had been in the repository all along
   and had never once run, because Surefire's default includes do not match `*IT`. The
   bug was reachable from the public API in the demo profile.

## 7. Admin reads now require an actor (was the open item)

Probing the running service turned up **13** endpoints that answered with no
`X-Actor-Id` / `X-Actor-Role` at all, while every write required, role-checked and audited
one. Two of them returned personal data:

```
GET /api/admin/schemes/{s}          /schemes/{s}/status   /schemes/{s}/rule-sets
    /rule-sets/{version}            /schemes/{s}/duplicate-review-queue
    /rolls/{id}                     /rolls/{id}/canonical
    /draws/{id}                     /schemes/{s}/draws    /draws/{id}/waitlist
    /draws/{id}/allotments
GET /api/applications/{id}                          <- name, phone, date of birth, address
    /api/applications/{id}/eligibility-checks       <- the evidenced decisions on a person
```

All 13 now take the two headers and check the role, through one `requireReader` helper per
controller: scheme and draw reads accept `SCHEME_ADMIN`, `VERIFIER` or `AUDITOR`;
application reads also accept `DATA_ENTRY`, the clerk who keyed the form. `AUDITOR` is
included deliberately — an auditor reads everything and writes nothing, which is the
design's own rule.

Verified live against every one of them:

| Caller | Result |
|---|---|
| no headers | **400** "X-Actor-Id is required; every request must state who is making it" |
| `SCHEME_ADMIN` | **200** |
| `AUDITOR` | **200** |
| `APPLICANT` | **403** "role APPLICANT may not perform this action; it requires one of [SCHEME_ADMIN, AUDITOR, VERIFIER]" |

And the six public endpoints — `audit/head`, the draw JSON, `result`, `roll`, an
applicant's `explanation`, the verification bundle — all still answer **200** with no
credentials, which is the property that makes the scheme checkable by outsiders.

`SchemeLifecycleIT` caught its own six now-gated reads and was updated to send headers on
the five staff ones; the four public reads in it deliberately still send none, so the test
asserts both halves. `README.md` said admin endpoints needed the headers and was right
about writes only — it now says reads too.

After the change: `SchemeLifecycleIT` passes, 75/75 unit tests, 43/43 simulation checks.

## 8. Notes on this environment

- The Linux side of the workspace had only a Java 11 *runtime*; JDK 21 and Maven were
  fetched from the Ubuntu archive into a scratch directory. Nothing was installed
  system-wide and nothing outside the repo and `.m2` was touched.
- Maven Central is unreachable from the sandbox, so the build ran `-o` (offline) against
  your cached `.m2`. Two artifacts are missing there because Eclipse never needed them:
  `spring-boot-loader-tools` and friends (so `mvn package`'s `repackage` goal — the fat jar
  — cannot run offline) and `surefire-junit-platform` (so Surefire cannot run tests
  offline). Both resolve on the first networked `mvn verify` from Windows.
- Two files I could not delete, because deleting files on your machine needs a separate
  per-folder permission: `target/core-src.tar.gz` (staging tarball; `target/` is gitignored,
  `mvn clean` removes it) and `.m2/_to_delete/.probe` (an empty write-permission probe).
- `git status` shows every tracked file as modified, but each diff is 0 insertions /
  0 deletions — a file-mode artefact of the folder mount, not content. The only real
  content changes are the two in §6, plus `ARCHITECTURE.md` and this file.
