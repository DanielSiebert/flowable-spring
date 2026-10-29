package poc.validationprocess.infrastructure.flowable;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import poc.validationprocess.application.service.ValidationRunService;

@Component("validationExecution")
public class ValidationExecutionDelegate implements JavaDelegate {
  private final ValidationRunService validationRunService;

  public ValidationExecutionDelegate(ValidationRunService validationRunService) {
    this.validationRunService = validationRunService;
  }

  @Override
  public void execute(DelegateExecution execution) {
    String runId = (String) execution.getVariable("runId");
    String validatorId = (String) execution.getVariable("validator");
    validationRunService.executeValidator(runId, validatorId);
  }
}
