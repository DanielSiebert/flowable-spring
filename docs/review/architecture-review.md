# Architecture Review — Flowable Validation PoC (Approach C2)

## 1. Actual Architecture and Execution Model

The PoC implements a hybrid validation orchestrator combining Java-based execution planning with per-validator Flowable BPMN execution:

1. **Run Creation & Idempotency ([`ValidationRunService`](../../src/main/java/poc/ValidationRunService.java), [`RunStarter`](../../src/main/java/poc/RunStarter.java)):**
   - A client submits a validation or selective re-evaluation request containing a logical `requestKey`.
   - `RunStarter.start(...)` validates inputs, checks for an existing `ValidationRun` by `requestKey`, resolves the target validator set (either all registered validators `[P1, P2, P3, P4]` or the transitive dependent closure of requested/invalid validators via [`ExecutionPlanResolver`](../../src/main/java/poc/ExecutionPlanResolver.java)), persists and flushes the `ValidationRun` row (`@UniqueConstraint(columnNames = "requestKey")`), copies reusable `ValidationResult` records when performing a selective re-evaluation, and starts a Flowable process instance (`validation`) within a single Spring `@Transactional` boundary.
   - Concurrent duplicate requests with the same `requestKey` are serialized by the database unique constraint on `ValidationRun.requestKey`; the losing transaction rolls back before starting a Flowable process and `ValidationRunService` returns the committed winner.

2. **Dynamic Stage Resolution ([`WorkflowPlan`](../../src/main/java/poc/WorkflowPlan.java), [`ExecutionPlanResolver`](../../src/main/java/poc/ExecutionPlanResolver.java)):**
   - The BPMN process [`validation.bpmn20.xml`](../../src/main/resources/processes/validation.bpmn20.xml) starts at `<serviceTask id="resolve" flowable:expression="${workflowPlan.resolve(execution)}"/>`.
   - `WorkflowPlan.resolve` calls `ExecutionPlanResolver.resolve(selected)` to topologically sort selected validators into sequential stages of independent validators (for a full run: Stage 0 = `[P1]`, Stage 1 = `[P2, P3]`, Stage 2 = `[P4]`).
   - It stores `stages` (`List<List<String>>`), `stageIndex` (`0`), `more` (`boolean`), and `validators` (`List<String>` for the current stage) as Flowable process variables.

3. **Per-Validator Multi-Instance Execution ([`validation.bpmn20.xml`](../../src/main/resources/processes/validation.bpmn20.xml), [`ValidationExecutionDelegate`](../../src/main/java/poc/ValidationExecutionDelegate.java)):**
   - The BPMN process enters `<subProcess id="stage">` configured with `<multiInstanceLoopCharacteristics isSequential="false" flowable:collection="${validators}" flowable:elementVariable="validator"/>`.
   - Flowable creates one concurrent child execution per validator in `validators`. Each child instance runs two consecutive asynchronous service tasks:
     - **`validate` (`flowable:async="true" flowable:exclusive="false"`, retry cycle `R3/PT1S`):** Invokes `ValidationExecutionDelegate`, which records an independent attempt start via `AttemptAudit` (`Propagation.REQUIRES_NEW`), executes the target `Validator` from `ValidatorRegistry`, flushes a `ValidationResult` entity (`runId:validatorId`) in the shared transaction, and transitions to `join`.
     - **`join` (`flowable:async="true" flowable:exclusive="true"`):** Executes in a separate, process-exclusive transaction that completes the child subprocess instance (`<endEvent id="itemEnd"/>`) and updates Flowable's multi-instance completion counters (`nrOfCompletedInstances`, `nrOfActiveInstances`).
   - Only after all instances in the current stage complete `itemEnd` does Flowable exit `<subProcess id="stage">`, execute `<serviceTask id="advance" flowable:expression="${workflowPlan.advance(execution)}"/>`, and loop via `<exclusiveGateway id="next"/>` until `${more}` is `false`.

---

## 2. Comparison with Intended Hybrid C2 Architecture (AR-01)

The implementation is a **genuine Approach C2 architecture** (hybrid orchestration with per-validator Flowable execution), not a disguised C1 wrapper:

