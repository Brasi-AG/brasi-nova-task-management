package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.web.StreamingRejectionAdvice;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.web.autoconfigure.NovaHalAutoConfiguration;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.NovaExceptionHandlingProperties;
import io.keel.framework.web.exception.NovaExceptionMetrics;
import jakarta.servlet.DispatcherType;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Prueft ohne Datenbank, dass Inhalt, Download und Vorschaubild der Anhaenge den {@code
 * Accept}-Header ignorieren (Intake #152, Nachbesserung): ein Web-Kontext mit dem echten {@link
 * AttachmentController}, {@link AttachmentAcceptConfiguration}, der KSF-Fehlerbehandlung und den
 * KSF-Nachrichtenkonvertern ({@code NovaHalAutoConfiguration}: nur JSON und HAL-JSON), dazu der
 * begrenzte Executor der Auslieferung ({@link AttachmentStreamingConfiguration}).
 *
 * <p>Der Pool hat einen Thread. Die Faelle, in denen die Auslieferung gelingen muss, laufen auf
 * einem Pool mit Warteschlange ({@link QueuedPoolConfig}): Der Thread wird erst nach dem Ergebnis
 * der Anfrage wieder frei, und eine folgende Auslieferung wuerde ohne Warteschlange in dieser
 * Luecke abgelehnt (503 statt 200). Nur der 503-Fall laeuft auf einem Pool ohne Warteschlange
 * ({@link SaturatedPoolConfig}) und in einem eigenen Kontext, den er mit sich beendet; so kann kein
 * blockierter Thread in einen anderen Test hineinwirken.
 *
 * <p>Ursache, die der Test festhaelt: Der Erfolg ist unabhaengig von {@code Accept}, aber ein
 * Fehler (404, 400, 503) wurde gegen {@code Accept} ausgehandelt und endete bei einem Client, der
 * kein JSON annimmt (etwa {@code image/png}), mit 500. Erwartet ist der fachliche Status mit JSON
 * im KSF-Format, wie bei einem Client ohne {@code Accept}.
 */
class AttachmentAcceptHeaderTest {

