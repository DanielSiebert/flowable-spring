# Independent Final Review Report — Flowable Validation PoC

## 1. Executive Summary

An independent architectural and implementation audit was conducted on the Flowable 8 Validation Cockpit Proof of Concept (`/Users/dnsbrt/IdeaProjects/flowable-validation-poc`). The PoC evaluates **Approach C2 (Hybrid orchestration with per-validator Flowable execution)** on **Java 17 (`17.0.21`)**, **Spring Boot `4.0.2`**, **Flowable OSS `8.0.0`**, **Spring Data JPA (`Hibernate 7.2.1.Final`)**, and **file-based H2 (`2.4.240`)**.

Our audit found that the core BPMN and transaction design is **genuinely Approach C2** and technically well-crafted—specifically its use of a parallel multi-instance `<subProcess>` splitting each validator into a non-exclusive async `validate` job (`R3/PT1S`) followed by an exclusive async `join` job to achieve true parallel execution without optimistic-locking rollbacks.

However, our Phase A audit also uncovered **five concrete implementation and verification gaps** that were masked by the original test suite:
1. **Concurrency bug (`500 Internal Server Error`) in `ValidationRunService.inspect()`:** A race condition between `createProcessInstanceQuery().count() > 0` and `runtime.getActiveActivityIds(processId)` threw an unhandled `FlowableObjectNotFoundException` whenever a process instance completed concurrently during inspection.
2. **Incorrect REST HTTP status codes for unknown run IDs (FR-08):** Missing run IDs on `GET /{id}`, `POST /{id}/re-evaluations`, and `POST /{id}/dead-letter-retries` threw `IllegalArgumentException`, returning `400 Bad Request` instead of `404 Not Found`.
3. **Missing overall validation run outcome and re-evaluation lineage in `RunView` (FR-06, FR-07, FR-08):** The domain view exposed technical workflow state (`RUNNING`, `COMPLETED`, `INCIDENT`) and per-validator outcomes (`VALID`, `INVALID`), but lacked an explicit overall validation run business outcome (`outcome`), correlated run history (`correlatedRunIds`), and current effective validation result (`effectiveRunId`, `effectiveOutcome`).
4. **Lost technical failure messages after retry recovery (FR-03, FR-04):** Once a retried validator succeeded on a later attempt, Flowable deleted the timer job row (`ACT_RU_TIMER_JOB`), and `ExecutionAttempt` did not store the exception message (`ex.getMessage()`), leaving historical `TECHNICAL_FAILURE` attempt records without failure reasons.
5. **Insufficient restart recovery test evidence (FR-04):** The original restart test only restarted a Spring context inside the same JVM before *any* validator had executed (`attempts().isEmpty()`), failing to prove multi-process recovery or mid-workflow continuation without repeating completed validators.

All five issues were remediated in Phase B with minimal, targeted changes and verified by an expanded **11-test integration suite** (including a two-JVM-process mid-workflow recovery test), which passes completely (`11 run, 0 failures, 0 errors`).

---

## 2. Requirements Coverage Summary

