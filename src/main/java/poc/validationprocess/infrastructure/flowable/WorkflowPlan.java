package poc.validationprocess.infrastructure.flowable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import poc.executionplan.domain.ExecutionPlanResolver;

@Component("workflowPlan")
public class WorkflowPlan {
  private final ExecutionPlanResolver resolver;

  public WorkflowPlan(ExecutionPlanResolver resolver) {
    this.resolver = resolver;
  }

  @SuppressWarnings("unchecked")
  public void resolve(DelegateExecution execution) {
    List<String> selected = (List<String>) execution.getVariable("selected");
    execution.setVariable(
        "stages", new ArrayList<>(resolver.resolve(new LinkedHashSet<>(selected))));
    execution.setVariable("stageIndex", 0);
    current(execution);
  }

  @SuppressWarnings("unchecked")
  public void current(DelegateExecution execution) {
    var stages = (List<List<String>>) execution.getVariable("stages");
    int i = (Integer) execution.getVariable("stageIndex");
    execution.setVariable("more", i < stages.size());
    execution.setVariable("validators", i < stages.size() ? stages.get(i) : new ArrayList<String>());
  }

  public void advance(DelegateExecution execution) {
    execution.setVariable("stageIndex", (Integer) execution.getVariable("stageIndex") + 1);
    current(execution);
  }

  public void joined(DelegateExecution execution) {}
}
