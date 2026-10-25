package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.model.TaskSourceInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

class ContactTaskSourceAdapterTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CONTACT = UUID.randomUUID();
  private final CurrentActor actor =
      new CurrentActor(
          new TenantInfo(TENANT, 500L), new AuthUser(UUID.randomUUID(), "actor@test.invalid"));
  private final TaskSourceInput source = new TaskSourceInput("application", "contact", CONTACT);
  private final AtomicReference<String> bearer = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger bodyStarted = new AtomicInteger();
  private final CountDownLatch releaseStalledBody = new CountDownLatch(1);
  private HttpServer server;
  private volatile int responseStatus = 200;
  private volatile String responseBody;
  private volatile String redirectLocation;
  private volatile long responseDelayMillis;
  private volatile boolean stallBody;
  private volatile boolean stallBeforeFirstByte;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
    responseBody = "{\"tenantId\":\"" + TENANT + "\",\"contactId\":\"" + CONTACT + "\"}";
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer end-user-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @AfterEach
  void stopServer() {
    RequestContextHolder.resetRequestAttributes();
    server.stop(0);
  }

  @Test
  void forwardsCurrentBearerToExactApplicationProofAndHasNoNavigationLink() {
    registry().requireReadable(source, actor);

    assertThat(bearer.get()).isEqualTo("Bearer end-user-token");
    assertThat(path.get())
        .isEqualTo("/api/v1/contacts/" + TENANT + "/" + CONTACT + "/task-source-validation");
    assertThat(registry().href(source, TENANT)).isNull();
  }

  @Test
  void deniedOrMissingContactIsNeutralNotFound() {
    for (final int status : List.of(403, 404)) {
      responseStatus = status;
      assertThatThrownBy(() -> registry().requireReadable(source, actor))
          .describedAs("Application status %s, requests %s", status, requests.get())
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
    }
  }

  @Test
  void mismatchedProofIdsAreNeutralNotFound() {
    for (final String body :
        List.of(
            "{\"tenantId\":\"" + UUID.randomUUID() + "\",\"contactId\":\"" + CONTACT + "\"}",
            "{\"tenantId\":\"" + TENANT + "\",\"contactId\":\"" + UUID.randomUUID() + "\"}")) {
      responseBody = body;
      assertThatThrownBy(() -> registry().requireReadable(source, actor))
          .describedAs("Application proof %s, requests %s", body, requests.get())
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
    }
  }

  @Test
  void upstreamFailureAndInvalidProofFailClosedWithSafe503() {
    responseStatus = 503;
    assertSafeUnavailable();
    responseStatus = 200;
    responseBody = "{\"tenantId\":\"bad\"}";
    assertSafeUnavailable();
  }

  @Test
  void oversizedButOtherwiseValidProofFailsClosed() {
    responseBody = " ".repeat(5000) + responseBody;

    assertSafeUnavailable();
  }

  @Test
  void forbiddenWithLargeResponseRemainsNeutralNotFound() {
    responseStatus = 403;
    responseBody = "x".repeat(5000);

    assertThatThrownBy(() -> registry(5000).requireReadable(source, actor))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Quelle nicht gefunden.");
  }

  @Test
  void forbiddenHeadersRemainNeutralNotFoundWhileErrorBodyIsStalled() {
    responseStatus = 403;
    stallBody = true;

    try {
      assertThatThrownBy(() -> registry(3000).requireReadable(source, actor))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
      assertThat(bodyStarted.get()).isEqualTo(1);
    } finally {
      releaseStalledBody.countDown();
    }
  }

  @Test
  void notFoundHeadersRemainNeutralBeforeFirstErrorBodyByte() {
    responseStatus = 404;
    stallBody = true;
    stallBeforeFirstByte = true;

    final TaskSourceRegistry proofRegistry = registry(3000);
    try {
      assertThatThrownBy(() -> proofRegistry.requireReadable(source, actor))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
      assertThat(bodyStarted.get()).isZero();
    } finally {
      releaseStalledBody.countDown();
    }
  }

  @Test
  void literalNullProofIsMalformedRatherThanMissingContact() {
    responseBody = "null";

    assertSafeUnavailable();
  }

  @Test
  void bodyStallIsBoundedByRequestTimeout() {
    stallBody = true;

    try {
      assertSafeUnavailable(registry(3000));
      assertThat(bodyStarted.get()).isEqualTo(1);
    } finally {
      releaseStalledBody.countDown();
    }
  }

  @Test
  void redirectsAreNeverFollowedWithBearer() {
    responseStatus = 302;
    redirectLocation = baseUrl() + "/redirect-target";

    assertSafeUnavailable();
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  void timeoutFailsClosed() {
    responseDelayMillis = 300;

    assertSafeUnavailable(registry(100));
  }

  @Test
  void missingBearerAndUnlistedBaseNeverSendARequest() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    assertSafeUnavailable();
    assertThat(requests.get()).isZero();

    assertThatThrownBy(
            () ->
                new ContactTaskSourceAdapter(
                    baseUrl(), List.of("http://127.0.0.1:1"), new ObjectMapper(), 100, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests.get()).isZero();
  }

  @Test
  void ambiguousBearerHeadersNeverSendARequest() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer first-token");
    request.addHeader("Authorization", "Bearer second-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    assertSafeUnavailable();
    assertThat(requests.get()).isZero();
  }

  @Test
  void applicationAdapterIsAbsentUnlessExplicitlyEnabledWithAllowlistedBase() {
    final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(ContactTaskSourceAdapter.class);
    runner.run(context -> assertThat(context.getBeansOfType(TaskSourceAdapter.class)).isEmpty());
    runner
        .withPropertyValues(
            "task.source.contact.enabled=true",
            "task.source.contact.application-base-url=" + baseUrl(),
            "task.source.contact.allowed-base-urls=" + baseUrl())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(TaskSourceAdapter.class)).hasSize(1);
            });
  }

  private TaskSourceRegistry registry() {
    return registry(2000);
  }

  private TaskSourceRegistry registry(final int requestTimeoutMillis) {
    return new TaskSourceRegistry(
        List.of(
            new ContactTaskSourceAdapter(
                baseUrl(), List.of(baseUrl()), new ObjectMapper(), 1000, requestTimeoutMillis)));
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void assertSafeUnavailable() {
    assertSafeUnavailable(registry());
  }

  private void assertSafeUnavailable(final TaskSourceRegistry registry) {
    assertThatThrownBy(() -> registry.requireReadable(source, actor))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
        .hasMessageNotContaining("end-user-token");
  }

  private void respond(final HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
    path.set(exchange.getRequestURI().getPath());
    if (responseDelayMillis > 0) {
      try {
        Thread.sleep(responseDelayMillis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (redirectLocation != null) {
      exchange.getResponseHeaders().set("Location", redirectLocation);
    }
    final byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
    try {
      if (stallBody) {
        exchange.sendResponseHeaders(responseStatus, 0);
        exchange.getResponseBody().flush();
        if (!stallBeforeFirstByte) {
          exchange.getResponseBody().write(bytes, 0, 1);
          exchange.getResponseBody().flush();
          bodyStarted.incrementAndGet();
        }
        try {
          releaseStalledBody.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        if (stallBeforeFirstByte) {
          exchange.getResponseBody().write(bytes);
        } else {
          exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
        }
      } else {
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    } catch (IOException clientClosed) {
      // A request timeout closes the client before the fixture responds.
    } finally {
      exchange.close();
    }
  }
}