| ID | Requirement | Initial Audit | Post-Remediation | Evidence Summary |
|---|---|---|---|---|
| **FR-01** | Dynamic Execution Planning | PASS (minor gap) | **PASS** | `ExecutionPlanResolver` computes `[[P1], [P2, P3], [P4]]`; BPMN has zero hardcoded validator IDs; input validation hardened. |
| **FR-02** | True Parallel Validator Execution | PASS | **PASS** | `CyclicBarrier(2)` inside `P2`/`P3` proves concurrent execution across two distinct Flowable worker threads (`actualParallelJobs`). |
| **FR-03** | Independent Technical Retries | PARTIAL | **PASS** | `R3/PT1S` retries only `P3`; `TIMER` job retry decrement (`retries < 3`), durable attempt `error` messages, and `DEAD_LETTER` recovery verified. |
| **FR-04** | Persistent Execution and Recovery | PARTIAL | **PASS** | Verified across **two separate JVM processes** sharing file-based H2 (`twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure`); `P1`/`P2` not repeated, `P3` failure preserved, `P3` retry + `P4` completed. |
| **FR-05** | Duplicate Request Handling | PASS (minor gap) | **PASS** | `@UniqueConstraint(columnNames = "requestKey")` + `saveAndFlush` arbitrates concurrent, active-sequential, and post-completion duplicates; verified single Flowable process instance in `HistoryService`. |
| **FR-06** | Selective Re-evaluation | PARTIAL | **PASS** | Re-evaluating `P3` executes only `P3` and dependent `P4`, reuses `P1`/`P2` (`reusedFromRun`, `attempt=0`), preserves original run findings, and exposes `effectiveRunId`, `effectiveOutcome`, and `correlatedRunIds`. |
| **FR-07** | Business vs. Technical Failures | PARTIAL | **PASS** | Business findings return `ValidationFinding` without throwing, commit `outcome="INVALID"` without retry, and distinguish technical `state` (`COMPLETED`) from overall run `outcome` (`INVALID`). |
| **FR-08** | REST API | PARTIAL | **PASS** | Verified `200 OK`, `400 Bad Request` (validation errors), and `404 Not Found` (unknown run IDs) across all endpoints without leaking Flowable engine entities. |
| **AR-01** | Genuine C2 Implementation | PASS | **PASS** | Per-validator multi-instance async service tasks managed by Flowable. |
| **AR-02** | State Ownership | PARTIAL | **PASS** | Flowable owns active executions/jobs/retries; JPA owns domain runs/results/findings; race-free status and outcome derivation in `ValidationRunService.inspect()`. |
| **AR-03** | Transaction Boundaries | PASS | **PASS** | Shared `JpaTransactionManager`; per-validator transactions roll back domain results and Flowable jobs together on exception (`resultAndEngineJobRollbackTogether`). |
| **AR-04** | Framework vs. Custom Infrastructure | PASS | **PASS** | Eliminates custom job tables, retry loops, stage counters, and recovery scanners; retains Java planning, idempotency, result store, and `REQUIRES_NEW` attempt audit. |
| **AR-05** | Maintainability and Extensibility | PARTIAL | **PASS** | Clean separation of validator interface and engine adapter; production refactoring items documented. |
| **AR-06** | Technology Compatibility | PASS | **PASS** | Verified Java `17.0.21`, Spring Boot `4.0.2` (Spring `7.0.3`, Hibernate `7.2.1.Final`, Jackson `3.0.4`), Flowable `8.0.0`, H2 `2.4.240`, JUnit `5.13.4`. |

---

## 3. Critical and Major Findings

### Finding 1 (Critical — Fixed): Race Condition in `ValidationRunService.inspect()` Throwing `FlowableObjectNotFoundException`
- **Observation:** `ValidationRunService.inspect()` checked `runtime.createProcessInstanceQuery().processInstanceId(r.processId).count() > 0` and subsequently invoked `runtime.getActiveActivityIds(r.processId)`. When a background worker completed the process instance between those two calls, Flowable threw `FlowableObjectNotFoundException: execution ... doesn't exist`, causing sporadic test failures and HTTP 500 errors under polling.
- **Remediation:** Wrapped `runtime.getActiveActivityIds(r.processId)` in a `try/catch (FlowableObjectNotFoundException)` block and cleared `activities` whenever `state` resolves to `COMPLETED`.

### Finding 2 (Major — Fixed): Unknown Run IDs Returned HTTP `400 Bad Request` Instead of `404 Not Found`
- **Observation:** `ValidationRunService.inspect()`, `ValidationRunService.retryDeadLetters()`, and `RunStarter.start()` (for missing `originalRunId`) threw `IllegalArgumentException`, which `ValidationApi` mapped to `400 Bad Request`.
- **Remediation:** Changed missing-run lookups to throw `NoSuchElementException` and added `@ExceptionHandler(NoSuchElementException.class)` in `ValidationApi` returning `404 Not Found`.

### Finding 3 (Major — Fixed): Missing Overall Validation Run Outcome and Re-evaluation Lineage
- **Observation:** `ValidationRunService.RunView` did not distinguish the overall validation run business outcome (`VALID` / `INVALID` / `PENDING`) from the technical workflow execution state (`RUNNING` / `COMPLETED` / `INCIDENT`), nor did inspecting an original run reveal subsequent selective re-evaluation runs or the current effective validation result.
- **Remediation:** Added `createdAt` to `ValidationRun`, `findByOriginalRunIdOrderByCreatedAtAsc` to `RunRepository`, and `outcome`, `effectiveRunId`, `effectiveOutcome`, and `correlatedRunIds` to `RunView`.

