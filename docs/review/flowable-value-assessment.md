# Flowable Value Assessment — Hybrid C2 Validation Orchestration

## 1. What Does Flowable Replace?

By adopting Flowable 8 under the hybrid C2 model (per-validator asynchronous execution inside a dynamic multi-instance subprocess), the following custom Java orchestration infrastructure is **eliminated**:

| Custom Orchestration Component Replaced | How Flowable 8 Provides It |
|---|---|
| **Custom Persistent Job Queue & Worker Pool** | Replaced by `ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, and Flowable's `AsyncExecutor` backed by Spring's `ThreadPoolTaskExecutor`, including database-level job lock acquisition (`LOCK_OWNER_`, `LOCK_EXP_TIME_`) across threads/nodes. |
| **Custom Technical Retry Scheduler & Backoff State** | Replaced by declarative BPMN `<flowable:failedJobRetryTimeCycle>R3/PT1S</flowable:failedJobRetryTimeCycle>`, automatic `DecrementJobRetriesCmd` transaction handling on uncaught exceptions, and timer-job scheduling. |
| **Dead-Letter Queue & Incident Requeue Plumbing** | Replaced by `ACT_RU_DEADLETTER_JOB` and `ManagementService.moveDeadLetterJobToExecutableJob(jobId, retries)`, allowing failed runs to pause at the exact failed validator (`P3`) and resume without re-running completed validators (`P1`, `P2`). |
| **Persisted Stage Barrier & Completion Counters** | Replaced by Flowable's parallel multi-instance `<subProcess id="stage">`, which durably tracks `nrOfInstances`, `nrOfActiveInstances`, and `nrOfCompletedInstances` in `ACT_RU_VARIABLE` / `ACT_RU_EXECUTION` and advances to Stage $k+1$ only after all Stage $k$ validators complete. |
| **Application Restart & Crash Recovery Scanner** | Replaced by Flowable's durable execution tree and job tables. On startup, the async executor automatically acquires pending jobs and due retry timers from the database without custom state-graph rehydration code. |
| **Persisted Workflow Lifecycle State Machine** | `ValidationRun` requires no `status` column, current-stage column, or manual state-transition locking; `RUNNING`, `INCIDENT`, and `COMPLETED` states are derived directly from `RuntimeService`, `ManagementService`, and `HistoryService`. |

---

## 2. What Still Requires Custom Java Implementation?

Flowable is a technical workflow engine, not a domain validation framework. Even in C2, a substantial portion of the Validation Cockpit must remain in custom Java code:

1. **Validator Selection, Dependency Graph & Topological Planning ([`ExecutionPlanResolver`](../../src/main/java/poc/ExecutionPlanResolver.java)):**
   - *Why required:* Under C2, dependencies (`P1 -> {P2, P3} -> P4`) are resolved dynamically in Java rather than hardcoded in BPMN. Java must compute the staged execution plan (`[[P1], [P2, P3], [P4]]`), detect dependency cycles, and compute the transitive dependent closure (`affected(requested)`) for selective re-evaluation.
2. **Selective Re-evaluation & Result Reuse Policy ([`RunStarter`](../../src/main/java/poc/RunStarter.java)):**
   - *Why required:* Flowable does not have domain semantics for "create a correlated validation run, copy prior `VALID` results with provenance for unaffected upstream validators (`P1`, `P2`), force re-evaluation of any prior `INVALID` results and their dependents, and execute only the remaining subset (`P3`, `P4`)."
3. **Domain Result, Finding & Lineage Persistence ([`ValidationResult`](../../src/main/java/poc/ValidationResult.java), [`ValidationRun`](../../src/main/java/poc/ValidationRun.java), [`ValidationRunService`](../../src/main/java/poc/ValidationRunService.java)):**
   - *Why required:* Business outcomes (`VALID` / `INVALID`), domain findings (`ValidationFinding`), run-level business outcomes (`outcome`, `effectiveOutcome`), and correlation lineage (`originalRunId`, `reusedFromRun`, `correlatedRunIds`) are domain concepts queried by cockpit users and downstream APIs. Storing them as opaque Flowable process variables would prevent relational querying and indexing.
4. **Application-Level Idempotency ([`RunStarter`](../../src/main/java/poc/RunStarter.java), [`ValidationRunService`](../../src/main/java/poc/ValidationRunService.java)):**
   - *Why required:* Flowable's `businessKey` on `ACT_RU_EXECUTION` is **not** constrained to be unique by Flowable's schema. Preventing concurrent duplicate requests from spawning multiple active workflows requires an application-owned database unique constraint (`@UniqueConstraint(columnNames = "requestKey")` on `ValidationRun`), explicit `saveAndFlush` before starting the process instance, and `DataIntegrityViolationException` recovery.
5. **Per-Attempt Failure History Across Rollbacks ([`AttemptAudit`](../../src/main/java/poc/AttemptAudit.java), [`ExecutionAttempt`](../../src/main/java/poc/ExecutionAttempt.java)):**
   - *Why required:* When an async job fails and rolls back, Flowable records the exception message and decrements `RETRIES_` on the active timer/dead-letter job row. However, once a retry eventually succeeds, Flowable deletes the job row from `ACT_RU_JOB`, and `ACT_HI_ACTINST` contains only a single historic activity record for the task. Retaining a durable, per-attempt audit trail of rolled-back technical failures (`TECHNICAL_FAILURE` + error message + timestamps) requires custom `Propagation.REQUIRES_NEW` persistence (or a custom Flowable event listener writing in an independent transaction).
6. **BPMN-to-Domain Adapter Layer ([`WorkflowPlan`](../../src/main/java/poc/WorkflowPlan.java), [`ValidationExecutionDelegate`](../../src/main/java/poc/ValidationExecutionDelegate.java)):**
   - *Why required:* Translates Java stage lists into Flowable process variables (`stages`, `stageIndex`, `validators`, `more`) and adapts a single Flowable `DelegateExecution` into a `Validator.validate(...)` call.

---

## 3. What Additional Complexity Does Flowable Introduce?

Replacing custom Java orchestration with Flowable introduces significant framework and operational complexity that must be weighed honestly:

1. **Multi-Instance Concurrency vs. Optimistic Locking (The Two-Job Subprocess Workaround):**
   - To run `P2` and `P3` truly in parallel (`flowable:exclusive="false"`) without suffering `FlowableOptimisticLockingException` when both instances update parent multi-instance counters (`nrOfCompletedInstances`) upon completion, the BPMN cannot use a simple multi-instance service task.
   - Instead, it must wrap every validator in a multi-instance `<subProcess>` containing a non-exclusive async `validate` task followed by an exclusive async `join` task.
   - **Impact:** Every validator execution requires **two** persistent Flowable jobs and **two** database transactions (`validate` + `join`), doubling engine job churn and requiring deep Flowable expertise to maintain safely.
2. **Dual Persistence Stack (JPA/Hibernate + MyBatis) & Schema Footprint:**
   - Flowable embeds its own MyBatis persistence layer and creates dozens of `ACT_*` tables (`ACT_RU_*`, `ACT_HI_*`, `ACT_GE_*`, `ACT_RE_*`) alongside the application's 3 domain JPA tables (`VALIDATION_RUN`, `VALIDATION_RESULT`, `EXECUTION_ATTEMPT`).
   - While both share the same `JpaTransactionManager` and Hikari `DataSource`, database schema migrations, index tuning, lock escalation analysis (especially on SQL Server), and high-volume `ACT_HI_*` history cleanup become mandatory operational responsibilities.
3. **Opaque Generic BPMN History:**
   - Because C2 uses a generic stage loop (`<subProcess id="stage">` -> `<serviceTask id="validate">`), Flowable's runtime and historic activity queries report generic activity IDs (`validate`, `join`) rather than business validator names (`P1`, `P2`, `P3`, `P4`). Operators inspecting a workflow must correlate Flowable jobs/executions with `ValidationResult` and `ExecutionAttempt` domain tables to see which validator failed.
4. **Non-Atomic Cross-Service Inspection & Race Conditions:**
   - Assembling a cockpit view (`ValidationRunService.inspect`) requires querying JPA (`RunRepository`, `ValidatorResultStore`, `AttemptRepository`) alongside five separate Flowable queries (`createJobQuery`, `createTimerJobQuery`, `createDeadLetterJobQuery`, `createProcessInstanceQuery`, `getActiveActivityIds`, `createHistoricProcessInstanceQuery`).
   - Because Flowable async workers mutate runtime tables concurrently, callers can observe transient cross-query races (such as the `FlowableObjectNotFoundException` in `getActiveActivityIds` when a process finishes mid-inspection, which we had to patch during Phase B).
5. **Connection Pool Sizing Constraints:**
   - Combining Flowable async worker threads with `Propagation.REQUIRES_NEW` attempt auditing requires strict coordination between `spring.task.execution.pool.max-size` and `spring.datasource.hikari.maximum-pool-size` to avoid connection-pool deadlock.

---

## 4. Is the Framework Providing Enough Value?

### Comparison: Flowable Hybrid C2 vs. Custom Java Orchestrator (e.g., Spring + Database State Table / `db-scheduler`)

| Dimension | Hybrid Flowable C2 (This PoC) | Custom Java Orchestrator (DB-Backed State + Scheduler) |
|---|---|---|
| **Stage & Dependency Resolution** | Custom Java (`ExecutionPlanResolver`) | Custom Java (`ExecutionPlanResolver`) — **Identical** |
| **Selective Re-evaluation & Result Reuse** | Custom Java (`RunStarter`) | Custom Java (`RunStarter`) — **Identical** |
| **Request Idempotency (`requestKey`)** | Custom Java + DB Unique Constraint | Custom Java + DB Unique Constraint — **Identical** |
| **Per-Attempt Failure Audit Log** | Custom Java (`AttemptAudit` with `REQUIRES_NEW`) | Built directly into custom validator task table |
| **Async Task Scheduling & Retries** | Provided by Flowable (`ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, `R3/PT1S`) | Requires ~150–250 LOC using a lightweight library (e.g., `db-scheduler` / Quartz) or a polled `validation_task` table with `next_retry_at`, `retries_left`, `locked_by` |
| **Stage Barrier Coordination** | Provided by Flowable multi-instance subprocess + exclusive `join` job workaround | A SQL query (`SELECT COUNT(*) FROM validation_task WHERE run_id=? AND stage=? AND status!='COMPLETED'`) when a validator finishes |
| **Database Schema Footprint** | ~35+ Flowable `ACT_*` tables + 3 domain tables | 3–4 domain tables total (`validation_run`, `validation_task`, `validation_result`, `execution_attempt`) |
| **Single-Query Cockpit Inspection** | Requires 6+ Flowable + JPA queries stitched in memory | Single SQL/JPA query over `validation_run` + `validation_task` + `validation_result` |
| **Future Evolution to Human Tasks / Complex BPMN** | **Strong advantage:** Native support for BPMN user tasks, timers, boundary events, and visual process modeling | Would require building workflow features from scratch |