| Architectural Criterion | Expected in C2 | Actual PoC Implementation | Verdict |
|---|---|---|---|
| Validator-level task granularity | Flowable schedules each validator individually | Multi-instance subprocess spawns a separate `validate` async job per validator (`elementVariable="validator"`) | **Satisfied** |
| Dynamic plan vs. static BPMN | Java resolves dependencies; BPMN is generic | `validation.bpmn20.xml` has zero validator IDs (`P1`–`P4`); stages and stage members come from `ExecutionPlanResolver` | **Satisfied** |
| Independent technical retries | Failed validator retries without repeating peers or prior stages | `R3/PT1S` on `validate` retries only the failed validator's job; completed sibling/upstream results and executions remain committed | **Satisfied** |
| Stage barrier enforcement | Downstream stage waits for all upstream validators | Flowable's parallel multi-instance subprocess barrier blocks `advance` until all `validators` in `stageIndex` reach `itemEnd` | **Satisfied** |
| No duplicate workflow state machine | Flowable owns workflow lifecycle and active execution state | `ValidationRun` stores no status enum or current stage pointer; runtime/historic status is derived from Flowable APIs | **Satisfied** |

---

## 3. Component Responsibility Assessment & State Ownership (AR-02)

### State Ownership Matrix

| State Category | Expected Owner | Actual Owner in PoC | Persistence / Mechanism |
|---|---|---|---|
| **Active workflow activities** | Flowable | **Flowable** | `ACT_RU_EXECUTION`, queried via `RuntimeService.getActiveActivityIds(processId)` |
| **Async jobs and retry information** | Flowable | **Flowable** | `ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, `ACT_RU_DEADLETTER_JOB`, queried via `ManagementService` |
| **Technical workflow progress** | Flowable | **Flowable** | `ACT_RU_VARIABLE` (`stages`, `stageIndex`, `validators`, `more`) and `ACT_HI_PROCINST` |
| **Validator business results** | Application domain | **Application domain (JPA)** | `ValidationResult` entity (`ValidatorResultStore`), keyed by `runId:validatorId` |
| **Findings** | Application domain | **Application domain (JPA)** | `ValidationResult.finding` and `ValidationResult.outcome` (`VALID` / `INVALID`) |
| **Execution plan** | Application domain | **Application domain (Java) -> Flowable variables** | Computed by `ExecutionPlanResolver`, injected into process variables via `WorkflowPlan` |
| **Re-evaluation history** | Application domain | **Application domain (JPA)** | `ValidationRun.originalRunId`, `ValidationRun.createdAt`, and `ValidationResult.reusedFromRun` |
| **Cockpit status** | Derived from authoritative sources | **Derived dynamically** | `ValidationRunService.inspect()` combines Flowable runtime/history/job queries (`state`: `RUNNING`, `COMPLETED`, `INCIDENT`) with JPA results (`outcome` & `effectiveOutcome`: `VALID`, `INVALID`, `PENDING`) |

### Role of `AttemptAudit` / `ExecutionAttempt`

`ExecutionAttempt` is persisted via `AttemptAudit` using `Propagation.REQUIRES_NEW`. Its architectural role must be clearly understood:
1. **Why it exists:**
   - **Synthetic failure simulator state:** `ValidatorRegistry` needs a rollback-surviving attempt counter (`attempt.number`) to fail `P3` on attempts 1..N and succeed on attempt N+1.
   - **Rolled-back attempt history:** When a Flowable async job throws an exception, the job transaction rolls back. Flowable decrements `RETRIES_` on `ACT_RU_TIMER_JOB`, but once a subsequent retry succeeds, Flowable deletes the job row from `ACT_RU_JOB`, and `ACT_HI_ACTINST` only records a single historic activity instance for `validate`. Without an out-of-band audit log (`REQUIRES_NEW` or an event listener committing in a separate transaction), Flowable does not retain a per-attempt audit trail of prior failed attempts after eventual success.
2. **Why it is not a competing state machine:**
   - Neither Flowable nor `ValidationRunService` uses `ExecutionAttempt` to schedule work, decide retries, advance stages, or determine workflow completion.
   - During our review, we enhanced `ExecutionAttempt` to also record `error` (`ex.getMessage()`) on `TECHNICAL_FAILURE` so that technical failure causes remain inspectable even after a retry succeeds and Flowable deletes the timer job.

### Concurrency Race Fixed in Status Derivation (`ValidationRunService.inspect`)

During Phase B verification, we uncovered and fixed a race condition in `ValidationRunService.inspect(String id)`:
- **Defect:** `inspect()` is non-transactional across multiple engine queries. Originally, it queried `runtime.createProcessInstanceQuery().processInstanceId(r.processId).count() > 0` (`active = true`) and later called `runtime.getActiveActivityIds(r.processId)`. When a background worker thread completed the process instance in the sub-millisecond window between those two calls, `getActiveActivityIds` threw an unhandled `FlowableObjectNotFoundException` (`execution ... doesn't exist`).
- **Fix:** `inspect()` now catches `FlowableObjectNotFoundException` around `getActiveActivityIds`, resets `activities` to `List.of()` whenever `state` resolves to `COMPLETED`, and derives both technical `state` and business `outcome` consistently.

