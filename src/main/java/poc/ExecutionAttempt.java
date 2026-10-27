package poc;

import jakarta.persistence.*;

@Entity
public class ExecutionAttempt {
  @Id public String id;
  public String runId;
  public String validatorId;
  public int number;
  public long startedAt;
  public Long finishedAt;
  public String outcome;
  public String error;

  protected ExecutionAttempt() {}

  ExecutionAttempt(String run, String validator, int n) {
    id = run + ":" + validator + ":" + n;
    runId = run;
    validatorId = validator;
    number = n;
    startedAt = System.currentTimeMillis();
    outcome = "STARTED";
  }
}
