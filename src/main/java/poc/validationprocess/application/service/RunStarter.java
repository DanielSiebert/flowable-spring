package poc.validationprocess.application.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import poc.executionplan.domain.ExecutionPlanResolver;
import poc.validation.application.port.ValidationResultRepository;
import poc.validation.application.port.ValidatorCatalog;
import poc.validation.domain.ValidationResult;
import poc.validationprocess.application.port.ValidationRunRepository;
import poc.validationprocess.application.port.ValidationWorkflowPort;
import poc.validationprocess.domain.RunOutcomePolicy;
import poc.validationprocess.domain.ValidationRun;

@Service
public class RunStarter {
  private final ValidationRunRepository runs;
  private final ValidationResultRepository results;
  private final ValidationWorkflowPort workflow;
  private final ExecutionPlanResolver plans;
  private final ValidatorCatalog catalog;

  public RunStarter(
      ValidationRunRepository runs,
      ValidationResultRepository results,
      ValidationWorkflowPort workflow,
      ExecutionPlanResolver plans,
      ValidatorCatalog catalog) {
    this.runs = runs;
    this.results = results;
    this.workflow = workflow;
    this.plans = plans;
    this.catalog = catalog;
  }

  @Transactional
  public ValidationRun start(
      String key, int failures, String business, String original, Set<String> requested) {
    RunOutcomePolicy.validateStartParameters(key, failures, business, catalog.ids());
    var existing = runs.findByRequestKey(key);
    if (existing.isPresent()) {
      return existing.get();
    }
    var selected =
        original == null ? new LinkedHashSet<>(catalog.ids()) : plans.affected(requested);
    List<ValidationResult> priorResults = List.of();
    if (original != null) {
      var parent =
          runs.findById(original)
              .orElseThrow(() -> new NoSuchElementException("Original run missing"));
      if (workflow.isProcessActive(parent.processId)) {
        throw new IllegalArgumentException("Original run must be complete");
      }
      priorResults = results.findByRunId(original);
      if (priorResults.size() != catalog.ids().size()) {
        throw new IllegalArgumentException("Original results incomplete");
      }
      // Invalid results cannot be reused as valid inputs. Re-evaluate their dependents too.
      for (var old : priorResults) {
        if (!old.hasValidOutcome()) {
          selected.addAll(plans.affected(Set.of(old.validatorId)));
        }
      }
    }
    var run = ValidationRun.createNew(key, original, failures, business);
    run = runs.saveAndFlush(run);
    if (original != null) {
      for (var old : priorResults) {
        if (!selected.contains(old.validatorId)) {
          results.save(ValidationResult.reused(run.id, old.validatorId, old.finding, original));
        }
      }
    }
    String processId = workflow.startValidationProcess(run.id, new ArrayList<>(selected));
    return runs.save(run.withProcessId(processId));
  }
}
