package poc;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AttemptAudit {
  private final AttemptRepository attempts;

  public AttemptAudit(AttemptRepository a) {
    attempts = a;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExecutionAttempt start(String run, String validator) {
    return attempts.saveAndFlush(
        new ExecutionAttempt(
            run, validator, (int) attempts.countByRunIdAndValidatorId(run, validator) + 1));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String id, String outcome) {
    finish(id, outcome, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String id, String outcome, String error) {
    var a = attempts.findById(id).orElseThrow();
    a.finishedAt = System.currentTimeMillis();
    a.outcome = outcome;
    a.error = error;
  }
}
