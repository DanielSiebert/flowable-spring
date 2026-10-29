package poc.validation.application.service;

import org.springframework.stereotype.Service;
import poc.validation.application.port.AttemptAuditPort;
import poc.validation.application.port.ValidationProbe;
import poc.validation.application.port.ValidationResultRepository;
import poc.validation.application.port.ValidatorCatalog;
import poc.validation.domain.ValidationContext;
import poc.validation.domain.ValidationResult;

@Service
public class ValidatorExecutionService {
  private final ValidatorCatalog catalog;
  private final ValidationResultRepository results;
  private final AttemptAuditPort audit;
  private final ValidationProbe probe;

  public ValidatorExecutionService(
      ValidatorCatalog catalog,
      ValidationResultRepository results,
      AttemptAuditPort audit,
      ValidationProbe probe) {
    this.catalog = catalog;
    this.results = results;
    this.audit = audit;
    this.probe = probe;
  }

  public void execute(String runId, String validatorId, int p3Failures, String businessFailure) {
    if (results.existsByRunAndValidator(runId, validatorId)) {
      return;
    }
    var attempt = audit.start(runId, validatorId);
    try {
      probe.before(runId, validatorId);
      var context = new ValidationContext(runId, p3Failures, businessFailure, attempt.number);
      var finding = catalog.get(validatorId).validate(context);
      results.saveAndFlush(
          ValidationResult.evaluated(runId, validatorId, finding, attempt.number));
      probe.afterResult(runId, validatorId, attempt.number);
      audit.finish(attempt.id, "RETURNED");
    } catch (RuntimeException ex) {
      audit.finish(attempt.id, "TECHNICAL_FAILURE", ex.getMessage());
      throw ex;
    }
  }
}
