# Validation Cockpit – Architecture Evaluation

**Status:** Working assessment, 27 October 2026  
**Next step:** Evaluate C2 (BPMN-native orchestration)  
**Decision:** Open; no implementation approach has been selected

## 1. Context

The Validation Cockpit executes validators (P1–P16) for insurance applications. Input channels include BiPRO `setOrder`, VBon/E-Antrag, e-mail and qualified BiPRO `setOrder`.

The validation workflows and their dependencies are expected to be **largely stable**. Different input channels may, however, trigger **different processes or combinations of processes**. Validators can be reused across workflows.

Relevant requirements:

- Select the applicable validation workflow(s) for each input.
- Execute validators in dependency order and run independent validators concurrently where beneficial.
- Persist execution progress and recover after application restarts or crashes.
- Support technical retries and operational handling of permanently failed executions.
- Avoid unnecessarily repeating successful validators during selective re-evaluation.
- Guarantee application-level idempotency and retain business findings, results and lineage.
- Expose meaningful status and diagnostic information to the cockpit.

## 2. The three approaches

| Responsibility | C0 – Plain Java | C1 – Flowable reliability | C2 – BPMN-native Flowable |
| --- | --- | --- | --- |
| Workflow definition | Java | Java | BPMN |
| Validator dependencies | Java `ExecutionPlanResolver` | Java `ExecutionPlanResolver` | BPMN sequence flows, where dependencies are fixed |
| Input-channel routing | Java | Java | Java and/or BPMN |
| Validator execution order | Java orchestrator | Java stages executed by Flowable | Flowable BPMN execution semantics |
| Parallel execution and synchronization | Custom coordination | Flowable multi-instance execution | BPMN parallel/inclusive paths |
| Durable jobs, retries and recovery | Custom or lightweight DB-backed scheduler | Flowable | Flowable |
| Selective re-evaluation and result reuse | Custom domain logic | Custom domain logic | Custom logic and/or explicitly modeled process behavior |
| Business results and findings | Application database | Application database | Application database |
| Application-level idempotency | Application | Application | Application |
| Current evaluation state | Conceptual baseline | **PoC evaluated** | **PoC pending** |

### C0 – Plain Java orchestration

The Spring Boot application owns the execution plan and workflow state. A proven database-backed scheduler can supply persistent job acquisition and retries; C0 does not imply implementing a scheduler from scratch.

**Advantages**

- No workflow-engine persistence schema or additional BPMN abstraction.
- Direct control over execution state, result persistence and cockpit queries.
- Java-based selection and dependency logic remains straightforward to test and debug.
- Potentially lower operational overhead when the problem remains a technical validation DAG.

**Disadvantages**

- Application code must safely coordinate dependencies, concurrent completions and state transitions.
- Restart recovery, retry policy, failed-job handling and monitoring must be designed and verified.
- Future human review steps, long-lived waits and escalation flows require more custom infrastructure.
- A simplistic line-count estimate understates the work needed for robust concurrency and recovery.

**Assessment:** A credible alternative and the reference against which Flowable's additional complexity should be justified. A production-quality C0 implementation has not yet been evaluated on equal terms with the Flowable PoC.

### C1 – Flowable used primarily as a reliability engine

Java computes a staged validation plan; Flowable runs those stages durably. This is the **hybrid multi-instance architecture from the existing PoC** (previously named C2 in some PoC documents). The labels in this document supersede that earlier naming.

**Advantages**

- Flowable provides persistent jobs, retries, dead-letter handling and crash recovery.
- Flowable coordinates stage completion and maintains workflow execution state.
- The existing PoC has exercised important technical scenarios, including parallel execution, failures and restarts.
- Less custom job-queue and recovery infrastructure than a fully hand-built orchestrator.

**Disadvantages**

- `ExecutionPlanResolver`, validator-selection policy and selective re-evaluation remain in Java.
- Flowable adds its own tables and persistence layer alongside application JPA entities.
- The tested parallel multi-instance design required a non-trivial asynchronous join workaround to control optimistic-locking conflicts.
- Generic BPMN activities obscure individual business validator names in engine history.
- Cockpit inspection combines several engine queries with domain queries and must tolerate concurrent state changes.
- A substantial framework is introduced without using BPMN to model the actual validation processes.

**Assessment:** **Evaluation complete for the tested C1 design.** Technically viable, but its current value proposition is limited: Flowable largely replaces durable scheduling and stage synchronization while domain orchestration stays custom. Further C1 implementation is not the next priority.

### C2 – BPMN-native Flowable orchestration

