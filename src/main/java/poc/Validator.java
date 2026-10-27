package poc;

public interface Validator {
  String id();

  ValidationFinding validate(ValidationRun run, int attempt);
}
