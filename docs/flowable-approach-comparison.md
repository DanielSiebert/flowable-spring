# Architectural Comparison: Flowable Validation Orchestration (C0 / C1 / C2)

| | |
|---|---|
| **Purpose** | Compare avoided custom implementation with added framework and operational complexity |
| **Status** | Evidence-based working assessment, 29 October 2026; production decision open |
| **Current implementation** | **C1**, Java planning with generic BPMN execution, Flowable OSS **8.0.0** |
| **Alternatives** | **C0**, Java orchestration with custom or library-backed reliability; **C2**, explicit BPMN orchestration; neither implemented here |

Earlier exploratory material called the generic multi-instance implementation “C2”. This document reserves C2 for explicit BPMN workflow dependencies. See [architecture](architecture.md) for current implementation and [README](../README.md) for setup and usage.

## 1. Executive Assessment and Evidence Rules

The PoC demonstrates useful C1 capabilities: durable per-validator jobs, automatic acquisition, retries, dead letters, stage synchronization and continuation after graceful restart. Java retains validator selection, dependency planning, re-evaluation and business persistence. Flowable already performs some orchestration through the generic BPMN loop; it is more than a background queue.

There are **not two competing persisted workflow state machines**. Flowable owns execution progression; domain results and independently committed attempts serve different purposes. Their consistency and the derived status projection still require application design.

The intended system accepts insurance applications through channels such as BiPRO, VBon/E-Antrag and e-mail. The implementation exposes one REST adapter, one process key and four synthetic validators (P1–P4). Channel routing, insurance payloads and real external validator calls are not demonstrated.

Use these evidence categories throughout:

- **Demonstrated**: source/configuration and an identified existing test support the behavior in the tested Java 17, Spring Boot 4.0.2, Flowable 8.0.0 and file-H2 environment.
- **Framework capability**: supported by version-specific framework source or authoritative documentation; not automatically an application guarantee.
- **Hypothesis**: a proposed C0/C2 design or projected saving requiring implementation and equivalent acceptance tests.

The independent review ran the unchanged suite successfully: **29 tests, 18 unit/architecture and 11 integration tests**. This is functional evidence, not proof of production resilience. C0/C2 performance, implementation effort, maintenance cost and operational superiority have not been measured. No LOC estimate or fixed complexity ranking is justified.

## 2. Approaches and Execution Ownership

### C0 — Java Orchestration

Distinguish two alternatives:

1. **Entirely custom reliability**: the team implements durable task storage, acquisition, worker coordination, leases, retry scheduling, abandoned-job recovery and incident handling, as well as orchestration.
2. **Durable scheduler library**: evaluate a mature scheduler such as db-scheduler or Quartz for acquisition, persistent scheduling and recovery. Retry/incident features and transaction integration depend on the selected library and configuration; no candidate has been validated here.

In both, the application owns durable dependency/stage progression. Committing a result, completing its task and making subsequent work eligible must be coordinated transactionally or by another explicitly recoverable protocol. An in-memory future/latch alone cannot survive restart. A scheduler does not automatically supply this workflow state machine.

Schema size depends on the scheduler, audit and orchestration design. Fewer engine tables may simplify operation, but does not establish lower total complexity or higher throughput. Integration, locking and recovery tests remain necessary.

### C1 — Java Planning, Generic BPMN Execution (Implemented)

[`RunStarter.start`](../src/main/java/poc/validationprocess/application/service/RunStarter.java) creates the run, enforces request uniqueness, copies reusable results and starts the engine within a Spring transaction. [`WorkflowPlan`](../src/main/java/poc/validationprocess/infrastructure/flowable/WorkflowPlan.java) calls [`ExecutionPlanResolver`](../src/main/java/poc/executionplan/domain/ExecutionPlanResolver.java) synchronously during process start and stores `selected`, `stages`, `stageIndex`, `validators` and `more` as process variables.

`resolve(selected)` orders the selected set, **assuming omitted registered prerequisites are already done**. It does not add prerequisite closure. Separately, `affected(requested)` computes downstream invalidation for re-evaluation. The starter also invalidates previously invalid validators and their dependents, then copies unaffected valid results.

The [BPMN](../src/main/resources/processes/validation.bpmn20.xml) runs a parallel multi-instance subprocess per stage: non-exclusive async `validate`, then exclusive async `join`. Thus a successful item traverses two jobs/engine transactions, plus independent attempt-audit transactions. The join boundary separates committed validator work from multi-instance completion. This is the current design, not a universal requirement for all Flowable models.