Channel-specific validation workflows and their stable dependencies are represented directly in BPMN. Java delegates execute reusable validators, while Flowable determines which activity can run next.

**Advantages**

- Potential elimination of `ExecutionPlanResolver` from normal execution: dependency ordering is encoded by BPMN flows.
- Explicit modeling of alternative paths, concurrency and synchronization.
- Different input channels can select different process definitions; common validation logic can be reused.
- Individual service tasks can be named and monitored as business validators rather than only as generic stage-loop activities.
- Durable execution, retries and restart recovery remain available.
- More natural extension toward human tasks, external wait states, timers and escalation workflows.

**Disadvantages and open questions**

- BPMN modeling, engine persistence and operational complexity still have a cost.
- Conditional routing can make models difficult to maintain if process variants proliferate.
- Application idempotency, domain findings, lineage and result reuse do **not** disappear.
- Selective re-evaluation of an earlier validator and its dependents may require a dedicated new process or custom dependency logic; it is not automatically solved by BPMN.
- Parallel *paths* do not, by themselves, prove actual concurrent Java execution. Async configuration, locking and retry behavior must be tested.
- The amount of custom Java genuinely eliminated and the quality of cockpit observability remain unverified.

**Assessment:** The most promising **Flowable** option given stable dependencies and channel-specific workflows, but not yet validated. A focused C2 PoC is needed before comparing it conclusively with C0.

## 3. Responsibilities that remain application-owned

Regardless of approach, the Validation Cockpit still needs:

- Validator implementations and domain business rules.
- Structured validation findings and queryable business results.
- Application-level request deduplication/idempotency.
- Correlation between related runs and provenance of reused results.
- A defined selective re-evaluation policy.
- APIs and projections for the Angular cockpit.

Flowable is a workflow engine, not a replacement for the validation domain model.

## 4. Evaluation status and provisional direction

| Option | Status | Current conclusion |
| --- | --- | --- |
| **C0** | Architectural baseline | Viable; estimate and validate reliability implementation rather than assuming it is trivial. |
| **C1** | **Evaluated** | Technically sound in the tested PoC, but substantial framework overhead for limited orchestration benefit. Freeze further work. |
| **C2** | **To evaluate next** | Potentially captures Flowable's full value by moving stable dependencies and workflow routing into BPMN. |

**Provisional preference:** Evaluate C2 before choosing between C2 and C0. Do not select Flowable solely because the C1 PoC demonstrated reliable job execution.

## 5. Next evaluation: C2

The next PoC should reuse existing Java validators and relevant reliability test cases, but **must not use `ExecutionPlanResolver` to construct the normal execution stages**.

### Minimal scenarios

1. **BPMN-defined dependencies:** Model `P1 -> (P2 || P3) -> P4` explicitly using BPMN service tasks and gateways.
2. **Channel routing:** Show two input channels starting meaningfully different BPMN workflow definitions (or a documented common model with conditional routing).
3. **Reuse:** Invoke at least one common Java validator from both workflows without duplicating its implementation.
4. **Concurrency:** Verify that P2 and P3 actually execute concurrently when intended, and that P4 starts only after both complete.
5. **Reliability:** Exercise technical failure, retries, terminal incident handling and restart recovery without rerunning already completed upstream tasks.
6. **Re-evaluation:** Demonstrate or explicitly design how a changed P3 result causes the necessary P3/P4 re-evaluation while P1/P2 results are reused. Identify any residual dependency logic.
7. **Observability:** Verify that operators and the cockpit can identify the active or failed validator without excessive cross-system reconstruction.

### Decision criteria

After the C2 PoC, compare C0 and C2 on:

- **Custom orchestration removed:** Which Java responsibilities and failure modes genuinely disappear?
- **Maintainability:** Are stable validator dependencies easier to understand and modify in BPMN?
- **Reliability:** Do concurrency, recovery and retry scenarios pass with acceptable complexity?
- **Observability:** Are active validators, failures and run results easy to inspect?
- **Operational fit:** Is the additional Flowable schema, monitoring and expertise acceptable in the target environment, including SQL Server?
- **Future needs:** Are human tasks, long-running waits or escalation mechanisms real requirements rather than speculative benefits?

## 6. Decision to be recorded after C2

- **Choose C2** if BPMN materially simplifies stable process orchestration and its reliability/operational costs are justified.
- **Choose C0** if BPMN adds little beyond what a lightweight scheduler and focused Java orchestration would provide.
- **Do not reopen C1** without a new requirement that changes its value proposition.

**Open decision:** C0 versus C2. The C1 evaluation is complete; the C2 evaluation is the next step.
