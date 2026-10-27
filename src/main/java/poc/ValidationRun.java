package poc;

import jakarta.persistence.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "requestKey"))
public class ValidationRun {
  @Id public String id;

  @Column(nullable = false)
  public String requestKey;

  public String originalRunId;
  public String processId;
  public int p3Failures;
  public String businessFailure;
  public long createdAt;

  protected ValidationRun() {}

  ValidationRun(String id, String key, String original, int failures, String business) {
    this.id = id;
    requestKey = key;
    originalRunId = original;
    p3Failures = failures;
    businessFailure = business;
    createdAt = System.currentTimeMillis();
  }
}