[`ValidationExecutionDelegate`](../src/main/java/poc/validationprocess/infrastructure/flowable/ValidationExecutionDelegate.java) only forwards IDs. [`ValidatorExecutionService`](../src/main/java/poc/validation/application/service/ValidatorExecutionService.java) checks for a result, audits, invokes the validator, flushes the result, catches runtime exceptions and rethrows. Flowable supplies retry scheduling and execution continuation. `R3/PT1S` is demonstrated as **three total failed executions before dead-lettering**, with one-second retry delays; findings return normally and do not block later stages. Requeue resets the job's remaining execution budget to three.

### C2 — Explicit BPMN Orchestration (Hypothesis)

Model stable channel workflows as named tasks and sequence flows, with parallel/conditional gateways as appropriate. A shared delegate parameterized by validator ID can execute named tasks; separate delegate classes are not required. Channel-to-definition routing remains an integration/configuration responsibility. The current adapter hardcodes the `validation` key and would need adaptation.

For normal runs, explicit models could remove `WorkflowPlan`, the generic stage loop and its stage variables, and the normal execution use of `ExecutionPlanResolver`. Flowable would navigate the business flow directly. Potential savings are not yet demonstrated. The resolver cannot simply be deleted if re-evaluation still uses its invalidation policy.

Dependencies and stages can be expressed in BPMN; applicability and the meaning of a dependency remain business decisions. Stable automated processes can justify BPMN without human tasks. Named activities could improve diagnosis, but the current starter provides no Cockpit UI. Any proposed modeling/operations UI needs separate evaluation of product/version compatibility, integration and licensing.

Selective re-evaluation could copy valid reusable results into a new run and traverse the ordinary process with delegates that skip already valid results. The existing result-existence guard is a starting point, not proof of a complete C2 reuse policy. Invalidation, input/rule versions and result validity still need a single consistent source of truth. Separate models and skip expressions are options, not mandatory solutions.

Parallel gateways do **not universally require** the extra exclusive join task used here. Flowable 8.0.0's [parallel gateway implementation](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/bpmn/behavior/ParallelGatewayActivityBehavior.java) locks the parent scope. This does not prove absence of contention; transaction boundaries and concurrent paths must be tested in the proposed model.

