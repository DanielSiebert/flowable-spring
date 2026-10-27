package poc;

import java.util.*;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ValidationRunService {
  private final RunStarter starter;
  private final RunRepository runs;
  private final ValidatorResultStore results;
  private final AttemptRepository attempts;
  private final RuntimeService runtime;
  private final HistoryService history;
  private final ManagementService management;

  public ValidationRunService(
      RunStarter s,
      RunRepository r,
      ValidatorResultStore rs,
      AttemptRepository a,
      RuntimeService rt,
      HistoryService h,
      ManagementService m) {
    starter = s;
    runs = r;
    results = rs;
    attempts = a;
    runtime = rt;
    history = h;
    management = m;
  }

  public ValidationRun start(
      String key, int failures, String business, String original, Set<String> requested) {
    try {
      return starter.start(key, failures, business, original, requested);
    } catch (DataIntegrityViolationException ex) {
      return runs.findByRequestKey(key).orElseThrow(() -> ex);
    }
  }

  public void retryDeadLetters(String id) {
    var run = runs.findById(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
    management
        .createDeadLetterJobQuery()
        .processInstanceId(run.processId)
        .list()
        .forEach(j -> management.moveDeadLetterJobToExecutableJob(j.getId(), 3));
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

  private String stateOf(String processId, List<JobView> jobs) {
    boolean active =
        processId != null
            && runtime.createProcessInstanceQuery().processInstanceId(processId).count() > 0;
    return jobs.stream().anyMatch(j -> j.kind().equals("DEAD_LETTER"))
        ? "INCIDENT"
        : active
            ? "RUNNING"
            : processId != null
                    && history
                            .createHistoricProcessInstanceQuery()
                            .processInstanceId(processId)
                            .finished()
                            .count()
                        > 0
                ? "COMPLETED"
                : "UNKNOWN";
  }

  private String computeOutcome(String state, List<ValidationResult> runResults) {
    if (!"COMPLETED".equals(state)) return "PENDING";
    return runResults.stream().anyMatch(res -> "INVALID".equals(res.outcome))
        ? "INVALID"
        : "VALID";
  }

  private List<ValidationRun> lineage(ValidationRun current) {
    ValidationRun root = current;
    while (root.originalRunId != null) {
      var parent = runs.findById(root.originalRunId);
      if (parent.isEmpty()) break;
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
    chain.sort(Comparator.comparingLong((ValidationRun x) -> x.createdAt).thenComparing(x -> x.id));
    return chain;
  }

  public RunView inspect(String id) {
    var r = runs.findById(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
    var jobs = new ArrayList<JobView>();
    management
        .createJobQuery()
        .processInstanceId(r.processId)
        .list()
        .forEach(
            j ->
                jobs.add(new JobView(j.getId(), "ASYNC", j.getRetries(), j.getExceptionMessage())));
    management
        .createTimerJobQuery()
        .processInstanceId(r.processId)
        .list()
        .forEach(
            j ->
                jobs.add(new JobView(j.getId(), "TIMER", j.getRetries(), j.getExceptionMessage())));
    management
        .createDeadLetterJobQuery()
        .processInstanceId(r.processId)
        .list()
        .forEach(
            j ->
                jobs.add(
                    new JobView(
                        j.getId(), "DEAD_LETTER", j.getRetries(), j.getExceptionMessage())));
    boolean active =
        runtime.createProcessInstanceQuery().processInstanceId(r.processId).count() > 0;
    List<String> activities = List.of();
    if (active) {
      try {
        activities = runtime.getActiveActivityIds(r.processId);
      } catch (FlowableObjectNotFoundException ex) {
        active = false;
      }
    }
    String state = stateOf(r.processId, jobs);
    if ("COMPLETED".equals(state)) activities = List.of();
    var runResults = results.findByRunId(id);
    String outcome = computeOutcome(state, runResults);

    var chain = lineage(r);
    ValidationRun effective = r;
    String effectiveState = state;
    for (var candidate : chain) {
      String cState =
          candidate.id.equals(r.id) ? state : stateOf(candidate.processId, List.of());
      if ("COMPLETED".equals(cState)) {
        effective = candidate;
        effectiveState = cState;
      }
    }
    String effectiveOutcome =
        effective.id.equals(r.id)
            ? outcome
            : computeOutcome(effectiveState, results.findByRunId(effective.id));

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
        activities,
        runResults,
        attempts.findByRunId(id),
        jobs);
  }
}
