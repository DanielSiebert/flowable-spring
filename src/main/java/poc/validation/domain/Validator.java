package poc.validation.domain;

public interface Validator {
  String id();

  ValidationFinding validate(ValidationContext context);
}
