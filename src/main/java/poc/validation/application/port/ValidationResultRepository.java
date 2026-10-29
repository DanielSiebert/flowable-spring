package poc.validation.application.port;

import java.util.List;
import poc.validation.domain.ValidationResult;

public interface ValidationResultRepository {
  boolean existsByRunAndValidator(String runId, String validatorId);

  ValidationResult save(ValidationResult result);

  ValidationResult saveAndFlush(ValidationResult result);

  List<ValidationResult> findByRunId(String runId);
}
