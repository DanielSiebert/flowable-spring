# Architectural evaluation: hybrid C2

## Decision and evidence

**Recommendation: Adopt hybrid Flowable orchestration for this staged validation model**, subject to normal production qualification. The implementation removes a meaningful amount of infrastructure while keeping validation policy in Java. This is evidence for the architecture in the bounded synthetic workload, not a throughput or production-readiness claim.

The engine is Flowable OSS 8.0.0 with the process-only starter, Spring Boot 4.0.2, Java 17, JPA and file H2. The tests deploy the supplied BPMN, use real engine workers and reopen real file databases. No engine API is mocked. The JVM compiler target is 17 and the final build runs on Java 17. Source versions were checked against the official release BOM and documentation linked in the README.

| Required scenario | Evidence | Result |
|---|---|---|
| Normal execution | `normalOrderingAndPersistence`: durable invocation audit checks P1 before P2/P3 and both before P4, four persisted results, historic process finished | Passed |
| Actual parallel execution | `actualParallelJobs`: P2/P3 wait at the same two-party barrier inside actual async validator jobs; both return on their first attempts | Passed |
| Individual retry | `technicalRetryIsIndividual`: P3 audit records two failures then success; P1, P2, P4 each invoked once; P4 starts after P3 success | Passed |
| Exhaustion and operator recovery | `exhaustedRetriesBecomeDeadLetter`: three failed attempts, engine dead-letter with zero retries, no P4; engine requeue succeeds on P3 attempt 4 without repeating P1/P2 | Passed |
| Restart | `restartResumesCommittedPendingJob`: commit jobs with acquisition disabled, close the full context, reopen the same H2 file with workers enabled; process finishes without reconstruction | Passed for graceful restart with pending work |
| Duplicate request | `concurrentDuplicateRequests`: two simultaneous starts with the same key return the same run ID; exactly four validator invocations | Passed |
| Selective re-evaluation | `selectiveReevaluation`: new correlated process invokes only P3/P4, copies P1/P2 with provenance, leaves original results intact | Passed |
| Business finding | `businessFindingDoesNotRetryOrBlockStages`: P3 returns INVALID/finding once, P4 still runs and process ends | Passed |
| Shared transaction rollback | `resultAndEngineJobRollbackTogether`: fail after flushing P3 result; persisted final result is from attempt 2; verify engine and JPA share the same transaction-manager instance | Passed |
| HTTP application | `restApiStartsInspectsAndReevaluates`: real embedded server and HTTP client start/poll/deduplicate/re-evaluate | Passed |

Final verification: `./mvnw clean verify` on Amazon Corretto Java 17 completed with **10 tests, 0 failures, 0 errors**. The executable jar was also started separately and exercised over HTTP: a run with two synthetic P3 failures completed with invocation counts P1=1, P2=1, P3=3, P4=1. The smoke response is retained in `target/packaged-smoke-result.json`.

The final test report is generated under `target/surefire-reports/poc.ValidationIntegrationTest.txt`. Run `./mvnw clean verify` to reproduce all ten tests. Expected synthetic exceptions and a losing unique-key insert are logged during successful tests.

## A. What Flowable replaced

There is no application job table, scheduler, retry loop, stage-completion counter, persisted run-state enum, recovery scan or custom queue. Flowable persists the current stage and individual active validator executions, creates and acquires async jobs, handles retry timers, records incidents, enforces the multi-instance barrier and maintains technical process/activity history.

On restart, committed jobs and execution variables are recovered directly by the engine. The application does not read a collection of domain statuses and rebuild an execution graph. The domain run table has only identity, correlation, synthetic input configuration and the engine process ID. GET derives technical state from runtime/history/job queries. Findings and results do not form a competing authoritative technical state machine.

The strongest evidence is the failure test: P3 fails twice while P2 stays committed, and P4 remains blocked by the engine barrier until P3 succeeds. This directly addresses the C2 purpose; a single task delegating the full workflow to Java would not provide this result.

## B. What custom implementation remains

- `ValidatorRegistry` and individual `Validator` implementations provide domain behavior.
- `ExecutionPlanResolver` owns selection, dependencies, stage calculation and dependent closure. It detects a cycle/no-progress condition. The synthetic registry/graph is deliberately small; the generic BPMN has no P1–P4 references.
- `ValidatorResultStore` persists outcomes/findings and their provenance. Results use a stable `runId:validatorId` primary key plus a run/validator unique constraint.
- `ValidationRunService` / `RunStarter` create and correlate runs, arbitrate application-level idempotency using a unique request key and assemble domain-oriented inspection responses.
- Selective re-evaluation is a new run, not token rewinding. Java decides what can be reused and what needs invalidation. Invalid prior results and their dependents are recomputed as well.
- `ValidationExecutionDelegate` is the adapter between one engine task and one domain validator. `WorkflowPlan` exposes plan variables and stage advancement to the process.
- `AttemptAudit` persists synthetic attempts independently of rollback. It is diagnostic/failure-simulator state, not a custom retry mechanism. The test probe supplies barriers and a post-flush fault without bypassing the actual engine.

This is still a material amount of business code. Flowable does not decide validity, dependency policy, input versions, re-evaluation scope or duplicate-request semantics. Those responsibilities would exist under either orchestration approach.

## C. Complexity introduced

### BPMN and concurrency

A naive parallel multi-instance task can execute independent bodies concurrently but encounter contention updating the common multi-instance completion state. Retrying a transaction that includes a validator body can repeat it. Setting every job exclusive avoids much contention but serializes P2/P3, defeating the concurrency requirement.

