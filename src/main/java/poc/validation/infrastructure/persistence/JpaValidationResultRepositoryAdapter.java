package poc.validation.infrastructure.persistence;

import java.util.List;
import org.springframework.stereotype.Repository;
import poc.validation.application.port.ValidationResultRepository;
import poc.validation.domain.ValidationResult;

@Repository
public class JpaValidationResultRepositoryAdapter implements ValidationResultRepository {
  private final SpringDataValidationResultRepository repository;

  public JpaValidationResultRepositoryAdapter(SpringDataValidationResultRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean existsByRunAndValidator(String runId, String validatorId) {
    return repository.existsById(ValidationResult.idFor(runId, validatorId));
  }

  @Override
  public ValidationResult save(ValidationResult result) {
    return repository.save(ValidationResultJpaEntity.fromDomain(result)).toDomain();
  }

  @Override
  public ValidationResult saveAndFlush(ValidationResult result) {
    return repository.saveAndFlush(ValidationResultJpaEntity.fromDomain(result)).toDomain();
  }

  @Override
  public List<ValidationResult> findByRunId(String runId) {
    return repository.findByRunId(runId).stream()
        .map(ValidationResultJpaEntity::toDomain)
        .toList();
  }
}
