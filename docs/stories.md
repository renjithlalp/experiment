# IDRP-30326 — Experiment Configuration and Test/Control Split Management

Story breakdown with acceptance criteria. Stories S1–S11 map to the epic's "Key user stories" and "In scope" sections; T1–T4 are enabling technical tasks. The decisions this breakdown had to make where the epic is silent or contradicts itself are listed at the end under **Assumptions and open questions**. Confirm them with the epic owner before the stories are committed to a sprint.

Conventions used in the criteria:

- "Rejected" means the API answers with an error, changes nothing and does not create a version. Validation errors return HTTP 400 and list every failing field with a readable message. Lock and lifecycle errors return HTTP 409.
- "User" is the value of the `X-User` header. Access Control is out of scope, so the user is recorded but not authorised.
- Each successful change creates exactly one new version (see S10).

---

## S1 — Define experiment details

> As a merchandising manager, I can define an experiment with a name, start and end date, and treatment type, so it is clearly identified and runs only in its planned period.

**Acceptance criteria**

1. An experiment cannot be saved without a name, a start date-time, an end date-time and a treatment type. Each missing field is reported by name.
2. The name is 1–100 characters and is not blank. A description is optional (max 500 characters).
3. The end date-time must be after the start date-time. Otherwise: *"endAt must be after startAt"*.
4. Treatment type accepts `PRICING` only in this epic. Any other value is rejected.
5. The treatment type decides which treatment section is accepted: with `PRICING`, the `pricing` section is accepted. A treatment section that does not match the type is rejected.
6. Details come first: an experiment can be created with only details and priority. The split, bucketing identifier and treatment can be added later while it is a Draft.
7. A new experiment is created in state **Draft** as version 1. The response returns its id.
8. Dates are ISO-8601 instants (UTC). Example: `2027-01-04T00:00:00Z`.

## S2 — Configure the test / control split

> As an airline user, I want to set the traffic split between a test cohort and a mandatory control group, so that I can compare the treatment against a baseline.

**Acceptance criteria**

1. The split has exactly two shares: `testPercent` and `controlPercent`. Additional cohorts cannot be expressed.
2. Both shares are whole numbers and must add up to exactly 100. Example: 90/10 is accepted, 90/20 is rejected with *"testPercent + controlPercent must equal 100"*.
3. The control share must be above zero. 100/0 is rejected with *"control group is mandatory and must be above 0%"*.
4. The test share must also be above zero (a 0/100 test has no treatment).
5. The control group cannot be removed. Sending a split without `controlPercent` is rejected.
6. An experiment cannot be published without a split (S6).

## S3 — Choose the bucketing identifier and fallback order

> As an airline user, I want to choose the identifier used to assign customers and the fallback order, so that customers are assigned consistently even when the main identifier is missing.

**Acceptance criteria**

1. Exactly one primary identifier is chosen from `USER_ID`, `DEVICE_COOKIE_ID`, `SESSION_ID` or `MOBILE_APP_ID` (UDID on iOS / SSAID on Android).
2. A fallback order can be stored as an ordered list of the remaining identifiers. It may be empty.
3. The fallback order must not repeat an identifier and must not contain the primary identifier.
4. The fallback order is saved with the experiment and appears in every version snapshot.
5. The bucketing feed (S11) passes the primary identifier and the fallback order to the bucketing engine. This epic does not assign customers.
6. An experiment cannot be published without a bucketing identifier.

## S4 — Define the pricing treatment

> As a merchandising manager, I can define a pricing treatment as discount / markup / flat rate, so I can test a price change on a share of customers.

**Acceptance criteria**

1. `adjustment` is one of `DISCOUNT`, `MARKUP` or `FLAT_RATE`.
2. `DISCOUNT` and `MARKUP` need a `valueType` of `PERCENT` or `AMOUNT` and a `value` above 0.
3. A `PERCENT` discount cannot exceed 100. A `PERCENT` markup cannot exceed 1000.
4. `FLAT_RATE` sets a fixed price. Its `value` is an amount above 0. `valueType` is `AMOUNT`, or it may be left out.
5. A 3-letter uppercase `currency` (ISO 4217) is required.
6. The treatment applies to one or more `productCodes`. `fareBrands` is optional and narrows it further.
7. An experiment of type `PRICING` cannot be published without a pricing treatment.

