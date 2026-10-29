package poc.validationprocess.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataValidationRunRepository
    extends JpaRepository<ValidationRunJpaEntity, String> {
  Optional<ValidationRunJpaEntity> findByRequestKey(String requestKey);

  List<ValidationRunJpaEntity> findByOriginalRunIdOrderByCreatedAtAsc(String originalRunId);
}
