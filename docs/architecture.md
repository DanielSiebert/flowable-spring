# Architecture – Validation Cockpit (Flowable C1 PoC)

This document describes the **current implemented architecture (C1)** of the Validation Cockpit Proof of Concept. In this hybrid design, Java owns validator selection, dependency planning, selective re-evaluation policy, idempotency, and business persistence, while Flowable OSS 8 serves as a durable reliability engine executing individual validators as asynchronous jobs.

For the architectural comparison between C0, C1, and C2, see [flowable-approach-comparison.md](flowable-approach-comparison.md). For setup, build, test, and scenario execution instructions, see the [README](../README.md).

---

## 1. Architectural Overview and Execution Model

The Validation Cockpit evaluates staged validation rules for insurance applications across diverse input channels (BiPRO `setOrder`, VBon/E-Antrag, e-mail). The core orchestration flow operates as follows:

1. **Request Ingestion & Idempotency**:
   - Clients submit validation or selective re-evaluation requests via [`ValidationApi`](../src/main/java/poc/validationprocess/api/ValidationApi.java) with a unique logical `requestKey`.
   - [`RunStarter`](../src/main/java/poc/validationprocess/application/service/RunStarter.java) verifies input parameters, enforces global uniqueness on `ValidationRun.requestKey` via JPA and database constraint, and establishes a single Spring transaction.
   - Duplicate concurrent or sequential submissions under the same `requestKey` return the existing [`ValidationRun`](../src/main/java/poc/validationprocess/domain/ValidationRun.java) without starting duplicate workflow instances.

2. **Dynamic Stage Planning**:
   - The validation dependency DAG (`P1 -> {P2, P3} -> P4`) is resolved dynamically in Java by [`ExecutionPlanResolver`](../src/main/java/poc/executionplan/domain/ExecutionPlanResolver.java).
   - Java topologically sorts selected validators into sequential stages of mutually independent validators (for a full run: Stage 0 = `[P1]`, Stage 1 = `[P2, P3]`, Stage 2 = `[P4]`).
   - The BPMN model [`validation.bpmn20.xml`](../src/main/resources/processes/validation.bpmn20.xml) contains zero hardcoded validator IDs; it dynamically loops over the stage list passed as process variables.

3. **Per-Validator Multi-Instance Execution**:
   - Flowable enters a parallel multi-instance subprocess `<subProcess id="stage">` over the current stage collection `${validators}`.
   - For each validator in the stage, Flowable creates a separate asynchronous execution running two sequential service tasks:
     - **`validate` (`flowable:async="true" flowable:exclusive="false"`)**: Executes [`ValidationExecutionDelegate`](../src/main/java/poc/validationprocess/infrastructure/flowable/ValidationExecutionDelegate.java), which delegates to [`ValidatorExecutionService`](../src/main/java/poc/validation/application/service/ValidatorExecutionService.java). This task records an independent attempt in [`AttemptAuditPort`](../src/main/java/poc/validation/application/port/AttemptAuditPort.java) (`REQUIRES_NEW`), invokes the domain [`Validator`](../src/main/java/poc/validation/domain/Validator.java), and flushes a [`ValidationResult`](../src/main/java/poc/validation/domain/ValidationResult.java) in the shared transaction. Retries are configured declaratively as `R3/PT1S`.
     - **`join` (`flowable:async="true" flowable:exclusive="true"`)**: A lightweight synchronization task that runs after `validate` succeeds. Because it is marked `exclusive="true"`, Flowable serializes updates to multi-instance completion counters (`nrOfCompletedInstances`), preventing optimistic locking exceptions from rolling back the validator execution body.
   - Once all validator instances in the stage reach `itemEnd`, Flowable exits the subprocess, executes `${workflowPlan.advance(execution)}`, and loops to the next stage if `${more}` is true.

---

## 2. Package-by-Feature and Hexagonal Architecture