## S5 — Set pricing guardrails

> As a merchandising manager, I can set a floor and ceiling value, so a test never goes beyond my price limits.

**Acceptance criteria**

1. `floor` and `ceiling` are required, set per experiment, and expressed in the treatment currency.
2. The floor must be 0 or more, and must be below the ceiling. A floor above or equal to the ceiling is rejected with *"floor must be below ceiling"*.
3. For `FLAT_RATE`, the flat price must lie between the floor and the ceiling, inclusive.
4. Every product code must exist in the product catalogue. Unknown codes are listed in the error.
5. Every fare brand must exist in the product catalogue. Unknown fare brands are listed in the error.

## S6 — Move an experiment through its lifecycle

> As a merchandising manager, I can move an experiment through Draft, Scheduled, Running, Paused and Ended, and only valid moves are possible.

**Acceptance criteria**

1. Allowed moves:

   | Action | From | To | Who |
   |---|---|---|---|
   | publish | Draft | Scheduled | user |
   | unpublish | Scheduled | Draft | user |
   | start | Scheduled | Running | system, when the start time is reached |
   | pause | Running | Paused | user |
   | resume | Paused | Running | user |
   | end | Scheduled / Running / Paused | Ended | user, or system when the end time is reached |

2. Any other move is rejected with HTTP 409 and a message naming the current state and the allowed actions. Example: Draft → Running is rejected.
3. Publishing runs the full validation: the split, the bucketing identifier and the treatment must all be present and valid, and the end time must be in the future.
4. Resume is rejected when the end time has passed.
5. Each state change records the user (or `system`), the time, and an optional reason. It is stored as a new version.
6. **Ended** is read-only. Every change or action on an ended experiment is rejected.
7. While **Paused**, the experiment is left out of the bucketing feed, so all traffic gets the default experience. On resume it returns with the same configuration version, so customers stay in the same groups.

## S7 — Start and end experiments automatically

**Acceptance criteria**

1. A background job runs every 30 seconds (configurable).
2. When a Scheduled experiment's start time is reached, the job moves it to Running, recorded as user `system`.
3. When a Running or Paused experiment's end time is reached, the job moves it to Ended, recorded as user `system`.
4. When an experiment is published after its start time has already passed, it starts on the next run of the job.
5. The job is idempotent. Running it twice for the same instant changes nothing the second time.

## S8 — Running lock

> As a merchandising manager, I can pause, resume or end a running experiment but not edit it, so results stay valid.

**Acceptance criteria**

1. On a Running or Paused experiment, every edit is rejected with HTTP 409, code `EXPERIMENT_LOCKED`, and this message: *"Experiment is RUNNING; only pause, resume or end are allowed. If a different setup is needed, end this experiment and create a new one."* Edits include the name, the dates, the split, the identifier, the treatment and the priority.
2. Pause, resume and end remain allowed.
3. A Scheduled experiment cannot be edited. It has to be unpublished back to Draft first, so the published (approved) configuration cannot change silently.
4. A rejected edit does not create a version.

## S9 — Experiment priority

> As a merchandising manager, I can set a priority for each experiment, so the right experiment can be chosen later when a customer qualifies for more than one.

**Acceptance criteria**

1. Priority is required when the experiment is defined. It is a whole number from 1 to 1000, and a higher number means higher priority.
2. Priority can be changed while the experiment is a Draft. After that it is locked, like every other setting.
3. Priority is returned in the experiment and in the bucketing feed. Choosing between overlapping experiments is out of scope.

## S10 — Version history and audit

> As an analyst, I can view earlier versions of an experiment and see who changed what and when, so every change can be traced.

**Acceptance criteria**

