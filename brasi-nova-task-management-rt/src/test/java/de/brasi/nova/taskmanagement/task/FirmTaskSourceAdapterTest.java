package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.model.TaskSourceInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

class FirmTaskSourceAdapterTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID FIRM = TENANT;
  private static final UUID FOREIGN_FIRM = UUID.randomUUID();
  private final CurrentActor actor =
      new CurrentActor(
          new TenantInfo(TENANT, 500L), new AuthUser(UUID.randomUUID(), "actor@test.invalid"));
  private final TaskSourceInput source = new TaskSourceInput("application", "firm", FIRM);
  private TaskSourceProofServer server;

  @BeforeEach
  void startServer() throws IOException {
    server =
        TaskSourceProofServer.start(
            "{\"tenantId\":\"" + TENANT + "\",\"firmId\":\"" + FIRM + "\"}");
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer end-user-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @AfterEach
  void stopServer() {
    RequestContextHolder.resetRequestAttributes();
    server.close();
  }

  @Test
  void forwardsCurrentBearerToExactApplicationProofAndHasNoNavigationLink() {
    registry().requireReadable(source, actor);

    assertThat(server.bearer()).isEqualTo("Bearer end-user-token");
    assertThat(server.path())
        .isEqualTo("/api/v1/firms/" + TENANT + "/" + FIRM + "/task-source-validation");
    assertThat(registry().href(source, TENANT)).isNull();
  }

  @Test
  void foreignFirmIdIsNeutralNotFoundWithoutOutboundRequest() {
    final TaskSourceInput foreign = new TaskSourceInput("application", "firm", FOREIGN_FIRM);

    assertThatThrownBy(() -> registry().requireReadable(foreign, actor))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Quelle nicht gefunden.");
    assertThat(server.requests()).isZero();
  }

  @Test
  void deniedOrMissingFirmIsNeutralNotFound() {
    for (final int status : List.of(403, 404)) {
      server.status(status);
      assertThatThrownBy(() -> registry().requireReadable(source, actor))
          .describedAs("Application status %s, requests %s", status, server.requests())
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
    }
  }

  @Test
  void mismatchedProofIdsAreNeutralNotFound() {
    for (final String body :
        List.of(
            "{\"tenantId\":\"" + UUID.randomUUID() + "\",\"firmId\":\"" + FIRM + "\"}",
            "{\"tenantId\":\"" + TENANT + "\",\"firmId\":\"" + UUID.randomUUID() + "\"}")) {
      server.body(body);
      assertThatThrownBy(() -> registry().requireReadable(source, actor))
          .describedAs("Application proof %s, requests %s", body, server.requests())
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
    }
  }

  @Test
  void upstreamFailureAndInvalidProofFailClosedWithSafe503() {
    server.status(503);
    assertSafeUnavailable();
    server.status(200);
    server.body("{\"tenantId\":\"bad\"}");
    assertSafeUnavailable();
  }

  @Test
  void oversizedButOtherwiseValidProofFailsClosed() {
    server.body(" ".repeat(5000) + server.body());

    assertSafeUnavailable();
  }

  @Test
  void forbiddenWithLargeResponseRemainsNeutralNotFound() {
    server.status(403);
    server.body("x".repeat(5000));

    assertThatThrownBy(() -> registry(5000).requireReadable(source, actor))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Quelle nicht gefunden.");
  }

  @Test
  void forbiddenHeadersRemainNeutralNotFoundWhileErrorBodyIsStalled() throws InterruptedException {
    server.status(403);
    server.stallBody(false);

    try {
      assertThatThrownBy(() -> registry(3000).requireReadable(source, actor))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
      server.assertStalledHeadersSent();
    } finally {
      server.releaseStalledBody();
    }
  }

  @Test
  void notFoundHeadersRemainNeutralBeforeFirstErrorBodyByte() throws InterruptedException {
    server.status(404);
    server.stallBody(true);

    final TaskSourceRegistry proofRegistry = registry(3000);
    try {
      assertThatThrownBy(() -> proofRegistry.requireReadable(source, actor))
          .isInstanceOf(EntityNotFoundException.class)
          .hasMessage("Quelle nicht gefunden.");
      server.assertStalledHeadersSent();
    } finally {
      server.releaseStalledBody();
    }
  }

  @Test
  void literalNullProofIsMalformedRatherThanMissingFirm() {
    server.body("null");

    assertSafeUnavailable();
  }

  @Test
  void bodyStallIsBoundedByRequestTimeout() throws InterruptedException {
    server.stallBody(false);

    try {
      assertSafeUnavailable(registry(3000));
      server.assertFirstBodyByteSent();
    } finally {
      server.releaseStalledBody();
    }
  }

  @Test
  void redirectsAreNeverFollowedWithBearer() {
    server.status(302);
    server.redirectTo(baseUrl() + "/redirect-target");

    assertSafeUnavailable();
    assertThat(server.requests()).isEqualTo(1);
  }

  @Test
  void timeoutFailsClosed() {
    server.delayMillis(300);

    assertSafeUnavailable(registry(100));
  }

  @Test
  void missingBearerAndUnlistedBaseNeverSendARequest() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    assertSafeUnavailable();
    assertThat(server.requests()).isZero();

    assertThatThrownBy(
            () ->
                new FirmTaskSourceAdapter(
                    baseUrl(), List.of("http://127.0.0.1:1"), new ObjectMapper(), 100, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server.requests()).isZero();
  }

  @Test
  void ambiguousBearerHeadersNeverSendARequest() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer first-token");
    request.addHeader("Authorization", "Bearer second-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    assertSafeUnavailable();
    assertThat(server.requests()).isZero();
  }

  @Test
  void applicationAdapterIsAbsentUnlessExplicitlyEnabledWithAllowlistedBase() {
    final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(FirmTaskSourceAdapter.class);
    runner.run(context -> assertThat(context.getBeansOfType(TaskSourceAdapter.class)).isEmpty());
    runner
        .withPropertyValues(
            "task.source.firm.enabled=true",
            "task.source.firm.application-base-url=" + baseUrl(),
            "task.source.firm.allowed-base-urls=" + baseUrl())
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
            new FirmTaskSourceAdapter(
                baseUrl(), List.of(baseUrl()), new ObjectMapper(), 1000, requestTimeoutMillis)));
  }

  private String baseUrl() {
    return server.baseUrl();
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
}
