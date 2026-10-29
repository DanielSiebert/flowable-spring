package poc.validation.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import poc.validation.domain.ValidationResult;

@Entity(name = "ValidationResult")
@Table(
    name = "validation_result",
    uniqueConstraints = @UniqueConstraint(columnNames = {"runId", "validatorId"}))
public class ValidationResultJpaEntity {
  @Id public String id;
  public String runId;
  public String validatorId;
  public String outcome;
  public String finding;
  public String reusedFromRun;
  public int attempt;
  public long completedAt;

  protected ValidationResultJpaEntity() {}

  public ValidationResultJpaEntity(
      String id,
      String runId,
      String validatorId,
      String outcome,
      String finding,
      String reusedFromRun,
      int attempt,
      long completedAt) {
    this.id = id;
    this.runId = runId;
    this.validatorId = validatorId;
    this.outcome = outcome;
    this.finding = finding;
    this.reusedFromRun = reusedFromRun;
    this.attempt = attempt;
    this.completedAt = completedAt;
  }

  public static ValidationResultJpaEntity fromDomain(ValidationResult domain) {
    return new ValidationResultJpaEntity(
        domain.id,
        domain.runId,
        domain.validatorId,
        domain.outcome,
        domain.finding,
        domain.reusedFromRun,
        domain.attempt,
        domain.completedAt);
  }

  public ValidationResult toDomain() {
    return new ValidationResult(
        id, runId, validatorId, outcome, finding, reusedFromRun, attempt, completedAt);
  }
}
