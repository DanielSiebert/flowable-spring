package poc.validation.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import poc.validation.domain.ExecutionAttempt;

@Entity(name = "ExecutionAttempt")
@Table(name = "execution_attempt")
public class ExecutionAttemptJpaEntity {
  @Id public String id;
  public String runId;
  public String validatorId;
  public int number;
  public long startedAt;
  public Long finishedAt;
  public String outcome;
  public String error;

  protected ExecutionAttemptJpaEntity() {}

  public ExecutionAttemptJpaEntity(
      String id,
      String runId,
      String validatorId,
      int number,
      long startedAt,
      Long finishedAt,
      String outcome,
      String error) {
    this.id = id;
    this.runId = runId;
    this.validatorId = validatorId;
    this.number = number;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.outcome = outcome;
    this.error = error;
  }

  public static ExecutionAttemptJpaEntity fromDomain(ExecutionAttempt domain) {
    return new ExecutionAttemptJpaEntity(
        domain.id,
        domain.runId,
        domain.validatorId,
        domain.number,
        domain.startedAt,
        domain.finishedAt,
        domain.outcome,
        domain.error);
  }

  public ExecutionAttempt toDomain() {
    return new ExecutionAttempt(
        id, runId, validatorId, number, startedAt, finishedAt, outcome, error);
  }
}