The codebase is organized by business feature (`poc.<feature>`), with hexagonal architecture (Ports & Adapters) applied within each feature:

```text
src/main/java/poc/
├── Application.java                                  # Spring Boot entry point
│
├── validation/                                       # Feature: Validator rules, execution & attempts
│   ├── domain/
│   │   ├── ExecutionAttempt.java                     # Plain Java domain model for attempts
│   │   ├── ValidationContext.java                    # Immutable context passed to Validator
│   │   ├── ValidationFinding.java                    # Domain finding value object
│   │   ├── ValidationResult.java                     # Domain result entity (outcome, finding, timestamps)
│   │   └── Validator.java                            # Domain validator contract
│   ├── application/
│   │   ├── port/
│   │   │   ├── AttemptAuditPort.java                 # Outbound port for attempt persistence
│   │   │   ├── ValidationProbe.java                  # Test instrumentation seam
│   │   │   ├── ValidationResultRepository.java       # Outbound port for result persistence
│   │   │   └── ValidatorCatalog.java                 # Outbound port for validator lookup
│   │   └── service/
│   │       └── ValidatorExecutionService.java        # Use case: executes validator & coordinates audit
│   └── infrastructure/
│       ├── persistence/                              # JPA entities, Spring Data repos & adapters
│       │   ├── AttemptAuditAdapter.java              # REQUIRES_NEW attempt audit adapter
│       │   ├── ExecutionAttemptJpaEntity.java
│       │   ├── JpaValidationResultRepositoryAdapter.java
│       │   ├── SpringDataAttemptRepository.java
│       │   ├── SpringDataValidationResultRepository.java
│       │   └── ValidationResultJpaEntity.java
│       └── validator/
│           ├── DefaultValidationProbe.java
│           └── ValidatorRegistry.java                # Concrete synthetic P1–P4 implementations
│
├── executionplan/                                    # Feature: Dependency graph & stage resolution
│   ├── domain/
│   │   └── ExecutionPlanResolver.java                # Pure Java topological sorter & closure resolver
│   └── infrastructure/
│       └── ExecutionPlanConfiguration.java           # Spring bean configuration for resolver
│
└── validationprocess/                                # Feature: Run lifecycle, orchestration & API
    ├── domain/
    │   ├── DuplicateRequestKeyException.java         # Domain exception on duplicate requestKey
    │   ├── JobSnapshot.java                          # Value object for engine job state
    │   ├── RunOutcomePolicy.java                     # Domain rules for run outcome derivation
    │   ├── ValidationRun.java                        # Plain Java domain model for validation runs
    │   └── WorkflowStatus.java                       # Value object for technical workflow state
    ├── application/
    │   ├── port/
    │   │   ├── ValidationRunRepository.java          # Outbound port for run persistence
    │   │   └── ValidationWorkflowPort.java           # Outbound port isolating Flowable engine
    │   └── service/
    │       ├── RunStarter.java                       # Use case: run creation & re-evaluation planning
    │       └── ValidationRunService.java             # Use case: start, retry, inspect & validator bridge
    ├── infrastructure/
    │   ├── flowable/                                 # Flowable adapters & BPMN delegates
    │   │   ├── FlowableWorkflowAdapter.java          # Implements ValidationWorkflowPort
    │   │   ├── ValidationExecutionDelegate.java      # Thin JavaDelegate bridge
    │   │   └── WorkflowPlan.java                     # BPMN expression adapter for plan variables
    │   └── persistence/
    │       ├── JpaValidationRunRepositoryAdapter.java
    │       ├── SpringDataValidationRunRepository.java
    │       └── ValidationRunJpaEntity.java
    └── api/
        └── ValidationApi.java                        # Inbound REST controller & request DTOs
```

### Dependency Rules and Hexagonal Boundaries

