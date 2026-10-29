# Validation Cockpit – Flowable Orchestration PoC (C1)

A standalone, synthetic proof of concept for orchestrating Validation Cockpit validators with **Flowable 8**. Java computes the validator plan (dependencies P1 → P2 ∥ P3 → P4). A generic BPMN process runs **each validator as its own durable async job**, followed by a separate async join job, with stage barriers, retries, dead-letter incidents, graceful restart continuation and engine history. Business results, findings, idempotency and re-evaluation lineage stay in the application database.

No customer code or infrastructure is required. The validators are synthetic and can simulate technical failures and business findings on demand.

> [!NOTE]
> In the approach comparison this PoC is **C1** ("Flowable as reliability engine"). Earlier documents called it "C2"/"hybrid C2". That label now refers to the not-yet-built BPMN-native approach.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Feature/package structure, hexagonal boundaries, BPMN model and variable contract, transactions, retry/recovery, status derivation, design decisions |
| [docs/flowable-approach-comparison.md](docs/flowable-approach-comparison.md) | C0 vs. C1 vs. C2: verified evidence, effort avoided, remaining custom code, added complexity, production risks, open decision |

## Technology

Java **17**, Maven 3.9+ (wrapper included), Spring Boot **4.0.2** (Spring 7.0.3), Flowable OSS **8.0.0** (`flowable-spring-boot-starter-process`), Spring Data JPA / Hibernate 7.2.1, file-based H2 2.4.240, JUnit Jupiter **5.13.4** (pinned explicitly because Boot 4 defaults to JUnit 6), AssertJ, Awaitility.

Compatibility references: [Flowable 8 release notes](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0), [Flowable release BOM](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-dependencies/pom.xml), [Flowable Spring Boot docs](https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot/), [Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html).

## Prerequisites

- JDK 17 or newer on `JAVA_HOME`. In this workspace a Corretto 17 build is available at `.tools/amazon-corretto-17.jdk/Contents/Home` (git-ignored):
  ```sh
  export JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home"
  ```
- `curl` for the examples below.

## Build, test and run

```sh
./mvnw clean verify                          # compile, run all tests, build the jar
./mvnw spring-boot:run                       # or:
java -jar target/validation-cockpit-1.0.0.jar
```

| Setting | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:h2:file:./data/validation;DB_CLOSE_ON_EXIT=FALSE` | Embedded file database, relative to the working directory. Only one application can open it at a time. Use the same directory across restarts |
| `ASYNC_ENABLED` | `true` | Flowable async executor on/off (used for the restart demo) |
| `server.port` | `8080` | Any standard Spring property can be overridden |

Worker concurrency comes from Boot's task executor (`spring.task.execution.pool.core-size/max-size=4`). Job acquisition polls every 100 ms. Schema auto-update is enabled for convenience; it is **not** suitable for production. Do not delete `./data` during a recovery demo.

## REST API

Base path: `/api/v1/validation-runs`. Every endpoint returns the full run view.

| Method & path | Body | Purpose |
|---|---|---|
| `POST /` | `{"requestKey": "...", "p3Failures": 0, "businessFailure": null}` | Start a run (idempotent per `requestKey`) |
| `GET /{id}` | n/a | Inspect a run |
| `POST /{id}/re-evaluations` | `{"requestKey": "...", "validators": ["P3"], "p3Failures": 0, "businessFailure": null}` | Selective re-evaluation as a new correlated run |
| `POST /{id}/dead-letter-retries` | n/a | Move dead-letter jobs back to executable (budget of 3 executions) |

- `p3Failures` (0..100, default 0): P3 throws a technical exception on attempts 1..n.
- `businessFailure` (validator ID): that validator returns a business finding.
- Status codes: `200`; `400` for invalid input (blank key, `p3Failures` out of range, unknown validator, empty re-evaluation set, original run still active or incomplete); `404` for unknown run IDs.

The response contains `id`, `requestKey`, `originalRunId`, `processId`, `state` (`RUNNING`/`COMPLETED`/`INCIDENT`/`UNKNOWN`), `outcome` (`PENDING`/`VALID`/`INVALID`), `effectiveRunId`, `effectiveOutcome`, `correlatedRunIds`, `activities`, `results` (outcome, finding, attempt, `reusedFromRun`), `attempts` (number, timestamps, outcome, error) and `jobs` (kind `ASYNC`/`TIMER`/`DEAD_LETTER`, retries, exception). Inspection combines several engine and database queries, so poll `GET` for an eventually consistent result.

## Evaluation scenarios

Each scenario can be run by hand against a running application and is also covered by an automated test in [`ValidationIntegrationTest`](src/test/java/poc/ValidationIntegrationTest.java).

| Scenario | Request / steps | Expected result | Test |
|---|---|---|---|
| Normal run | `POST {"requestKey":"demo-1"}` | P1, then P2 ∥ P3, then P4. `COMPLETED`/`VALID` | `normalOrderingAndPersistence`, `actualParallelJobs` |
| Technical retry | `POST {"requestKey":"retry-demo","p3Failures":2}` | P3 attempts: 2× `TECHNICAL_FAILURE` + `RETURNED`. TIMER job visible meanwhile. P1/P2 not repeated. P4 waits | `technicalRetryIsIndividual` |
| Incident & recovery | `POST {"requestKey":"incident-demo","p3Failures":3}`, then `POST /{id}/dead-letter-retries` | `INCIDENT` with a DEAD_LETTER job and no P4. After requeue, P3 attempt 4 succeeds → `COMPLETED` | `exhaustedRetriesBecomeDeadLetter` |
| Business finding | `POST {"requestKey":"finding-demo","businessFailure":"P3"}` | P3 `INVALID` with a finding, no retry, P4 still runs. Outcome `INVALID` | `businessFindingDoesNotRetryOrBlockStages` |
| Duplicate request | Repeat any `POST` with the same `requestKey` | Same run ID, also concurrently and after completion | `concurrentDuplicateRequests` |
| Selective re-evaluation | `POST /{id}/re-evaluations {"requestKey":"demo-1-v2","validators":["P3"]}` on a completed run | New run executes P3+P4 only. P1/P2 copied with `reusedFromRun`. Original run untouched. Lineage and effective outcome updated | `selectiveReevaluation`, `restApiStartsInspectsAndReevaluates` |
| Graceful restart | See below | Run completes after orderly shutdown/restart without rebuilding the workflow | `restartResumesCommittedPendingJob`, `twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure` |
| Shared rollback | Test only | A failure after the result flush rolls back the result and the engine step together | `resultAndEngineJobRollbackTogether` |

Example:

```sh
curl -s localhost:8080/api/v1/validation-runs -H 'Content-Type: application/json' \
  -d '{"requestKey":"retry-demo","p3Failures":2}'
