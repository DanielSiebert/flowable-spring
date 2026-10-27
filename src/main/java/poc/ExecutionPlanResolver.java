package poc;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ExecutionPlanResolver {
  private final ValidatorRegistry registry;
  private final Map<String, Set<String>> dependencies =
      Map.of("P1", Set.of(), "P2", Set.of("P1"), "P3", Set.of("P1"), "P4", Set.of("P2", "P3"));

  public ExecutionPlanResolver(ValidatorRegistry r) {
    registry = r;
  }

  public Set<String> affected(Set<String> requested) {
    if (!registry.ids().containsAll(requested) || requested.isEmpty())
      throw new IllegalArgumentException("Unknown or empty validators");
    Set<String> result = new LinkedHashSet<>(requested);
    boolean changed;
    do {
      changed = false;
      for (var e : dependencies.entrySet())
        if (!Collections.disjoint(e.getValue(), result)) changed |= result.add(e.getKey());
    } while (changed);
    return result;
  }

  public List<List<String>> resolve(Set<String> selected) {
    if (selected == null || selected.isEmpty() || !registry.ids().containsAll(selected))
      throw new IllegalArgumentException("Unknown or empty validators");
    Set<String> remaining = new LinkedHashSet<>(selected), done = new HashSet<>(registry.ids());
    done.removeAll(selected);
    List<List<String>> stages = new ArrayList<>();
    while (!remaining.isEmpty()) {
      var stage = remaining.stream().filter(id -> done.containsAll(dependencies.get(id))).toList();
      if (stage.isEmpty()) throw new IllegalStateException("Cyclic dependencies");
      stages.add(new ArrayList<>(stage));
      done.addAll(stage);
      remaining.removeAll(stage);
    }
    return stages;
  }
}
