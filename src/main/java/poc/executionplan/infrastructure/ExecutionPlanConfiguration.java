package poc.executionplan.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import poc.executionplan.domain.ExecutionPlanResolver;
import poc.validation.application.port.ValidatorCatalog;

@Configuration
public class ExecutionPlanConfiguration {
  @Bean
  public ExecutionPlanResolver executionPlanResolver(ValidatorCatalog catalog) {
    return new ExecutionPlanResolver(catalog.ids());
  }
}
