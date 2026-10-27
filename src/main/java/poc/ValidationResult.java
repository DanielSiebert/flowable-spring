package poc;

import jakarta.persistence.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"runId", "validatorId"}))
public class ValidationResult {
  @Id public String id;
  public String runId;
  public String validatorId;
  public String outcome;
  public String finding;
  public String reusedFromRun;
  public int attempt;
  public long completedAt;

  protected ValidationResult() {}

  ValidationResult(String run, String validator, String finding, int attempt, String reused) {
    id = run + ":" + validator;
    runId = run;
    validatorId = validator;
    this.finding = finding;
    outcome = finding == null ? "VALID" : "INVALID";
    this.attempt = attempt;
    reusedFromRun = reused;
    completedAt = System.currentTimeMillis();
  }
}
