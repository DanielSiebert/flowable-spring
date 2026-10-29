package poc.executionplan.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ExecutionPlanResolver {
  private static final Map<String, Set<String>> DEFAULT_DEPENDENCIES =
      Map.of("P1", Set.of(), "P2", Set.of("P1"), "P3", Set.of("P1"), "P4", Set.of("P2", "P3"));

  private final Set<String> registeredIds;
  private final Map<String, Set<String>> dependencies;

  public ExecutionPlanResolver(Set<String> registeredIds) {
    this(registeredIds, DEFAULT_DEPENDENCIES);
  }

  public ExecutionPlanResolver(
      Set<String> registeredIds, Map<String, Set<String>> dependencies) {
    this.registeredIds = new LinkedHashSet<>(Objects.requireNonNull(registeredIds));
    this.dependencies = Map.copyOf(Objects.requireNonNull(dependencies));
  }

  public Set<String> affected(Set<String> requested) {
    if (requested == null || requested.isEmpty() || !registeredIds.containsAll(requested)) {
      throw new IllegalArgumentException("Unknown or empty validators");
    }
    Set<String> result = new LinkedHashSet<>(requested);
    boolean changed;
    do {
      changed = false;
      for (var e : dependencies.entrySet()) {
        if (!Collections.disjoint(e.getValue(), result)) {
          changed |= result.add(e.getKey());
        }
      }
    } while (changed);
    return result;
  }

  public List<List<String>> resolve(Set<String> selected) {
    if (selected == null || selected.isEmpty() || !registeredIds.containsAll(selected)) {
      throw new IllegalArgumentException("Unknown or empty validators");
    }
    Set<String> remaining = new LinkedHashSet<>(selected);
    Set<String> done = new HashSet<>(registeredIds);
    done.removeAll(selected);
    List<List<String>> stages = new ArrayList<>();
    while (!remaining.isEmpty()) {
      var stage =
          remaining.stream()
              .filter(id -> done.containsAll(dependencies.getOrDefault(id, Set.of())))
              .toList();
      if (stage.isEmpty()) {
        throw new IllegalStateException("Cyclic dependencies");
      }
      stages.add(new ArrayList<>(stage));
      done.addAll(stage);
      remaining.removeAll(stage);
    }
    return stages;
  }
}