This model deliberately uses a parallel multi-instance **subprocess**. Its async, **nonexclusive** validator task commits into a separate async, **exclusive** join task. Each validator job has independent rollback/retry; join jobs serialize shared stage completion. The barrier test proves this configuration allows real overlap, and the invocation-count tests demonstrate isolation under synthetic failures. The model pays one additional persistent job/transaction per validator for that separation.

Worker concurrency is configured through Boot's application task executor (`spring.task.execution.pool.core-size=4`, `max-size=4`); Flowable uses this executor in its Boot integration. Acquisition settings are under `flowable.process.async.executor`, with 100 ms polling for this small demo. The shared Hikari pool defaults to ten connections; each running validator can temporarily require an extra connection for its `REQUIRES_NEW` audit. Increasing workers without sizing the pool accordingly risks starvation. A real installation must measure capacity and contention.

Exclusive execution is an engine coordination mechanism, not a database-wide guarantee that every possible runtime API mutation is serialized. Multi-node deployment, cancellation, migrations and concurrent administrative interventions are not covered. No optimistic-lock stress or load benchmark was conducted. These limitations do not negate the tested retry isolation but bound its claim.

### Transactions and crash behavior

Flowable and JPA share the same datasource and `JpaTransactionManager`. A validator's successful result flush, job completion and creation of its join job commit together. The post-flush injected-failure test confirms rollback discards a domain result and allows the engine to retry it. Technical failures throw; business findings return and commit normally. Long validators hold transactions/connections; external calls introduce ambiguity and should use stable downstream idempotency keys or an outbox design where appropriate.

The independent attempt audit deliberately survives failed transactions. An attempt marked RETURNED means the Java call returned, not that the encompassing engine transaction definitely committed. A crash can leave STARTED. Attempt counts are a deterministic simulator input and diagnostic trail, not a replacement for engine history or committed results.

The file-database test proves recovery of committed, unacquired pending jobs across complete Spring context shutdown and restart. It does not prove all abrupt crash windows, OS/power-loss durability or lock-expiry behavior. A validator executing at crash time may run again. Exactly-once external side effects are not claimed. The local result uniqueness guard cannot provide that guarantee.

### Persistence and operations

Flowable adds engine schema tables and MyBatis alongside JPA, BPMN deployment/version management, job locks, timers, incident handling, history retention and engine upgrades. The process-only starter avoids broad engine activation; IDM and event-registry activation are explicitly disabled. Only local embedded H2 is evaluated.

Automatic schema updates are appropriate only for this disposable experiment. Production requires controlled migrations and backups. Operator requeue is demonstrated with Flowable's API; a production incident UI, authorization, metrics, exception-data policy and run retention are not implemented. The generic model gives operators activity IDs such as `validate`; application results/attempts supply the validator ID, so inspection must combine both views.

### Tests and API lessons

Real asynchronous tests require polling, deterministic synchronization, restart lifecycle control and independent file databases. Testing only mocked delegates would miss JPA managed-entity mistakes, engine transaction behavior and HTTP serialization defaults. During implementation the tests exposed a detached-entity update in run creation and Jackson 3's strict handling of omitted primitive record fields; both were corrected. Optional failure counts now default explicitly to zero.

Request-key uniqueness is global and preserved after completion. Reusing a key with different payload currently returns the original request; callers must treat keys as immutable request identities. Request fingerprints/conflict responses and tenant scope remain possible extensions. Inspection is eventually consistent across several queries rather than a transactionally atomic snapshot.

## D. Would full BPMN orchestration add value?

Individually modeled P1/P2/P3/P4 tasks and BPMN-managed dependency gateways would make validator relationships, failure paths and operator history visible directly in diagrams. Business-specific skip rules, human intervention, deadlines, compensation and different retry policies could then be represented per task. For a stable small graph managed by a workflow-oriented team, this can be meaningful additional value.

It also moves changes from Java planning into deployed process definitions. To evolve this design, replace stage calculation and looping (`ExecutionPlanResolver.resolve`, `WorkflowPlan`, generic multi-instance stage) with explicit validator tasks/gateways, retaining a one-validator execution adapter and domain result store. `ValidatorRegistry` could remain an implementation lookup. Dependency ownership and selective re-evaluation closure must move together: retaining a Java dependency graph alongside independent BPMN dependencies would create two conflicting sources of truth. Re-evaluation could use modeled entry points/subprocesses or a dedicated derived process, but would require a deliberate design for result reuse and provenance. Run identity/idempotency and domain persistence still remain Java responsibilities.

For this dynamic-plan requirement, full BPMN would trade flexibility for visual explicitness without evidence of additional required behavior. The current generic process already removes scheduler/retry/recovery machinery; explicit P1–P4 tasks are not necessary to gain those benefits.

## E. Recommendation and limits

Adopt hybrid Flowable orchestration for this model. All seven requested scenarios have direct integration evidence, with the restart guarantee explicitly limited to committed pending jobs and graceful application restart. The extra exclusive join boundary is essential to the demonstrated concurrency/retry combination and should be preserved in further evaluation.

Before production adoption, qualify the target database, worker/connection sizing, abrupt-crash/lock-expiry windows, external-side-effect idempotency, real validator duration and contention, process/schema upgrades, and operational access/history retention. These are remaining production risks rather than failed PoC criteria. No required scenario remains unresolved within the tested scope. There is no benchmark against an existing proprietary Java implementation, so the infrastructure reduction is established by responsibilities eliminated here, not by a measured cost or line-count comparison.
