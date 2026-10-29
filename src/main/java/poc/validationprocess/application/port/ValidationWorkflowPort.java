package poc.validationprocess.application.port;

import java.util.List;
import poc.validationprocess.domain.WorkflowStatus;

public interface ValidationWorkflowPort {
  String startValidationProcess(String runId, List<String> selectedValidators);

  boolean isProcessActive(String processId);

  WorkflowStatus inspectProcess(String processId);

  String getProcessState(String processId);

  void retryDeadLetterJobs(String processId);
}