---

## 4. Transaction and Persistence Analysis (AR-03)

Flowable (`SpringProcessEngineConfiguration`) and Spring Data JPA share the single Spring-managed `DataSource` (HikariCP) and `JpaTransactionManager` (`org.springframework.orm.jpa.JpaTransactionManager`). There is **no long-running transaction** spanning the workflow; instead, the process executes across short, discrete transactions:

1. **Start Transaction (`RunStarter.start`):** Inserts `ValidationRun` (and any reused `ValidationResult` rows), starts the process instance, resolves stages synchronously, creates the first stage's async `validate` job(s), and commits.
2. **Validator Job Transaction (`validate` service task):** Each validator in a stage runs in its own independent async job transaction (`exclusive="false"`).
3. **Stage Join Transaction (`join` service task):** Runs in a separate async job transaction (`exclusive="true"`) to update multi-instance counters and advance stages.

### Answers to AR-03 Investigation Questions

1. **What happens when a validator throws an exception?**
   - `ValidationExecutionDelegate` catches `RuntimeException`, records `TECHNICAL_FAILURE` (and the exception message) via `AttemptAudit.finish(...)` in an independent `REQUIRES_NEW` transaction, and rethrows the exception.
   - Flowable's `SpringTransactionInterceptor` marks the outer `JpaTransactionManager` transaction for rollback.
   - Flowable's `DefaultAsyncRunnableExecutionExceptionHandler` opens a separate command transaction (`DecrementJobRetriesCmd`), decrements the job's `retries` count, and moves the job to `ACT_RU_TIMER_JOB` (with `PT1S` delay and `exceptionMessage`) or to `ACT_RU_DEADLETTER_JOB` if `retries` reaches `0`.
2. **Which database operations roll back?**
   - Any JPA changes in the outer transaction (such as a flushed `ValidationResult`) and Flowable's execution transition from `validate` to `join` roll back atomically. Only the `AttemptAudit` (`REQUIRES_NEW`) record commits.
3. **Can a successful validator result be committed while Flowable reports failure?**
   - **No** for local JPA results. Because `ValidatorResultStore.saveAndFlush(...)` participates in the exact same `JpaTransactionManager` transaction as Flowable's `ExecuteAsyncRunnable` job completion, a rollback of the Flowable job rolls back the `ValidationResult` row (proven by `ValidationIntegrationTest#resultAndEngineJobRollbackTogether`).
4. **Can Flowable commit execution progress while domain results are missing?**
   - **No.** `ValidationExecutionDelegate` calls `results.saveAndFlush(...)` synchronously before returning to Flowable within the same database transaction. If the JPA insert/flush fails, the Flowable job transaction rolls back.
