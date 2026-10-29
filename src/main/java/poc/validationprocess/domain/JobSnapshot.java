package poc.validationprocess.domain;

public record JobSnapshot(String id, String kind, int retries, String exception) {}
