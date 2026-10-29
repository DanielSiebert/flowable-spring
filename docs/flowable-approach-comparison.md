# Architectural Comparison: Flowable Validation Orchestration (C0 / C1 / C2)

| | |
|---|---|
| **Document Purpose** | Comprehensive architectural evaluation of three implementation options for Validation Cockpit orchestration |
| **Status** | Working assessment & architectural recommendation, 29 October 2026 |
| **Current Implementation** | **C1** (implemented and evaluated in this repository) |
| **Alternatives Compared** | **C0** (Plain Java baseline) and **C2** (BPMN-native Flowable orchestration) |
| **Primary Decision Question** | *What implementation and maintenance effort can we avoid by introducing Flowable, and what additional complexity do we accept in return?* |

> [!NOTE]
> **Terminology & Historical Labels**: Early exploratory documents in this repository referred to the hybrid multi-instance model as "C2". In the definitive architectural classification (established 27 October 2026), **this repository implements C1** ("Flowable as Reliability Engine"). "C2" is reserved for BPMN-native orchestration where sequence flows and gateways encode workflow dependencies.

---

## 1. Executive Summary and Central Trade-Off

The Validation Cockpit executes validation rules (P1–P16) for incoming insurance applications across multiple protocols (BiPRO `setOrder`, VBon/E-Antrag, e-mail). Validator workflows and dependencies are largely stable, but different input channels trigger different workflow definitions, and individual validators are reused across products.

### The Central Trade-Off

Introducing Flowable OSS 8 into the Validation Cockpit resolves **generic workflow reliability concerns** while introducing **substantial infrastructure and schema overhead**:

- **What Flowable avoids**: Custom database polling, custom worker thread coordination, custom retry scheduler loops, dead-letter queue tables, and custom recovery scanners across application restarts.
- **What Flowable introduces**: A dual persistence stack (30 `ACT_*` tables + MyBatis alongside JPA/Hibernate), multi-instance concurrency workarounds (the two-job `validate` + exclusive `join` pattern), Java-serialized process variables (`ACT_GE_BYTEARRAY`), distributed locking considerations on SQL Server, and multi-query composite cockpit inspection.
- **What remains custom in all approaches**: Domain validation rules, validator implementations, structured validation findings, queryable business results, application-level request deduplication (`requestKey`), re-evaluation result reuse policy, and lineage tracking.

---

## 2. Overview of the Three Approaches

### C0 – Plain Java Orchestration (Architectural Baseline)

In C0, the Spring Boot application owns the complete execution plan and workflow state. Persistent job acquisition and retries are provided either by a proven lightweight database-backed scheduler (such as `db-scheduler` or Quartz) or a dedicated application task table (`validation_task`).