5. **What happens if an external service call succeeds but the local transaction rolls back?**
   - External non-transactional side effects (HTTP calls, message publication) cannot be rolled back by the local JDBC transaction. If a validator performs an external mutation and the local transaction subsequently fails before commit (or crashes mid-flight), Flowable will retry the job and invoke the external service again (**at-least-once execution**). Validators with external side effects must therefore pass a deterministic idempotency key (such as `runId + ":" + validatorId`) to downstream systems.
6. **Are parallel validator executions transactionally independent?**
   - **Yes.** Because `validate` is `flowable:async="true" flowable:exclusive="false"`, `P2` and `P3` execute in two separate threads and two separate JDBC transactions. If `P3` throws an exception and rolls back, `P2`'s transaction still commits its `ValidationResult` and its transition to `join`.

### Connection Pool Sizing Hazard (`REQUIRES_NEW`)

Because `AttemptAudit` uses `Propagation.REQUIRES_NEW` inside `ValidationExecutionDelegate`, each active validator worker thread holds **one** outer JDBC connection and briefly acquires a **second** JDBC connection from HikariCP to insert/update `ExecutionAttempt`.
- In this PoC, `spring.task.execution.pool.max-size=4` and HikariCP defaults to `maximum-pool-size=10` (`2 * 4 = 8 < 10`), avoiding deadlock.
- **Production warning:** If the Flowable async worker pool size $W$ is ever configured such that $W \ge \text{HikariPoolSize}$, all $W$ worker threads can simultaneously hold an outer transaction connection while blocking indefinitely waiting for a `REQUIRES_NEW` connection, causing a **connection-pool starvation deadlock**. In production, either size HikariCP to at least $2W + \text{buffer}$ or move attempt auditing to a dedicated datasource/asynchronous event sink.

---

## 5. Retry and Recovery Design (FR-03, FR-04)

- **Retry Configuration:** Defined declaratively in BPMN on `<serviceTask id="validate">` via `<flowable:failedJobRetryTimeCycle>R3/PT1S</flowable:failedJobRetryTimeCycle>`. Flowable sets initial `retries = 3` on the async job.
  - Attempt 1 fails -> `retries` decremented to `2`, moved to `ACT_RU_TIMER_JOB` (`+1s`).
  - Attempt 2 fails -> `retries` decremented to `1`, moved to `ACT_RU_TIMER_JOB` (`+1s`).
  - Attempt 3 fails -> `retries` decremented to `0`, moved to `ACT_RU_DEADLETTER_JOB` (`INCIDENT` state).
- **Operator Recovery:** `ValidationRunService.retryDeadLetters(runId)` calls `management.moveDeadLetterJobToExecutableJob(jobId, 3)`, which moves only the failed validator's dead-letter job back to `ACT_RU_JOB` without re-running completed validators (`P1`, `P2`).
- **Process Restart & Crash Recovery:**
  - **Graceful / Pending Job Restart:** Verified across **two separate JVM processes** sharing a file-based H2 database (`ValidationIntegrationTest#twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure`). When JVM 1 stops after `P1` and `P2` complete and `P3` fails attempt 1, JVM 2 recovers the persisted Flowable timer job, does not repeat `P1` or `P2`, retains `P3`'s attempt 1 failure record, and executes `P3` (attempt 2) and `P4` to completion.
  - **Abrupt Crash (`kill -9` / power loss) During Active Validator Execution:**
    - Uncommitted database transactions in the terminated JVM roll back automatically when the database connection closes.
    - Because Flowable's async job executor acquires a lock (`LOCK_OWNER_`, `LOCK_EXP_TIME_`, default 5 minutes in Flowable) on `ACT_RU_JOB` before starting the job execution transaction, an abrupt JVM crash leaves the job row locked in `ACT_RU_JOB` until `LOCK_EXP_TIME_` expires. Once the lock expires, Flowable's global acquire thread re-acquires and executes the job.
    - If `AttemptAudit.start(...)` committed its `REQUIRES_NEW` transaction before the crash, an `ExecutionAttempt` row with `outcome = "STARTED"` and `finishedAt = null` will remain in the audit table as evidence of the interrupted attempt.

---

## 6. Concurrency Analysis (FR-02)

### Why the Two-Step (`validate` + `join`) Subprocess Pattern is Critical

