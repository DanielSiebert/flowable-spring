package poc.validation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import poc.validation.application.port.AttemptAuditPort;
import poc.validation.application.port.ValidationProbe;
import poc.validation.application.port.ValidationResultRepository;
import poc.validation.domain.ExecutionAttempt;
import poc.validation.domain.ValidationResult;
import poc.validation.infrastructure.validator.ValidatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidatorExecutionServiceUnitTest {

  private final Map<String, ValidationResult> storedResults = new LinkedHashMap<>();
  private final List<ExecutionAttempt> storedAttempts = new ArrayList<>();
  private ValidatorExecutionService service;

  @BeforeEach
  void setUp() {
    storedResults.clear();
    storedAttempts.clear();

    ValidationResultRepository resultRepo =
        new ValidationResultRepository() {
          @Override
          public boolean existsByRunAndValidator(String runId, String validatorId) {
            return storedResults.containsKey(ValidationResult.idFor(runId, validatorId));
          }

          @Override
          public ValidationResult save(ValidationResult result) {
            storedResults.put(result.id, result);
            return result;
          }

          @Override
          public ValidationResult saveAndFlush(ValidationResult result) {
            storedResults.put(result.id, result);
            return result;
          }

          @Override
          public List<ValidationResult> findByRunId(String runId) {
            return storedResults.values().stream()
                .filter(r -> r.runId.equals(runId))
                .toList();
          }
        };

    AttemptAuditPort attemptAudit =
        new AttemptAuditPort() {
          @Override
          public ExecutionAttempt start(String runId, String validatorId) {
            int next =
                (int)
                        storedAttempts.stream()
                            .filter(a -> a.runId.equals(runId) && a.validatorId.equals(validatorId))
                            .count()
                    + 1;
            var started = ExecutionAttempt.started(runId, validatorId, next);
            storedAttempts.add(started);
            return started;
          }

          @Override
          public void finish(String id, String outcome) {
            finish(id, outcome, null);
          }

          @Override
          public void finish(String id, String outcome, String error) {
            for (int i = 0; i < storedAttempts.size(); i++) {
              var current = storedAttempts.get(i);
              if (current.id.equals(id)) {
                storedAttempts.set(
                    i,
                    new ExecutionAttempt(
                        current.id,
                        current.runId,
                        current.validatorId,
                        current.number,
                        current.startedAt,
                        System.currentTimeMillis(),
                        outcome,
                        error));
              }
            }
          }

          @Override
          public List<ExecutionAttempt> findByRunId(String runId) {
            return storedAttempts.stream().filter(a -> a.runId.equals(runId)).toList();
          }
        };

    service =
        new ValidatorExecutionService(
            new ValidatorRegistry(), resultRepo, attemptAudit, new ValidationProbe());
  }

  @Test
  void executesValidatorAndPersistsValidResultAndReturnedAttempt() {
    service.execute("run-1", "P1", 0, null);

    assertThat(storedResults.values())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.validatorId).isEqualTo("P1");
              assertThat(r.outcome).isEqualTo("VALID");
              assertThat(r.finding).isNull();
              assertThat(r.attempt).isEqualTo(1);
            });
    assertThat(storedAttempts)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.outcome).isEqualTo("RETURNED");
              assertThat(a.error).isNull();
            });
  }

  @Test
  void skipsExecutionWhenResultAlreadyExistsForRunAndValidator() {
    service.execute("run-1", "P1", 0, null);
    service.execute("run-1", "P1", 0, null);

    assertThat(storedAttempts).hasSize(1);
    assertThat(storedResults).hasSize(1);
  }

  @Test
  void recordsTechnicalFailureAttemptWithErrorMessageAndRethrowsException() {
    assertThatThrownBy(() -> service.execute("run-1", "P3", 1, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Synthetic document service failure 1");

    assertThat(storedResults).isEmpty();
    assertThat(storedAttempts)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.outcome).isEqualTo("TECHNICAL_FAILURE");
              assertThat(a.error).isEqualTo("Synthetic document service failure 1");
            });
  }

  @Test
  void recordsBusinessFindingAsInvalidOutcomeWithoutThrowing() {
    service.execute("run-1", "P3", 0, "P3");

    assertThat(storedResults.values())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.outcome).isEqualTo("INVALID");
              assertThat(r.finding).isEqualTo("Synthetic business finding for P3");
            });
    assertThat(storedAttempts)
        .singleElement()
        .satisfies(a -> assertThat(a.outcome).isEqualTo("RETURNED"));
  }
}
