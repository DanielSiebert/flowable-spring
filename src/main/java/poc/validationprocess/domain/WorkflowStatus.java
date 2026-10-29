package poc.validationprocess.domain;

import java.util.List;

public record WorkflowStatus(
    String state, List<String> activities, List<JobSnapshot> jobs) {}
