package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import io.jsonwebtoken.Jwts;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer die WebSocket-Einmal-Ticket-Schicht (arch-v2 §6, Codex-BLOCKER 7).
 *
 * <p>Verbindet die reale HTTP-Ausgabe ({@code POST /api/v1/ws-tickets}, JWT, authenticated-only) mit
 * der realen Handshake-Einloesung ({@link WsTicketHandshakeInterceptor} + {@link
 * WebSocketTicketService} als Context-Beans). Beweist:
 *
 * <ul>
 *   <li>Zwei gleichzeitige {@code consume}s desselben per HTTP ausgegebenen Tickets -> genau ein
 *       Erfolg (atomare {@code ConcurrentHashMap.remove}-Einmaligkeit).
 *   <li>Ticket-Ausgabe anonym -> 401, authentifiziert-ohne-Grant -> 200 (authenticated-only);
 *       verbrauchtes (Replay) und abgelaufenes Ticket -> Handshake-Abbruch mit 401 (kein JWT in
 *       der URL).
 * </ul>
 *
 * <p>Die injizierte {@link Clock} ({@link MutableClock}) macht den Ablauftest deterministisch ohne
 * Wartezeit; sie ersetzt nur den Zeit-Seam der realen {@link WebSocketTicketService}, nicht deren
 * atomare Einloese-Logik.
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = WebSocketTicketIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class WebSocketTicketIT {

  private static final String TEST_KID = "ws-ticket-it-key";

  /** Mandant A (Klon-Scope 500-599). */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-wsticket@brasi.de";
  private static final long USER_A_DB_ID = 561L;

  /** Ticket-TTL fuer diesen IT (gross genug, dass nur der Ablauftest per Clock-Advance ablaeuft). */
  private static final long TTL_SECONDS = 30L;

  /** Fester Startzeitpunkt der injizierten Uhr; je Test ueber {@link #resetClock()} zurueckgesetzt. */
  private static final Instant BASE_INSTANT = Instant.parse("2026-10-18T10:00:00Z");

  /** Geteilte, testgesteuerte Uhr des realen {@link WebSocketTicketService}-Context-Beans. */
  private static final MutableClock CLOCK = new MutableClock(BASE_INSTANT);

  private static final KeyPair RSA_KEY_PAIR;

  static {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static Path pemTempFile;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    registry.add("spring.datasource.url", () -> RefSchemaItDb.throwawayJdbcUrl(dbName));
    registry.add("spring.datasource.username", RefSchemaItEnv::user);
    registry.add("spring.datasource.password", RefSchemaItEnv::password);
    registry.add(
        "spring.datasource.driver-class-name",
        () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");

    final String pemContent = toPem(RSA_KEY_PAIR.getPublic().getEncoded());
    try {
      pemTempFile = Files.createTempFile("ws-ticket-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
  }

  @AfterAll
  static void teardown() {
    try {
      if (master != null && dbName != null) {
        RefSchemaItDb.dropThrowawayDb(master, dbName);
      }
    } finally {
      if (master != null) {
        master.close();
      }
      if (pemTempFile != null) {
        try {
          Files.deleteIfExists(pemTempFile);
        } catch (IOException ignored) {
          // Cleanup best-effort
        }
      }
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private WsTicketHandshakeInterceptor interceptor;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
  }

  @BeforeEach
  void resetClock() {
    CLOCK.reset(BASE_INSTANT);
  }

  // ---- IT 1: zwei gleichzeitige consume des HTTP-Tickets -> genau ein Erfolg -------------------

  @Test
  @DisplayName(
      "zwei_gleichzeitige_consume_genau_ein_erfolg:"
          + " POST /ws-tickets (TASK_READ) 200 -> zwei parallele Handshake-consume,"
          + " genau einer erfolgreich (atomare Einmaligkeit), Attribute gesetzt")
  void zwei_gleichzeitige_consume_genau_ein_erfolg() throws Exception {
    final MockMvc mvc = buildMvc();
    final String token = issueTicketViaHttp(mvc);

    final String uri = "http://host/ws?ticket=" + token;
    final CountDownLatch startGate = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(2);
    final AtomicInteger successes = new AtomicInteger();
    final List<Map<String, Object>> successAttributes = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 2; i++) {
        pool.submit(
            () -> {
              final Map<String, Object> attributes = new HashMap<>();
              try {
                startGate.await();
                final boolean allowed =
                    interceptor.beforeHandshake(
                        requestWithUri(uri), mock(ServerHttpResponse.class), null, attributes);
                if (allowed) {
                  successes.incrementAndGet();
                  successAttributes.add(attributes);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      startGate.countDown();
      assertThat(done.await(10, TimeUnit.SECONDS)).as("beide consume-Versuche fertig").isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(successes.get())
        .as("genau ein gleichzeitiger consume darf das Einmal-Ticket gewinnen")
        .isEqualTo(1);
    assertThat(successAttributes).hasSize(1);
    final Map<String, Object> attrs = successAttributes.get(0);
    assertThat(attrs.get(TaskWebSocketHandler.ATTR_TENANT_ID))
        .as("tenantId-Attribut aus dem aufgeloesten Principal")
        .isEqualTo(TENANT_A_ID);
    assertThat(attrs.get(TaskWebSocketHandler.ATTR_USER_REF))
        .as("userRef-Attribut aus dem aufgeloesten Principal")
        .isEqualTo(USER_A_REF);
  }

  // ---- IT 2: Ausgabe-AuthZ (401/403) + Replay-401 + Expiry-401 --------------------------------

  @Test
  @DisplayName(
      "ausgabe_anonym_401_ohne_grant_200_replay_expiry_401:"
          + " Ausgabe anonym 401 / authentifiziert-ohne-Grant 200 (authenticated-only);"
          + " verbrauchtes (Replay) und abgelaufenes Ticket -> Handshake 401")
  void ausgabe_anonym_401_ohne_grant_200_replay_expiry_401() throws Exception {
    final MockMvc mvc = buildMvc();

    // (401) Ticket-Ausgabe anonym -> 401
    mvc.perform(post("/api/v1/ws-tickets")).andExpect(status().isUnauthorized());

    // (200) Ticket-Ausgabe authentifiziert OHNE task-management-Grant -> authenticated-only -> 200.
    //       Ein WS-Ticket fuer die eigene Inbox ist eine Basis-Faehigkeit jedes angemeldeten Nutzers.
    final String noGrant = jwt(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN);
    mvc.perform(post("/api/v1/ws-tickets").header("Authorization", "Bearer " + noGrant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket").isNotEmpty());

    // (Replay-401) gueltiges Ticket einmal einloesen -> true; zweites Mal -> 401 + false
    final String replayToken = issueTicketViaHttp(mvc);
    final String replayUri = "http://host/ws?ticket=" + replayToken;
    assertThat(
            interceptor.beforeHandshake(
                requestWithUri(replayUri), mock(ServerHttpResponse.class), null, new HashMap<>()))
        .as("erster Handshake verbraucht das Ticket")
        .isTrue();
    final ServerHttpResponse replayResponse = mock(ServerHttpResponse.class);
    assertThat(
            interceptor.beforeHandshake(
                requestWithUri(replayUri), replayResponse, null, new HashMap<>()))
        .as("Replay desselben Tickets wird abgelehnt")
        .isFalse();
    verify(replayResponse).setStatusCode(HttpStatus.UNAUTHORIZED);

    // (Expiry-401) frisches Ticket, Uhr ueber die TTL vorstellen -> Handshake 401 + false
    final String expiredToken = issueTicketViaHttp(mvc);
    CLOCK.advance(Duration.ofSeconds(TTL_SECONDS + 1));
    final ServerHttpResponse expiredResponse = mock(ServerHttpResponse.class);
    assertThat(
            interceptor.beforeHandshake(
                requestWithUri("http://host/ws?ticket=" + expiredToken),
                expiredResponse,
                null,
                new HashMap<>()))
        .as("abgelaufenes Ticket wird abgelehnt")
        .isFalse();
    verify(expiredResponse).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  /** Gibt ueber die reale REST-Schicht (JWT/TASK_READ) ein Ticket aus und liefert den Token. */
  private String issueTicketViaHttp(final MockMvc mvc) throws Exception {
    final String token = jwt(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    final String json =
        mvc.perform(post("/api/v1/ws-tickets").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticket").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = new ObjectMapper().readTree(json);
    final String ticket = root.get("ticket").asText();
    assertThat(ticket).isNotBlank();
    return ticket;
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static ServerHttpRequest requestWithUri(final String uri) {
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getURI()).thenReturn(URI.create(uri));
    return request;
  }

  private static String jwt(
      final UUID tenantUuid,
      final UUID userUuid,
      final String loginName,
      final String... authorities) {
    final String profileJson =
        "{\"userId\":\""
            + userUuid
            + "\",\"username\":\""
            + loginName
            + "\",\"firstName\":\"IT\",\"lastName\":\"Test\","
            + "\"is2faEnabled\":false,\"is2faVerified\":true}";

    final Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .keyId(TEST_KID)
        .and()
        .subject(loginName)
        .issuedAt(Date.from(now.minusSeconds(60)))
        .expiration(Date.from(now.plusSeconds(600)))
        .claim("lastActiveTenantId", tenantUuid.toString())
        .claim("profile", profileJson)
        .claim("authorities", List.of(authorities))
        .claim("global", List.of())
        .claim("byTenant", Map.of())
        .signWith(RSA_KEY_PAIR.getPrivate())
        .compact();
  }

  private static void seedFirmaIfAbsent(
      final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, GUELTIG_VON) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void seedBenutzerIfAbsent(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final String loginName,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, LOGIN_NAME, VOLLER_NAME) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id, id, externalId, tenantId, loginName);
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  /** Testgesteuerte, thread-sichere Uhr mit vorstellbarem Zeitpunkt. */
  private static final class MutableClock extends Clock {
    private volatile Instant now;

    private MutableClock(final Instant start) {
      this.now = start;
    }

    private void reset(final Instant start) {
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

  // ---- IT-Konfigurations-Context ------------------------------------------------------------

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @org.springframework.context.annotation.ComponentScan(
      basePackages = {
        "de.brasi.nova.taskmanagement.resolver",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
      },
      excludeFilters = {
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ANNOTATION,
            classes = org.springframework.boot.SpringBootConfiguration.class)
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class,
    KsfPersistenceAutoConfiguration.class,
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    TaskExecutionAutoConfiguration.class,
    ErrorMvcAutoConfiguration.class,
    SecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    KsfSecurityAutoConfiguration.class,
    KsfIntegrationAutoConfiguration.class,
    AopAutoConfiguration.class,
    io.keel.framework.web.autoconfigure.NovaJacksonInfrastructureAutoConfiguration.class,
    io.keel.framework.web.autoconfigure.NovaExceptionHandlingAutoConfiguration.class,
  })
  @Import({
    KsfWebSecurityAutoConfiguration.class,
    SecurityContextUserAccessor.class,
    WhoAmIProbeController.class,
  })
  static class ItConfig {

    /**
     * Ersetzt den component-gescannten {@link WebSocketTicketService} durch eine Instanz mit
     * testgesteuerter Uhr; so bleibt der Ablauftest deterministisch ohne Wartezeit. Die atomare
     * Einloese-Logik der realen Klasse bleibt unveraendert.
     */
    @Bean
    @Primary
    WebSocketTicketService testClockWebSocketTicketService() {
      return new WebSocketTicketService(TTL_SECONDS, CLOCK);
    }

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "ws-ticket-it";
    }
  }
}
