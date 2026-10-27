package poc;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.File;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ValidationIntegrationTest {
  static ConfigurableApplicationContext context;
  static String database;
  static final AtomicReference<String> rollbackRun = new AtomicReference<>();
  static final AtomicReference<Barrier> barrier = new AtomicReference<>();
  static final Map<String, String> validatorThreads = new ConcurrentHashMap<>();

  record Barrier(String run, CyclicBarrier gate) {}

  @Configuration(proxyBeanMethods = false)
  static class ProbeConfig {
    @Bean
    @Primary
    ValidationProbe testProbe() {
      return new ValidationProbe() {
        @Override
        public void afterResult(String run, String validator, int attempt) {
          if (run.equals(rollbackRun.get()) && validator.equals("P3") && attempt == 1)
            throw new IllegalStateException("Failure after domain result flush");
        }

        @Override
        public void before(String run, String validator) {
          var b = barrier.get();
          if (b != null && b.run.equals(run)) {
            validatorThreads.put(validator, Thread.currentThread().getName());
            if (Set.of("P2", "P3").contains(validator)) {
              try {
                b.gate.await(15, TimeUnit.SECONDS);
              } catch (Exception ex) {
                throw new IllegalStateException("Validators did not overlap", ex);
              }
            }
          }
        }
      };
    }
  }

  public static void main(String[] args) {
    String dbUrl = args[0];
    String reqKey = args[1];
    try (var ctx =
        new SpringApplicationBuilder(Application.class)
            .run(
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + dbUrl,
                "--flowable.async-executor-activate=true",
                "--spring.main.banner-mode=off",
                "--spring.task.execution.pool.core-size=4",
                "--spring.task.execution.pool.max-size=4")) {
      var svc = ctx.getBean(ValidationRunService.class);
      var run = svc.start(reqKey, 1, null, null, Set.of());
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                var v = svc.inspect(run.id);
                assertThat(v.results())
                    .extracting(r -> r.validatorId)
                    .contains("P1", "P2");
                assertThat(v.attempts())
                    .anyMatch(
                        a ->
                            a.validatorId.equals("P3")
                                && a.number == 1
                                && "TECHNICAL_FAILURE".equals(a.outcome));
              });
      ctx.getBean(org.flowable.engine.ProcessEngine.class)
          .getProcessEngineConfiguration()
          .getAsyncExecutor()
          .shutdown();
    }
  }

  static ConfigurableApplicationContext open(boolean async) {
    return new SpringApplicationBuilder(Application.class, ProbeConfig.class)
        .run(
            "--spring.main.web-application-type=none",
            "--spring.datasource.url=" + database,
            "--flowable.async-executor-activate=" + async,
            "--spring.main.banner-mode=off",
            "--spring.task.execution.pool.core-size=4",
            "--spring.task.execution.pool.max-size=4");
  }

  @BeforeAll
  static void boot() throws Exception {
    database =
        "jdbc:h2:file:"
            + Files.createTempDirectory("validation-it-").resolve("db")
            + ";DB_CLOSE_ON_EXIT=FALSE";
    context = open(true);
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
  }

  ValidationRunService service() {
    return context.getBean(ValidationRunService.class);
  }

  ValidationRun start(int failures, String business) {
    return service().start(UUID.randomUUID().toString(), failures, business, null, Set.of());
  }

  ValidationRunService.RunView complete(String id) {
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(service().inspect(id).state()).isEqualTo("COMPLETED"));
    return service().inspect(id);
  }

  List<ExecutionAttempt> attempts(ValidationRunService.RunView view, String id) {
    return view.attempts().stream()
        .filter(a -> a.validatorId.equals(id))
        .sorted(Comparator.comparingInt(a -> a.number))
        .toList();
  }

  @Test
  void restApiStartsInspectsAndReevaluates() throws Exception {
    String url =
        "jdbc:h2:file:"
            + Files.createTempDirectory("validation-http-").resolve("db")
            + ";DB_CLOSE_ON_EXIT=FALSE";
    try (var web =
        new SpringApplicationBuilder(Application.class, ProbeConfig.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.main.banner-mode=off")) {
      var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) web).build();
      var mapper = new tools.jackson.databind.ObjectMapper();

      // 1. Unknown run IDs return 404 Not Found
      mvc.perform(get("/api/v1/validation-runs/non-existent-id"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").exists());
      mvc.perform(
              post("/api/v1/validation-runs/non-existent-id/re-evaluations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"requestKey\":\"reeval-missing\",\"validators\":[\"P3\"]}"))
          .andExpect(status().isNotFound());
      mvc.perform(post("/api/v1/validation-runs/non-existent-id/dead-letter-retries"))
          .andExpect(status().isNotFound());

      // 2. Request validation errors return 400 Bad Request
      mvc.perform(
              post("/api/v1/validation-runs")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"requestKey\":\"\",\"p3Failures\":0}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              post("/api/v1/validation-runs")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"requestKey\":\"bad-failures\",\"p3Failures\":-1}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              post("/api/v1/validation-runs")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"requestKey\":\"bad-validator\",\"businessFailure\":\"P99\"}"))
          .andExpect(status().isBadRequest());

      // 3. Start a run with a business finding on P3
      String startBody =
          mvc.perform(
                  post("/api/v1/validation-runs")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"requestKey\":\"http-demo\",\"p3Failures\":0,\"businessFailure\":\"P3\"}"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String id = (String) mapper.readValue(startBody, Map.class).get("id");

      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () ->
                  mvc.perform(get("/api/v1/validation-runs/" + id))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.state").value("COMPLETED"))
                      .andExpect(jsonPath("$.outcome").value("INVALID")));

      // 4. Duplicate request returns the same run ID
      mvc.perform(
              post("/api/v1/validation-runs")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"requestKey\":\"http-demo\",\"p3Failures\":0}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(id));

      // 5. Invalid re-evaluation (empty/unknown validators) returns 400 Bad Request
      mvc.perform(
              post("/api/v1/validation-runs/" + id + "/re-evaluations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"requestKey\":\"http-bad-reeval\",\"validators\":[]}"))
          .andExpect(status().isBadRequest());

      // 6. Valid re-evaluation of P3 clears the finding and updates effective outcome to VALID
      String nextBody =
          mvc.perform(
                  post("/api/v1/validation-runs/" + id + "/re-evaluations")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"requestKey\":\"http-next\",\"validators\":[\"P3\"]}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.originalRunId").value(id))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(nextBody).contains("originalRunId", "reusedFromRun");
      String nextId = (String) mapper.readValue(nextBody, Map.class).get("id");

      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () ->
                  mvc.perform(get("/api/v1/validation-runs/" + nextId))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.state").value("COMPLETED"))
                      .andExpect(jsonPath("$.outcome").value("VALID"))
                      .andExpect(jsonPath("$.effectiveRunId").value(nextId))
                      .andExpect(jsonPath("$.effectiveOutcome").value("VALID")));

      // Original run preserves its own INVALID outcome while pointing to nextId as effective run
      mvc.perform(get("/api/v1/validation-runs/" + id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.outcome").value("INVALID"))
          .andExpect(jsonPath("$.effectiveRunId").value(nextId))
          .andExpect(jsonPath("$.effectiveOutcome").value("VALID"));
    }
  }

  @Test
  void resultAndEngineJobRollbackTogether() {
    var engine =
        context.getBean(org.flowable.engine.ProcessEngine.class).getProcessEngineConfiguration();
    assertThat(
            ((org.flowable.spring.SpringProcessEngineConfiguration) engine).getTransactionManager())
        .isSameAs(context.getBean(org.springframework.transaction.PlatformTransactionManager.class))
        .isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
    var executor = engine.getAsyncExecutor();
    executor.shutdown();
    var run = start(0, null);
    rollbackRun.set(run.id);
    try {
      executor.start();
      var view = complete(run.id);
      assertThat(attempts(view, "P3")).hasSize(2);
      assertThat(view.results())
          .filteredOn(r -> r.validatorId.equals("P3"))
          .singleElement()
          .satisfies(r -> assertThat(r.attempt).isEqualTo(2));
    } finally {
      rollbackRun.set(null);
    }
  }

  @Test
  void normalOrderingAndPersistence() {
    var resolver = context.getBean(ExecutionPlanResolver.class);
    assertThat(resolver.resolve(new LinkedHashSet<>(List.of("P1", "P2", "P3", "P4"))))
        .isEqualTo(List.of(List.of("P1"), List.of("P2", "P3"), List.of("P4")));
    assertThat(resolver.affected(Set.of("P3"))).containsExactlyInAnyOrder("P3", "P4");
    assertThatThrownBy(() -> resolver.resolve(Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.affected(Set.of("UNKNOWN")))
        .isInstanceOf(IllegalArgumentException.class);

    var run = start(0, null);
    var view = complete(run.id);
    assertThat(view.outcome()).isEqualTo("VALID");
    assertThat(view.results()).hasSize(4).allMatch(r -> r.outcome.equals("VALID"));
    assertThat(view.attempts()).hasSize(4);
    long p1 = attempts(view, "P1").get(0).finishedAt;
    assertThat(attempts(view, "P2").get(0).startedAt).isGreaterThanOrEqualTo(p1);
    assertThat(attempts(view, "P3").get(0).startedAt).isGreaterThanOrEqualTo(p1);
    long p4 = attempts(view, "P4").get(0).startedAt;
    assertThat(p4)
        .isGreaterThanOrEqualTo(attempts(view, "P2").get(0).finishedAt)
        .isGreaterThanOrEqualTo(attempts(view, "P3").get(0).finishedAt);
  }

  @Test
  void actualParallelJobs() {
    // Pause acquisition before creating the process so the barrier is installed before any
    // validator runs.
    var executor =
        context
            .getBean(org.flowable.engine.ProcessEngine.class)
            .getProcessEngineConfiguration()
            .getAsyncExecutor();
    executor.shutdown();
    validatorThreads.clear();
    var run = start(0, null);
    barrier.set(new Barrier(run.id, new CyclicBarrier(2)));
    try {
      executor.start();
      var view = complete(run.id);
      assertThat(view.attempts()).hasSize(4).allMatch(a -> a.outcome.equals("RETURNED"));
      assertThat(validatorThreads.get("P2"))
          .isNotBlank()
          .isNotEqualTo(validatorThreads.get("P3"));
    } finally {
      barrier.set(null);
      validatorThreads.clear();
    }
  }

  @Test
  void technicalRetryIsIndividual() {
    var run = start(2, null);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              var inFlight = service().inspect(run.id);
              assertThat(inFlight.jobs())
                  .anyMatch(
                      j ->
                          j.kind().equals("TIMER")
                              && j.retries() < 3
                              && j.exception() != null
                              && j.exception().contains("Synthetic document service failure"));
            });
    var view = complete(run.id);
    assertThat(attempts(view, "P3")).hasSize(3);
    assertThat(attempts(view, "P3").stream().map(a -> a.outcome))
        .containsExactly("TECHNICAL_FAILURE", "TECHNICAL_FAILURE", "RETURNED");
    assertThat(attempts(view, "P3").stream().map(a -> a.error))
        .containsExactly(
            "Synthetic document service failure 1",
            "Synthetic document service failure 2",
            null);
    for (String id : List.of("P1", "P2", "P4")) assertThat(attempts(view, id)).hasSize(1);
    assertThat(attempts(view, "P4").get(0).startedAt)
        .isGreaterThanOrEqualTo(attempts(view, "P3").get(2).finishedAt);
  }

  @Test
  void exhaustedRetriesBecomeDeadLetter() {
    var run = start(3, null);
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(service().inspect(run.id).state()).isEqualTo("INCIDENT"));
    var view = service().inspect(run.id);
    assertThat(view.outcome()).isEqualTo("PENDING");
    assertThat(attempts(view, "P3")).hasSize(3);
    assertThat(attempts(view, "P4")).isEmpty();
    assertThat(view.jobs())
        .anyMatch(
            j ->
                j.kind().equals("DEAD_LETTER")
                    && j.retries() == 0
                    && j.exception() != null
                    && j.exception().contains("Synthetic document service failure 3"));
    service().retryDeadLetters(run.id);
    var recovered = complete(run.id);
    assertThat(recovered.outcome()).isEqualTo("VALID");
    assertThat(attempts(recovered, "P3")).hasSize(4);
    assertThat(attempts(recovered, "P1")).hasSize(1);
    assertThat(attempts(recovered, "P2")).hasSize(1);
  }

  @Test
  void concurrentDuplicateRequests() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    String key = UUID.randomUUID().toString();
    var gate = new CyclicBarrier(2);
    try {
      Callable<String> request =
          () -> {
            gate.await();
            return service().start(key, 0, null, null, Set.of()).id;
          };
      var a = pool.submit(request);
      var b = pool.submit(request);
      String id = a.get(30, TimeUnit.SECONDS);
      assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(id);
      // Sequential duplicate while active or completing
      assertThat(service().start(key, 0, null, null, Set.of()).id).isEqualTo(id);
      assertThat(complete(id).attempts()).hasSize(4);
      // Sequential duplicate after completion
      assertThat(service().start(key, 0, null, null, Set.of()).id).isEqualTo(id);
      var history = context.getBean(HistoryService.class);
      assertThat(
              history
                  .createHistoricProcessInstanceQuery()
                  .processInstanceBusinessKey(id)
                  .count())
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void selectiveReevaluation() {
    var original = start(0, "P3");
    var origView = complete(original.id);
    assertThat(origView.outcome()).isEqualTo("INVALID");
    assertThat(origView.effectiveRunId()).isEqualTo(original.id);
    assertThat(origView.effectiveOutcome()).isEqualTo("INVALID");

    var next = service().start(UUID.randomUUID().toString(), 0, null, original.id, Set.of("P3"));
    var view = complete(next.id);
    assertThat(view.originalRunId()).isEqualTo(original.id);
    assertThat(view.outcome()).isEqualTo("VALID");
    assertThat(view.effectiveRunId()).isEqualTo(next.id);
    assertThat(view.effectiveOutcome()).isEqualTo("VALID");
    assertThat(view.correlatedRunIds()).containsExactly(original.id, next.id);
    assertThat(view.attempts())
        .extracting(a -> a.validatorId)
        .containsExactlyInAnyOrder("P3", "P4");
    assertThat(view.results().stream().filter(r -> r.reusedFromRun != null))
        .extracting(r -> r.validatorId)
        .containsExactlyInAnyOrder("P1", "P2");

    var updatedOrig = service().inspect(original.id);
    assertThat(updatedOrig.results()).hasSize(4);
    assertThat(updatedOrig.outcome()).isEqualTo("INVALID");
    assertThat(updatedOrig.effectiveRunId()).isEqualTo(next.id);
    assertThat(updatedOrig.effectiveOutcome()).isEqualTo("VALID");
    assertThat(updatedOrig.correlatedRunIds()).containsExactly(original.id, next.id);
  }

  @Test
  void businessFindingDoesNotRetryOrBlockStages() {
    var run = start(0, "P3");
    var view = complete(run.id);
    assertThat(view.state()).isEqualTo("COMPLETED");
    assertThat(view.outcome()).isEqualTo("INVALID");
    assertThat(attempts(view, "P3")).hasSize(1);
    assertThat(view.results())
        .anyMatch(
            r -> r.validatorId.equals("P3") && r.outcome.equals("INVALID") && r.finding != null);
    assertThat(attempts(view, "P4")).hasSize(1);
  }

  @Test
  void restartResumesCommittedPendingJob() {
    context.close();
    context = open(false);
    var run = start(0, null);
    assertThat(service().inspect(run.id).jobs()).isNotEmpty();
    assertThat(service().inspect(run.id).attempts()).isEmpty();
    context.close();
    context = open(true);
    assertThat(complete(run.id).attempts()).hasSize(4);
  }

  @Test
  void twoProcessRestartResumesMidWorkflowAfterPartialStageAndFailure() throws Exception {
    String sharedDb =
        "jdbc:h2:file:"
            + Files.createTempDirectory("validation-2proc-").resolve("db")
            + ";DB_CLOSE_ON_EXIT=FALSE";
    String reqKey = "two-proc-" + UUID.randomUUID();
    String javaBin =
        Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    String classpath = System.getProperty("java.class.path");

    Process child =
        new ProcessBuilder(
                javaBin, "-cp", classpath, ValidationIntegrationTest.class.getName(), sharedDb, reqKey)
            .redirectErrorStream(true)
            .start();
    String childOut = new String(child.getInputStream().readAllBytes());
    int exitCode = child.waitFor();
    assertThat(exitCode).as("Child JVM output:\n%s", childOut).isZero();

    // Open a second independent context in this JVM against the same file-based H2 database,
    // first with async executor disabled to inspect persisted mid-workflow state.
    try (var secondJvm =
        new SpringApplicationBuilder(Application.class, ProbeConfig.class)
            .run(
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + sharedDb,
                "--flowable.async-executor-activate=false",
                "--spring.main.banner-mode=off")) {
      var svc = secondJvm.getBean(ValidationRunService.class);
      var run = secondJvm.getBean(RunRepository.class).findByRequestKey(reqKey).orElseThrow();
      var midView = svc.inspect(run.id);

      assertThat(midView.state()).isEqualTo("RUNNING");
      assertThat(midView.outcome()).isEqualTo("PENDING");
      assertThat(midView.results())
          .extracting(r -> r.validatorId)
          .containsExactlyInAnyOrder("P1", "P2");
      assertThat(attempts(midView, "P1")).hasSize(1);
      assertThat(attempts(midView, "P2")).hasSize(1);
      assertThat(attempts(midView, "P3"))
          .singleElement()
          .satisfies(
              a -> {
                assertThat(a.outcome).isEqualTo("TECHNICAL_FAILURE");
                assertThat(a.error).isEqualTo("Synthetic document service failure 1");
              });
      assertThat(attempts(midView, "P4")).isEmpty();
      assertThat(midView.jobs()).isNotEmpty();

      // Start the async executor to resume the persisted workflow to completion
      secondJvm
          .getBean(org.flowable.engine.ProcessEngine.class)
          .getProcessEngineConfiguration()
          .getAsyncExecutor()
          .start();

      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(svc.inspect(run.id).state()).isEqualTo("COMPLETED"));

      var finalView = svc.inspect(run.id);
      assertThat(finalView.outcome()).isEqualTo("VALID");
      assertThat(attempts(finalView, "P1")).hasSize(1);
      assertThat(attempts(finalView, "P2")).hasSize(1);
      assertThat(attempts(finalView, "P3")).hasSize(2);
      assertThat(attempts(finalView, "P3").stream().map(a -> a.outcome))
          .containsExactly("TECHNICAL_FAILURE", "RETURNED");
      assertThat(attempts(finalView, "P4")).hasSize(1);
    }
  }
}