Model changes may deploy new definition versions. Existing instances normally remain on their definition; migration is optional and must validate activity mappings and variable compatibility. Unchanged tasks can use changed Java code without a BPMN change. **BPMN versioning does not pin Java delegate implementations.** See [Flowable deployment](https://www.flowable.com/open-source/docs/bpmn/ch06-Deployment/) and [instance migration](https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration/).

## 3. Comparison Across 15 Dimensions

C0 and C2 columns describe candidates, not tested implementations. Compare all approaches against the same durability, concurrency, recovery, reuse and query requirements.

| # | Dimension | C0 — Java | C1 — Current PoC | C2 — Explicit BPMN | Evidence / qualification |
|---|---|---|---|---|---|
| 1 | Orchestration | Durable Java progression | Java stages; BPMN executes/barriers | Named BPMN paths could replace normal stage planning | C1: `WorkflowPlan`, resolver and BPMN; alternatives unbuilt |
| 2 | Selection/dependencies | Application policy | Downstream invalidation separate from prerequisite assumptions | Model paths plus application applicability/reuse policy | Resolver unit tests cover ordering, subset assumptions and cycles |
| 3 | Parallel execution | Scheduler/executor plus durable coordination | Non-exclusive validate + exclusive join | Async tasks and gateways; no universal extra-join requirement | `actualParallelJobs` proves overlap on distinct threads in one JVM |
| 4 | State/persistence | Application workflow state plus scheduler schema | Engine tokens/jobs; domain runs/results/attempts | Same split, named token locations | Inspected C1 H2 schema: 30 `ACT_*`, 2 `FLW_*`, 3 domain tables |
| 5 | Retry/recovery | Custom or library mechanisms; integration required | Timers/counters/dead letters; graceful continuation tested | Same engine capabilities; model-specific policies | Abrupt crash, expired locks and multi-node behavior unverified |
| 6 | Error handling | Application classification and scheduler policy | Execution service rethrows technical failures; findings commit | Shared delegate; optional modeled business error paths | Existing retry/finding tests; permanent technical classification absent |
| 7 | Idempotency | Application request identity, result constraints, downstream protocol | Unique request key and result key; existence check | Same business responsibilities | Concurrent request test proves one start locally, not exactly-once invocation |
| 8 | Long waits | Selected library/protocol or custom support | Async boundaries are persisted wait points | Can add human/external waits if required | No human tasks or externally correlated business waits implemented |
| 9 | Visibility | Custom projection over app/scheduler state | Generic activity IDs; multi-source projection | Named activities could improve diagnosis | `inspect()` query count varies with state/lineage; no UI supplied |
| 10 | Testing | Unit plus real DB/scheduler resilience tests | 18 unit/architecture + 11 engine integration tests | Same levels plus deployment/path tests | 29 passing tests; timing is environment-specific |
| 11 | Versioning | Code, persisted task/input compatibility | Stored plan; current registry executes IDs | Definition versions plus Java/data compatibility | Rolling deployment compatibility untested in all candidates |
| 12 | Operations | Scheduler/schema/lease/retention operations | Flowable/MyBatis + JPA, history, pool and upgrades | Same engine plus model deployment lifecycle | No measured total-cost ranking |
| 13 | Initial effort | Depends on custom vs library reliability | Working implementation; some integration mechanisms avoided | Could replace planning/variable plumbing with models | No comparable implementation effort/LOC measurements |
| 14 | Maintenance | Own durable orchestration; library upgrades if used | Own planning/reuse; engine and integration upgrades | Own models/reuse; engine and delegate compatibility | Stability may reduce model churn; costs unmeasured |
| 15 | Coupling | Spring/persistence and chosen scheduler | Flowable imports isolated behind workflow port | Model semantics/extensions plus adapter | Architecture tests verify import isolation, not semantic independence |

## 4. Responsibility and Avoided Implementation

| Responsibility | C0 | C1 (implemented) | C2 (potential) |
|---|---|---|---|
| Queue/acquisition/workers | Custom or scheduler library | Delegated to Flowable | Delegated to Flowable |
| Retry timers/counters | Custom or library-specific | Engine/BPMN policy | Engine/task policy |
| Abandoned-job recovery | Custom or library-specific | Engine mechanism; crash acceptance tests missing | Same engine mechanism; integration tests needed |
| Incident storage/requeue mechanics | Custom or library-specific | Engine storage/API | Engine storage/API |
| Operator recovery policy/API | Application | Application endpoint/policy remains | Application integration remains |
| Durable progress and barriers | Application orchestration | Generic BPMN token progression/barriers | Explicit BPMN paths/gateways |
| Normal stage calculation | Application | Java resolver | Could remove normal-run resolver use |
| Re-evaluation invalidation/reuse | Application | Application | Application/model-derived policy still required |
| Rules, findings and result storage | Application | Application | Application |
| Request identity/external idempotency | Application | Application | Application |
| Attempt history surviving rollback | Explicit audit design | Independent JPA audit | Explicit audit design still needed |
| Status, outcome and lineage | Application projection | Engine + domain projection | Named engine + domain projection |

C1 avoids meaningful scheduler/recovery infrastructure relative to bespoke C0. Relative to library-backed C0, the incremental saving includes engine token progression and barriers, but needs measurement. No approach automatically eliminates application input versioning, external-effect coordination or business outcome policy.

C1 has additional integration surfaces: generic process-variable contracts, two jobs per successful validator in this model, engine persistence/history, shared Spring/JPA/Flowable transactions and multi-source queries. C2 may exchange Java planning for model maintenance rather than simply add complexity. Stable channel workflows favor evaluating that exchange; runtime-generated graphs may favor C1 or C0. None establishes a final winner.

## 5. Production Resilience: Demonstrated Guarantees and Outstanding Validation

### 5.1 Existing Evidence and Its Limits

The unchanged [integration suite](../src/test/java/poc/ValidationIntegrationTest.java) supports the following within the tested single-worker-application/file-H2 setup (the restart test uses successive JVMs, not concurrent nodes):

| Demonstrated behavior | Existing test | Limit of evidence |
|---|---|---|
| Ordering, persistence and terminal result retrieval | `normalOrderingAndPersistence` | Synthetic rules; no production input/result dependencies |
| Overlapping P2/P3 execution | `actualParallelJobs` | Barrier and distinct worker threads in one JVM |
| Individual retry without repeating successful peers | `technicalRetryIsIndividual` | Synthetic runtime exceptions; fixed retry interval |
| Exhaustion, incident visibility and operator requeue | `exhaustedRetriesBecomeDeadLetter` | No permanent-error classification or operator authorization |
| Concurrent request arbitration | `concurrentDuplicateRequests` | Two threads, same H2; one historic instance |
| Selective reuse, findings and lineage/API | `selectiveReevaluation`, `businessFindingDoesNotRetryOrBlockStages`, `restApiStartsInspectsAndReevaluates` | Current catalog and simple lineage; no changed input/rule version |
| Result and engine-step rollback | `resultAndEngineJobRollbackTogether` | Exception after result flush but **before** success audit; not a commit-failure test |
| Graceful restart and continuation | `restartResumesCommittedPendingJob`, `twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure` | Executor shutdown/context close; **not** SIGKILL/abrupt worker termination |

Flowable supplies recovery machinery, not an unconditional availability or exactly-once guarantee. Required outstanding validation:

1. Recovery after abrupt JVM/process termination.
2. Recovery of abandoned acquired jobs after lock expiration/reset.
3. Coordination across multiple active application instances.
4. Duplicate validator invocation and external business effects.
5. Transaction consistency under late failures, including after successful return/audit.
6. Compatibility with the intended production database, **SQL Server**.
7. Rolling deployment, persisted-plan and validator implementation compatibility.
8. Connection pool saturation and long-running validator behavior.

### 5.2 Invocation, Result Records and Business Effects

Design validators for **at-least-once invocation**: an uncommitted job can be retried after failure, and overlapping work must be considered when ownership expires. This is an invocation model, not a promise of eventual success after permanent failures or exhausted retries.

The [result entity](../src/main/java/poc/validation/infrastructure/persistence/ValidationResultJpaEntity.java) has a deterministic ID and a unique `(runId, validatorId)` constraint: **at most one committed result record per key**. This does not establish one invocation, immutable result content, or one historical write. The existence check is not an atomic claim. [Attempt allocation](../src/main/java/poc/validation/infrastructure/persistence/AttemptAuditAdapter.java) uses `count + 1`, which is not a safe concurrent sequence allocator.

**Exactly-once business effects** require application/downstream design: a stable operation identity, atomic effect-and-deduplication recording at the receiver, replay semantics and an adequate deduplication retention period. Sending a token alone is insufficient. `runId:validatorId` may identify a step within a run; effects that must deduplicate across re-evaluations need a broader business identity. No external-effect idempotency is implemented here.

Request uniqueness prevents duplicate starts under the tested conditions; it does not prove exactly-once validators. Flowable business keys provide correlation, not a replacement for the application request-key unique constraint. Existing keys with changed payload or re-evaluation parent return the original run after basic parameter validation. Define key scope and payload conflict behavior before production.

### 5.3 Late Failures and Status Semantics

The shared `JpaTransactionManager` makes result persistence and engine advancement participate in the same job transaction in the current integration; the rollback test checks this. [`ValidatorExecutionService`](../src/main/java/poc/validation/application/service/ValidatorExecutionService.java) itself has no transaction annotation: direct use outside an engine transaction would not inherit that guarantee. See [Flowable 8 Spring transaction integration](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-spring/src/main/java/org/flowable/spring/SpringProcessEngineConfiguration.java).

Attempt start **and finish**, including `RETURNED`, use independent transactions. A returned validator can leave a committed `RETURNED` audit row while a later failure rolls back its result and token. Abrupt death can leave `STARTED`. The current audit is not a committed-result ledger; reconciliation/commit-aware outcomes require further design.

[`FlowableWorkflowAdapter`](../src/main/java/poc/validationprocess/infrastructure/flowable/FlowableWorkflowAdapter.java) derives completion from retained finished engine history. Cleanup can turn a completed run into `UNKNOWN`; administrative deletion is not distinguished from normal completion. Suspended processes/jobs lack an explicit projection. [`RunOutcomePolicy`](../src/main/java/poc/validationprocess/domain/RunOutcomePolicy.java) does not check expected-result completeness before returning `VALID` for a completed process. Define terminal states, retention and completeness rules before relying on this API operationally.

Inspection is not an atomic snapshot. Query count grows with [lineage traversal](../src/main/java/poc/validationprocess/application/service/ValidationRunService.java); the latest completed sibling is selected by creation-time/ID ordering, not an explicit business supersession policy. These concerns apply to equivalent C0/C2 projections too.

## 6. Production Portability & Risks (Flowable Implementations)

### 6.1 Locks, Recovery Time and Long-Running Jobs

Flowable **8.0.0** defaults async job locks to **one hour**, and expired-job reset polling to **one minute**, according to its [version-tagged executor configuration](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-job-service/src/main/java/org/flowable/job/service/impl/asyncexecutor/AsyncJobExecutorConfiguration.java). The current [application properties](../src/main/resources/application.properties) override acquisition polling, not these defaults. Lock expiry is not an immediate restart SLA: reset/acquisition scheduling, queues and database availability add delay. Graceful shutdown can release ownership; abrupt death does not run shutdown hooks. See [executor lifecycle source](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-job-service/src/main/java/org/flowable/job/service/impl/asyncexecutor/AbstractAsyncExecutor.java).

Choose production lease duration and validator timeouts together, measure recovery time and test ownership expiry with stalled work. Do not assume that exclusive jobs provide universal exclusion for non-exclusive validators or external effects.

### 6.2 SQL Server and Schema Operations

H2 results do not validate SQL Server acquisition SQL, uniqueness, isolation, deadlocks, driver behavior or DDL. `READ_COMMITTED_SNAPSHOT` enables row-versioned READ COMMITTED; `ALLOW_SNAPSHOT_ISOLATION` enables explicit SNAPSHOT transactions. Enabling both is **not an unconditional Flowable requirement**, nor does either eliminate all write contention. Evaluate the intended isolation behavior with the DBA and record it in test results. See [Microsoft isolation guidance](https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-transaction-locking-and-row-versioning-guide?view=sql-server-ver17).

The inspected H2 schema contained **30 `ACT_*` tables, two `FLW_*` tables (`FLW_RU_BATCH`, `FLW_RU_BATCH_PART`) and three domain tables**: 32 engine tables, 35 total. This is a configuration/version-specific observation, not a universal Flowable count. JPA and Flowable use one datasource with Hibernate and MyBatis, respectively. Auto schema updates and full history are PoC conveniences; production needs controlled migrations, retention, backup/restore and upgrade validation. C0 also needs such operations for its selected schema/library.

### 6.3 Connection Pool and Audit Transactions

An active outer job transaction retains its connection while `REQUIRES_NEW` requests another. **2 × worker count plus buffer is a sizing heuristic, not a universal minimum or sufficient guarantee.** Account for API traffic, acquisition threads, timers, other borrowers and database capacity; test saturation and bounded connection acquisition. [Spring's propagation guidance](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html) explains the starvation hazard. Moving audit to an event mechanism would itself need durability and ordering design.

### 6.4 Serialization and Deployment Compatibility

The inspected variables `selected`, `stages` and `validators` use Java serialization. A JSON representation may reduce Java class compatibility risk, but still requires versioned payload contracts and upgrade tests. The engine definition does not version Java validator code: existing runs resolve IDs against the currently deployed registry.

The starter compares prior result count with the **current** validator catalog for re-evaluation. Adding/removing validators can invalidate that assumption. Persisted expected-validator sets, immutable input revisions, rule versions and deployment compatibility policy are application concerns in all approaches; none is implemented by BPMN versioning alone.

## 7. Recommended Automated Resilience Test Strategy

**Proposal only:** the tests, harnesses, profiles and prerequisites below are not implemented by this documentation change. Build P1 coverage first, then P2. Passing tests should establish bounded claims for a recorded environment, not universal resilience.

### 7.1 Test Levels and Tooling

| Level | Scope | Recommended support |
|---|---|---|
| Unit | Business rules, transient/permanent retry classification, request/reuse identity decisions, selection and dependency/invalidation logic | Existing JUnit 5; no engine required. Classification and business identity policies need implementation before their intended behavior can pass. |
| Integration | Spring start/job transaction boundaries, engine persistence, constraints, retry/dead-letter behavior, result/audit consistency | Real Flowable + database; retain fast H2 checks, add SQL Server coverage for production claims |
| Process resilience | Real termination, restart over persistent state, concurrent nodes and downstream/network failures | JUnit 5 orchestrating child JVMs or application containers with an independently managed database and fake downstream |

[Testcontainers SQL Server](https://java.testcontainers.org/modules/databases/mssqlserver/) is recommended for reproducible databases; a managed ephemeral SQL Server is an alternative. Use separate JVMs via `ProcessBuilder` if app containers add no value. [WireMock](https://wiremock.org/docs/simulating-faults/) or an equivalent fake can inject HTTP failures; a fake with a durable deduplication/effect ledger is needed to verify business effects. A request journal alone does not implement idempotency. [Toxiproxy](https://java.testcontainers.org/modules/toxiproxy/) is optional for connection cuts, latency and response-loss cases that an HTTP fake cannot represent conveniently. Not all tools are required.

Use [Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/) in a proposed dedicated resilience profile to run heavier tests through `verify`, with cleanup even on failure. The current POM has no such profile, Failsafe configuration, SQL Server JDBC driver, Testcontainers or fault-injection dependencies. Select compatible versions when implementing; no dependency changes are made here.

### 7.2 Harness, Timing and CI Requirements

- Keep the database and downstream ledger alive independently of application death. Use a fresh database/schema per test, retained across that test's restart. File H2's default exclusive access is unsuitable for simultaneous application JVMs; use SQL Server.
- Launch app A (and B where required), await readiness, then submit stable request keys. Use explicit checkpoints/latches or downstream acknowledgements to prove the failure window. `sleep()` timing alone is not evidence.
- For crash tests, kill the OS process directly (for example SIGKILL/forced process destruction on the CI platform, or an immediate container kill), assert process exit, and verify no graceful Spring/executor close occurred. `context.close()`, normal container stop and SIGTERM are graceful-shutdown tests, not substitutes.
- Configure short **test-only** values, for example `flowable.process.async.executor.async-job-lock-time=10s` and `flowable.process.async.executor.reset-expired-jobs-interval=1s`, with explicit short acquisition waits. Assert effective configuration; do not edit production defaults or depend on the one-hour default. Keep unrelated work below the test lease except in the deliberate overrun case.
- Bound recovery assertions by lease expiry + reset/acquisition/queue allowance; record the actual lock expiration and recovery timestamps. Retry budgets and permanent incidents must have separate assertions from eventual-success paths.
- Preserve subprocess logs, engine jobs/locks, attempts, results and downstream ledger on failure. Always kill remaining processes and clean resources in teardown; do not rely on automatic reruns to hide failures.
- Pin SQL Server to the intended production major version/CU and record collation/isolation settings and JDBC version. Confirm license acceptance and allocate adequate memory/startup time. Microsoft supports its Linux SQL Server containers on **x86-64 Linux**; ARM emulation is not supported evidence. Use a suitable Linux x86-64 Docker runner, or connect ARM development machines to a remote compatible test server. See [Microsoft container requirements](https://learn.microsoft.com/en-us/sql/linux/install-upgrade/quickstart-install-docker?view=sql-server-ver17).
- Keep fast tests on normal commits. Run deterministic P1 SQL Server acceptance tests on relevant PRs; use scheduled/release runs for broader failure matrices, P2 cases and repeated concurrency runs. Record unavailable infrastructure as a missing required check, not a passing resilience result. Parallel CI tests need isolated databases/ports and capacity limits.

### 7.3 Prioritized Scenarios and Acceptance Criteria

| Priority | Scenario | Expected verification |
|---|---|---|
| P1 | Abrupt JVM termination during validator execution | Recovery after lock expiration, no lost committed results, eventual progression |
| P1 | Two application instances sharing one database | Safe acquisition, duplicate request arbitration, correct concurrent progression |
| P1 | External side effect followed by crash | Repeated invocation possible; no duplicate business effect with downstream idempotency |
| P1 | Failure after successful return but before transaction commit | Rollback and accurate distinction between returned and committed outcomes |
| P2 | Restart with changed validator implementation or graph | Compatibility of running processes and persisted data |
| P2 | Permanent failures, history cleanup and terminal states | Correct operational and business status |
| P2 | Connection pool exhaustion and long-running validators | Bounded behavior under contention and ownership expiry |

#### P1 — Abrupt Termination and Abandoned Locks

**Failure/infrastructure:** A child JVM enters a gated validator after prior results have committed; observe the acquired job and block before its result commits. Kill that JVM abruptly, retaining SQL Server, then start a replacement. Use short leases/reset polling as above. Include a checkpoint after result flush but before commit.

**Assertions/claim:** prior committed results and reuse provenance remain unchanged; the interrupted result is absent before retry; abandoned ownership expires and work becomes eligible; the replacement eventually completes with one result row per expected key. Repeated invocation and orphan `STARTED` attempts are allowed and measured, not mislabeled as lost results. Validate crash recovery and bounded recovery time, not exactly-once invocation.

**Prerequisites:** test-only deterministic execution gates/process harness, SQL Server driver/setup and effective timeout configuration are required. Engine recovery exists, but passing this scenario is outstanding; audit reconciliation is additional work if a resolved attempt ledger is required.

#### P1 — Concurrent Application Instances

**Failure/infrastructure:** run A/B simultaneously on one SQL Server, with unique executor ownership and overlapping submissions of the same request key. Run parallel independent work, then kill the node owning a gated job and let the survivor recover it. Deploy schema once before workers start to avoid mixing DDL races with acquisition testing.

**Assertions/claim:** one logical run/process is created per key; no duplicate acquisition for the same unexpired ownership is observed under controlled normal execution; unrelated jobs overlap; dependencies hold; committed result keys remain unique; ownership transfers after the killed worker's lease and the run progresses. Track invocations separately from result counts. Do not demand zero overlap after forced lease expiry without an application fencing/idempotency protocol.

**Prerequisites:** multi-process harness, acquisition/entry observations and SQL Server integration. Atomic attempt allocation/fencing may need application changes if overlapping work is required to produce a reliable audit or stronger exclusion guarantee. The existing two-thread duplicate test does not prove these claims.

#### P1 — External Effect Before Crash

**Failure/infrastructure:** a test validator sends a mutation to a separately surviving fake service, which durably records an effect and idempotency key. After acknowledgement and before local commit, gate and kill the app. Restart and allow the same logical operation to be retried. Also test response loss after downstream commit.

**Assertions/claim:** request/validator invocation count can exceed one; a local result eventually exists under the unique result key; downstream effect count is one for the stable business operation key; replay returns the original response. A control without downstream deduplication demonstrates why local uniqueness is insufficient. Validate the application idempotency protocol, not an engine exactly-once guarantee.

**Prerequisites:** no external mutation exists today. Implement a representative adapter, agreed business key/replay semantics and fake's atomic durable deduplication ledger before the single-effect guarantee can hold. WireMock alone is insufficient unless extended with these semantics; Toxiproxy is optional for response loss.

#### P1 — Late Failure After Successful Return

**Failure/infrastructure:** in a real Spring/engine transaction, allow the validator and independent `RETURNED` audit to finish, then inject a deterministic engine/transaction failure before commit. Separately simulate a commit failure; use fault injection at the transaction/connection boundary rather than the existing `afterResult` hook, which runs before success audit.

**Assertions/claim:** for a known rollback, result and token transition do not commit and the job remains recoverable; `RETURNED` can survive. Retry produces a consistent final result. For ambiguous connection loss during commit, inspect durable database state rather than assume rollback. No API/audit assertion may equate returned with committed. Validate transaction integration and truthful observability.

**Prerequisites:** late-failure test instrumentation is required. The current audit vocabulary records return, not commit; a committed-success marker/reconciliation design needs implementation if that stronger outcome is required. Add a start-boundary rollback test to ensure failed initialization leaves no orphan run/process.

#### P2 — Deployment and Graph Compatibility

**Failure/infrastructure:** run version A to a persisted mid-workflow checkpoint; restart with B that changes a validator, adds/removes an ID, changes dependencies or changes variable representation. Retain SQL Server and, for a rolling case, overlap A/B workers. For C2, include changed model definitions and optional migration as separate cases.

**Assertions/claim:** stored plans/variables deserialize, old tasks resolve compatible implementations, already committed results are preserved, and expected validator/result sets remain well defined. Re-evaluation uses the run's agreed input/rule version. Assert explicit rejection/incident for unsupported upgrades rather than silently changing semantics. Model-version tests must not imply Java implementation versioning.

**Prerequisites:** A/B artifacts and an explicit compatibility/version-routing policy. Current registry lookup and current-catalog result count cannot establish these guarantees; snapshot/version metadata or compatible deployment rules need implementation first.

#### P2 — Permanent Failures, Retention and Terminal States

**Failure/infrastructure:** inject permanent technical errors and transient outages separately; exhaust/requeue retries. Complete, suspend or administratively delete runs; apply intended engine-history cleanup while retaining domain results. Use real engine/history and database queries.

**Assertions/claim:** business findings do not retry; transient failures obey budget; permanent failures follow a defined non-retryable/incident policy; no downstream stage crosses an incomplete dependency. Status distinguishes success, incident and cancellation according to the chosen contract, and incomplete results never become business `VALID`. Verify API behavior after retention and operator actions.

**Prerequisites:** permanent-error classification, terminal-state/completeness semantics and retention policy need implementation for these intended guarantees. Today runtime failures share one retry path, finished history can imply completion, and cleaned history can produce `UNKNOWN`. First characterize these behaviors, then test the agreed corrections.

#### P2 — Pool Saturation and Long-Running Work

**Failure/infrastructure:** vary pool size and worker count with concurrent API/acquisition traffic and independent audit transactions; stall a validator and downstream responses, including a deliberate lease overrun. Record pool wait/timeout and invocation/ownership events. Optionally use Toxiproxy for slow/broken connections.

**Assertions/claim:** configured timeouts bound resource waits; no silent task/result loss; after releasing capacity, eligible work recovers or reaches a defined incident. Observe whether ownership expiry causes overlap and verify downstream deduplication if it does. Successful peers remain committed; dependency progression remains correct. Do not require a deliberately undersized pool to run without timeouts, or infer production capacity from a small functional test.

**Prerequisites:** load/gating instrumentation and a timeout/lease policy. Production sizing, safe long-running integration timeouts and any stronger fencing/audit requirements require implementation or configuration before guarantees can hold.

### 7.4 Reuse Across C0, C1 and C2

Define black-box acceptance tests around submit/query/retry operations, persistent result identity, dependency ordering and the external ledger. Use identical input cases, failure checkpoints, retry budgets and recovery-time objectives across approaches. A small approach-specific launcher/diagnostic adapter can expose acquisition/checkpoint evidence without imposing Flowable tables on C0.

Compare successful-result preservation, eventual completion or explicit incident, duplicate effects, recovery time and operator diagnosis. Supplement black-box tests with implementation-specific transaction/lease tests; API polling alone cannot prove an acquired lock or late rollback. Measure custom components, database load, job churn, throughput and maintenance/deployment procedures only after equivalent semantics pass. Do not compare C1's crash cases against only a C0/C2 happy path.

## 8. Architectural Recommendation and Decision Criteria

**Current decision: open.** C1 is viable with demonstrated benefits; C0 with a durable scheduler is a meaningful alternative; C2 is promising for stable, channel-specific workflows. The current evidence does not justify freezing C1, selecting C0 for presumed performance, or selecting C2 merely because workflows are stable.

| Candidate | Reason to evaluate | Evidence needed before selection |
|---|---|---|
| C0 + durable scheduler | Java-centric orchestration may meet requirements with less engine integration | Concrete scheduler transaction/recovery design; durable dependency progression; equivalent P1 tests; measured operational/custom-code cost |
| C1 | Working generic execution, retries and barriers; potential fit for runtime-calculated graphs or existing Flowable operations | P1 crash/multi-node/SQL Server evidence; variable/version contracts; measured benefit over library-backed C0 |
| C2 | Stable named channel flows may replace normal Java planning and improve diagnosis without human tasks | One representative model with shared delegate and reuse; concurrency/transaction tests; routing, deployment compatibility and model maintenance assessment |

Next, build the shared P1 acceptance harness against C1 on SQL Server, then implement a bounded equivalent C0 scheduler slice and C2 named-task model. Reuse the validators, results and identity policy. Record avoided code/components, model changes, failure diagnosis, recovery latency and database/resource cost; treat maintenance estimates as estimates until experience exists.

Choose on demonstrated reliability, workflow variability, operational ownership, required visibility, deployment compatibility and measured implementation/maintenance burden. Human tasks or external business waits would add Flowable value, but are not prerequisites for C2. If all candidates meet acceptance criteria, prefer the simplest design the team can operate and evolve. A production selection remains premature until the outstanding resilience and compatibility evidence is available.
