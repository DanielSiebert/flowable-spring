package poc.validation.application.port;

import java.util.Set;
import poc.validation.domain.Validator;

public interface ValidatorCatalog {
  Validator get(String id);

  Set<String> ids();
}
