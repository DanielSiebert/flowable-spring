package poc;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface RunRepository extends JpaRepository<ValidationRun, String> {
  Optional<ValidationRun> findByRequestKey(String key);

  List<ValidationRun> findByOriginalRunIdOrderByCreatedAtAsc(String originalRunId);
}

interface ValidatorResultStore extends JpaRepository<ValidationResult, String> {
  List<ValidationResult> findByRunId(String id);
}

interface AttemptRepository extends JpaRepository<ExecutionAttempt, String> {
  List<ExecutionAttempt> findByRunId(String id);

  long countByRunIdAndValidatorId(String run, String validator);
}
