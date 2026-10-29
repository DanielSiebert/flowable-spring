package poc.validation.infrastructure.persistence;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import poc.validation.application.port.AttemptAuditPort;
import poc.validation.domain.ExecutionAttempt;

@Service
public class AttemptAuditAdapter implements AttemptAuditPort {
  private final SpringDataAttemptRepository attempts;

  public AttemptAuditAdapter(SpringDataAttemptRepository attempts) {
    this.attempts = attempts;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExecutionAttempt start(String run, String validator) {
    int number = (int) attempts.countByRunIdAndValidatorId(run, validator) + 1;
    ExecutionAttempt started = ExecutionAttempt.started(run, validator, number);
    return attempts.saveAndFlush(ExecutionAttemptJpaEntity.fromDomain(started)).toDomain();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String id, String outcome) {
    finish(id, outcome, null);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String id, String outcome, String error) {
    var a = attempts.findById(id).orElseThrow();
    a.finishedAt = System.currentTimeMillis();
    a.outcome = outcome;
    a.error = error;
  }

  @Override
  public List<ExecutionAttempt> findByRunId(String runId) {
    return attempts.findByRunId(runId).stream().map(ExecutionAttemptJpaEntity::toDomain).toList();
  }
}