### Finding 4 (Major — Fixed): Technical Failure Exception Messages Lost After Retry Success
- **Observation:** Once a retried validator succeeded on a later attempt, Flowable removed the job from `ACT_RU_TIMER_JOB`, and `ExecutionAttempt` only stored `outcome = "TECHNICAL_FAILURE"` without the exception message.
- **Remediation:** Added `public String error` to `ExecutionAttempt` and persisted `ex.getMessage()` via `AttemptAudit.finish(attempt.id, "TECHNICAL_FAILURE", ex.getMessage())`.

### Finding 5 (Major — Fixed): Single-JVM Pre-Execution Restart Test Did Not Prove Mid-Workflow Multi-Process Recovery
- **Observation:** `ValidationIntegrationTest#restartResumesCommittedPendingJob` only closed and reopened a Spring context within the same JVM while `attempts().isEmpty()`.
- **Remediation:** Added `ValidationIntegrationTest#twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure`, which spawns a separate child JVM process via `ProcessBuilder`, executes `P1` and `P2` to completion, fails `P3` attempt 1, terminates the child JVM, and resumes in a second JVM against the shared H2 file database.

---

## 4. Summary of Code Changes Made During Remediation

1. [`src/main/java/poc/ExecutionAttempt.java`](../../src/main/java/poc/ExecutionAttempt.java): Added `public String error;` field to store technical failure messages across rollbacks.
2. [`src/main/java/poc/AttemptAudit.java`](../../src/main/java/poc/AttemptAudit.java): Added `finish(String id, String outcome, String error)` overload in `Propagation.REQUIRES_NEW`.
3. [`src/main/java/poc/ValidationExecutionDelegate.java`](../../src/main/java/poc/ValidationExecutionDelegate.java): Passed `ex.getMessage()` to `audit.finish(...)` on `RuntimeException`.
4. [`src/main/java/poc/ValidationRun.java`](../../src/main/java/poc/ValidationRun.java): Added `public long createdAt` timestamp for deterministic lineage ordering.
5. [`src/main/java/poc/Repositories.java`](../../src/main/java/poc/Repositories.java): Added `findByOriginalRunIdOrderByCreatedAtAsc(String originalRunId)` on `RunRepository`.
6. [`src/main/java/poc/ExecutionPlanResolver.java`](../../src/main/java/poc/ExecutionPlanResolver.java): Added explicit null/empty/unknown validator check in `resolve(Set<String>)`.
7. [`src/main/java/poc/RunStarter.java`](../../src/main/java/poc/RunStarter.java): Moved input validation before `findByRequestKey` and threw `NoSuchElementException` when `original` run ID does not exist.
8. [`src/main/java/poc/ValidationRunService.java`](../../src/main/java/poc/ValidationRunService.java): Threw `NoSuchElementException` on unknown run ID, fixed `FlowableObjectNotFoundException` race in `inspect()`, and added `outcome`, `effectiveRunId`, `effectiveOutcome`, and `correlatedRunIds` to `RunView`.
9. [`src/main/java/poc/ValidationApi.java`](../../src/main/java/poc/ValidationApi.java): Added `@ExceptionHandler(NoSuchElementException.class)` returning HTTP `404 Not Found`.
10. [`src/test/java/poc/ValidationIntegrationTest.java`](../../src/test/java/poc/ValidationIntegrationTest.java): Strengthened existing tests and added `twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure` as well as comprehensive REST negative/status-code assertions (`400`, `404`, `200`, lineage, and findings).

---

## 5. Tests Executed and Actual Outcomes

Command executed on Amazon Corretto JDK `17.0.21`:
```sh
./mvnw clean verify
```
**Outcome:** `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` (Build Success).

