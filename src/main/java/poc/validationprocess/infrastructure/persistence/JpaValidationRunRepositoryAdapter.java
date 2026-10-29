package poc.validationprocess.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import poc.validationprocess.application.port.ValidationRunRepository;
import poc.validationprocess.domain.DuplicateRequestKeyException;
import poc.validationprocess.domain.ValidationRun;

@Repository
public class JpaValidationRunRepositoryAdapter implements ValidationRunRepository {
  private final SpringDataValidationRunRepository repository;

  public JpaValidationRunRepositoryAdapter(SpringDataValidationRunRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<ValidationRun> findById(String id) {
    return repository.findById(id).map(ValidationRunJpaEntity::toDomain);
  }

  @Override
  public Optional<ValidationRun> findByRequestKey(String requestKey) {
    return repository.findByRequestKey(requestKey).map(ValidationRunJpaEntity::toDomain);
  }

  @Override
  public List<ValidationRun> findByOriginalRunIdOrderByCreatedAtAsc(String originalRunId) {
    return repository.findByOriginalRunIdOrderByCreatedAtAsc(originalRunId).stream()
        .map(ValidationRunJpaEntity::toDomain)
        .toList();
  }

  @Override
  public ValidationRun saveAndFlush(ValidationRun run) {
    try {
      return repository.saveAndFlush(ValidationRunJpaEntity.fromDomain(run)).toDomain();
    } catch (DataIntegrityViolationException ex) {
      throw new DuplicateRequestKeyException(run.requestKey, ex);
    }
  }

  @Override
  public ValidationRun save(ValidationRun run) {
    return repository.save(ValidationRunJpaEntity.fromDomain(run)).toDomain();
  }
}
