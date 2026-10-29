package poc.validationprocess.domain;

public class DuplicateRequestKeyException extends RuntimeException {
  private final String requestKey;

  public DuplicateRequestKeyException(String requestKey, Throwable cause) {
    super("Duplicate requestKey: " + requestKey, cause);
    this.requestKey = requestKey;
  }

  public String requestKey() {
    return requestKey;
  }
}