In Flowable, a standard parallel multi-instance `<serviceTask id="validate" flowable:async="true" flowable:exclusive="false">` has a well-known concurrency pitfall:
- When `P2` and `P3` finish at nearly the same instant in non-exclusive transactions, both transactions attempt to exit the multi-instance activity and update the parent execution's `nrOfCompletedInstances` / `nrOfActiveInstances` variables (`ACT_RU_VARIABLE` / `ACT_RU_EXECUTION`).
- Flowable's optimistic locking (`REV_`) causes one of the two transactions to fail with `FlowableOptimisticLockingException`, which **rolls back the entire validator execution** and forces `P3` (or `P2`) to re-execute from scratch even though the validator itself succeeded!
- Conversely, marking `validate` as `flowable:exclusive="true"` prevents optimistic locking exceptions by serializing job execution per process instance, which **destroys parallel execution** of `P2` and `P3`.

The PoC solves this cleanly by wrapping each multi-instance item in a `<subProcess id="stage">` with two tasks:
1. `validate` (`async="true"`, `exclusive="false"`): Executes the potentially slow validator logic concurrently across worker threads (`ValidationIntegrationTest#actualParallelJobs` proves `P2` and `P3` pass a 2-party `CyclicBarrier` on two distinct worker threads). Its transaction only writes the domain `ValidationResult` and advances the child execution token to `join`.
2. `join` (`async="true"`, `exclusive="true"`): Acquires the process-instance exclusive lock only for the lightweight multi-instance barrier completion and stage transition (`advance`). Any optimistic lock contention on `join` retries only the no-op `join` task, never the validator body.

**Tradeoff:** Every validator execution incurs **two** Flowable async job transactions instead of one (`validate` + `join`). For validators that perform meaningful I/O or computation, this extra lightweight job is a worthwhile price for true parallel isolation.

---

## 7. Remaining Custom Orchestration Responsibilities (AR-04)

Even with Flowable C2, the following responsibilities remain in custom Java code:
1. **Validator Registry & Business Logic (`Validator`, `ValidatorRegistry`):** Domain validation rules and findings generation.
2. **Dependency Graph & Topological Stage Resolution (`ExecutionPlanResolver`):** Computing stages (`[[P1], [P2, P3], [P4]]`) and transitive downstream closure (`affected(requested)`) for selective re-evaluation.
3. **Process Variable Bridge (`WorkflowPlan`, `ValidationExecutionDelegate`):** Translating resolved Java stages into Flowable process variables (`stages`, `stageIndex`, `validators`, `more`) and bridging a Flowable `DelegateExecution` to a single `Validator` invocation.
4. **Application-Level Idempotency & Run Correlation (`RunStarter`, `ValidationRunService`):** Enforcing `requestKey` uniqueness, copying reusable `ValidationResult` records for untouched upstream validators, and computing re-evaluation lineage (`effectiveRunId`, `effectiveOutcome`, `correlatedRunIds`).
5. **Domain Result & Attempt Persistence (`ValidationResult`, `ExecutionAttempt`, `AttemptAudit`):** Storing business outcomes/findings and rollback-independent attempt audit records.

---

## 8. Operational, Maintainability, and Technology Assessment (AR-05, AR-06)

### Maintainability & Code Quality (AR-05)
- **Strengths:** Compact codebase (~620 LOC across 16 main classes), clear separation between technical exceptions (thrown `RuntimeException`) and business findings (returned `ValidationFinding`), zero mocked engine components in tests.
- **PoC Shortcuts Requiring Production Hardening:**
  1. **Package Structure & Encapsulation:** All 16 classes reside in a single flat `poc` package, and JPA entities (`ValidationRun`, `ValidationResult`, `ExecutionAttempt`) use public mutable fields and are serialized directly in `ValidationRunService.RunView`. Production code should separate `domain`, `application`, `infrastructure`, and `api` packages and map JPA entities to immutable API DTOs.
  2. **Validator Signature:** `Validator.validate(ValidationRun run, int attempt)` leaks synthetic failure-simulation parameters (`attempt`, `run.p3Failures`). In production, `Validator` should accept a clean domain context (`ValidationContext`) without retry attempt numbers.
  3. **Schema Management:** `spring.jpa.hibernate.ddl-auto=update` and `flowable.database-schema-update=true` must be replaced by Flyway/Liquibase migrations in production.

