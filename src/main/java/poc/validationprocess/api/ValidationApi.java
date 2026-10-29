package poc.validationprocess.api;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import poc.validationprocess.application.service.ValidationRunService;

@RestController
@RequestMapping("/api/v1/validation-runs")
public class ValidationApi {
  private final ValidationRunService service;

  public ValidationApi(ValidationRunService service) {
    this.service = service;
  }

  public record StartRequest(String requestKey, Integer p3Failures, String businessFailure) {
    public StartRequest {
      p3Failures = p3Failures == null ? 0 : p3Failures;
    }
  }

  public record ReevaluateRequest(
      String requestKey, Set<String> validators, Integer p3Failures, String businessFailure) {
    public ReevaluateRequest {
      p3Failures = p3Failures == null ? 0 : p3Failures;
    }
  }

  @PostMapping
  public ValidationRunService.RunView start(@RequestBody StartRequest r) {
    return service.inspect(
        service.start(r.requestKey(), r.p3Failures(), r.businessFailure(), null, Set.of()).id);
  }

  @GetMapping("/{id}")
  public ValidationRunService.RunView get(@PathVariable String id) {
    return service.inspect(id);
  }

  @PostMapping("/{id}/re-evaluations")
  public ValidationRunService.RunView reevaluate(
      @PathVariable String id, @RequestBody ReevaluateRequest r) {
    return service.inspect(
        service.start(
                r.requestKey(),
                r.p3Failures(),
                r.businessFailure(),
                id,
                r.validators() == null ? Set.of() : r.validators())
            .id);
  }

  @PostMapping("/{id}/dead-letter-retries")
  public ValidationRunService.RunView retry(@PathVariable String id) {
    service.retryDeadLetters(id);
    return service.inspect(id);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
  }
}
