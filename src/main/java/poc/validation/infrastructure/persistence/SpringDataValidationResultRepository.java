package poc.validation.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataValidationResultRepository
    extends JpaRepository<ValidationResultJpaEntity, String> {
  List<ValidationResultJpaEntity> findByRunId(String runId);
}
