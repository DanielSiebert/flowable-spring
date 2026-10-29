package poc.validationprocess.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import poc.executionplan.domain.ExecutionPlanResolver;
import poc.validation.application.port.AttemptAuditPort;
import poc.validation.application.port.ValidationProbe;
import poc.validation.application.port.ValidationResultRepository;
import poc.validation.application.service.ValidatorExecutionService;
import poc.validation.domain.ExecutionAttempt;
import poc.validation.domain.ValidationFinding;
import poc.validation.domain.ValidationResult;
import poc.validation.infrastructure.validator.ValidatorRegistry;
import poc.validationprocess.application.port.ValidationRunRepository;
import poc.validationprocess.application.port.ValidationWorkflowPort;
import poc.validationprocess.domain.DuplicateRequestKeyException;
import poc.validationprocess.domain.ValidationRun;
import poc.validationprocess.domain.WorkflowStatus;

class ValidationRunServiceUnitTest {

  private final Map<String, ValidationRun> runsById = new LinkedHashMap<>();
  private final Map<String, ValidationResult> resultsById = new LinkedHashMap<>();
  private final Map<String, List<String>> startedProcessSelections = new LinkedHashMap<>();
  private final Map<String, String> processStates = new LinkedHashMap<>();

  private ValidationRunService service;

  @BeforeEach
  void setUp() {
    runsById.clear();
    resultsById.clear();
    startedProcessSelections.clear();
    processStates.clear();

    var catalog = new ValidatorRegistry();
    var planResolver = new ExecutionPlanResolver(catalog.ids());

    ValidationRunRepository runRepo =
        new ValidationRunRepository() {
          @Override
          public Optional<ValidationRun> findById(String id) {
            return Optional.ofNullable(runsById.get(id));
          }

          @Override
          public Optional<ValidationRun> findByRequestKey(String requestKey) {
            return runsById.values().stream()
                .filter(r -> r.requestKey.equals(requestKey))
                .findFirst();
          }

          @Override
          public List<ValidationRun> findByOriginalRunIdOrderByCreatedAtAsc(String originalRunId) {
            return runsById.values().stream()
                .filter(r -> originalRunId.equals(r.originalRunId))
                .sorted(Comparator.comparingLong(r -> r.createdAt))
                .toList();
          }

          @Override
          public ValidationRun saveAndFlush(ValidationRun run) {
            if (findByRequestKey(run.requestKey).isPresent() && !runsById.containsKey(run.id)) {
              throw new DuplicateRequestKeyException(run.requestKey, null);
            }
            runsById.put(run.id, run);
            return run;
          }

          @Override
          public ValidationRun save(ValidationRun run) {
            runsById.put(run.id, run);
            return run;
          }
        };

    ValidationResultRepository resultRepo =
        new ValidationResultRepository() {
          @Override
          public boolean existsByRunAndValidator(String runId, String validatorId) {
            return resultsById.containsKey(ValidationResult.idFor(runId, validatorId));
          }

          @Override
          public ValidationResult save(ValidationResult result) {
            resultsById.put(result.id, result);
            return result;
          }

          @Override
          public ValidationResult saveAndFlush(ValidationResult result) {
            resultsById.put(result.id, result);
            return result;
          }

          @Override
          public List<ValidationResult> findByRunId(String runId) {
            return resultsById.values().stream().filter(r -> r.runId.equals(runId)).toList();
          }
        };

    AttemptAuditPort attemptAudit =
        new AttemptAuditPort() {
          @Override
          public ExecutionAttempt start(String runId, String validatorId) {
            return ExecutionAttempt.started(runId, validatorId, 1);
          }

          @Override
          public void finish(String id, String outcome) {}

          @Override
          public void finish(String id, String outcome, String error) {}

          @Override
          public List<ExecutionAttempt> findByRunId(String runId) {
            return List.of();
          }
        };

    ValidationWorkflowPort workflowPort =
        new ValidationWorkflowPort() {
          @Override
          public String startValidationProcess(String runId, List<String> selectedValidators) {
            String procId = "proc-" + runId;
            startedProcessSelections.put(runId, new ArrayList<>(selectedValidators));
            processStates.put(procId, "RUNNING");
            return procId;
          }

          @Override
          public boolean isProcessActive(String processId) {
            return "RUNNING".equals(processStates.get(processId));
          }

          @Override
          public WorkflowStatus inspectProcess(String processId) {
            String state = processStates.getOrDefault(processId, "UNKNOWN");
            return new WorkflowStatus(state, List.of(), List.of());
          }

          @Override
          public String getProcessState(String processId) {
            return processStates.getOrDefault(processId, "UNKNOWN");
          }

          @Override
          public void retryDeadLetterJobs(String processId) {
            processStates.put(processId, "RUNNING");
          }
        };

    var starter = new RunStarter(runRepo, resultRepo, workflowPort, planResolver, catalog);
    var validatorExec =
        new ValidatorExecutionService(catalog, resultRepo, attemptAudit, new ValidationProbe());
    service =
        new ValidationRunService(
            starter, runRepo, resultRepo, attemptAudit, workflowPort, validatorExec);
  }

  @Test
  void startsNewRunAndSelectsAllValidatorsWithoutFlowableEngine() {
    var run = service.start("req-1", 0, null, null, Set.of());

    assertThat(run.processId).isEqualTo("proc-" + run.id);
    assertThat(startedProcessSelections.get(run.id))
        .containsExactly("P1", "P2", "P3", "P4");
  }

  @Test
  void selectiveReevaluationIncludesPriorInvalidValidatorsAndCopiesValidUpstreamResults() {
    var original = service.start("orig-key", 0, "P2", null, Set.of());
    processStates.put(original.processId, "COMPLETED");
    resultsById.put(
        original.id + ":P1", ValidationResult.evaluated(original.id, "P1", null, 1));
    resultsById.put(
        original.id + ":P2",
        ValidationResult.evaluated(original.id, "P2", new ValidationFinding("P2 invalid"), 1));
    resultsById.put(
        original.id + ":P3", ValidationResult.evaluated(original.id, "P3", null, 1));
    resultsById.put(
        original.id + ":P4", ValidationResult.evaluated(original.id, "P4", null, 1));

    // Requesting only P3 when P2 was INVALID must also include P2 and its dependent P4
    var reeval = service.start("reeval-key", 0, null, original.id, Set.of("P3"));

    assertThat(startedProcessSelections.get(reeval.id))
        .containsExactlyInAnyOrder("P2", "P3", "P4");
    assertThat(resultsById.values().stream().filter(r -> r.runId.equals(reeval.id)))
        .singleElement()
        .satisfies(
            reused -> {
              assertThat(reused.validatorId).isEqualTo("P1");
              assertThat(reused.reusedFromRun).isEqualTo(original.id);
              assertThat(reused.attempt).isZero();
            });
  }

  @Test
  void unknownRunIdThrowsNoSuchElementException() {
    assertThatThrownBy(() -> service.inspect("missing"))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.retryDeadLetters("missing"))
        .isInstanceOf(NoSuchElementException.class);
  }
}
