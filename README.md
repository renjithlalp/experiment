# Experiment Configuration (IDRP-30326)

This is the backend for setting up airline pricing experiments. It covers:

- experiment details
- the test/control split with a mandatory control group
- the bucketing identifier and its fallback order
- the pricing treatment with floor and ceiling guardrails
- the lifecycle Draft → Scheduled → Running ⇄ Paused → Ended
- the running lock
- priority
- an append-only version history

Customer assignment, runtime treatment and the GUI are out of scope. They belong to later epics.

| Document | Contents |
|---|---|
| [docs/stories.md](docs/stories.md) | Stories S1–S11 and tasks T1–T4 with acceptance criteria, plus the assumptions to confirm with the epic owner |
| [docs/flow-diagram.md](docs/flow-diagram.md) | Mermaid diagrams: configuration flow, state machine, hand-off sequence |
| [docs/experiment-flow.png](docs/experiment-flow.png) / [.svg](docs/experiment-flow.svg) | The one-page diagram to attach to the Jira epic |

## Run

Requires Java 17 and Maven 3.8+.

```
mvn package          # builds and runs the tests
run.cmd              # Windows   (or ./run.sh) — http://localhost:8090
```

Environment variables:

- `PORT` (default 8090)
- `TICK_SECONDS` (default 30): how often the scheduler checks start and end times

Storage is in memory, so data is lost on restart.

## API

Every change needs an `X-User` header. Edits and actions accept `If-Match: <version>` for optimistic locking; a stale version returns 409 `VERSION_CONFLICT`.

| Method & path | Purpose |
|---|---|
| `POST /api/experiments` | Create a Draft. Only the details and priority are required. |
| `GET /api/experiments?state=RUNNING` | List experiments, with an optional state filter. |
| `GET /api/experiments/{id}` | Current state, `allowedActions`, `editable`, and an `ETag` with the version. |
| `PUT /api/experiments/{id}` | Replace the configuration of a Draft. Creates a new version. |
| `POST /api/experiments/{id}/actions/{publish\|unpublish\|pause\|resume\|end}` | Lifecycle actions. Optional body `{"reason": "..."}`. |
| `GET /api/experiments/{id}/versions` and `/versions/{n}` | History: who, when, state, changed fields, full snapshot. |
| `GET /api/experiments/{id}/served?at=2027-01-10T12:00:00Z` | Which config version was live at that instant, or whether the default experience was served. |
| `GET /api/bucketing/active` | Running experiments for the bucketing engine, highest priority first. |
| `GET /api/catalogue` | The product codes and fare brands a treatment may use. |

Errors share one shape:

```json
{"status":409,"code":"EXPERIMENT_LOCKED","message":"Experiment EXP-000001 is RUNNING; only pause, resume or end are allowed ...","errors":[]}
```

| Code | Meaning |
|---|---|
| `VALIDATION_FAILED` (400) | Lists every failing field. |
| `NOT_FOUND` (404) | The experiment, version or path does not exist. |
| `EXPERIMENT_LOCKED` (409) | The experiment is Scheduled, Running or Paused and cannot be edited. |
| `INVALID_TRANSITION` (409) | That move is not allowed from the current state. |
| `READ_ONLY` (409) | The experiment has Ended. |
| `VERSION_CONFLICT` (409) | The `If-Match` version is stale. |

### Example

```sh
curl -X POST localhost:8090/api/experiments -H "Content-Type: application/json" -H "X-User: maria" -d '{
  "name": "Extra baggage 10% discount",
  "startAt": "2027-01-04T00:00:00Z", "endAt": "2027-02-01T00:00:00Z",
  "treatmentType": "PRICING", "priority": 10 }'

curl -X PUT localhost:8090/api/experiments/EXP-000001 -H "Content-Type: application/json" -H "X-User: maria" \
  -H 'If-Match: "1"' -d '{
  "name": "Extra baggage 10% discount",
  "startAt": "2027-01-04T00:00:00Z", "endAt": "2027-02-01T00:00:00Z",
  "treatmentType": "PRICING", "priority": 10,
  "split": {"testPercent": 90, "controlPercent": 10},
  "bucketing": {"primary": "USER_ID", "fallbackOrder": ["DEVICE_COOKIE_ID", "SESSION_ID"]},
  "pricing": {"adjustment": "DISCOUNT", "valueType": "PERCENT", "value": 10, "currency": "EUR",
              "floor": 20, "ceiling": 120, "productCodes": ["BAG_EXTRA_23KG"], "fareBrands": ["LIGHT"]} }'

curl -X POST localhost:8090/api/experiments/EXP-000001/actions/publish -H "X-User: maria"
```

## Layout

```
domain/      records and enums: ExperimentConfig, Experiment, ExperimentVersion, LifecycleAction (the allowed moves)
validation/  ExperimentValidator: save rules and publish rules
service/     ExperimentService (lock, lifecycle, versions, served-at, bucketing feed), LifecycleScheduler, ConfigDiff
store/       ExperimentRepository + in-memory implementation (append-only history)
catalogue/   ProductCatalogue + sample in-memory catalogue
web/         ApiServer (JDK HttpServer + Jackson)
```

## Next steps (not done)

- Add a PostgreSQL `ExperimentRepository`, with version rows written in the same transaction as the experiment row.
- Plug in the real product catalogue service behind `ProductCatalogue`.
- Confirm the open questions at the end of `docs/stories.md`.
