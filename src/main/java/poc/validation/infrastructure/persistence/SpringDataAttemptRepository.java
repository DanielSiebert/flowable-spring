package poc.validation.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAttemptRepository
    extends JpaRepository<ExecutionAttemptJpaEntity, String> {
  List<ExecutionAttemptJpaEntity> findByRunId(String runId);

  long countByRunIdAndValidatorId(String runId, String validatorId);
}
