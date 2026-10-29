package poc.validation.domain;

import java.util.Objects;

public final class ExecutionAttempt {
  public final String id;
  public final String runId;
  public final String validatorId;
  public final int number;
  public final long startedAt;
  public final Long finishedAt;
  public final String outcome;
  public final String error;

  public ExecutionAttempt(
      String id,
      String runId,
      String validatorId,
      int number,
      long startedAt,
      Long finishedAt,
      String outcome,
      String error) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.runId = Objects.requireNonNull(runId, "runId must not be null");
    this.validatorId = Objects.requireNonNull(validatorId, "validatorId must not be null");
    this.number = number;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    this.error = error;
  }

  public static ExecutionAttempt started(String runId, String validatorId, int number) {
    return new ExecutionAttempt(
        runId + ":" + validatorId + ":" + number,
        runId,
        validatorId,
        number,
        System.currentTimeMillis(),
        null,
        "STARTED",
        null);
  }

  public String id() {
    return id;
  }

  public String runId() {
    return runId;
  }

  public String validatorId() {
    return validatorId;
  }

  public int number() {
    return number;
  }

  public long startedAt() {
    return startedAt;
  }

  public Long finishedAt() {
    return finishedAt;
  }

  public String outcome() {
    return outcome;
  }

  public String error() {
    return error;
  }
}
