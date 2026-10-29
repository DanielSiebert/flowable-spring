package poc.validation.infrastructure.validator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import poc.validation.application.port.ValidatorCatalog;
import poc.validation.domain.ValidationContext;
import poc.validation.domain.ValidationFinding;
import poc.validation.domain.Validator;

@Component
public class ValidatorRegistry implements ValidatorCatalog {
  private final Map<String, Validator> validators = new LinkedHashMap<>();

  public ValidatorRegistry() {
    for (String id : List.of("P1", "P2", "P3", "P4")) {
      validators.put(
          id,
          new Validator() {
            @Override
            public String id() {
              return id;
            }

            @Override
            public ValidationFinding validate(ValidationContext context) {
              if (id.equals("P3") && context.attempt() <= context.p3Failures()) {
                throw new IllegalStateException(
                    "Synthetic document service failure " + context.attempt());
              }
              return id.equals(context.businessFailure())
                  ? new ValidationFinding("Synthetic business finding for " + id)
                  : null;
            }
          });
    }
  }

  @Override
  public Validator get(String id) {
    return Objects.requireNonNull(validators.get(id), "Unknown validator " + id);
  }

  @Override
  public Set<String> ids() {
    return validators.keySet();
  }
}
