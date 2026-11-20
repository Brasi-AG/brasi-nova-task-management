package de.brasi.nova.taskmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.attachment.AttachmentApiExamples;
import de.brasi.nova.taskmanagement.attachment.AttachmentCommandService;
import de.brasi.nova.taskmanagement.attachment.AttachmentController;
import de.brasi.nova.taskmanagement.attachment.AttachmentDelivery;
import de.brasi.nova.taskmanagement.attachment.AttachmentQueryService;
import de.brasi.nova.taskmanagement.attachment.AttachmentStreamingConfiguration;
import de.brasi.nova.taskmanagement.attachment.AttachmentStreamingProperties;
import de.brasi.nova.taskmanagement.attachment.AttachmentUploadService;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.ApiError;
import io.keel.framework.web.exception.NovaExceptionHandlingProperties;
import io.keel.framework.web.exception.NovaExceptionMetrics;
import jakarta.servlet.DispatcherType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Prueft ohne Spring-Kontext, dass eine vom Pool abgelehnte Auslieferung als 503 mit {@code
 * Retry-After} im KSF-Fehlerformat endet (Intake #152): ein eigenstaendiges MockMvc mit dem echten
 * {@link AttachmentController}, {@link StreamingRejectionAdvice} und KSF-Fehlerbehandlung, dazu der
 * echte begrenzte Executor aus {@link AttachmentStreamingConfiguration}, den der Test belegt, damit
 * {@code WebAsyncManager} beim Start der asynchronen Verarbeitung eine {@link
 * TaskRejectedException} bekommt.
 */
class StreamingRejectionAdviceTest {

  private static final UUID TASK_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
  private static final UUID ATTACHMENT_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
  private static final String CONTENT_URL =
      "/api/v1/tasks/" + TASK_ID + "/attachments/" + ATTACHMENT_ID + "/content";
  private static final long STALE_LENGTH = 123_456L;
  private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};
  private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private final AttachmentQueryService queryService = mock(AttachmentQueryService.class);
  private final AttachmentController controller =
      new AttachmentController(
          mock(AttachmentUploadService.class), mock(AttachmentCommandService.class), queryService);
  private final GlobalApiExceptionHandler apiHandler =
      new GlobalApiExceptionHandler(
          new NovaExceptionHandlingProperties(), NovaExceptionMetrics.noop());
  private final AttachmentStreamingProperties properties =
      new AttachmentStreamingProperties(1, 1, 0, "test-stream-", RETRY_AFTER);
  private final StreamingRejectionAdvice advice =
      new StreamingRejectionAdvice(apiHandler, properties);

  private final CountDownLatch release = new CountDownLatch(1);
  private AttachmentStreamingConfiguration configuration;
  private ThreadPoolTaskExecutor executor;

  @BeforeEach
  void setUp() {
    configuration = new AttachmentStreamingConfiguration(properties);
    final ExecutorProbe probe = new ExecutorProbe();
    configuration.configureAsyncSupport(probe);
    executor = (ThreadPoolTaskExecutor) probe.executor();
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    configuration.destroy();
  }

  @Test
  void i152_rejected_delivery_is_503_with_retry_after_in_ksf_format() throws Exception {
    saturate(executor);
    final TrackingStream stream = new TrackingStream(PDF_BYTES);
    when(queryService.serveContent(TASK_ID, ATTACHMENT_ID, null))
        .thenReturn(delivery("application/pdf", stream));

    final MockMvc mvc = mvc();
    final MvcResult first = mvc.perform(get(CONTENT_URL)).andReturn();
    assertThat(first.getRequest().isAsyncStarted()).isTrue();
    final MvcResult result = mvc.perform(dispatchAsync(first)).andReturn();
    final MockHttpServletResponse response = result.getResponse();

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    assertThat(response.getContentType()).startsWith("application/json");
    final JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
    assertThat(body.path("status").asInt()).isEqualTo(503);
    assertThat(body.path("error").asText()).isEqualTo("Service Unavailable");
    assertThat(body.path("errorKey").asText()).isEqualTo("NOVA_INTERNAL_ERROR");
    assertThat(body.path("path").asText()).isEqualTo(CONTENT_URL);
    assertThat(body.path("details").get(0).asText())
        .isEqualTo(StreamingRejectionAdvice.BUSY_REASON);
    // Die Header der 200-Antwort duerfen die Fehlerantwort nicht verfaelschen.
    assertThat(response.getHeader("Content-Length")).isNotEqualTo(Long.toString(STALE_LENGTH));
    assertThat(response.getHeader("ETag")).isNull();
    assertThat(response.getHeader("Cache-Control")).isNull();
    assertThat(response.getHeader("Content-Disposition")).isNull();
    // Der Body laeuft nie; der geoeffnete Strom wird von der Advice geschlossen.
    assertThat(stream.closed).isTrue();
  }

  @Test
  void i152_openapi_example_matches_live_503() throws Exception {
    saturate(executor);
    when(queryService.serveContent(TASK_ID, ATTACHMENT_ID, null))
        .thenReturn(delivery("application/pdf", new TrackingStream(PDF_BYTES)));
    final MockMvc mvc = mvc();
    final MvcResult first = mvc.perform(get(CONTENT_URL)).andReturn();
    final MvcResult result = mvc.perform(dispatchAsync(first)).andReturn();

    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode live = mapper.readTree(result.getResponse().getContentAsString());
    final JsonNode example = mapper.readTree(AttachmentApiExamples.SERVICE_BUSY_CONTENT);

    for (final String field : new String[] {"status", "error", "message", "errorKey", "details"}) {
      assertThat(example.path(field)).as("Feld " + field).isEqualTo(live.path(field));
    }
    assertThat(example.path("path").asText()).endsWith("/content");
    final Set<String> liveFields = new TreeSet<>();
    live.fieldNames().forEachRemaining(liveFields::add);
    liveFields.remove("traceId");
    final Set<String> exampleFields = new TreeSet<>();
    example.fieldNames().forEachRemaining(exampleFields::add);
    assertThat(exampleFields).containsAll(liveFields);
  }

  @Test
  void i152_handler_removes_delivery_headers_and_keeps_all_others() {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", CONTENT_URL);
    final TrackingStream stream = new TrackingStream(PDF_BYTES);
    request.setAttribute(AttachmentController.PENDING_STREAM_ATTRIBUTE, stream);
    final MockHttpServletResponse response = new MockHttpServletResponse();
    response.setContentType("application/pdf");
    response.setHeader("Content-Length", Long.toString(STALE_LENGTH));
    response.setHeader("ETag", "W/\"abc\"");
    response.setHeader("Cache-Control", "max-age=86400, private");
    response.setHeader("Content-Disposition", "attachment; filename=\"Bericht.pdf\"");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Fremd", "bleibt");
    response.addHeader("Vary", "Origin");
    response.addHeader("Vary", "Accept");

    final ResponseEntity<ApiError> entity =
        advice.handleRejected(new TaskRejectedException("Pool voll"), request, response);

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(entity.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    assertThat(entity.getBody()).isNotNull();
    assertThat(entity.getBody().getStatus()).isEqualTo(503);
    assertThat(entity.getBody().getErrorKey()).isEqualTo("NOVA_INTERNAL_ERROR");
    assertThat(response.getHeaderNames())
        .doesNotContain("Content-Type", "Content-Length", "ETag", "Cache-Control")
        .doesNotContain("Content-Disposition", "X-Content-Type-Options");
    assertThat(response.getContentType()).isNull();
    assertThat(response.getHeader("X-Fremd")).isEqualTo("bleibt");
    assertThat(response.getHeaders("Vary")).containsExactly("Origin", "Accept");
    assertThat(stream.closed).isTrue();
    assertThat(request.getAttribute(AttachmentController.PENDING_STREAM_ATTRIBUTE)).isNull();
  }

  @Test
  void i152_handler_leaves_committed_response_untouched() {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", CONTENT_URL);
    final MockHttpServletResponse response = new MockHttpServletResponse();
    response.setHeader("ETag", "W/\"abc\"");
    response.setCommitted(true);

    final ResponseEntity<ApiError> entity =
        advice.handleRejected(new TaskRejectedException("Pool voll"), request, response);

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeader("ETag")).isEqualTo("W/\"abc\"");
  }

  @Test
  void i152_handler_without_pending_stream_is_fine() {
    final ResponseEntity<ApiError> entity =
        advice.handleRejected(
            new TaskRejectedException("Pool voll"),
            new MockHttpServletRequest("GET", CONTENT_URL),
            new MockHttpServletResponse());

    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void i152_retry_after_is_rounded_up_to_whole_seconds() {
    assertThat(retryAfterOf(Duration.ofSeconds(5))).isEqualTo("5");
    assertThat(retryAfterOf(Duration.ofMillis(1500))).isEqualTo("2");
    assertThat(retryAfterOf(Duration.ofMillis(200))).isEqualTo("1");
  }

  @Test
  void i152_delivery_headers_constant_matches_the_controller() throws IOException {
    when(queryService.serveContent(TASK_ID, ATTACHMENT_ID, null))
        .thenReturn(delivery("application/pdf", new TrackingStream(PDF_BYTES)));

    final ResponseEntity<StreamingResponseBody> response =
        controller.content(TASK_ID, ATTACHMENT_ID, null);

    final Set<String> actual =
        response.getHeaders().keySet().stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    assertThat(actual)
        .containsExactlyInAnyOrderElementsOf(StreamingRejectionAdvice.DELIVERY_HEADERS);
  }

  private static String retryAfterOf(final Duration retryAfter) {
    return new ServiceBusyException("x", retryAfter, null).getHeaders().getFirst("Retry-After");
  }

  /**
   * Der erneute Dispatch nach dem Async-Start wie bei {@code MockMvcRequestBuilders#asyncDispatch},
   * aber ohne dessen Vorbedingung, dass der Test-Servlet das Ergebnis mitgeschrieben hat: Lehnt der
   * Executor schon beim Start ab, laeuft keine Interceptor-Nachbehandlung, das Ergebnis liegt nur
   * im {@code WebAsyncManager} (wie im Servlet-Container).
   */
  private static RequestBuilder dispatchAsync(final MvcResult first) {
    return servletContext -> {
      final MockHttpServletRequest request = first.getRequest();
      request.setDispatcherType(DispatcherType.ASYNC);
      request.setAsyncStarted(false);
      return request;
    };
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(advice, apiHandler)
        .addDispatcherServletCustomizer(
            servlet ->
                servlet
                    .getWebApplicationContext()
                    .getBean(RequestMappingHandlerAdapter.class)
                    .setTaskExecutor(executor))
        .build();
  }

  /**
   * Belegt den einzigen Thread des Pools; ohne Warteschlange wird jede weitere Aufgabe abgelehnt.
   */
  private void saturate(final ThreadPoolTaskExecutor pool) throws InterruptedException {
    final CountDownLatch running = new CountDownLatch(1);
    pool.execute(
        () -> {
          running.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private static AttachmentDelivery delivery(final String mimeType, final ByteArrayInputStream in) {
    return new AttachmentDelivery(
        false, "W/\"abc\"", mimeType, STALE_LENGTH, "Bericht.pdf", in, Duration.ofDays(1));
  }

  /** Strom, der das Schliessen merkt. */
  private static final class TrackingStream extends ByteArrayInputStream {

    private boolean closed;

    TrackingStream(final byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  /** Macht den von der Konfiguration gesetzten Executor lesbar. */
  private static final class ExecutorProbe extends AsyncSupportConfigurer {

    Object executor() {
      return getTaskExecutor();
    }
  }
}
