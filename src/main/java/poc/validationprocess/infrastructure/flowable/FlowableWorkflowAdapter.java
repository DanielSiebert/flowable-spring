package poc.validationprocess.infrastructure.flowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;
import poc.validationprocess.application.port.ValidationWorkflowPort;
import poc.validationprocess.domain.JobSnapshot;
import poc.validationprocess.domain.WorkflowStatus;

@Component
public class FlowableWorkflowAdapter implements ValidationWorkflowPort {
  private final RuntimeService runtime;
  private final HistoryService history;
  private final ManagementService management;

  public FlowableWorkflowAdapter(
      RuntimeService runtime, HistoryService history, ManagementService management) {
    this.runtime = runtime;
    this.history = history;
    this.management = management;
  }

  @Override
  public String startValidationProcess(String runId, List<String> selectedValidators) {
    return runtime
        .startProcessInstanceByKey(
            "validation",
            runId,
            Map.of("runId", runId, "selected", new ArrayList<>(selectedValidators)))
        .getId();
  }

  @Override
  public boolean isProcessActive(String processId) {
    return processId != null
        && runtime.createProcessInstanceQuery().processInstanceId(processId).count() > 0;
  }

  @Override
  public String getProcessState(String processId) {
    return stateOf(processId, List.of());
  }

  @Override
  public WorkflowStatus inspectProcess(String processId) {
    var jobs = new ArrayList<JobSnapshot>();
    management
        .createJobQuery()
        .processInstanceId(processId)
        .list()
        .forEach(
            j ->
                jobs.add(
                    new JobSnapshot(j.getId(), "ASYNC", j.getRetries(), j.getExceptionMessage())));
    management
        .createTimerJobQuery()
        .processInstanceId(processId)
        .list()
        .forEach(
            j ->
                jobs.add(
                    new JobSnapshot(j.getId(), "TIMER", j.getRetries(), j.getExceptionMessage())));
    management
        .createDeadLetterJobQuery()
        .processInstanceId(processId)
        .list()
        .forEach(
            j ->
                jobs.add(
                    new JobSnapshot(
                        j.getId(), "DEAD_LETTER", j.getRetries(), j.getExceptionMessage())));

    boolean active = isProcessActive(processId);
    List<String> activities = List.of();
    if (active) {
      try {
        activities = runtime.getActiveActivityIds(processId);
      } catch (FlowableObjectNotFoundException ex) {
        active = false;
      }
    }
    String state = stateOf(processId, jobs);
    if ("COMPLETED".equals(state)) {
      activities = List.of();
    }
    return new WorkflowStatus(state, activities, jobs);
  }

  @Override
  public void retryDeadLetterJobs(String processId) {
    management
        .createDeadLetterJobQuery()
        .processInstanceId(processId)
        .list()
        .forEach(j -> management.moveDeadLetterJobToExecutableJob(j.getId(), 3));
  }

  private String stateOf(String processId, List<JobSnapshot> jobs) {
    boolean active = isProcessActive(processId);
    return jobs.stream().anyMatch(j -> j.kind().equals("DEAD_LETTER"))
        ? "INCIDENT"
        : active
            ? "RUNNING"
            : processId != null
                    && history
                            .createHistoricProcessInstanceQuery()
                            .processInstanceId(processId)
                            .finished()
                            .count()
                        > 0
                ? "COMPLETED"
                : "UNKNOWN";
  }
}
