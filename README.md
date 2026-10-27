# Validation Cockpit — Flowable C2 experiment

Standalone synthetic PoC. Java owns validator selection, dependency planning and domain data. Flowable owns execution of **each validator**, persisted stage barriers, jobs, retries and process history. No customer code or infrastructure is required.

## Versions and build

Java **17**, Maven 3.9+, Spring Boot **4.0.2**, Flowable OSS **8.0.0**, file H2, JPA, JUnit **5.13.4**, AssertJ. Compiler release is 17; Jupiter is explicitly pinned because Boot 4 defaults to JUnit 6.

Official compatibility evidence:

- [Flowable 8 release notes](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0): Spring 7 / Boot 4, Java 17 baseline.
- [Release dependency BOM](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-dependencies/pom.xml): Boot 4.0.2 and Spring 7.0.3.
- [Flowable Boot documentation](https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot/): Boot 4 support and process-only starter.
- [Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html): Java 17+.

```sh
export JAVA_HOME=/path/to/jdk-17
./mvnw clean verify
./mvnw spring-boot:run
# Alternatively:
java -jar target/validation-cockpit-1.0.0.jar
```

For this workspace, a local Java 17 installation is also available at `.tools/amazon-corretto-17.jdk/Contents/Home` (ignored build tooling). You can use `export JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home"` from the project directory.

The default database is `./data/validation.mv.db`, relative to the working directory. Use the same working directory across restarts. Override with `DB_URL`; only one application may open this embedded file database at a time. Ports and other Spring settings can be overridden normally.

## BPMN and responsibilities

`src/main/resources/processes/validation.bpmn20.xml` has no validator IDs or dependencies. Java dynamically topologically sorts the registry's dependency graph into stages: P1; P2+P3; P4. The process resolves this plan once, stores it as process variables, and loops over its stages.

A parallel multi-instance subprocess creates one instance per validator in the current stage. Each instance contains:

1. An **async, nonexclusive** validator service task. Worker threads can execute P2 and P3 simultaneously. The configured retry cycle is `R3/PT1S` (three total failed attempts before dead letter).
2. A separate **async, exclusive** join task. Its committed incoming boundary separates the validator transaction from multi-instance completion. Exclusive jobs serialize stage-counter updates within a process. Any optimistic locking at completion retries the no-op join rather than the validator body.

The engine's multi-instance barrier advances the stage only when every instance ends. The Java plan adapter only resolves/advances process variables; it does not schedule validators, wait for futures, manage retries or persist a parallel workflow state machine. Successful completed instances remain committed while another validator retries. Executor thread-pool configuration and proof of actual overlap are covered by integration tests.

## REST examples

```sh
curl -s localhost:8080/api/v1/validation-runs \
  -H 'Content-Type: application/json' \
  -d '{"requestKey":"application-1-v1","p3Failures":0}'

curl -s localhost:8080/api/v1/validation-runs/RUN_ID

curl -s localhost:8080/api/v1/validation-runs/RUN_ID/re-evaluations \
  -H 'Content-Type: application/json' \
  -d '{"requestKey":"application-1-v2","validators":["P3"]}'
```

Responses include run/process IDs, original-run correlation, engine-derived state (`RUNNING`, `COMPLETED`, `INCIDENT`), active activities, domain results/findings, durable attempt records and jobs with remaining retries and exception messages. Poll GET for a consistent eventual result; inspection assembles several engine/repository queries and is not an atomic snapshot.

`requestKey` is globally unique and represents one immutable logical request. Repeating a key returns its existing run, including after completion. A unique database constraint arbitrates concurrent creation; the loser reads the winner only after its failed transaction has rolled back. The caller must use a new key for changed input. This small PoC does not fingerprint or reject changed payloads under an existing key.

## Failure and retry demonstrations

Start with `{"requestKey":"retry-demo","p3Failures":2}`. P3 throws on attempts 1 and 2 and succeeds on attempt 3. Observe timer jobs and attempt outcomes with GET. P1/P2 are not repeated and P4 waits.