- **Inward Dependency Direction**: `API / Infrastructure -> Application -> Domain`.
- **Domain Layer Purity**: Classes in `*.domain` contain plain Java records and classes. They have zero imports or annotations from Spring, Flowable, JPA, or Jackson.
- **Application Layer Isolation**: Classes in `*.application` define use cases and ports. They have zero dependencies on Flowable engine APIs, JPA repositories, or HTTP web controllers.
- **Strict Framework Containment**:
  - `org.flowable.*` is referenced **exclusively** inside `poc.validationprocess.infrastructure.flowable`.
  - `jakarta.persistence.*` and `org.springframework.data.jpa.*` are referenced **exclusively** inside `*.infrastructure.persistence`.
- **Acyclic Feature Dependencies**: Cross-feature communication follows `validationprocess -> executionplan -> validation` and `validationprocess -> validation`. No feature imports another feature's infrastructure internals.
- **Automated Verification**: [`ArchitectureTest`](../src/test/java/poc/ArchitectureTest.java) executes 6 automated tests on every build enforcing layer purity, framework isolation, and feature boundaries.

```mermaid
flowchart LR
    REST["ValidationApi (REST)"] --> RUNSVC["RunStarter / ValidationRunService"]
    RUNSVC --> PLAN["ExecutionPlanResolver"]
    RUNSVC --> EXEC["ValidatorExecutionService"]
    RUNSVC --> WFPORT["ValidationWorkflowPort"]
    RUNSVC --> REPOS["Run / Result / Attempt ports"]
    EXEC --> CAT["ValidatorCatalog"]
    EXEC --> REPOS
    WFPORT -. implemented by .-> FWA["FlowableWorkflowAdapter"]
    FWA --> ENGINE["Flowable ProcessEngine"]
    ENGINE --> DELEGATES["WorkflowPlan / ValidationExecutionDelegate"]
    DELEGATES --> PLAN
    DELEGATES --> RUNSVC
    REPOS -. implemented by .-> JPA["JPA Adapters (H2 / Hibernate)"]
    CAT -. implemented by .-> REG["ValidatorRegistry (P1..P4)"]
```

---

## 3. Component Responsibilities

### Custom Java Process Orchestration

- **`ExecutionPlanResolver`**: Defines validator dependency relationships (`dependencies = Map.of("P1", Set.of(), "P2", Set.of("P1"), "P3", Set.of("P1"), "P4", Set.of("P2", "P3"))`). Computes topological stages for normal runs and calculates the transitive downstream closure (`affected(requested)`) for selective re-evaluations.
- **`RunStarter`**: Coordinates the transactional start boundary. Validates request arguments, enforces `requestKey` uniqueness, copies unaffected prior results with `reusedFromRun` provenance, and starts the Flowable process instance.
- **`ValidationRunService`**: Coordinates application-level operations:
  - Handles concurrent duplicate `requestKey` conflicts by catching `DuplicateRequestKeyException` and loading the committed winner.
  - Exposes `retryDeadLetters` by delegating to the workflow port.
  - Dynamically computes composite status in `inspect(id)` by querying Flowable job/runtime/history state, combining it with domain results, and calculating re-evaluation lineage.
- **`ValidatorExecutionService`**: Orchestrates single-validator execution: checks result existence for idempotency, triggers attempt auditing, invokes the domain validator, flushes the domain result, and handles error propagation.

### Flowable Integration & BPMN Responsibilities

- **`FlowableWorkflowAdapter`**: Implements [`ValidationWorkflowPort`](../src/main/java/poc/validationprocess/application/port/ValidationWorkflowPort.java). Encapsulates all calls to Flowable `RuntimeService`, `ManagementService`, and `HistoryService`. Handles transient concurrency races (such as `FlowableObjectNotFoundException` during inspection when a process finishes concurrently).
- **`WorkflowPlan`**: Spring bean (`@Component("workflowPlan")`) invoked from BPMN expressions:
  - `${workflowPlan.resolve(execution)}`: Reads `selected` process variable, resolves stages, and initializes `stageIndex = 0`.
  - `${workflowPlan.advance(execution)}`: Increments `stageIndex` and updates `validators` and `more`.
  - `${workflowPlan.joined(execution)}`: No-op execution target for the exclusive join task.
