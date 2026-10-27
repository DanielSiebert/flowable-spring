package poc;

import java.util.*;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunStarter {
  private final RunRepository runs;
  private final ValidatorResultStore results;
  private final RuntimeService runtime;
  private final ExecutionPlanResolver plans;
  private final ValidatorRegistry registry;

  public RunStarter(
      RunRepository r,
      ValidatorResultStore s,
      RuntimeService rt,
      ExecutionPlanResolver p,
      ValidatorRegistry v) {
    runs = r;
    results = s;
    runtime = rt;
    plans = p;
    registry = v;
  }

  @Transactional
  public ValidationRun start(
      String key, int failures, String business, String original, Set<String> requested) {
    if (key == null || key.isBlank() || failures < 0 || failures > 100)
      throw new IllegalArgumentException("requestKey required; p3Failures must be 0..100");
    if (business != null && !registry.ids().contains(business))
      throw new IllegalArgumentException("Unknown businessFailure validator");
    var existing = runs.findByRequestKey(key);
    if (existing.isPresent()) return existing.get();
    var selected =
        original == null ? new LinkedHashSet<>(registry.ids()) : plans.affected(requested);
    if (original != null) {
      var parent =
          runs.findById(original)
              .orElseThrow(() -> new NoSuchElementException("Original run missing"));
      if (runtime.createProcessInstanceQuery().processInstanceId(parent.processId).count() > 0)
        throw new IllegalArgumentException("Original run must be complete");
      if (results.findByRunId(original).size() != registry.ids().size())
        throw new IllegalArgumentException("Original results incomplete");
      // Invalid results cannot be reused as valid inputs. Re-evaluate their dependents too.
      for (var old : results.findByRunId(original))
        if (!old.outcome.equals("VALID")) selected.addAll(plans.affected(Set.of(old.validatorId)));
    }
    var run = new ValidationRun(UUID.randomUUID().toString(), key, original, failures, business);
    run = runs.saveAndFlush(run);
    if (original != null)
      for (var old : results.findByRunId(original))
        if (!selected.contains(old.validatorId))
          results.save(new ValidationResult(run.id, old.validatorId, old.finding, 0, original));
    run.processId =
        runtime
            .startProcessInstanceByKey(
                "validation",
                run.id,
                Map.of("runId", run.id, "selected", new ArrayList<>(selected)))
            .getId();
    return run;
  }
}