curl -s localhost:8080/api/v1/validation-runs/RUN_ID
```

**Graceful restart demo:**

```sh
ASYNC_ENABLED=false java -jar target/validation-cockpit-1.0.0.jar
# POST a run and note its ID. GET shows pending ASYNC jobs and no attempts. Stop with Ctrl-C.
java -jar target/validation-cockpit-1.0.0.jar      # same working directory
# GET the ID until COMPLETED.
```

Semantics to keep in mind:

- Stage dependencies mean "completed", not "valid": downstream validators run even after an upstream finding.
- The dead-letter endpoint is unauthenticated and intended for local evaluation only.
- Re-evaluation also re-runs validators that were previously invalid, together with their dependents.

## Tests

`./mvnw clean verify` runs 29 tests: 18 unit/architecture and 11 integration tests (independent review run: 29 passed, 0 failures/errors):

| Test class | Type | Covers |
|---|---|---|
| [`ValidationIntegrationTest`](src/test/java/poc/ValidationIntegrationTest.java) | Integration (11) | Real Spring contexts, deployed BPMN, real Flowable workers, temporary file H2. Includes a barrier-based parallelism proof, a graceful two-JVM restart and HTTP via MockMvc |
| [`ArchitectureTest`](src/test/java/poc/ArchitectureTest.java) | Architecture (6) | Feature/layer placement, domain purity, Flowable and JPA isolation, feature dependency direction |
| [`ExecutionPlanResolverUnitTest`](src/test/java/poc/executionplan/domain/ExecutionPlanResolverUnitTest.java) | Unit (5) | Stages, dependent closure, invalid input, cycle detection |
| [`ValidatorExecutionServiceUnitTest`](src/test/java/poc/validation/application/service/ValidatorExecutionServiceUnitTest.java) | Unit (4) | Idempotency guard, findings, technical failure audit |
| [`ValidationRunServiceUnitTest`](src/test/java/poc/validationprocess/application/service/ValidationRunServiceUnitTest.java) | Unit (3) | Run start, re-evaluation selection/reuse, unknown runs, all without Flowable |

The logs contain expected stack traces from synthetic failures and from the losing duplicate insert.

## Limitations

This is an evaluation PoC, not a production service. Not covered: abrupt-crash/lock-expiry windows (only graceful restart with pending jobs was tested), multi-node operation, SQL Server, load and contention, external side-effect idempotency, request payload fingerprinting, authentication, schema migrations and history retention. Flowable 8.0.0 defaults async job locks to one hour and expired-job reset polling to one minute; the current acquisition polling settings do not shorten these. Request uniqueness does not guarantee exactly-once validator invocation or downstream effects. See [production risks](docs/flowable-approach-comparison.md#6-production-portability--risks-flowable-implementations), [resilience evidence](docs/flowable-approach-comparison.md#5-production-resilience-demonstrated-guarantees-and-outstanding-validation) and the [proposed test strategy](docs/flowable-approach-comparison.md#7-recommended-automated-resilience-test-strategy).

## Project layout

```text
src/main/java/poc/{validation,executionplan,validationprocess}/   feature packages (see docs/architecture.md)
src/main/resources/processes/validation.bpmn20.xml                 generic staged BPMN process
src/main/resources/application.properties                          configuration
src/test/java/poc/                                                 integration, architecture and unit tests
docs/                                                              architecture and approach comparison
```
