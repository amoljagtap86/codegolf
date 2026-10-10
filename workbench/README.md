# Orchestration Reliability Workbench

A small workbench that simulates a five-step business process, defined as a DAG, and records
every execution and step attempt in PostgreSQL.

```
                 ┌─► RESERVE_INVENTORY ─┐
VALIDATE_ORDER ──┤                      ├─► CREATE_SHIPMENT ─► SEND_CONFIRMATION
                 └─► CHARGE_PAYMENT ────┘
```

Stack: Java 21, Maven, Jersey 3.1 (JAX-RS) on embedded Grizzly, plain JDBC, PostgreSQL, JUnit 5.

## Status

- [x] Milestone 1: run all five steps (happy path), persist the execution and its attempts, JSON API
- [x] Milestone 2: failure injection and error capture
- [x] Milestone 3: retry from the failed step (including repeated and concurrent retry requests)
- [x] Milestone 4: execution history and web UI
- [x] Milestone 5a: process as a DAG, run one step at a time
- [ ] Milestone 5b: run independent steps in parallel

## Prerequisites

- JDK 21+
- Maven 3.9+
- PostgreSQL (any recent version) with **two** databases: one for the app, one for tests.

The test suite **truncates all tables** in the test database, so never point
`TEST_DATABASE_URL` at a database with data you care about.

### Example local setup

```bash
psql -U postgres -c "CREATE ROLE workbench LOGIN PASSWORD 'workbench';"
psql -U postgres -c "CREATE DATABASE workbench OWNER workbench;"
psql -U postgres -c "CREATE DATABASE workbench_test OWNER workbench;"
```

## Configuration

| Variable            | Used by | Example |
|---------------------|---------|---------|
| `DATABASE_URL`      | app     | `postgresql://workbench:workbench@localhost:5432/workbench` |
| `TEST_DATABASE_URL` | tests   | `postgresql://workbench:workbench@localhost:5432/workbench_test` |
| `PORT`              | app     | `8080` (default) |

Both URL forms are accepted:

- libpq style: `postgresql://user:password@host:5432/db?sslmode=require` (URL-encode special characters in the password)
- JDBC style: `jdbc:postgresql://host:5432/db?user=user&password=password`

The schema (`src/main/resources/schema.sql`) is applied automatically on startup
and before tests. It is idempotent.

## Run the tests

```bash
cd workbench
export TEST_DATABASE_URL='postgresql://workbench:workbench@localhost:5432/workbench_test'
mvn test
```

## Run the app

```bash
cd workbench
export DATABASE_URL='postgresql://workbench:workbench@localhost:5432/workbench'
mvn compile exec:java   # exec:java alone does not recompile
```

Then open http://localhost:8080/ for the web UI (see below), or use the API:

```bash
curl -i -X POST http://localhost:8080/api/executions   # start and run an execution
curl http://localhost:8080/api/executions/1            # inspect it

# simulate a failure: step 3 fails on its first attempt
curl -i -X POST http://localhost:8080/api/executions \
     -H 'Content-Type: application/json' -d '{"failStep": 3, "failTimes": 1}'

# retry it from the failed step (use the id returned above)
curl -i -X POST http://localhost:8080/api/executions/2/retry
```

## Web UI

- **`/`**: start an execution (optionally choosing a failing step and how many times it fails) and
  browse the execution history, newest first, with attempt and retry counts.
- **`/execution.html?id=N`**: the step-by-step timeline. Each step shows its derived status
  (`PENDING` = not attempted yet) and every attempt with start time, duration, error message, and
  a collapsible stack trace. A **Retry from failed step** button appears for `FAILED` executions;
  if the retry is rejected (e.g. another tab retried first), the server's `409` message is shown.

Plain HTML, CSS and vanilla JavaScript in `src/main/resources/static`; no build step. Data is
inserted with `textContent`/`createElement`, never `innerHTML`.

## API