- **Workflow & Dependencies**: Defined and managed purely in Java (`ExecutionPlanResolver` and orchestrator services).
- **Execution Engine**: Spring task executor coordinates stages; task status updates (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`) are persisted directly to application database tables.
- **Retries & Recovery**: Backoff intervals, retry counters, and dead-letter handling are handled by scheduled database queries or library workers.
- **Persistence Footprint**: 3–4 application domain tables (`validation_run`, `validation_task`, `validation_result`, `execution_attempt`). Zero external engine tables.

### C1 – Flowable as Reliability Engine (Current Implementation)

In C1, Java dynamically computes the execution plan and passes it to a generic, reusable Flowable BPMN process (`validation.bpmn20.xml`). Flowable does not know validator IDs or business rules; it serves as a durable job executor and stage synchronization barrier.

- **Workflow & Dependencies**: Java resolves stages (`[[P1], [P2, P3], [P4]]`); Flowable executes each stage in a parallel multi-instance subprocess loop.
- **Execution Engine**: Flowable async workers execute each validator as an independent async job (`validate`), followed by an exclusive synchronization job (`join`).
- **Retries & Recovery**: Declarative BPMN retry cycles (`R3/PT1S`), automatic timer jobs, dead-letter job transitions, and automatic startup recovery of unacquired jobs.
- **Persistence Footprint**: 3 domain JPA tables + 30 Flowable `ACT_*` engine tables sharing a single `JpaTransactionManager` and HikariCP pool.

### C2 – Full Flowable Adoption with BPMN (Planned BPMN-Native Model)

In C2, channel-specific validation workflows and their dependencies are modeled explicitly as BPMN 2.0 process definitions (`P1 -> (P2 || P3) -> P4`) using parallel/inclusive gateways and dedicated service tasks.

- **Workflow & Dependencies**: Sequence flows and gateways define execution order; `ExecutionPlanResolver` is eliminated from normal execution.
- **Execution Engine**: Flowable navigates tokens across gateways, scheduling asynchronous jobs for explicitly named validator tasks (`validate_P1`, `validate_P2`, etc.).
- **Retries & Recovery**: Individual service tasks define task-specific retry policies, boundary timer events, and error-handling paths directly in BPMN.
- **Persistence Footprint**: 3 domain JPA tables + 30 Flowable `ACT_*` engine tables, with BPMN deployment versioning (`ACT_RE_PROCDEF.VERSION_`) tracking workflow evolutions.

---

## 3. Comprehensive Comparison Across 15 Dimensions

The following table evaluates C0, C1, and C2 across all fifteen required architectural dimensions. **Verified findings** (demonstrated by code and test execution in this repository) are explicitly distinguished from **assumptions and projections**.

| # | Dimension | C0 – Plain Java | C1 – Flowable as Reliability Engine (Current PoC) | C2 – Full BPMN Adoption | Verified vs Assumption |
|---|---|---|---|---|---|
| **1** | **Orchestration & Execution Planning** | Orchestrator service manages stages, latches, and task completion in Java. Full control over scheduling logic. | Dynamic stage resolution in Java (`ExecutionPlanResolver`); Flowable runs generic multi-instance loop. | Flowable orchestrates sequence flows directly from BPMN definitions; Java planning eliminated for normal runs. | **Verified for C1** in `WorkflowPlan.java` and `ExecutionPlanResolver.java`. C0 and C2 are architectural models. |
| **2** | **Validator Selection & Dependencies** | Java graph resolves dependencies, detects cycles, and calculates transitive closure. | Java graph resolves dependencies and stages; BPMN contains zero validator IDs. | BPMN gateways define dependencies. *Crucial challenge*: Selective re-evaluation still requires dependency logic. | **Verified for C1**: `validation.bpmn20.xml` has 0 validator IDs; all 4 validators resolved dynamically. |
| **3** | **Sequential & Parallel Execution** | `CompletableFuture`, thread pool, or polled task table. Stage barrier requires custom latch or DB count query. | Multi-instance parallel subprocess. Requires separate async non-exclusive `validate` + exclusive `join` tasks to prevent optimistic lock rollbacks. | Parallel gateways (`<parallelGateway>`). Converging join gateways must still handle optimistic locking under concurrent worker completion. | **Verified for C1**: `ValidationIntegrationTest#actualParallelJobs` proves concurrent execution of P2/P3 on distinct worker threads. |
| **4** | **State Management & Persistence** | Persisted task rows (`validation_task`) with status, attempt, and timestamps. Simple single-schema relational model. | Flowable persists tokens, variables, and jobs in 30 `ACT_*` tables. JPA persists domain runs and results. Status derived via multi-query. | Flowable persists tokens at named BPMN activity IDs. Domain results remain in JPA. Composite inspection still needed. | **Verified for C1**: H2 schema contains exactly 3 domain tables and 30 `ACT_*` engine tables. |
| **5** | **Retry & Restart / Recovery** | Custom retry scheduler or library (`db-scheduler`). Polling thread checks `next_retry_at`. Stale lock cleanup required. | Native Flowable `R3/PT1S` timer jobs, dead-letter jobs, and startup recovery of unacquired jobs. Tested across two JVM processes. | Native Flowable retries per service task. Task-specific retry schedules and error boundaries configurable in model. | **Verified for C1**: `ValidationIntegrationTest` verifies timer retries, dead letters, and 2-JVM restart recovery. |
| **6** | **Error Handling** | Application catches exceptions, marks task `FAILED`, increments retry count, or flags terminal incident. | `ValidationExecutionDelegate` catches technical exceptions, marks attempt `TECHNICAL_FAILURE`, and rethrows to trigger Flowable retry. Findings return normally. | Similar adapter pattern, with optional BPMN Error Event boundaries (`<errorBoundaryEvent>`) routing business failures to alternate paths. | **Verified for C1**: `technicalRetryIsIndividual` and `businessFindingDoesNotRetryOrBlockStages`. |
| **7** | **Idempotency & Duplicate Prevention** | Database unique constraint on `requestKey` + `saveAndFlush`. | Database unique constraint on `requestKey` + `saveAndFlush` before process start + `DuplicateRequestKeyException` recovery. | Database unique constraint on `requestKey`. Flowable `businessKey` is not unique by schema and cannot replace DB constraint. | **Verified for C1**: `ValidationIntegrationTest#concurrentDuplicateRequests` arbitrates concurrent identical keys. |
| **8** | **Long-Running Processes & Waits** | Requires building custom wait state tables, polling listeners, and event correlation mechanism. | Supported natively by Flowable, but completely unused in this validation PoC (all validators are automated tasks). | Strong advantage if human review, customer document upload waits, or external webhooks are added. | **Verified**: PoC has 0 wait states; all tasks are automated async service tasks. Long waits are speculative. |
| **9** | **Process Visibility & Monitoring** | Direct relational SQL query over `validation_run` and `validation_task`. Straightforward for Angular cockpit. | Engine history shows generic activity IDs (`validate`, `join`). Cockpit must correlate engine jobs with domain attempts and results. | Strong visual advantage: Flowable Cockpit / BPMN diagram highlights exact active or failed validator task (`validate_P3`). | **Verified for C1**: `inspect()` stitches 6 Flowable and JPA queries to assemble status, activities, and jobs. |
| **10** | **Testing & Debugging** | Fast, lightweight plain JUnit tests without workflow engine startup or MyBatis schema creation. | Real Flowable engine required for integration tests (async workers, file H2). Domain/application unit tests decoupled via ports. | Similar to C1: unit testing delegates requires mocking or engine, plus BPMN XML syntax and deployment validation tests. | **Verified**: 17 unit/arch tests run in <100ms; 11 Flowable integration tests take ~14s due to real async workers. |
| **11** | **Process Versioning** | Java code versioning (Git + CI/CD). Plan changes deploy with standard application JAR releases. | Java code versioning. BPMN never changes (`VERSION_ = 1`); new validators deploy via Java registry updates. | BPMN deployment versioning (`ACT_RE_PROCDEF.VERSION_`). In-flight instances stay on old version unless migrated via migration builder. | **Verified for C1**: Zero BPMN changes required when adding validators or changing stage dependencies. |
| **12** | **Operational Complexity** | Low: 1 datasource, 1 schema migration tool (Flyway), standard Spring Boot operational monitoring. | High: Dual persistence (JPA + MyBatis), 30 engine tables, connection pool sizing constraints, engine history cleanup. | Highest: Engine tables, BPMN deployment pipelines, migration scripts for in-flight workflows, model maintenance. | **Verified for C1**: `AttemptAuditAdapter` (`REQUIRES_NEW`) requires pool size >= 2W to prevent connection deadlock. |
| **13** | **Implementation Effort** | High for initial reliability (~300 LOC custom scheduler/recovery), low for domain modeling. | Moderate: Flowable starter integrates cleanly, but requires multi-instance join workarounds and variable mapping (~137 LOC engine code). | Moderate-High: Modeling all channel workflows, designing selective re-evaluation in BPMN, handling version migration. | **Verified for C1**: Flowable integration is 137 LOC across 3 classes, but core domain/app logic is ~500 LOC. |
| **14** | **Ongoing Maintenance** | Application team maintains custom scheduling and locking code; no external engine upgrade overhead. | Low workflow maintenance (BPMN static), but ongoing Flowable/Spring/Hibernate dependency upgrade overhead. | Ongoing maintenance of multiple BPMN diagrams; risk of process diagram sprawl as product variants multiply. | **Assumption**: BPMN maintenance overhead depends heavily on workflow stability and visual tooling adoption. |
| **15** | **Framework Coupling** | Pure Spring Boot + JPA; zero workflow engine lock-in. | Flowable isolated behind [`ValidationWorkflowPort`](../src/main/java/poc/validationprocess/application/port/ValidationWorkflowPort.java); application logic decoupled. | High coupling to Flowable BPMN extensions, gateway semantics, execution listeners, and process engine lifecycle. | **Verified for C1**: Refactored architecture confines `org.flowable.*` to a single infrastructure package. |

---

## 4. Responsibility Matrix: Custom Implementation Impact

The following matrix categorizes how adopting Flowable (C1 vs C2) impacts custom Java implementation compared to the plain Java baseline (C0):

| Functional Responsibility | Status in C1 (Current PoC) | Status in C2 (Full BPMN) | Explanation |
|---|---|---|---|
| **Async Job Queue & Worker Pool** | **Eliminated** | **Eliminated** | Flowable's `AsyncExecutor` and `ACT_RU_JOB` replace custom thread dispatching, job polling, and distributed DB lock acquisition. |
| **Technical Retry & Backoff Loop** | **Eliminated** | **Eliminated** | Declarative `<flowable:failedJobRetryTimeCycle>R3/PT1S</flowable:failedJobRetryTimeCycle>` replaces custom retry timers and retry count tracking. |
| **Dead-Letter Handling & Requeue** | **Eliminated** | **Eliminated** | `ACT_RU_DEADLETTER_JOB` and `moveDeadLetterJobToExecutableJob` replace custom dead-letter queue tables and requeue endpoints. |
| **Crash & Restart Recovery Scanner** | **Eliminated** | **Eliminated** | Flowable engine automatically acquires pending jobs and due timers on startup without custom state rehydration scans. |
| **Workflow Progress & Token State** | **Substantially reduced** | **Eliminated** | C1 replaces status/stage columns with process variables and derived state; C2 replaces Java planning with BPMN token positions. |
| **Stage Barrier Synchronization** | **Substantially reduced** | **Substantially reduced** | Flowable multi-instance (C1) or parallel gateways (C2) coordinate completion, but both require custom exclusive join handling to prevent optimistic locking. |
| **Dependency Graph & Stage Planning** | **Unchanged** | **Substantially reduced** | C1 still computes stages in Java (`ExecutionPlanResolver`). C2 moves normal dependencies into BPMN gateways, but selective re-evaluation still needs dependency logic. |
| **Selective Re-evaluation & Result Reuse** | **Unchanged** | **Partially reduced** | Computing invalid dependent closure, creating correlated runs, and copying reusable `ValidationResult` entities remains custom in both approaches. |
| **Domain Validation Rules & Findings** | **Unchanged** | **Unchanged** | Business validation rules (`Validator.validate`) and structured findings (`ValidationFinding`) are pure domain logic unaffected by workflow engines. |
| **Domain Result Persistence & Schema** | **Unchanged** | **Unchanged** | Queryable validation outcomes and timestamps reside in JPA `VALIDATION_RESULT`, keyed by `runId:validatorId`. |
| **Application Idempotency (`requestKey`)** | **Unchanged** | **Unchanged** | Enforced via database `@UniqueConstraint` on `ValidationRunJpaEntity`. Flowable business keys cannot arbitrate concurrent unique creation. |
| **Per-Attempt Rolled-Back Audit Trail** | **Unchanged** | **Unchanged** | Flowable deletes timer jobs upon retry success; retaining historical failure messages requires custom `Propagation.REQUIRES_NEW` attempt auditing. |
| **Cockpit Status Querying** | **Increased** | **Increased** | Replacing single-table queries with composite in-memory stitching of 6+ Flowable job/runtime/history and JPA repository queries. |
| **Database Schema Footprint & Operations** | **Increased** | **Increased** | Adds 30 `ACT_*` engine tables, MyBatis, dual-ORM transactions, lock contention on SQL Server, and engine table maintenance. |
| **Multi-Instance Concurrency Workaround** | **Increased** | **Increased** | Adding an extra persistent job and transaction per validator (`validate` + exclusive `join`) to avoid multi-instance optimistic lock conflicts. |

---

## 5. In-Depth Evaluation of Each Approach

### 5.1 C0 – Plain Java Orchestration

#### Advantages
- **Single relational schema**: 3–4 domain tables total; zero external engine tables. Database migrations (Flyway) and index tuning remain straightforward.
- **Single-query cockpit inspection**: Status, active tasks, results, and attempts can be fetched via a single SQL JOIN query, eliminating cross-service concurrency races.
- **Zero workflow engine coupling**: No dependency on Flowable, MyBatis, or BPMN XML parsing. Fast, lightweight unit tests.
- **Direct transaction control**: Task transitions and results commit together without engine interceptors or connection pool multiplication.

#### Disadvantages & Trade-offs
- **Custom reliability infrastructure**: Requires implementing or integrating a durable job scheduler (~200–300 LOC) to manage worker thread dispatching, lock leases, and retry backoff timers.
- **Custom recovery scanning**: On application startup, a recovery scanner must query for abandoned jobs with expired lock leases and re-dispatch them.
- **Future workflow extension burden**: If business requirements later introduce human approval tasks, multi-day wait timers, or complex compensation flows, custom infrastructure must be written from scratch.

### 5.2 C1 – Flowable as Reliability Engine (Current PoC)

#### Advantages
- **Proven asynchronous execution**: Flowable natively manages persistent jobs, worker threads, timer delays, dead letters, and crash recovery.
- **Tested resilience**: All 7 core scenarios pass without custom queue code, including concurrent duplicate requests, individual retries, and mid-workflow multi-process restart recovery.
- **Clean Java planning**: Dynamic execution planning remains in Java (`ExecutionPlanResolver`), allowing arbitrary DAG resolution, cycle detection, and selective re-evaluation without BPMN diagram proliferation.
- **Clean architectural isolation**: Hexagonal refactoring successfully confined all Flowable engine dependencies to `poc.validationprocess.infrastructure.flowable` behind [`ValidationWorkflowPort`](../src/main/java/poc/validationprocess/application/port/ValidationWorkflowPort.java).

#### Disadvantages & Trade-offs
- **Limited orchestration leverage**: Flowable is used primarily as an expensive background job queue and stage latch. The actual business workflow is not modeled in BPMN.
- **Two-job subprocess workaround**: Due to Flowable's parallel multi-instance optimistic locking behavior, every validator execution requires **two** persistent jobs and **two** transactions (`validate` + `join`), doubling engine job churn.
- **Opaque engine history**: Engine history tables record generic activity IDs (`validate`, `join`) rather than business validator names (`P1`–`P4`).
- **Connection-pool starvation hazard**: The combination of Flowable async workers and `REQUIRES_NEW` attempt auditing requires careful pool sizing ($Pool \ge 2W + buffer$).

### 5.3 C2 – BPMN-Native Flowable Orchestration

#### Advantages
- **Explicit visual modeling**: Validator dependencies (`P1 -> (P2 || P3) -> P4`), sequence flows, and parallel gateways are visible directly in BPMN diagrams.
- **Channel routing without Java code**: Different input channels can trigger distinct BPMN processes that reuse common Java validator delegates.
- **Named activity observability**: Flowable history, Cockpit UI, and dead-letter jobs explicitly reference business validator tasks (`validate_P3`) rather than generic `validate`.
- **Extensibility toward human tasks**: Natural path forward if insurance underwriting requires human review steps, SLA timers, or escalation paths.

#### Disadvantages & Trade-offs
- **Selective re-evaluation conflict**: In BPMN, sequence flows define execution. However, selective re-evaluation requires executing only an affected downstream subset while skipping completed upstream tasks. Either a separate re-evaluation process must be modeled, or complex skip expressions (`flowable:skipExpression`) must be maintained, re-introducing dependency logic.
- **Concurrency does not become simpler**: Converging parallel paths into a parallel join gateway still suffers from optimistic locking if multiple async worker threads complete simultaneously. The converging join must remain async and exclusive.
- **BPMN versioning overhead**: Every validator addition or dependency change creates a new deployed BPMN version (`ACT_RE_PROCDEF.VERSION_`). In-flight process instances must either finish on their original version or be migrated via `ProcessInstanceMigrationBuilder`.

---

## 6. Production Portability & Risks (Flowable Implementations)

The following operational risks were identified during PoC evaluation and must be addressed before adopting Flowable in production:

1. **SQL Server Concurrency & Locking**:
   - The PoC evaluated H2. In Microsoft SQL Server, Flowable's concurrent job acquisition and variable updates on `ACT_RU_EXECUTION` and `ACT_RU_VARIABLE` can cause lock escalation and deadlocks under standard `READ COMMITTED`.
   - *Requirement*: Production SQL Server databases **must** enable `ALLOW_SNAPSHOT_ISOLATION ON` and `READ_COMMITTED_SNAPSHOT ON`.
2. **Connection Pool Starvation (`REQUIRES_NEW`)**:
   - Each active validator worker holds an outer transaction connection and briefly requests a second connection for `AttemptAuditAdapter`.
   - *Requirement*: Configure `spring.datasource.hikari.maximum-pool-size >= 2 * spring.task.execution.pool.max-size + 4` or move attempt auditing to a separate event queue.
3. **External Side-Effect Idempotency (At-Least-Once Execution)**:
   - If a validator makes an external HTTP or messaging call and the local transaction or JVM crashes before commit, Flowable will retry the job.
   - *Requirement*: Validators performing external mutations must pass a deterministic idempotency token (`runId + ":" + validatorId`) downstream.
4. **Abrupt Crash (`SIGKILL`) Lock Expiration Delay**:
   - If a JVM crashes abruptly while a worker holds a lock on `ACT_RU_JOB`, Flowable will not re-execute that job until `lockExpirationTime` (default 5 minutes) expires.
5. **Process Variable Serialization**:
   - `stages`, `validators`, and `selected` are currently stored as Java `Serializable` byte arrays in `ACT_GE_BYTEARRAY`.
   - *Requirement*: Replace Java binary serialization with JSON (`JsonNode`) to avoid class version incompatibility across application upgrades.
6. **Request Fingerprinting**:
   - Submitting an existing `requestKey` with a modified payload currently returns the original run. Production should hash/compare payloads and return `409 Conflict` on mismatch.

---

## 7. Evidence-Based Recommendation

### Decision Rule

| Recommendation | Condition |
|---|---|
| **Adopt C2 (BPMN-Native Flowable)** | If validation workflows are stable, visual business modeling provides genuine stakeholder value, and the roadmap requires **human-in-the-loop tasks, SLA escalation timers, or external wait states**. |
| **Adopt C0 (Plain Java + Lightweight Scheduler)** | If the problem remains a **purely technical, dynamically calculated Java validation DAG** without human tasks or waiting states. A lightweight scheduler (`db-scheduler`) eliminates Flowable's 30 engine tables and dual-persistence complexity while fulfilling all reliability requirements. |
| **Do Not Reopen C1** | C1 is evaluated and frozen. While technically viable, using Flowable merely as an async job queue and stage latch incurs the full operational and schema cost of the engine without delivering its core modeling benefits. |

### Final Conclusion

For the current scope of the Validation Cockpit—a dynamic validation DAG executed as short automated service tasks—**Flowable C1 adds substantial framework complexity for limited orchestration benefit**. 

If the organization intends to expand the cockpit into end-to-end insurance workflow management with human review tasks and visual BPMN governance, proceed with evaluating **C2**. Otherwise, adopt **C0** to achieve high performance, simple persistence, and minimal operational overhead.
