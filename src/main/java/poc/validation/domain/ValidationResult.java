package poc.validation.domain;

import java.util.Objects;

public final class ValidationResult {
  public final String id;
  public final String runId;
  public final String validatorId;
  public final String outcome;
  public final String finding;
  public final String reusedFromRun;
  public final int attempt;
  public final long completedAt;

  public ValidationResult(
      String id,
      String runId,
      String validatorId,
      String outcome,
      String finding,
      String reusedFromRun,
      int attempt,
      long completedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.runId = Objects.requireNonNull(runId, "runId must not be null");
    this.validatorId = Objects.requireNonNull(validatorId, "validatorId must not be null");
    this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    this.finding = finding;
    this.reusedFromRun = reusedFromRun;
    this.attempt = attempt;
    this.completedAt = completedAt;
  }

  public static String idFor(String runId, String validatorId) {
    return runId + ":" + validatorId;
  }

  public static ValidationResult evaluated(
      String runId, String validatorId, ValidationFinding finding, int attempt) {
    String message = finding == null ? null : finding.message();
    String outcome = message == null ? "VALID" : "INVALID";
    return new ValidationResult(
        idFor(runId, validatorId),
        runId,
        validatorId,
        outcome,
        message,
        null,
        attempt,
        System.currentTimeMillis());
  }

  public static ValidationResult reused(
      String runId, String validatorId, String finding, String reusedFromRun) {
    String outcome = finding == null ? "VALID" : "INVALID";
    return new ValidationResult(
        idFor(runId, validatorId),
        runId,
        validatorId,
        outcome,
        finding,
        reusedFromRun,
        0,
        System.currentTimeMillis());
  }

  public boolean hasValidOutcome() {
    return "VALID".equals(outcome);
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

  public String outcome() {
    return outcome;
  }

  public String finding() {
    return finding;
  }

  public String reusedFromRun() {
    return reusedFromRun;
  }

  public int attempt() {
    return attempt;
  }

  public long completedAt() {
    return completedAt;
  }
}