### Value Verdict

1. **If the Validation Cockpit remains a purely technical, dynamically planned DAG of Java validators (`ExecutionPlanResolver` driving `P1..Pn`):**
   - Flowable replaces only the **async job queue, retry timer, and stage-completion counter** (~200–300 lines of infrastructure code), while leaving execution planning, selective re-evaluation, idempotency, result provenance, and per-attempt failure auditing in custom Java.
   - At the same time, Flowable introduces ~35 engine tables, MyBatis alongside JPA, two async jobs per validator (`validate` + exclusive `join`) to work around multi-instance optimistic locking, and multi-query state assembly.
2. **When Flowable DOES provide net positive value:**
   - Flowable provides clear net value **if and only if** the team intends to leverage broader BPMN capabilities (such as explicit visual BPMN modeling of validation workflows, human-in-the-loop review/approval tasks for `INVALID` findings, SLA escalation timers, or standardized enterprise operations tooling around Flowable dead-letter incidents) or wants to standardize on an enterprise-supported job/retry engine rather than maintaining custom database polling/locking code.
   - Within the scope of this PoC, the hybrid C2 design is **technically sound, verified to work under concurrency, retries, and multi-process restarts**, and cleanly eliminates custom retry/recovery threads—provided the production risks documented in our Final Review Report are addressed before rollout.
