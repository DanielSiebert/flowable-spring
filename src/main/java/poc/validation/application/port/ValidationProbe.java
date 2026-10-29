package poc.validation.application.port;

public class ValidationProbe {
  public void afterResult(String run, String validator, int attempt) {}

  public void before(
      String run, String validator) {} // Test seam: real jobs still execute the complete delegate.
}
