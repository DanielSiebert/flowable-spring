package poc.validation.domain;

public record ValidationContext(
    String runId, int p3Failures, String businessFailure, int attempt) {}
