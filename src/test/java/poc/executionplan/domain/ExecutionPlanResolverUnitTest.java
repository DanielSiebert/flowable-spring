package poc.executionplan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionPlanResolverUnitTest {

  private final ExecutionPlanResolver resolver =
      new ExecutionPlanResolver(new LinkedHashSet<>(List.of("P1", "P2", "P3", "P4")));

  @Test
  void resolvesFullPlanIntoExpectedTopologicalStages() {
    var stages = resolver.resolve(new LinkedHashSet<>(List.of("P1", "P2", "P3", "P4")));
    assertThat(stages).isEqualTo(List.of(List.of("P1"), List.of("P2", "P3"), List.of("P4")));
  }

  @Test
  void resolvesSubsetPlanAssumingOmittedUpstreamValidatorsAreAlreadyDone() {
    var stages = resolver.resolve(new LinkedHashSet<>(List.of("P3", "P4")));
    assertThat(stages).isEqualTo(List.of(List.of("P3"), List.of("P4")));
  }

  @Test
  void computesTransitiveDownstreamAffectedValidators() {
    assertThat(resolver.affected(Set.of("P1")))
        .containsExactlyInAnyOrder("P1", "P2", "P3", "P4");
    assertThat(resolver.affected(Set.of("P3"))).containsExactlyInAnyOrder("P3", "P4");
    assertThat(resolver.affected(Set.of("P4"))).containsExactly("P4");
  }

  @Test
  void rejectsEmptyOrUnknownValidators() {
    assertThatThrownBy(() -> resolver.resolve(Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.resolve(Set.of("P99")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.affected(Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.affected(Set.of("P99")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void detectsCyclicDependencies() {
    var cyclicResolver =
        new ExecutionPlanResolver(
            Set.of("A", "B"), Map.of("A", Set.of("B"), "B", Set.of("A")));
    assertThatThrownBy(() -> cyclicResolver.resolve(Set.of("A", "B")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cyclic dependencies");
  }
}
