# Orchestration Reliability Workbench

A small workbench that simulates a five-step business process and records every
execution and step attempt in PostgreSQL.

Steps: `VALIDATE_ORDER → RESERVE_INVENTORY → CHARGE_PAYMENT → CREATE_SHIPMENT → SEND_CONFIRMATION`

Stack: Java 21, Maven, Jersey 3.1 (JAX-RS) on embedded Grizzly, plain JDBC, PostgreSQL, JUnit 5.

## Status

- [x] Milestone 1: run all five steps (happy path), persist the execution and its attempts, JSON API
- [ ] Milestone 2: failure injection and error capture
- [ ] Milestone 3: retry from the failed step (including repeated and concurrent retry requests)
- [ ] Milestone 4: execution history and web UI

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

Then open http://localhost:8080/ for the web UI (currently a hello-world page), or use the API:

```bash
curl -i -X POST http://localhost:8080/api/executions   # start and run an execution
curl http://localhost:8080/api/executions/1            # inspect it
```

## API

| Method | Path                   | Description |
|--------|------------------------|-------------|
| GET    | `/`                    | Web UI (static files from `src/main/resources/static`). |
| POST   | `/api/executions`      | Create an execution and run all steps synchronously. Returns `201` with the execution. |
| GET    | `/api/executions/{id}` | The execution and all its step attempts. `404` if unknown. |

## Data model

- `executions`: one row per process run (`status`, `created_at`, `started_at`, `finished_at`).
- `step_attempts`: append-only, one row per attempt of a step (`step_index`, `attempt_no`,
  `status`, error fields, timestamps). A step's current status is its latest attempt.

## Troubleshooting

**Maven fails with `PKIX path building failed` / certificate errors** (typical behind a
corporate TLS-inspecting proxy such as Zscaler): import your company root certificate
into the JDK truststore, or point Maven at a truststore that contains it:

```bash
keytool -importcert -alias corp-root -file company-root-ca.pem \
        -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
```