- **`ValidationExecutionDelegate`**: Thin Spring bean (`@Component("validationExecution")`) implementing Flowable `JavaDelegate`. Extracts `runId` and `validator` from `DelegateExecution` and calls `ValidationRunService.executeValidator(runId, validatorId)`. Contains zero business logic.

---

## 4. State Ownership and Persistence Architecture

| State Category | Authoritative Owner | Persistence Mechanism | Access Path |
|---|---|---|---|
| **Active Activities & Tokens** | Flowable | `ACT_RU_EXECUTION` | `RuntimeService.getActiveActivityIds(processId)` |
| **Async Jobs, Timers & Retries** | Flowable | `ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, `ACT_RU_DEADLETTER_JOB` | `ManagementService` job queries via `ValidationWorkflowPort` |
| **Workflow Progress & Variables** | Flowable | `ACT_RU_VARIABLE` (`stages`, `stageIndex`, `validators`, `more`) | Process variables on `DelegateExecution` |
| **Historic Process Completion** | Flowable | `ACT_HI_PROCINST` | `HistoryService.createHistoricProcessInstanceQuery()` |
| **Validation Run Aggregate** | Application Domain | `VALIDATION_RUN` table via JPA | [`ValidationRunRepository`](../src/main/java/poc/validationprocess/application/port/ValidationRunRepository.java) |
| **Business Results & Findings** | Application Domain | `VALIDATION_RESULT` table (`runId:validatorId`) | [`ValidationResultRepository`](../src/main/java/poc/validation/application/port/ValidationResultRepository.java) |
| **Per-Attempt Audit Trail** | Application Domain | `EXECUTION_ATTEMPT` table via JPA | [`AttemptAuditPort`](../src/main/java/poc/validation/application/port/AttemptAuditPort.java) (`REQUIRES_NEW`) |
| **Composite Cockpit Status** | Derived Dynamically | In-memory composite projection | [`ValidationRunService.inspect()`](../src/main/java/poc/validationprocess/application/service/ValidationRunService.java) |

### Transaction Boundaries and Consistency

Flowable and Spring Data JPA share a single Spring-managed `DataSource` (HikariCP) and `JpaTransactionManager`. No single database transaction spans the entire workflow; instead, execution proceeds through discrete transactional steps:

1. **Start Transaction (`RunStarter.start`)**: Inserts `ValidationRunJpaEntity`, flushes to enforce `requestKey` uniqueness, copies reused `ValidationResult` rows if re-evaluating, starts the Flowable process instance (executing `resolve` synchronously and creating stage 0 async jobs), and commits.
2. **Validator Job Transaction (`validate` task)**: Executed by Flowable async workers (`exclusive="false"`). Flushes `ValidationResultJpaEntity` and transitions execution to `join`. If an exception is thrown, both the flushed result and the Flowable transition roll back atomically.
3. **Stage Join Transaction (`join` task)**: Executed in a separate async job (`exclusive="true"`). Updates multi-instance completion variables and executes `advance` when the stage finishes.
4. **Attempt Audit (`AttemptAuditAdapter`)**: Uses `Propagation.REQUIRES_NEW` to commit attempt start and failure records in an independent transaction, ensuring synthetic attempt counters and technical error messages survive outer job rollback.

---

## 5. Retry, Error Handling, and Recovery Mechanisms

- **Declarative Technical Retries**: Configured in BPMN on `<serviceTask id="validate">` as `<flowable:failedJobRetryTimeCycle>R3/PT1S</flowable:failedJobRetryTimeCycle>`.
  - Failure 1: Decrements retries to 2, schedules `ACT_RU_TIMER_JOB` with 1-second delay, audit records `TECHNICAL_FAILURE` with error message.
  - Failure 2: Decrements retries to 1, schedules `ACT_RU_TIMER_JOB` with 1-second delay.
  - Failure 3: Moves job to `ACT_RU_DEADLETTER_JOB`, process state becomes `INCIDENT`.
- **Operator Incident Recovery**: An unauthenticated endpoint `POST /api/v1/validation-runs/{id}/dead-letter-retries` invokes `management.moveDeadLetterJobToExecutableJob(jobId, 3)`, returning the dead-letter job to executable status with 3 new retries without repeating completed upstream validators.
- **Business Findings vs Technical Failures**: A business finding returns a [`ValidationFinding`](../src/main/java/poc/validation/domain/ValidationFinding.java) without throwing an exception. The result is saved with `outcome = "INVALID"`, the job commits normally, no retries are triggered, and downstream validators execute.
- **Restart Recovery**:
  - Graceful restart with pending unacquired jobs resumes automatically when Flowable's async executor starts up.
  - Mid-workflow process restart is verified by `twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure`: child JVM completes P1/P2 and fails P3 attempt 1 before termination; parent JVM resumes against the same file database, retains P1/P2 results and P3 failure history, and completes P3 attempt 2 and P4.

---

## 6. Requirements-to-Implementation Traceability Matrix

This matrix documents the verification status of all functional requirements (FR-01 through FR-08) and architectural requirements (AR-01 through AR-06), incorporating evidence from tests and codebase inspection.

| ID | Requirement & Description | Implementation Mechanism | Status | Evidence & Verification | Limitations & Remaining Gaps |
|---|---|---|---|---|---|
| **FR-01** | **Dynamic Execution Planning**: Calculate validator execution stages dynamically in Java without hardcoded BPMN paths. | [`ExecutionPlanResolver`](../src/main/java/poc/executionplan/domain/ExecutionPlanResolver.java) topologically sorts validators into stages `[[P1], [P2, P3], [P4]]`. Injected into process via [`WorkflowPlan.resolve`](../src/main/java/poc/validationprocess/infrastructure/flowable/WorkflowPlan.java). | **Implemented** | `ExecutionPlanResolverUnitTest#resolvesFullPlanIntoExpectedTopologicalStages`, `ValidationIntegrationTest#normalOrderingAndPersistence`. | `dependencies` map is hardcoded in Java; dynamic configuration per insurance product is not yet implemented. |
| **FR-02** | **True Parallel Validator Execution**: Independent validators in the same stage must execute concurrently across worker threads. | BPMN `<serviceTask id="validate">` configured with `async="true" exclusive="false"`. Spring `ThreadPoolTaskExecutor` pool size = 4. | **Implemented** | `ValidationIntegrationTest#actualParallelJobs`: P2 and P3 meet at a two-party `CyclicBarrier` on two distinct thread names simultaneously. | Two jobs per validator required (`validate` + `join`) to avoid multi-instance optimistic-locking contention. |
| **FR-03** | **Independent Technical Retries & Incidents**: A failing validator must retry independently without repeating completed peers or prior stages. Exhausted retries become incidents. | Flowable `failedJobRetryTimeCycle` (`R3/PT1S`), `ACT_RU_TIMER_JOB`, `ACT_RU_DEADLETTER_JOB`, and `AttemptAuditAdapter` (`REQUIRES_NEW`). | **Implemented** | `ValidationIntegrationTest#technicalRetryIsIndividual`, `ValidationIntegrationTest#exhaustedRetriesBecomeDeadLetter`. | After retry succeeds, Flowable deletes the timer job; historical error messages survive only via custom `ExecutionAttempt` audit table. |
| **FR-04** | **Persistent Execution & Recovery**: Workflow execution state must survive application restarts and resume pending work. | Shared H2 file database, Flowable async job tables, and engine job acquisition scanner on restart. | **Implemented** (for graceful restart) | `ValidationIntegrationTest#restartResumesCommittedPendingJob`, `ValidationIntegrationTest#twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure`. | Abrupt kill (`SIGKILL`) leaves job locked until Flowable `lockExpirationTime` (5 minutes) elapses. Crash timing around worker locks not exhaustively tested. |
| **FR-05** | **Duplicate Request Handling (Idempotency)**: Submitting identical `requestKey` must return existing run without duplicate execution. | `@UniqueConstraint(columnNames = "requestKey")` on `ValidationRunJpaEntity`, `saveAndFlush` before process start, and `DuplicateRequestKeyException` recovery. | **Implemented** | `ValidationIntegrationTest#concurrentDuplicateRequests`: 2 concurrent threads get same run ID; `HistoryService` confirms exactly 1 process instance. | Repeating a key with a changed payload returns the existing run without checking payload equality (needs fingerprinting / `409 Conflict`). |
| **FR-06** | **Selective Re-evaluation**: Re-evaluating a subset of validators must re-run only requested and dependent validators, copying valid prior results with provenance. | [`RunStarter.start`](../src/main/java/poc/validationprocess/application/service/RunStarter.java) calculates transitive closure (`plans.affected`), forces re-evaluation of prior invalid results, copies unaffected results (`reusedFromRun`, `attempt=0`). | **Implemented** | `ValidationIntegrationTest#selectiveReevaluation`, `ValidationRunServiceUnitTest#selectiveReevaluationIncludesPriorInvalidValidatorsAndCopiesValidUpstreamResults`. | Re-evaluation creates a new correlated `ValidationRun`; active process tokens are never rewound. Only completed runs can be re-evaluated. |
| **FR-07** | **Business vs. Technical Failures**: Business rule findings produce `INVALID` outcome without technical retry; technical exceptions trigger retries. | [`Validator.validate`](../src/main/java/poc/validation/domain/Validator.java) returns [`ValidationFinding`](../src/main/java/poc/validation/domain/ValidationFinding.java) on rule failure. Throws `RuntimeException` on technical failure. | **Implemented** | `ValidationIntegrationTest#businessFindingDoesNotRetryOrBlockStages`, `ValidatorExecutionServiceUnitTest#recordsBusinessFindingAsInvalidOutcomeWithoutThrowing`. | Downstream validators execute even when upstream results are `INVALID` (dependencies mean "completed", not "valid"). Business skipping rules not modeled. |
| **FR-08** | **REST API**: Manage validation runs, selective re-evaluations, dead-letter retries, and inspection over HTTP. | [`ValidationApi`](../src/main/java/poc/validationprocess/api/ValidationApi.java) with endpoints `POST /`, `GET /{id}`, `POST /{id}/re-evaluations`, `POST /{id}/dead-letter-retries`. | **Implemented** | `ValidationIntegrationTest#restApiStartsInspectsAndReevaluates`: verifies `200`, `400` (bad payload), `404` (unknown ID), findings, and lineage. | Dead-letter retry endpoint is unauthenticated; suitable for PoC evaluation only. |
| **AR-01** | **Genuine C2/C1 Execution Granularity**: Flowable schedules each validator individually, not as a single monolithic Java block. | BPMN multi-instance subprocess spawns individual `validate` async service task per validator. | **Implemented** | `ValidationIntegrationTest` all 11 tests; inspectable in `ACT_RU_JOB` and `ACT_HI_ACTINST`. | Multi-instance subprocess adds engine overhead and job table churn. |
| **AR-02** | **State Ownership**: Flowable owns technical progress and jobs; application owns business results and lineage. | `VALIDATION_RUN`, `VALIDATION_RESULT`, and `EXECUTION_ATTEMPT` tables. Status derived dynamically in `inspect()`. | **Implemented** | `ValidationIntegrationTest#normalOrderingAndPersistence`. Inspect handles `FlowableObjectNotFoundException` race. | Inspection combines 6+ queries across Flowable and JPA; eventually consistent, not an atomic snapshot. |
| **AR-03** | **Transaction Boundaries**: Shared transaction manager; domain results and engine step commit or roll back together. | Shared `JpaTransactionManager` (`PlatformTransactionManager`) between Spring Data JPA and Flowable. | **Implemented** | `ValidationIntegrationTest#resultAndEngineJobRollbackTogether`: post-flush exception rolls back JPA result and engine step atomically. | `AttemptAuditAdapter` (`REQUIRES_NEW`) consumes 2 connections per active worker; risks HikariCP deadlock if pool size < 2W. |
| **AR-04** | **Framework vs. Custom Infrastructure**: Eliminate custom job queues, retry schedulers, stage latches, and recovery scanners. | Flowable provides `ACT_RU_JOB`, `failedJobRetryTimeCycle`, multi-instance barrier, and startup acquisition. | **Implemented** | Replaced custom scheduler and state tables. Flowable value verified in `flowable-approach-comparison.md`. | Flowable introduces 30 `ACT_*` tables, MyBatis, and two-job subprocess workaround. |
| **AR-05** | **Maintainability & Clean Architecture**: Package-by-feature, hexagonal layers, decoupled validator interface, domain purity. | Packages `poc.validation`, `poc.executionplan`, `poc.validationprocess`; pure domain records; thin adapters. | **Implemented** | [`ArchitectureTest`](../src/test/java/poc/ArchitectureTest.java) passes 6 checks enforcing layer purity and dependency rules. | `ValidationRunService.RunView` directly exposes domain models (`ValidationResult`, `ExecutionAttempt`) in read projection. |
| **AR-06** | **Technology Compatibility**: Verified versions, clean build on Java 17, and documented production portability. | Java 17, Boot 4.0.2, Spring 7.0.3, Hibernate 7.2.1, Flowable 8.0.0, H2 2.4.240, JUnit 5.13.4. | **Implemented** | Verified from packaged jar `BOOT-INF/lib/` and clean test execution. | Production SQL Server deployment requires `READ_COMMITTED_SNAPSHOT ON` and lock escalation tuning (documented in comparison). |

