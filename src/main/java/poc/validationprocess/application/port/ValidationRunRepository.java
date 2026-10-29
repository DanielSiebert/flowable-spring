package poc.validationprocess.application.port;

import java.util.List;
import java.util.Optional;
import poc.validationprocess.domain.ValidationRun;

public interface ValidationRunRepository {
  Optional<ValidationRun> findById(String id);

  Optional<ValidationRun> findByRequestKey(String requestKey);

  List<ValidationRun> findByOriginalRunIdOrderByCreatedAtAsc(String originalRunId);

  ValidationRun saveAndFlush(ValidationRun run);

  ValidationRun save(ValidationRun run);
}
