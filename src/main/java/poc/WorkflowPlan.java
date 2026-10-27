package poc;

import java.util.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

@Component("workflowPlan")
public class WorkflowPlan {
  private final ExecutionPlanResolver resolver;

  public WorkflowPlan(ExecutionPlanResolver r) {
    resolver = r;
  }

  @SuppressWarnings("unchecked")
  public void resolve(DelegateExecution e) {
    e.setVariable(
        "stages",
        new ArrayList<>(
            resolver.resolve(new LinkedHashSet<>((List<String>) e.getVariable("selected")))));
    e.setVariable("stageIndex", 0);
    current(e);
  }

  @SuppressWarnings("unchecked")
  public void current(DelegateExecution e) {
    var stages = (List<List<String>>) e.getVariable("stages");
    int i = (Integer) e.getVariable("stageIndex");
    e.setVariable("more", i < stages.size());
    e.setVariable("validators", i < stages.size() ? stages.get(i) : new ArrayList<String>());
  }

  public void advance(DelegateExecution e) {
    e.setVariable("stageIndex", (Integer) e.getVariable("stageIndex") + 1);
    current(e);
  }

  public void joined(DelegateExecution e) {}
}
