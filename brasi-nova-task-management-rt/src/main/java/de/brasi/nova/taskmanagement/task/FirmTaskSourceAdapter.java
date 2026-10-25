package de.brasi.nova.taskmanagement.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.exception.EntityNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Firm read proof through Application. Registration requires an explicit profile config gate. */
@Component
@ConditionalOnProperty(name = "task.source.firm.enabled", havingValue = "true")
public final class FirmTaskSourceAdapter implements TaskSourceAdapter {
  private static final int MAX_PROOF_BYTES = 4096;
  private final URI baseUrl;
  private final ObjectMapper objectMapper;
  private final HttpClient client;
  private final Duration requestTimeout;

  @Autowired
  public FirmTaskSourceAdapter(
      @Value("${task.source.firm.application-base-url:}") final String baseUrl,
      @Value("${task.source.firm.allowed-base-urls:}") final String allowedBaseUrls,
      final ObjectMapper objectMapper) {
    this(baseUrl, Arrays.asList(allowedBaseUrls.split(",")), objectMapper, 1500, 2500);
  }

  FirmTaskSourceAdapter(
      final String baseUrl,
      final List<String> allowedBaseUrls,
      final ObjectMapper objectMapper,
      final int connectTimeoutMillis,
      final int requestTimeoutMillis) {
    this.baseUrl = serviceBase(baseUrl);
    if (allowedBaseUrls.stream().map(String::trim).noneMatch(this.baseUrl.toString()::equals)) {
      throw new IllegalArgumentException("Application service base is not allowlisted.");
    }
    if (connectTimeoutMillis < 1 || requestTimeoutMillis < 1) {
      throw new IllegalArgumentException("Application proof timeouts must be positive.");
    }
    this.objectMapper = objectMapper;
    this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public String module() {
    return "application";
  }

  @Override
  public String entityType() {
    return "firm";
  }

  @Override
  public void requireReadable(final CurrentActor actor, final UUID entityRef) {
    final UUID tenantUuid = actor.tenant().uuid();
    // A Firm source represents the active tenant's own Firm, not an arbitrary Firm in it.
    if (!tenantUuid.equals(entityRef)) {
      throw new EntityNotFoundException("Quelle nicht gefunden.");
    }
    final String bearer = currentBearer();
    final URI uri =
        baseUrl.resolve(
            "/api/v1/firms/" + tenantUuid + "/" + entityRef + "/task-source-validation");
    final HttpRequest request =
        HttpRequest.newBuilder(uri)
            .GET()
            .header("Authorization", bearer)
            .header("Accept", "application/json")
            .timeout(requestTimeout)
            .build();
    final CompletableFuture<HttpResponse<ProofBody>> exchange =
        client.sendAsync(
            request,
            responseInfo ->
                responseInfo.statusCode() == 200
                    ? HttpResponse.BodySubscribers.mapping(
                        new BoundedProofSubscriber(), bytes -> new ProofBody(bytes, null))
                    : HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodySubscribers.ofInputStream(),
                        stream -> new ProofBody(null, stream)));
    final HttpResponse<ProofBody> response;
    try {
      response = exchange.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException interrupted) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (final ExecutionException | TimeoutException unavailable) {
      exchange.cancel(true);
      throw unavailable();
    }
    try {
      if (response.statusCode() == 403 || response.statusCode() == 404) {
        throw new EntityNotFoundException("Quelle nicht gefunden.");
      }
      if (response.statusCode() != 200) {
        throw unavailable();
      }
      final FirmProof proof;
      try {
        proof = objectMapper.readValue(response.body().bytes(), FirmProof.class);
      } catch (final IOException malformed) {
        throw unavailable();
      }
      if (proof == null || proof.tenantId() == null || proof.firmId() == null) {
        throw unavailable();
      }
      if (!tenantUuid.equals(proof.tenantId()) || !entityRef.equals(proof.firmId())) {
        throw new EntityNotFoundException("Quelle nicht gefunden.");
      }
    } finally {
      if (response.body().errorStream() != null) {
        try {
          response.body().errorStream().close();
        } catch (final IOException ignored) {
          // The status is already known; the upstream error body is never part of the contract.
        }
      }
    }
  }

  @Override
  public String href(final UUID tenantUuid, final UUID entityRef) {
    // The existing Firm detail route does not yet enforce the proof's read gate.
    return null;
  }

  private static String currentBearer() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      throw unavailable();
    }
    final Enumeration<String> headers = attrs.getRequest().getHeaders("Authorization");
    if (!headers.hasMoreElements()) {
      throw unavailable();
    }
    final String value = headers.nextElement();
    if (headers.hasMoreElements() || !value.matches("Bearer [A-Za-z0-9._~+/-]+={0,2}")) {
      throw unavailable();
    }
    return value;
  }

  private static URI serviceBase(final String value) {
    try {
      final URI uri = new URI(value);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        throw new IllegalArgumentException("Invalid Application service base.");
      }
      return uri;
    } catch (final URISyntaxException invalid) {
      throw new IllegalArgumentException("Invalid Application service base.");
    }
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Quellpruefung nicht verfuegbar.");
  }

  /** Keep the error stream open until the response future has completed, then cancel it. */
  private record ProofBody(byte[] bytes, InputStream errorStream) {}

  /** Cancels the HTTP subscription before an upstream response can exceed the proof contract. */
  private static final class BoundedProofSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletableFuture<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(final List<ByteBuffer> chunks) {
      for (final ByteBuffer chunk : chunks) {
        if (chunk.remaining() > MAX_PROOF_BYTES - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new IOException("Application proof exceeds byte limit."));
          return;
        }
        final byte[] next = new byte[chunk.remaining()];
        chunk.get(next);
        bytes.writeBytes(next);
      }
      subscription.request(1);
    }

    @Override
    public void onError(final Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }

  private record FirmProof(UUID tenantId, UUID firmId) {}
}