Start with `{"requestKey":"incident-demo","p3Failures":3}`. After three failures the process remains active, P4 has no result and a dead-letter incident is exposed. This synthetic failure expires on attempt 4, so operator recovery can be demonstrated:

```sh
curl -s -X POST localhost:8080/api/v1/validation-runs/RUN_ID/dead-letter-retries
```

This invokes Flowable's dead-letter-to-executable API with three new retries. It does not reconstruct workflow progress. Real incidents need diagnosis before requeue. The endpoint is deliberately unauthenticated for local evaluation; this is not a production service.

For business failure use `{"requestKey":"finding-demo","businessFailure":"P3"}`. A finding produces `INVALID`, commits normally and does not trigger technical retries. This PoC runs downstream validators even when upstream results are invalid; stage dependencies mean “completed,” not “valid.” A real product must define blocking/skipping semantics explicitly.

## Restart demonstration

```sh
ASYNC_ENABLED=false java -jar target/validation-cockpit-1.0.0.jar
# POST a run and save its ID. GET shows pending ASYNC jobs, no attempts.
# Stop the application with Ctrl-C, then restart from the same directory:
java -jar target/validation-cockpit-1.0.0.jar
# GET the saved ID until COMPLETED; no workflow reconstruction is needed.
```

The automated restart test closes and recreates complete Spring application contexts against the same file database, initially with acquisition disabled. It verifies durable pending-job recovery. This is a graceful restart test, not a kill-at-every-instruction crash simulation. Timer retry jobs are also engine state, but crash timing around worker locks is not exhaustively tested. After abrupt termination acquired jobs may wait for their lock timeout before recovery.

Committed jobs, process variables, results and history survive. In-flight uncommitted validator/result work rolls back and can run again. Independent attempt audit records may survive even when the execution transaction does not. External side effects are **not exactly once**; use downstream idempotency tokens/outbox patterns as appropriate. A stable result key is `runId:validatorId`.

## Persistence and transactions

Flowable and JPA use one Spring-managed datasource and transaction manager. Each async validator job has a transaction containing its domain result and engine transition to the join job. A technical exception rolls both back; Flowable records failure/retry in its own engine handling. No database transaction spans the whole workflow. Independent validator jobs hold their own transactions; slow remote calls would keep a connection/transaction open and require additional design.

`AttemptAudit` uses `REQUIRES_NEW` transactions to preserve synthetic attempt counts across rollback and restart. Its table has no foreign-key dependency on rows locked by the job. It is an evaluation audit/failure simulator, **not a job queue or retry scheduler**. `RETURNED` means the validator returned; it does not independently prove that the surrounding job committed. A crash can leave `STARTED`. Business results and Flowable history remain the sources for committed work.

Domain result uniqueness and the existence guard provide a local idempotency safeguard. They do not promise exactly-once validator invocation under all crashes/concurrent duplicate delivery. Attempts are numbered per run/validator; the engine normally runs only one job per instance. Concurrent manual execution of the same locked job is outside the supported test setup.

Schema auto-update (`flowable.database-schema-update=true`, Hibernate `update`) is convenient for a disposable PoC. Production requires explicit schema upgrades, backups, retention and operational incident handling. Do not delete the data directory during a recovery demonstration.

## Selective re-evaluation

Only completed runs with a full result set can be re-evaluated. Java computes the transitive dependent closure of requested validators; invalid prior results and their dependents are also included. Requesting P3 on an all-valid run selects P3+P4 and copies P1/P2 results into a new correlated run with provenance and zero new attempts. The original results/history remain untouched. No active process is rewound. Synthetic inputs are assumed immutable per request; input versioning and richer validity/cache rules are outside scope.

## Tests and assessment

```sh
./mvnw clean verify
```

Tests use a temporary **file** H2 database, real Spring contexts, real deployed BPMN and real Flowable workers. The parallel test uses a two-party barrier inside P2/P3, so a serialized executor cannot pass. No mocked runtime or manual shortcut substitutes for engine execution. Tests also cover retry isolation, exhausted retries and requeue, concurrent request deduplication, findings, re-evaluation, and restart recovery.

See [docs/evaluation.md](docs/evaluation.md) for measured results, architectural tradeoffs and recommendation.
