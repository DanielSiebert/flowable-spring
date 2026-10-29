package poc.validation.application.port;

import java.util.List;
import poc.validation.domain.ExecutionAttempt;

public interface AttemptAuditPort {
  ExecutionAttempt start(String runId, String validatorId);

  void finish(String id, String outcome);

  void finish(String id, String outcome, String error);

  List<ExecutionAttempt> findByRunId(String runId);
}