### Technology Compatibility & Resolved Dependencies (AR-06)
Verified against actual packaged libraries in `target/validation-cockpit-1.0.0.jar` (`BOOT-INF/lib/`) and runtime execution on **Amazon Corretto JDK 17.0.21**:

| Component | Declared (`pom.xml`) | Actual Resolved Version (`BOOT-INF/lib`) | Compatibility Notes |
|---|---|---|---|
| **Java** | `17` | `17.0.21` (Corretto) | Matches `--release 17` compiler target and Spring Boot 4 / Flowable 8 baseline |
| **Spring Boot** | `4.0.2` | `4.0.2` | Pulls **Spring Framework `7.0.3`**, **Spring Data JPA `4.0.2`**, **Tomcat `11.0.15`** |
| **Hibernate ORM** | Managed by Boot | `7.2.1.Final` | Jakarta Persistence `3.2.0` |
| **Jackson** | Managed by Boot | `3.0.4` (`tools.jackson.*`) | Uses Jackson 3 alongside `jackson-annotations-2.20` |
| **HikariCP** | Managed by Boot | `7.0.2` | Shared by JPA and Flowable |
| **Flowable** | `8.0.0` (`flowable-spring-boot-starter-process`) | `8.0.0` | Process engine (`flowable-engine-8.0.0`) + MyBatis `3.5.19`. Note: `flowable-spring-boot-starter-process` still brings `flowable-idm-engine-8.0.0.jar`, `flowable-event-registry-8.0.0.jar`, and `flowable-cmmn-api`/`dmn-api` jars onto the classpath transitively, though IDM and Event Registry engines are disabled via `application.properties` (`flowable.idm.enabled=false`, `flowable.eventregistry.enabled=false`). |
| **H2 Database** | Managed by Boot | `2.4.240` | File-mode persistence (`jdbc:h2:file:...;DB_CLOSE_ON_EXIT=FALSE`) |
| **JUnit Jupiter** | `5.13.4` | `5.13.4` | Explicitly pinned via `<junit-jupiter.version>5.13.4</junit-jupiter.version>` |

### SQL Server Migration Considerations
While H2 is used for this local PoC, migrating to **Microsoft SQL Server** in production requires addressing four specific areas:
1. **`READ_COMMITTED_SNAPSHOT` Isolation:** By default, SQL Server `READ COMMITTED` uses shared reader locks that can block or deadlock under concurrent Flowable job acquisition and parent-execution reads. Production SQL Server databases **must** enable `ALLOW_SNAPSHOT_ISOLATION ON` and `READ_COMMITTED_SNAPSHOT ON`.
2. **Lock Escalation & Deadlocks on Flowable Tables:** High concurrency on `ACT_RU_EXECUTION`, `ACT_RU_JOB`, and `ACT_RU_VARIABLE` can trigger SQL Server page/table lock escalation or index key-range deadlocks during multi-instance completion. Row-level locking hints and proper index maintenance are essential.
3. **Process Variable Serialization (`stages`):** `WorkflowPlan.resolve` stores `stages` (`ArrayList<List<String>>`) as a Java `Serializable` process variable (`ACT_GE_BYTEARRAY`). In production, storing execution plans as JSON (`JsonNode` / Jackson) or normalized domain columns is safer across JDK/class evolutions than Java binary serialization.
4. **Nullable Unique Constraints:** In `ValidationRun`, `requestKey` is `@Column(nullable = false)` with a unique constraint, which works identically in SQL Server and H2.

---

## 9. Evolution Toward Full BPMN Orchestration (Section 6)

Can this hybrid C2 architecture evolve into **full BPMN-driven orchestration** where individual validators and their dependencies are modeled directly in BPMN?

### Evaluation of the 6 Transition Questions

