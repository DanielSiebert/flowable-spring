package poc.validationprocess.application.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import org.springframework.stereotype.Service;
import poc.validation.application.port.AttemptAuditPort;
import poc.validation.application.port.ValidationResultRepository;
import poc.validation.application.service.ValidatorExecutionService;
import poc.validation.domain.ExecutionAttempt;
import poc.validation.domain.ValidationResult;
import poc.validationprocess.application.port.ValidationRunRepository;
import poc.validationprocess.application.port.ValidationWorkflowPort;
import poc.validationprocess.domain.DuplicateRequestKeyException;
import poc.validationprocess.domain.RunOutcomePolicy;
import poc.validationprocess.domain.ValidationRun;

@Service
public class ValidationRunService {
  private final RunStarter starter;
  private final ValidationRunRepository runs;
  private final ValidationResultRepository results;
  private final AttemptAuditPort attempts;
  private final ValidationWorkflowPort workflow;
  private final ValidatorExecutionService validatorExecution;

  public ValidationRunService(
      RunStarter starter,
      ValidationRunRepository runs,
      ValidationResultRepository results,
      AttemptAuditPort attempts,
      ValidationWorkflowPort workflow,
      ValidatorExecutionService validatorExecution) {
    this.starter = starter;
    this.runs = runs;
    this.results = results;
    this.attempts = attempts;
    this.workflow = workflow;
    this.validatorExecution = validatorExecution;
  }

  public ValidationRun start(
      String key, int failures, String business, String original, Set<String> requested) {
    try {
      return starter.start(key, failures, business, original, requested);
    } catch (DuplicateRequestKeyException ex) {
      return runs.findByRequestKey(key).orElseThrow(() -> ex);
    }
  }

  public void executeValidator(String runId, String validatorId) {
    var run =
        runs.findById(runId).orElseThrow(() -> new NoSuchElementException("Run not found"));
    validatorExecution.execute(run.id, validatorId, run.p3Failures, run.businessFailure);
  }

  public void retryDeadLetters(String id) {
    var run = runs.findById(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
    workflow.retryDeadLetterJobs(run.processId);
  }

  public record JobView(String id, String kind, int retries, String exception) {}

  public record RunView(
      String id,
      String requestKey,
      String originalRunId,
      String processId,
      String state,
      String outcome,
      String effectiveRunId,
      String effectiveOutcome,
      List<String> correlatedRunIds,
      List<String> activities,
      List<ValidationResult> results,
      List<ExecutionAttempt> attempts,
      List<JobView> jobs) {}

  private List<ValidationRun> lineage(ValidationRun current) {
    ValidationRun root = current;
    while (root.originalRunId != null) {
      var parent = runs.findById(root.originalRunId);
      if (parent.isEmpty()) {
        break;
      }
      root = parent.get();
    }
    List<ValidationRun> chain = new ArrayList<>();
    Queue<ValidationRun> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty()) {
      var next = queue.poll();
      chain.add(next);
      queue.addAll(runs.findByOriginalRunIdOrderByCreatedAtAsc(next.id));
    }
    chain.sort(
        Comparator.comparingLong((ValidationRun x) -> x.createdAt).thenComparing(x -> x.id));
    return chain;
  }

  public RunView inspect(String id) {
    var r = runs.findById(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
    var wfStatus = workflow.inspectProcess(r.processId);
    String state = wfStatus.state();
    var jobs =
        wfStatus.jobs().stream()
            .map(j -> new JobView(j.id(), j.kind(), j.retries(), j.exception()))
            .toList();
    var runResults = results.findByRunId(id);
    String outcome = RunOutcomePolicy.computeOutcome(state, runResults);

    var chain = lineage(r);
    ValidationRun effective = r;
    String effectiveState = state;
    for (var candidate : chain) {
      String cState =
          candidate.id.equals(r.id) ? state : workflow.getProcessState(candidate.processId);
      if ("COMPLETED".equals(cState)) {
        effective = candidate;
        effectiveState = cState;
      }
    }
    String effectiveOutcome =
        effective.id.equals(r.id)
            ? outcome
            : RunOutcomePolicy.computeOutcome(effectiveState, results.findByRunId(effective.id));

    return new RunView(
        r.id,
        r.requestKey,
        r.originalRunId,
        r.processId,
        state,
        outcome,
        effective.id,
        effectiveOutcome,
        chain.stream().map(x -> x.id).toList(),
        wfStatus.activities(),
        runResults,
        attempts.findByRunId(id),
        jobs);
  }
}