---

## 7. Relevant Architectural Decisions and Constraints

1. **The Two-Step Subprocess Pattern (`validate` + `join`)**:
   - *Problem*: In Flowable, concurrent non-exclusive multi-instance service tasks collide when updating parent execution completion counters (`nrOfCompletedInstances`), causing `FlowableOptimisticLockingException` which rolls back and re-executes the validator body. Making the validator exclusive serializes execution and destroys parallelism.
   - *Decision*: Split each multi-instance item into a non-exclusive async `validate` task followed by an exclusive async `join` task. Retries on `join` retry only the lightweight join, never the validator.
2. **`REQUIRES_NEW` for Attempt Auditing**:
   - *Problem*: Flowable deletes timer jobs once a retried task succeeds, erasing historical exception messages. Furthermore, synthetic failure counters require persistence across job rollbacks.
   - *Decision*: [`AttemptAuditAdapter`](../src/main/java/poc/validation/infrastructure/persistence/AttemptAuditAdapter.java) commits attempt starts and failures in `Propagation.REQUIRES_NEW`. *Constraint*: HikariCP maximum pool size must be sized to at least $2 \times \text{workers} + \text{buffer}$ to prevent connection-pool starvation.
3. **Derived Status vs Persisted Workflow Enum**:
   - *Decision*: `ValidationRun` has no `status` column. Run state (`RUNNING`, `INCIDENT`, `COMPLETED`) is derived dynamically from Flowable runtime and history queries, avoiding competing state machines.
4. **Clean Domain Records and Methods**:
   - *Decision*: Domain entities are immutable classes/records. Domain methods use domain-specific naming (e.g., [`ValidationResult.hasValidOutcome()`](../src/main/java/poc/validation/domain/ValidationResult.java)) to prevent Jackson from synthesizing unexpected JSON properties in REST responses.
