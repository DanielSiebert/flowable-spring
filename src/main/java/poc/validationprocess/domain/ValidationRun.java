package poc.validationprocess.domain;

import java.util.Objects;
import java.util.UUID;

public final class ValidationRun {
  public final String id;
  public final String requestKey;
  public final String originalRunId;
  public final String processId;
  public final int p3Failures;
  public final String businessFailure;
  public final long createdAt;

  public ValidationRun(
      String id,
      String requestKey,
      String originalRunId,
      String processId,
      int p3Failures,
      String businessFailure,
      long createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.requestKey = Objects.requireNonNull(requestKey, "requestKey must not be null");
    this.originalRunId = originalRunId;
    this.processId = processId;
    this.p3Failures = p3Failures;
    this.businessFailure = businessFailure;
    this.createdAt = createdAt;
  }

  public static ValidationRun createNew(
      String requestKey, String originalRunId, int p3Failures, String businessFailure) {
    return new ValidationRun(
        UUID.randomUUID().toString(),
        requestKey,
        originalRunId,
        null,
        p3Failures,
        businessFailure,
        System.currentTimeMillis());
  }

  public ValidationRun withProcessId(String newProcessId) {
    return new ValidationRun(
        id, requestKey, originalRunId, newProcessId, p3Failures, businessFailure, createdAt);
  }

  public String id() {
    return id;
  }

  public String requestKey() {
    return requestKey;
  }

  public String originalRunId() {
    return originalRunId;
  }

  public String processId() {
    return processId;
  }

  public int p3Failures() {
    return p3Failures;
  }

  public String businessFailure() {
    return businessFailure;
  }

  public long createdAt() {
    return createdAt;
  }
}
