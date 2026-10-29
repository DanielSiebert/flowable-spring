package poc.validationprocess.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import poc.validationprocess.domain.ValidationRun;

@Entity(name = "ValidationRun")
@Table(
    name = "validation_run",
    uniqueConstraints = @UniqueConstraint(columnNames = "requestKey"))
public class ValidationRunJpaEntity {
  @Id public String id;

  @Column(nullable = false)
  public String requestKey;

  public String originalRunId;
  public String processId;
  public int p3Failures;
  public String businessFailure;
  public long createdAt;

  protected ValidationRunJpaEntity() {}

  public ValidationRunJpaEntity(
      String id,
      String requestKey,
      String originalRunId,
      String processId,
      int p3Failures,
      String businessFailure,
      long createdAt) {
    this.id = id;
    this.requestKey = requestKey;
    this.originalRunId = originalRunId;
    this.processId = processId;
    this.p3Failures = p3Failures;
    this.businessFailure = businessFailure;
    this.createdAt = createdAt;
  }

  public static ValidationRunJpaEntity fromDomain(ValidationRun domain) {
    return new ValidationRunJpaEntity(
        domain.id,
        domain.requestKey,
        domain.originalRunId,
        domain.processId,
        domain.p3Failures,
        domain.businessFailure,
        domain.createdAt);
  }

  public ValidationRun toDomain() {
    return new ValidationRun(
        id, requestKey, originalRunId, processId, p3Failures, businessFailure, createdAt);
  }
}