| Method | Path                   | Description |
|--------|------------------------|-------------|
| GET    | `/`                    | Web UI (static files from `src/main/resources/static`). |
| GET    | `/api/executions`      | Execution history, newest first: summaries with `attemptCount` and `retryCount`. `?limit=` 1–200, default 50; otherwise `400`. |
| GET    | `/api/executions/steps` | The process DAG: `[{"index", "name", "dependsOn", "stage"}]` in definition order. |
| POST   | `/api/executions`      | Create an execution and run its steps synchronously. Returns `201` with the execution, also when the process failed (`status: FAILED`). Optional JSON body, see below. |
| GET    | `/api/executions/{id}` | The execution and all its step attempts. `404` if unknown. |
| POST   | `/api/executions/{id}/retry` | Retry a `FAILED` execution from its failed step. `200` with the execution after the retry ran (it may be `FAILED` again). `409` if the execution is not `FAILED`, including when a concurrent retry got there first. `404` if unknown. |

### Simulating failures

`POST /api/executions` accepts an optional failure plan:

```json
{"failStep": 3, "failTimes": 1}
```

- `failStep` (1–5): the step that fails.
- `failTimes` (≥ 1, default 1): how many attempts of that step fail before it succeeds. The plan is
  stored with the execution, so retries replay it: with `failTimes: 2` the first retry fails again.
- No body: happy path. Invalid plan or malformed JSON: `400`, nothing is created.

When a step fails, its attempt is marked `FAILED` with `errorMessage` and `errorDetail` (stack trace),
the execution is marked `FAILED`, and later steps are not attempted (they have no attempt rows).

### Process definition and scheduling

The DAG is defined in code (`ProcessDefinition.ORDER`) and validated at startup: step names are
unique, and every dependency must name a step defined *earlier* in the list. That one rule rejects
unknown dependencies and cycles, and makes definition order a valid execution order. `stage` is the
dependency depth, used by the UI to group the timeline.

- A step is **ready** when all its dependencies have succeeded and it has not. The runner repeatedly
  runs the first ready step (milestone 5a runs one step at a time) until none is left.
- **Failure policy:** once a step fails, no further step starts, not even an independent one, and
  the execution fails. Example: if `RESERVE_INVENTORY` fails, `CHARGE_PAYMENT` is not started, so a
  customer is never charged for an order that cannot ship.
- The graph is not versioned per execution: if it changes, retries of old executions use the new graph.

### Retry semantics

- A retry runs every step without a successful attempt, each once its dependencies have succeeded.
  Succeeded steps are never rerun.
- Each retry adds new attempt rows (`attempt_no` 2, 3, ...); earlier attempts are kept as history.
- Only one retry can run at a time: the retry first claims the execution with a single atomic
  `UPDATE ... SET status = 'RUNNING' WHERE status = 'FAILED'`. Concurrent or repeated requests
  that lose the claim get `409` and change nothing. The `UNIQUE (execution_id, step_index, attempt_no)`
  constraint is a second line of defence.

## Data model

- `executions`: one row per process run (`status`, `created_at`, `started_at`, `finished_at`).
- `step_attempts`: append-only, one row per attempt of a step (`step_index`, `attempt_no`,
  `status`, error fields, timestamps). A step's current status is its latest attempt.

## Known limitations

- An execution whose process crashes mid-run (e.g. the JVM dies or the database goes away) stays
  `RUNNING`; it cannot be retried (`409`) and there is no recovery yet.
- Requests with an `Upgrade: h2c` header (Java's `HttpClient` with default settings, `curl --http2`)
  and a body hang for 30 s and fail with `500`: Grizzly withholds the body of upgrade requests.
  Browsers and plain `curl` are not affected. Use HTTP/1.1 in Java clients.

## Troubleshooting

**Maven fails with `PKIX path building failed` / certificate errors** (typical behind a
corporate TLS-inspecting proxy such as Zscaler): import your company root certificate
into the JDK truststore, or point Maven at a truststore that contains it:

```bash
keytool -importcert -alias corp-root -file company-root-ca.pem \
        -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
```