| Test Method | Requirement(s) Verified | Result |
|---|---|---|
| `normalOrderingAndPersistence` | FR-01, AR-01, AR-02 | **PASS** |
| `actualParallelJobs` | FR-02, AR-01, AR-03 | **PASS** |
| `technicalRetryIsIndividual` | FR-03, AR-01, AR-03 | **PASS** |
| `exhaustedRetriesBecomeDeadLetter` | FR-03, AR-01 | **PASS** |
| `restartResumesCommittedPendingJob` | FR-04 | **PASS** |
| `twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure` | FR-04, FR-03, AR-02 | **PASS** |
| `concurrentDuplicateRequests` | FR-05, AR-02 | **PASS** |
| `selectiveReevaluation` | FR-06, FR-07, AR-02 | **PASS** |
| `businessFindingDoesNotRetryOrBlockStages` | FR-07, AR-02 | **PASS** |
| `resultAndEngineJobRollbackTogether` | AR-03, FR-03 | **PASS** |
| `restApiStartsInspectsAndReevaluates` | FR-08, FR-05, FR-06, FR-07 | **PASS** |

---

## 6. Remaining Unresolved Risks & Production Limitations

While all PoC requirements are satisfied, the following architectural and operational risks remain outside the scope of a local H2 PoC and must be addressed before production deployment:

1. **Hikari Connection Pool Starvation under `REQUIRES_NEW` (`AttemptAudit`):** Each concurrent validator job holds one outer connection and briefly acquires a second connection for `AttemptAudit`. Production configuration **must** enforce `hikari.maximum-pool-size >= 2 * asyncExecutorMaxPoolSize + buffer` or move attempt auditing off the primary transactional pool.
2. **External Side-Effect Idempotency (At-Least-Once Execution):** If a validator calls an external HTTP/messaging service and the local transaction or JVM crashes before commit, Flowable will re-run the validator. Validators performing external mutations must pass a deterministic idempotency token (`runId:validatorId`).
3. **Abrupt Crash (`SIGKILL`) Lock-Expiration Delay:** If a JVM crashes abruptly while a worker holds a lock on `ACT_RU_JOB`, Flowable will not re-execute that job until `lockExpirationTime` (default 5 minutes) elapses.
4. **SQL Server Lock Contention & Isolation Level:** Production deployment on SQL Server must be tested with `READ_COMMITTED_SNAPSHOT ON` under realistic multi-node concurrency to verify that multi-instance variable updates do not trigger page-lock escalation or deadlocks.
5. **Request Payload Fingerprinting on Duplicate `requestKey`:** Repeating an existing `requestKey` with a modified payload currently returns the existing run without verifying payload equality. Production should hash/compare the request payload and return `409 Conflict` on mismatch.

---

## 7. Recommended Next Steps

1. **SQL Server Multi-Node Benchmark:** Run the 11-test suite and a 50-concurrent-run load test against two application instances sharing a SQL Server database with `READ_COMMITTED_SNAPSHOT ON`.
2. **Replace Java Serialization of `stages` Variable:** Store the resolved execution plan (`stages`, `validators`) as JSON rather than Java `Serializable` byte arrays in `ACT_GE_BYTEARRAY`.
3. **Enforce Connection Pool Sizing Guardrail:** Add a startup check or configuration binding verifying `spring.datasource.hikari.maximum-pool-size > 2 * spring.task.execution.pool.max-size`.
4. **Decide Long-Term BPMN Trajectory:** Determine whether the cockpit will remain a dynamic Java-planned DAG (C2) or transition to explicit BPMN validator tasks/gateways (where Flowable's visual modeling and human-task capabilities provide their highest leverage).

---

## 8. Final Architecture Recommendation

**ADOPT HYBRID FLOWABLE** — *Requirements are satisfied, and the framework offers meaningful simplification.*

**Justification:**
All 8 functional requirements (FR-01–FR-08) and all 6 architectural requirements (AR-01–AR-06) have been independently verified against real Flowable 8 execution, including deterministic parallel execution across worker threads, isolated technical retries (`R3/PT1S`), dead-letter incident recovery, shared JPA/Flowable transaction rollback, concurrent request deduplication, selective re-evaluation lineage, and mid-workflow recovery across separate JVM processes. The hybrid C2 design eliminates custom persistent job queues, retry schedulers, stage-completion latches, and restart recovery scanners while keeping validation rules and dependency planning cleanly in Java.