  private static final UUID TASK_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
  private static final UUID ATTACHMENT_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
  private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};
  private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
  private static final List<String> KINDS = List.of("content", "download", "thumbnail");

  /** Accept-Werte, die JSON nicht annehmen (dazu ein nicht lesbarer Wert). */
  private static final List<String> NON_JSON_ACCEPTS =
      List.of("image/png", "application/pdf", "text/html", "application/xml", ";;nicht-lesbar");

  /** Accept-Werte, die JSON annehmen. */
  private static final List<String> JSON_ACCEPTS =
      List.of("application/json", "application/hal+json", "*/*", "application/*");

  private static final AttachmentQueryService QUERY_SERVICE = mock(AttachmentQueryService.class);
  private static AnnotationConfigWebApplicationContext context;
  private static MockMvc mvc;

  @BeforeAll
  static void startContext() {
    context = newContext(QueuedPoolConfig.class);
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterAll
  static void closeContext() {
    context.close();
  }

  @BeforeEach
  void resetService() {
    reset(QUERY_SERVICE);
  }

  @Test
  @DisplayName(
      "i152_success_ignores_accept: 200 mit gespeichertem Content-Type und unveraendertem Inhalt,"
          + " egal welcher Accept")
  void i152_success_ignores_accept() throws Exception {
    for (final String kind : KINDS) {
      for (final String accept : allAccepts()) {
        stubDelivery(kind);
        final MvcResult result = fetch(kind, accept, null);
        final String label = kind + " mit Accept " + accept;

        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).as(label).isEqualTo(expectedType(kind));
        assertThat(result.getResponse().getContentAsByteArray())
            .as(label)
            .isEqualTo(expectedBytes(kind));
      }
    }
  }

  @Test
  @DisplayName("i152_not_modified_ignores_accept: 304 ohne Inhalt, egal welcher Accept")
  void i152_not_modified_ignores_accept() throws Exception {
    for (final String kind : KINDS) {
      for (final String accept : allAccepts()) {
        stubUnchanged(kind);
        final MvcResult result = fetch(kind, accept, "W/\"abc\"");
        final String label = kind + " mit Accept " + accept;

        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(304);
        assertThat(result.getResponse().getContentAsByteArray()).as(label).isEmpty();
      }
    }
  }

  @Test
  @DisplayName(
      "i152_not_found_is_ksf_json_for_every_accept: unbekannter Anhang -> 404 mit JSON im"
          + " KSF-Format, auch wenn Accept JSON nicht annimmt (vorher 500)")
  void i152_not_found_is_ksf_json_for_every_accept() throws Exception {
    for (final String kind : KINDS) {
      for (final String accept : allAccepts()) {
        stubNotFound(kind);
        final MvcResult result = fetch(kind, accept, null);
        final String label = kind + " mit Accept " + accept;

        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).as(label).startsWith("application/");
        assertThat(result.getResponse().getContentType()).as(label).contains("json");
        final JsonNode body = json(result);
        assertThat(body.path("status").asInt()).as(label).isEqualTo(404);
        assertThat(body.path("errorKey").asText()).as(label).isEqualTo("NOVA_NOT_FOUND");
      }
    }
  }

  @Test
  @DisplayName(
      "i152_invalid_uuid_is_ksf_json_for_non_json_accept: keine UUID im Pfad -> 400 mit JSON im"
          + " KSF-Format, auch wenn Accept JSON nicht annimmt")
  void i152_invalid_uuid_is_ksf_json_for_non_json_accept() throws Exception {
    for (final String kind : KINDS) {
      for (final String accept : NON_JSON_ACCEPTS) {
        final MvcResult result =
            perform(
                get("/api/v1/tasks/" + TASK_ID + "/attachments/keine-uuid/" + kind)
                    .header("Accept", accept));
        final String label = kind + " mit Accept " + accept;

        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(400);
        assertThat(json(result).path("errorKey").asText())
            .as(label)
            .isEqualTo("NOVA_TYPE_MISMATCH");
      }
    }
  }

  @Test
  @DisplayName(
      "i152_rejected_delivery_is_503_for_non_json_accept: voller Pool -> 503 mit Retry-After und"
          + " JSON im KSF-Format, auch wenn Accept JSON nicht annimmt")
  void i152_rejected_delivery_is_503_for_non_json_accept() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final AnnotationConfigWebApplicationContext saturated = newContext(SaturatedPoolConfig.class);
    try {
      final MockMvc saturatedMvc = MockMvcBuilders.webAppContextSetup(saturated).build();
      saturate(saturated.getBean(ExecutorHolder.class).executor(), release);
      for (final String kind : KINDS) {
        stubDelivery(kind);
        final MvcResult first = perform(saturatedMvc, request(kind, "image/png", null));
        assertThat(first.getRequest().isAsyncStarted()).as(kind).isTrue();
        final MvcResult result = perform(saturatedMvc, dispatchAsync(first));

        assertThat(result.getResponse().getStatus()).as(kind).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After")).as(kind).isEqualTo("5");
        assertThat(result.getResponse().getContentType()).as(kind).contains("json");
        assertThat(json(result).path("status").asInt()).as(kind).isEqualTo(503);
      }
    } finally {
      release.countDown();
      saturated.close();
    }
  }

  @Test
  @DisplayName(
      "i152_other_endpoints_keep_negotiation: die Liste der Anhaenge handelt Accept weiter aus"
          + " (406 bei image/png), nur die drei Auslieferungen ignorieren ihn")
  void i152_other_endpoints_keep_negotiation() throws Exception {
    when(QUERY_SERVICE.listTaskAttachments(TASK_ID)).thenReturn(List.of());
    final String url = "/api/v1/tasks/" + TASK_ID + "/attachments";

    final MvcResult json = perform(get(url).header("Accept", "application/json"));
    final MvcResult png = perform(get(url).header("Accept", "image/png"));

    assertThat(json.getResponse().getStatus()).isEqualTo(200);
    assertThat(png.getResponse().getStatus()).isEqualTo(406);
  }

  // ---- Hilfen ---------------------------------------------------------------------------------

  private static List<String> allAccepts() {
    final List<String> all = new ArrayList<>(NON_JSON_ACCEPTS);
    all.addAll(JSON_ACCEPTS);
    all.add(null);
    return all;
  }

  private static String expectedType(final String kind) {
    return "thumbnail".equals(kind) ? "image/jpeg" : "application/pdf";
  }

  private static byte[] expectedBytes(final String kind) {
    return "thumbnail".equals(kind) ? JPEG_BYTES : PDF_BYTES;
  }

  private static AttachmentDelivery delivery(final String kind) {
    return new AttachmentDelivery(
        false,
        "W/\"abc\"",
        expectedType(kind),
        expectedBytes(kind).length,
        "Bericht.pdf",
        new ByteArrayInputStream(expectedBytes(kind)),
        Duration.ofDays(1));
  }

  private static void stubDelivery(final String kind) {
    if ("thumbnail".equals(kind)) {
      doAnswer(i -> delivery(kind)).when(QUERY_SERVICE).serveThumbnail(any(), any(), isNull());
    } else {
      doAnswer(i -> delivery(kind)).when(QUERY_SERVICE).serveContent(any(), any(), isNull());
    }
  }

  private static void stubUnchanged(final String kind) {
    final AttachmentDelivery unchanged =
        AttachmentDelivery.unchanged("W/\"abc\"", Duration.ofDays(1));
    if ("thumbnail".equals(kind)) {
      doReturn(unchanged).when(QUERY_SERVICE).serveThumbnail(any(), any(), any());
    } else {
      doReturn(unchanged).when(QUERY_SERVICE).serveContent(any(), any(), any());
    }
  }

  private static void stubNotFound(final String kind) {
    final EntityNotFoundException notFound = new EntityNotFoundException("Anhang nicht gefunden.");
    if ("thumbnail".equals(kind)) {
      doThrow(notFound).when(QUERY_SERVICE).serveThumbnail(any(), any(), any());
    } else {
      doThrow(notFound).when(QUERY_SERVICE).serveContent(any(), any(), any());
    }
  }

  private static MockHttpServletRequestBuilder request(
      final String kind, final String accept, final String ifNoneMatch) {
    MockHttpServletRequestBuilder request =
        get("/api/v1/tasks/" + TASK_ID + "/attachments/" + ATTACHMENT_ID + "/" + kind);
    if (accept != null) {
      request = request.header("Accept", accept);
    }
    if (ifNoneMatch != null) {
      request = request.header("If-None-Match", ifNoneMatch);
    }
    return request;
  }

  /** Ruft einen Binaer-Endpunkt ab; ein gestarteter Async-Lauf wird zu Ende gefuehrt. */
  private static MvcResult fetch(final String kind, final String accept, final String ifNoneMatch)
      throws Exception {
    final MvcResult first = perform(request(kind, accept, ifNoneMatch));
    if (!first.getRequest().isAsyncStarted()) {
      return first;
    }
    return perform(asyncDispatch(first));
  }

  private static MvcResult perform(final RequestBuilder request) throws Exception {
    return perform(mvc, request);
  }

  private static MvcResult perform(final MockMvc target, final RequestBuilder request)
      throws Exception {
    return target.perform(request).andReturn();
  }

  /** Startet einen Web-Kontext des Tests mit der angegebenen Pool-Ausstattung. */
  private static AnnotationConfigWebApplicationContext newContext(final Class<?> poolConfig) {
    final AnnotationConfigWebApplicationContext created =
        new AnnotationConfigWebApplicationContext();
    created.setServletContext(new MockServletContext());
    created.register(TestConfig.class, poolConfig);
    created.refresh();
    return created;
  }

  private static JsonNode json(final MvcResult result) throws Exception {
    return new ObjectMapper().readTree(result.getResponse().getContentAsString());
  }

  /**
   * Der erneute Dispatch nach einem abgelehnten Async-Start (wie in {@code
   * StreamingRejectionAdviceTest}): das Ergebnis liegt nur im {@code WebAsyncManager}.
   */
  private static RequestBuilder dispatchAsync(final MvcResult first) {
    return servletContext -> {
      final MockHttpServletRequest request = first.getRequest();
      request.setDispatcherType(DispatcherType.ASYNC);
      request.setAsyncStarted(false);
      return request;
    };
  }

  /**
   * Belegt den einzigen Thread des Pools bis {@code release}; ohne Warteschlange wird jede weitere
   * Aufgabe abgelehnt.
   */
  private static void saturate(final ThreadPoolTaskExecutor pool, final CountDownLatch release)
      throws InterruptedException {
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

  /** Macht den von der Konfiguration gesetzten Executor lesbar. */
  private static final class ExecutorProbe extends AsyncSupportConfigurer {

    ThreadPoolTaskExecutor executor() {
      return (ThreadPoolTaskExecutor) getTaskExecutor();
    }
  }

  /** Haelt den Executor der Auslieferung fuer den Test bereit. */
  static final class ExecutorHolder {

    private final ThreadPoolTaskExecutor executor;

    ExecutorHolder(final AttachmentStreamingConfiguration configuration) {
      final ExecutorProbe probe = new ExecutorProbe();
      configuration.configureAsyncSupport(probe);
      this.executor = probe.executor();
    }

    ThreadPoolTaskExecutor executor() {
      return executor;
    }
  }

  /**
   * Web-Kontext des Tests: echter Controller, Fehlerbehandlung und begrenzter Executor.
   *
   * <p>Bewusst ohne {@code @Configuration}: Die ITs scannen das Paket {@code attachment}, und eine
   * als Konfiguration markierte Klasse wuerde dort mitgeladen. Der Test registriert sie selbst.
   */
  @EnableWebMvc
  static class TestConfig {

    @Bean
    ObjectMapper objectMapper() {
      return Jackson2ObjectMapperBuilder.json().build();
    }

    @Bean
    NovaHalAutoConfiguration novaHalAutoConfiguration(final ObjectMapper objectMapper) {
      return new NovaHalAutoConfiguration(objectMapper);
    }

    @Bean
    AttachmentController attachmentController() {
      return new AttachmentController(
          mock(AttachmentUploadService.class), mock(AttachmentCommandService.class), QUERY_SERVICE);
    }

    @Bean
    GlobalApiExceptionHandler globalApiExceptionHandler() {
      return new GlobalApiExceptionHandler(
          new NovaExceptionHandlingProperties(), NovaExceptionMetrics.noop());
    }

    @Bean
    StreamingRejectionAdvice streamingRejectionAdvice(
        final GlobalApiExceptionHandler handler, final AttachmentStreamingProperties properties) {
      return new StreamingRejectionAdvice(handler, properties);
    }

    @Bean
    AttachmentStreamingConfiguration attachmentStreamingConfiguration(
        final AttachmentStreamingProperties properties) {
      return new AttachmentStreamingConfiguration(properties);
    }

    @Bean
    ExecutorHolder executorHolder(final AttachmentStreamingConfiguration configuration) {
      return new ExecutorHolder(configuration);
    }

    @Bean
    AttachmentAcceptConfiguration attachmentAcceptConfiguration() {
      return new AttachmentAcceptConfiguration();
    }
  }

  /**
   * Pool mit einem Thread und Warteschlange: Eine Auslieferung wird nie abgelehnt, auch wenn der
   * Thread der vorigen noch nicht wieder frei ist. Bewusst ohne {@code @Configuration}, siehe
   * {@link TestConfig}.
   */
  static class QueuedPoolConfig {

    @Bean
    AttachmentStreamingProperties streamingProperties() {
      return new AttachmentStreamingProperties(1, 1, 4, "accept-test-", Duration.ofSeconds(5));
    }
  }

  /**
   * Pool mit einem Thread ohne Warteschlange: Ist der Thread belegt, wird jede weitere Auslieferung
   * abgelehnt (503). Bewusst ohne {@code @Configuration}, siehe {@link TestConfig}.
   */
  static class SaturatedPoolConfig {

    @Bean
    AttachmentStreamingProperties streamingProperties() {
      return new AttachmentStreamingProperties(1, 1, 0, "accept-test-", Duration.ofSeconds(5));
    }
  }
}
