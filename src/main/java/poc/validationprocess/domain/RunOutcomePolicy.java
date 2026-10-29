package poc.validationprocess.domain;

import java.util.List;
import java.util.Set;
import poc.validation.domain.ValidationResult;

public final class RunOutcomePolicy {
  private RunOutcomePolicy() {}

  public static void validateStartParameters(
      String requestKey, int p3Failures, String businessFailure, Set<String> registeredValidators) {
    if (requestKey == null || requestKey.isBlank() || p3Failures < 0 || p3Failures > 100) {
      throw new IllegalArgumentException("requestKey required; p3Failures must be 0..100");
    }
    if (businessFailure != null && !registeredValidators.contains(businessFailure)) {
      throw new IllegalArgumentException("Unknown businessFailure validator");
    }
  }

  public static String computeOutcome(String state, List<ValidationResult> runResults) {
    if (!"COMPLETED".equals(state)) {
      return "PENDING";
    }
    return runResults.stream().anyMatch(res -> !res.isValid()) ? "INVALID" : "VALID";
  }
}
