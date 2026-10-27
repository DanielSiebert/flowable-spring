package poc;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ValidatorRegistry {
  private final Map<String, Validator> validators = new LinkedHashMap<>();

  public ValidatorRegistry() {
    for (String id : List.of("P1", "P2", "P3", "P4"))
      validators.put(
          id,
          new Validator() {
            public String id() {
              return id;
            }

            public ValidationFinding validate(ValidationRun run, int attempt) {
              if (id.equals("P3") && attempt <= run.p3Failures)
                throw new IllegalStateException("Synthetic document service failure " + attempt);
              return id.equals(run.businessFailure)
                  ? new ValidationFinding("Synthetic business finding for " + id)
                  : null;
            }
          });
  }

  public Validator get(String id) {
    return Objects.requireNonNull(validators.get(id), "Unknown validator " + id);
  }

  public Set<String> ids() {
    return validators.keySet();
  }
}