1. Every successful create, edit or state change creates a new version. Versions are numbered 1, 2, 3 … and are never overwritten or deleted.
2. Each version records:
   - the version number
   - the change type (`CREATED`, `UPDATED` or `STATE_CHANGED`)
   - the user and the time
   - the state
   - the list of changed fields with their old and new values
   - a full snapshot of the configuration
3. `GET /experiments/{id}/versions` lists all versions, oldest first. `GET /experiments/{id}/versions/{n}` returns one version.
4. Edits use optimistic locking. A request with `If-Match: <version>` that does not match the current version is rejected with HTTP 409 `VERSION_CONFLICT`.
5. Each version also carries a `configVersion`. It changes only when the configuration changes, not on a pause or resume.

## S11 — Trace which version was applied to traffic

> As an analyst, I can see which version is applied to any traffic, so results can be traced.

**Acceptance criteria**

1. `GET /bucketing/active` returns every Running experiment, ordered by priority (highest first). Each entry carries:
   - `experimentId`
   - `configVersion`
   - `priority`
   - the split
   - the primary identifier and the fallback order
   - the treatment
   
   The bucketing engine stamps `experimentId` and `configVersion` on the traffic it assigns.
2. `GET /experiments/{id}/served?at=<instant>` answers which configuration version was live at that instant. When the experiment was not Running at that time, it reports that the default experience was served and gives the state.
3. Paused, Scheduled, Draft and Ended experiments are never in the active feed.

---

## Technical tasks

### T1 — Project skeleton and persistence

- Create a separate Maven project (Java 17) with a runnable jar and an HTTP server.
- Put a repository interface in front of storage, and keep version history append-only.
- Start with an in-memory implementation. Swapping in PostgreSQL is a follow-up.

### T2 — Error model and API conventions

- Errors share one JSON shape: `{status, code, message, errors:[{field, message}]}`.
- Status codes: 400 validation, 404 not found, 409 lock / transition / version conflict, 415 when the body is not JSON.
- Unknown JSON fields are rejected, so typos are not silently ignored.

### T3 — Product catalogue integration

- Validation goes through a `ProductCatalogue` interface.
- The in-memory catalogue is seeded with sample products and fare brands. The real catalogue service plugs in behind the same interface.

### T4 — API documentation and examples

- The README lists every endpoint with a curl example.
- The flow diagrams are in `docs/flow-diagram.md` and `docs/experiment-flow.svg`.

---

## Assumptions and open questions (confirm with the epic owner)

| # | Topic | Decision taken here | Why |
|---|---|---|---|
| 1 | Treatment type | `PRICING` only. Recommendation is a later epic. | In Scope says "Choose Pricing" and Out of Scope lists Recommendation, but Assumptions say "pricing or recommendation". |
| 2 | Version example "end date extended" | Allowed only in Draft. Scheduled must be unpublished first, and Running / Paused cannot be edited. | The running lock explicitly forbids editing dates. |
| 3 | Audit vs "Audit History" out of scope | Version history with field changes is built. A cross-experiment audit search UI is not. | Both appear in the epic. |
| 4 | Who publishes | Any user (`X-User`). There is no approval workflow. | Workflow Management and Access Control are out of scope. |
| 5 | Automatic transitions | Start and end happen automatically at the configured instants. Paused experiments also end at the end time. | The epic does not say. These are the least surprising choices. |
| 6 | Floor / ceiling | Absolute amounts in the treatment currency, per experiment. | The epic does not say whether they are amounts or percentages. |
| 7 | Flat rate | A fixed final price. | "Flat rate" is not defined in the epic. |
| 8 | Split precision | Whole percentages, with both shares ≥ 1. | Not defined in the epic. |
| 9 | Priority | 1–1000, higher wins. Duplicates are allowed for now (resolution is a future epic). | Not defined in the epic. |
| 10 | Session as primary identifier | Allowed, but a session identifier cannot keep a customer in the same group across visits. | Flag this to the bucketing epic. |
| 11 | Dates | UTC instants. | Time zone is not specified. |
| 12 | Traffic → version | The feed exposes `configVersion`, and the bucketing engine must stamp it on traffic. | Runtime assignment is out of scope. |