1. **Can individual validators become explicitly modeled BPMN tasks?**
   - **Yes.** Each validator (`P1`, `P2`, `P3`, `P4`) can be modeled as a dedicated `<serviceTask id="validate_P1" ...>` invoking a parameterized delegate expression (e.g., `${validationExecution.run(execution, 'P1')}`) or setting a local `validator` field injection.
2. **Can Java-defined dependencies be moved into BPMN gateways?**
   - **Yes.** Stage 1 -> Stage 2 -> Stage 3 transitions become standard BPMN `<parallelGateway id="fork_stage2"/>` (diverging after `P1` to `P2` and `P3`) and `<parallelGateway id="join_stage2"/>` (converging `P2` and `P3` before `P4`).
   - *Important concurrency note:* Even in explicit BPMN, if `P2` and `P3` are `async="true" exclusive="false"`, converging directly into `<parallelGateway id="join_stage2"/>` in the same transaction risks `FlowableOptimisticLockingException` on the converging gateway execution. Therefore, either the converging gateway / join step must remain `async="true" exclusive="true"`, or Flowable's async leave behavior must be used to separate validator execution from gateway join.
3. **Can business routing logic be progressively moved into BPMN?**
   - **Yes.** If business rules later require skipping `P4` when `P3` produces an `INVALID` finding (or routing `INVALID` findings to a human review user task), `<exclusiveGateway>` conditions reading validator outcomes can express this visually in BPMN.
4. **Can existing validator implementations remain unchanged?**
   - **Yes.** The `Validator` implementations and `ValidatorRegistry` (as well as `ValidationResult` persistence) are completely independent of whether the caller is a generic multi-instance subprocess or an explicitly named BPMN service task.
5. **Can generic execution tasks coexist with explicitly modeled BPMN tasks?**
   - **Yes.** A process can explicitly model high-level phases or critical gates as BPMN service tasks while using the existing multi-instance `<subProcess>` pattern for dynamic groups of rule validators within a phase.
6. **Are BPMN process definition versioning and migration requirements understood?**
   - In hybrid C2, adding a validator `P5` or changing dependencies requires only a Java deployment (`ExecutionPlanResolver`); the BPMN XML never changes (`VERSION_ = 1`).
   - In full BPMN orchestration, every validator addition or dependency change creates a new BPMN deployment version (`ACT_RE_PROCDEF.VERSION_`). In-flight process instances remain bound to the BPMN version they started on unless migrated via Flowable's `ProcessInstanceMigrationBuilder`, and selective re-evaluation across different BPMN versions requires explicit version compatibility rules.

### Component Transition Map (C2 -> Full BPMN)

| Component | Action in Full BPMN | Explanation |
|---|---|---|
| `Validator`, `ValidatorRegistry`, `ValidationFinding`, `ValidationResult` | **Retained** | Core business validation logic and domain result persistence remain identical. |
| `ValidationRun`, `RunStarter` (idempotency), `ValidationApi` | **Retained (modified for re-evaluation)** | Run identity, `requestKey` deduplication, and REST inspection remain in Java. |
| `ValidationExecutionDelegate` | **Retained (minor adaptation)** | Continues to execute one validator per task and persist `ValidationResult`, reading the validator ID from a task field/extension or activity ID instead of a multi-instance variable. |
| `WorkflowPlan` & multi-instance `<subProcess id="stage">` | **Removed** | Replaced by explicit `<serviceTask>` nodes and `<parallelGateway>` / `<inclusiveGateway>` elements in BPMN. |
| `ExecutionPlanResolver` | **Transferred / Replaced** | **Critical architectural constraint:** Ownership of dependencies must be transferred, not duplicated. If dependencies are modeled in BPMN gateways, maintaining a separate hardcoded `dependencies` map in `ExecutionPlanResolver` for selective re-evaluation creates two conflicting sources of truth. Either selective re-evaluation must derive downstream dependencies by inspecting the deployed `BpmnModel` graph (`RepositoryService.getBpmnModel(processDefinitionId)`), or BPMN tasks must use inclusive gateways / skip expressions (`flowable:skipExpression="${!selected.contains('P2')}"`) driven by a single dependency model. |
