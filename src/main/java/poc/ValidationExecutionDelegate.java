package poc;

import org.flowable.engine.delegate.*;
import org.springframework.stereotype.Component;

@Component("validationExecution")
public class ValidationExecutionDelegate implements JavaDelegate {
  private final RunRepository runs;
  private final ValidatorResultStore results;
  private final ValidatorRegistry registry;
  private final AttemptAudit audit;
  private final ValidationProbe probe;

  public ValidationExecutionDelegate(
      RunRepository r,
      ValidatorResultStore s,
      ValidatorRegistry v,
      AttemptAudit a,
      ValidationProbe p) {
    runs = r;
    results = s;
    registry = v;
    audit = a;
    probe = p;
  }

  public void execute(DelegateExecution e) {
    String run = (String) e.getVariable("runId"), id = (String) e.getVariable("validator");
    if (results.existsById(run + ":" + id)) return;
    var attempt = audit.start(run, id);
    try {
      probe.before(run, id);
      var finding = registry.get(id).validate(runs.findById(run).orElseThrow(), attempt.number);
      results.saveAndFlush(
          new ValidationResult(
              run, id, finding == null ? null : finding.message(), attempt.number, null));
      probe.afterResult(run, id, attempt.number);
      audit.finish(attempt.id, "RETURNED");
    } catch (RuntimeException ex) {
      audit.finish(attempt.id, "TECHNICAL_FAILURE", ex.getMessage());
      throw ex;
    }
  }
}
