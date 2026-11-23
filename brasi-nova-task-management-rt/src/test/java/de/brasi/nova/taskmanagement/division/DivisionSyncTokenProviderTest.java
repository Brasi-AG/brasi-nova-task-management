package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Prueft {@link DivisionSyncTokenProvider} (Intake #190, Task 2) gegen einen {@link
 * MockRestServiceServer} mit verstellbarer Uhr: client_credentials mit Basic Auth und Scope, Token
 * im Cache bis 30 s vor {@code expires_in}.
 *
 * <p>Angenommener Test-Seam: paketprivater Konstruktor {@code (DivisionSyncProperties,
 * RestTemplate, Clock)}.
 */
class DivisionSyncTokenProviderTest {

  private static final String TOKEN_URL = "https://identity.example.test/oauth2/token";
  private static final String CLIENT_ID = "tm-test-client";
  private static final String CLIENT_SECRET = "test-secret-value";
  private static final String SCOPE = "masterdata.sync.read";

  private MutableClock clock;
  private MockRestServiceServer server;
  private DivisionSyncTokenProvider provider;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    final RestTemplate restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    provider = new DivisionSyncTokenProvider(properties(), restTemplate, clock);
  }

  @Test
  void i190_token_cached_until_expiry_margin() {
    server
        .expect(once(), requestTo(TOKEN_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(tokenJson("tok-1", 300), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(TOKEN_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(tokenJson("tok-2", 300), MediaType.APPLICATION_JSON));

    assertThat(provider.accessToken()).isEqualTo("tok-1");
    assertThat(provider.accessToken()).as("zweiter Aufruf aus dem Cache").isEqualTo("tok-1");

    clock.advance(Duration.ofSeconds(269));
    assertThat(provider.accessToken()).as("vor der 30-s-Marge noch im Cache").isEqualTo("tok-1");

    clock.advance(Duration.ofSeconds(2)); // 271 s nach dem Abruf: innerhalb der Marge
    assertThat(provider.accessToken()).as("nach Ablauf der Marge neu geholt").isEqualTo("tok-2");

    server.verify();
  }

  @Test
  void i190_token_request_uses_basic_auth_and_scope() {
    final String basic =
        Base64.getEncoder()
            .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
    final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("scope", SCOPE);

    server
        .expect(once(), requestTo(TOKEN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().formData(form))
        .andRespond(withSuccess(tokenJson("tok-1", 300), MediaType.APPLICATION_JSON));

    assertThat(provider.accessToken()).isEqualTo("tok-1");

    server.verify();
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static String tokenJson(final String accessToken, final int expiresIn) {
    return "{\"access_token\":\""
        + accessToken
        + "\",\"token_type\":\"Bearer\",\"expires_in\":"
        + expiresIn
        + "}";
  }

  private static DivisionSyncProperties properties() {
    return new DivisionSyncProperties(
        true,
        "jms.topic.brasi-nova.dev-ui.divisions.DivisionMembershipChanged",
        "task-management.division-membership",
        "https://application.example.test",
        new DivisionSyncProperties.OAuth2(TOKEN_URL, CLIENT_ID, CLIENT_SECRET, SCOPE),
        5,
        new DivisionSyncProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(15)),
        new DivisionSyncProperties.Resync("0 30 3 * * *"),
        Duration.ofDays(30));
  }

  /** Verstellbare Uhr fuer deterministische Ablaufpruefung (Muster WebSocketTicketServiceTest). */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(final Instant start) {
      this.now = start;
    }

    private void advance(final Duration delta) {
      this.now = this.now.plus(delta);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }
  }
}
