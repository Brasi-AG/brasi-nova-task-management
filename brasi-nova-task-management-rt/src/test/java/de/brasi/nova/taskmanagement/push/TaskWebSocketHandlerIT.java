package de.brasi.nova.taskmanagement.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.configuration.WebSocketConfig;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * End-to-End-Integration-Test fuer den WebSocket-Push-Kanal (arch-v2 §6, Codex-BLOCKER 7) gegen
 * echtes MSSQL und einen echten Socket ({@code @SpringBootTest(RANDOM_PORT)} + {@link
 * StandardWebSocketClient}).
 *
 * <p>Beweist, dass der reale After-Commit-Seam ueber die echten {@link WebSocketNotificationPushPort}
 * / {@link WebSocketInboxSignalPort} ({@code @Primary}) auf einer realen Session ankommt:
 *
 * <ul>
 *   <li>{@code push_nach_commit}: ein per REST (Mention-Kommentar) ausgeloestes, committetes
 *       MENTION-Notification-Event erreicht die Empfaenger-Session als {@code notification}-Envelope.
 *   <li>{@code kein_push_bei_rollback}: eine in einer zurueckgerollten Transaktion registrierte
 *       Invalidierung erreicht die Session NICHT; eine danach committete Invalidierung schon
 *       (Positiv-Kontrolle, damit die Abwesenheit nicht an einer toten Session liegt).
 *   <li>{@code snapshot_invalidated_bei_mutation}: ein per REST committeter PATCH erreicht die
 *       Teilnehmer-Session als {@code snapshot-invalidated}-Envelope.
 *   <li>{@code skip_frei_durch_rebootstrap}: nach einer Mutation + Invalidierung liefert ein
 *       frischer Snapshot-Re-Bootstrap alle aktiven Aufgaben genau einmal (kein Skip/Dupe).
 * </ul>
 *
 * <p>{@code /ws} ist im Security-Layer {@code permitAll} (ticket-gated ueber den Handshake-
 * Interceptor); {@code /api/v1/ws-tickets} ist JWT-/authenticated-only (kein {@code TASK_READ}).
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskWebSocketHandlerIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
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
      "ksf.web-security.permit-paths=/actuator/health,/actuator/info,/v3/api-docs/**,/swagger-ui/**,/ws",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class TaskWebSocketHandlerIT {

  private static final String TEST_KID = "ws-handler-it-key";

  /** Mandant A (Klon-Scope 500-599). */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();

  /** USER_A: Akteur/Creator/Empfaenger der Snapshot-Signale. */
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-wsctl@brasi.de";
  private static final long USER_A_DB_ID = 561L;

  /** USER_B: Mention-Ziel (bare login, damit @handle aufloesbar ist). */
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-wsctl";
  private static final long USER_B_DB_ID = 562L;

  /** Deterministischer ID-Block fuer direkt geseedete Tasks (Rebootstrap-Test). */
  private static final long ID_BASE = 5700L;

  private static final ObjectMapper OM = new ObjectMapper();

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
      pemTempFile = Files.createTempFile("ws-handler-it-jwk-", ".pem");
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

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PushRegistry pushRegistry;
  @Autowired private InboxSignalService inboxSignalService;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_A_ID);
  }

  // ---- IT 1: push_nach_commit ----------------------------------------------------------------

  @Test
  @DisplayName(
      "push_nach_commit:"
          + " committeter Mention-Kommentar -> notification-Envelope erreicht die Empfaenger-Session")
  void push_nach_commit() throws Exception {
    final String tokenA =
        jwt(USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ", "task-management:TASK_WRITE");
    // B braucht NOTIFICATION_READ, damit der Notification-Push ihn erreicht (Gate seit Slice 2).
    final String tokenB =
        jwt(USER_B_REF, USER_B_LOGIN, "task-management:TASK_READ", "task-management:NOTIFICATION_READ");

    final UUID taskId = createTask(tokenA, "Push-nach-Commit-Task");

    final WsClientSession b = connect(issueTicket(tokenB));
    try {
      awaitRegistered(USER_B_REF);

      // Mention @user-b-wsctl -> MENTION-Notification an B (committet) -> Push an B-Session
      postComment(tokenA, taskId, "Bitte pruefen.", USER_B_REF);

      final JsonNode frame = awaitFrame(b, 5);
      assertThat(frame.get("type").asText()).isEqualTo(NotificationPushEnvelope.TYPE_NOTIFICATION);
      assertThat(frame.get("category").asText()).isEqualTo(NotificationTypeCodes.MENTION);
      assertThat(frame.get("notificationId").asText()).isNotBlank();
      assertThat(frame.get("payload").get("text").asText()).isNotBlank();
    } finally {
      close(b);
    }
  }

  // ---- IT 1b: WS-Notification-Gate (ohne NOTIFICATION_READ kein notification-Frame) ----------

  @Test
  @DisplayName(
      "ohne_notification_read_kein_notification_frame_aber_snapshot_invalidated:"
          + " TASK_READ-only-Ticket erhaelt trotz echtem MENTION-Event KEINEN notification-Frame,"
          + " aber das task-level snapshot-invalidated-Signal kommt an (Positiv-Kontrolle)")
  void ohne_notification_read_kein_notification_frame() throws Exception {
    final String tokenA =
        jwt(USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ", "task-management:TASK_WRITE");
    // B bewusst OHNE NOTIFICATION_READ -> Ticket traegt notificationRead=false.
    final String tokenB = jwt(USER_B_REF, USER_B_LOGIN, "task-management:TASK_READ");

    final UUID taskId = createTask(tokenA, "Gate-ohne-notif-read-Task");

    final WsClientSession b = connect(issueTicket(tokenB));
    try {
      awaitRegistered(USER_B_REF);

      // Echtes, committetes MENTION-Notification-Event fuer B -> Push WIRD GEGATET (kein Frame).
      postComment(tokenA, taskId, "Bitte pruefen.", USER_B_REF);

      // Positiv-Kontrolle: committete task-level Invalidierung fuer B -> MUSS ankommen.
      final TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.execute(
          status -> {
            inboxSignalService.invalidate(TENANT_A_ID, List.of(USER_B_REF));
            return null;
          });

      // Der erste (und einzige) Frame ist das snapshot-invalidated - NIE ein notification.
      final JsonNode frame = awaitFrame(b, 5);
      assertThat(frame.get("type").asText())
          .as("ohne NOTIFICATION_READ darf kein notification-Frame kommen; nur snapshot-invalidated")
          .isEqualTo("snapshot-invalidated");

      // Kein nachgelagerter notification-Frame im Puffer.
      final String extra = b.queue().poll(1, TimeUnit.SECONDS);
      if (extra != null) {
        assertThat(OM.readTree(extra).get("type").asText())
            .as("kein notification-Frame im Puffer (Gate greift)")
            .isNotEqualTo(NotificationPushEnvelope.TYPE_NOTIFICATION);
      }
    } finally {
      close(b);
    }
  }

  // ---- IT 2: kein_push_bei_rollback (+ Positiv-Kontrolle) ------------------------------------

  @Test
  @DisplayName(
      "kein_push_bei_rollback:"
          + " zurueckgerollte Invalidierung -> kein Frame; danach committete Invalidierung -> Frame")
  void kein_push_bei_rollback() throws Exception {
    final String tokenA = jwt(USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    final WsClientSession a = connect(issueTicket(tokenA));
    try {
      awaitRegistered(USER_A_REF);
      final TransactionTemplate tx = new TransactionTemplate(transactionManager);

      // (negativ) Invalidierung in zurueckgerollter Tx -> afterCommit feuert nicht -> kein Push
      tx.execute(
          status -> {
            inboxSignalService.invalidate(TENANT_A_ID, List.of(USER_A_REF));
            status.setRollbackOnly();
            return null;
          });
      assertThat(a.queue.poll(2, TimeUnit.SECONDS))
          .as("Rollback darf keinen snapshot-invalidated-Frame zustellen")
          .isNull();

      // (positiv) committete Invalidierung ueber dieselbe Session -> Frame kommt an
      tx.execute(
          status -> {
            inboxSignalService.invalidate(TENANT_A_ID, List.of(USER_A_REF));
            return null;
          });
      final JsonNode frame = awaitFrame(a, 5);
      assertThat(frame.get("type").asText())
          .as("Positiv-Kontrolle: Session ist verbunden und empfangsbereit")
          .isEqualTo("snapshot-invalidated");
    } finally {
      close(a);
    }
  }

  // ---- IT 3: snapshot_invalidated_bei_mutation -----------------------------------------------

  @Test
  @DisplayName(
      "snapshot_invalidated_bei_mutation:"
          + " committeter PATCH -> snapshot-invalidated-Envelope an die Teilnehmer-Session")
  void snapshot_invalidated_bei_mutation() throws Exception {
    final String tokenA =
        jwt(USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ", "task-management:TASK_WRITE");
    final UUID taskId = createTask(tokenA, "Mutations-Signal-Task");

    final WsClientSession a = connect(issueTicket(tokenA));
    try {
      awaitRegistered(USER_A_REF);

      // PATCH (committet) invalidiert alle aktiven Teilnehmer inkl. Creator A
      patchTitle(tokenA, taskId, "Geaenderter Titel (Signal)");

      final JsonNode frame = awaitFrame(a, 5);
      assertThat(frame.get("type").asText()).isEqualTo("snapshot-invalidated");
      assertThat(frame.get("version").asText()).isEqualTo("1");
    } finally {
      close(a);
    }
  }

  // ---- IT 4: skip_frei_durch_rebootstrap -----------------------------------------------------

  @Test
  @DisplayName(
      "skip_frei_durch_rebootstrap:"
          + " Mutation -> snapshot-invalidated; frischer Re-Bootstrap listet alle Aufgaben genau einmal")
  void skip_frei_durch_rebootstrap() throws Exception {
    final String tokenA =
        jwt(USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ", "task-management:TASK_WRITE");
    final long openId = statusId("OPEN");
    final long normalId = priorityId("NORMAL");
    final long dirId = directionId("CUSTOMER_INTERNAL");

    // 3 aktive Aufgaben fuer A mit strikt getrennten LAST_ACTIVITY_AT (t0 aelteste .. t2 neueste)
    final List<UUID> seeded = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      final long taskRowId = ID_BASE + 10 + i;
      final UUID uuid = UUID.randomUUID();
      seedTask(jdbcTemplate, taskRowId, uuid, TENANT_A_ID, openId, normalId, dirId,
          LocalDateTime.now().minusMinutes(30 - i), "Rebootstrap-Task-" + i);
      seedParticipant(jdbcTemplate, ID_BASE + 20 + i, taskRowId, USER_A_REF, TENANT_A_ID);
      seedUserState(jdbcTemplate, ID_BASE + 30 + i, taskRowId, USER_A_REF, TENANT_A_ID);
      seeded.add(uuid);
    }

    final WsClientSession a = connect(issueTicket(tokenA));
    try {
      awaitRegistered(USER_A_REF);

      // Bootstrap 1 (asOf1) - nur um den Ausgangszustand zu konsumieren
      enumerateActiveTaskIds(tokenA, 1);

      // Mutation der AELTESTEN Aufgabe -> LAST_ACTIVITY_AT springt nach vorn (Reorder) + Invalidierung
      patchTitle(tokenA, seeded.get(0), "t0 nach vorne gezogen");

      final JsonNode frame = awaitFrame(a, 5);
      assertThat(frame.get("type").asText()).isEqualTo("snapshot-invalidated");

      // Re-Bootstrap (asOf2) mit limit=1 durch alle Seiten -> jede Aufgabe genau einmal, kein Skip
      final List<UUID> walked = enumerateActiveTaskIds(tokenA, 1);
      final Set<UUID> walkedSet = new HashSet<>(walked);
      assertThat(walked)
          .as("Re-Bootstrap darf keine Aufgabe doppelt listen")
          .hasSize(walkedSet.size());
      assertThat(walkedSet)
          .as("Re-Bootstrap muss alle aktiven Aufgaben skip-frei enthalten")
          .containsAll(seeded);
    } finally {
      close(a);
    }
  }

  // ---- WS-/HTTP-Hilfen -----------------------------------------------------------------------

  /** Offene Client-Session plus Empfangs-Queue. */
  private record WsClientSession(WebSocketSession session, BlockingQueue<String> queue) {}

  private WsClientSession connect(final String ticket) throws Exception {
    final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    final StandardWebSocketClient client = new StandardWebSocketClient();
    final WebSocketSession session =
        client
            .execute(
                new TextWebSocketHandler() {
                  @Override
                  protected void handleTextMessage(
                      final WebSocketSession s, final TextMessage message) {
                    queue.add(message.getPayload());
                  }
                },
                "ws://localhost:" + port + "/ws?ticket=" + ticket)
            .get(5, TimeUnit.SECONDS);
    return new WsClientSession(session, queue);
  }

  private void close(final WsClientSession s) throws IOException {
    if (s != null && s.session().isOpen()) {
      s.session().close(CloseStatus.NORMAL);
    }
  }

  /** Wartet, bis die Empfaenger-Session serverseitig registriert ist (killt Handshake-Race). */
  private void awaitRegistered(final UUID userRef) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (!pushRegistry.sessionsFor(TENANT_A_ID, userRef).isEmpty()) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
    throw new AssertionError("Session wurde nicht innerhalb von 5s registriert: " + userRef);
  }

  /** Wartet bis zu {@code seconds} auf den naechsten Frame und parst ihn als JSON. */
  private JsonNode awaitFrame(final WsClientSession s, final int seconds) throws Exception {
    final String payload = s.queue().poll(seconds, TimeUnit.SECONDS);
    assertThat(payload).as("es muss ein Frame innerhalb von " + seconds + "s ankommen").isNotNull();
    return OM.readTree(payload);
  }

  private RestClient rest() {
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory())
        .baseUrl("http://localhost:" + port)
        .build();
  }

  private String issueTicket(final String token) {
    final ResponseEntity<String> resp =
        rest()
            .post()
            .uri("/api/v1/ws-tickets")
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .toEntity(String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    return readJson(resp.getBody()).get("ticket").asText();
  }

  private UUID createTask(final String token, final String title) {
    final ResponseEntity<String> resp =
        rest()
            .post()
            .uri("/api/v1/tasks")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"title\":\"" + title + "\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(201);
    return UUID.fromString(readJson(resp.getBody()).get("id").asText());
  }

  private void postComment(
      final String token, final UUID taskId, final String text, final UUID... mentions) {
    final StringBuilder body = new StringBuilder("{\"text\":\"").append(text).append("\"");
    if (mentions.length > 0) {
      body.append(",\"mentions\":[");
      for (int i = 0; i < mentions.length; i++) {
        if (i > 0) {
          body.append(",");
        }
        body.append("\"").append(mentions[i]).append("\"");
      }
      body.append("]");
    }
    body.append("}");
    final ResponseEntity<String> resp =
        rest()
            .post()
            .uri("/api/v1/tasks/" + taskId + "/comments")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body.toString())
            .retrieve()
            .toEntity(String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(201);
  }

  private void patchTitle(final String token, final UUID taskId, final String title) {
    final ResponseEntity<Void> resp =
        rest()
            .patch()
            .uri("/api/v1/tasks/" + taskId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"title\":\"" + title + "\"}")
            .retrieve()
            .toBodilessEntity();
    assertThat(resp.getStatusCode().value()).isEqualTo(204);
  }

  /** Laeuft GET /tasks?limit=.. vollstaendig durch (Keyset) und sammelt alle Aufgaben-UUIDs. */
  private List<UUID> enumerateActiveTaskIds(final String token, final int limit) {
    final List<UUID> ids = new ArrayList<>();
    String cursor = null;
    while (true) {
      final String uri =
          "/api/v1/tasks?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
      final ResponseEntity<String> resp =
          rest()
              .get()
              .uri(uri)
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .toEntity(String.class);
      assertThat(resp.getStatusCode().value()).isEqualTo(200);
      final JsonNode page = readJson(resp.getBody());
      for (final JsonNode item : page.get("items")) {
        ids.add(UUID.fromString(item.get("id").asText()));
      }
      if (!page.get("hasMore").asBoolean() || page.get("nextCursor").isNull()) {
        return ids;
      }
      cursor = page.get("nextCursor").asText();
    }
  }

  private static JsonNode readJson(final String json) {
    try {
      return OM.readTree(json);
    } catch (IOException e) {
      throw new RuntimeException("JSON nicht parsebar: " + json, e);
    }
  }

  // ---- Seeds / JWT ---------------------------------------------------------------------------

  private long statusId(final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[DIC_TASK_STATUS] WHERE CODE = ?", Long.class, code);
  }

  private long priorityId(final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[DIC_TASK_PRIORITY] WHERE CODE = ?", Long.class, code);
  }

  private long directionId(final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[DIC_TASK_DIRECTION] WHERE CODE = ?", Long.class, code);
  }

  private static void seedTask(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long tenantId,
      final long statusId,
      final long priorityId,
      final long directionId,
      final LocalDateTime lastActivityAt,
      final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'ACTIVE', 'ws-handler-it', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0)",
        id, id, Timestamp.valueOf(lastActivityAt), externalId, tenantId, title,
        statusId, priorityId, directionId, tenantId, Timestamp.valueOf(lastActivityAt));
  }

  private static void seedParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID participantRef,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND) "
            + "VALUES (?, 'ACTIVE', 'ws-handler-it', getdate(), ?, 1, ?, ?, ?, 'PERSON', ?, 1, 1, 1, NULL)",
        id, id, UUID.randomUUID(), tenantId, taskId, participantRef, tenantId);
  }

  private static void seedUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID userRef,
      final long tenantId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) "
            + "VALUES (?, 'ACTIVE', 'ws-handler-it', getdate(), ?, 1, ?, ?, ?, ?, 0, 0, 0, NULL)",
        id, id, UUID.randomUUID(), tenantId, taskId, userRef, tenantId);
  }

  private static String jwt(
      final UUID userUuid, final String loginName, final String... authorities) {
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
        .claim("lastActiveTenantId", TENANT_A_UUID.toString())
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
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.time",
        "de.brasi.nova.taskmanagement.paging",
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
    WebSocketServletAutoConfiguration.class,
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
    WebSocketConfig.class,
    KsfWebSecurityAutoConfiguration.class,
    SecurityContextUserAccessor.class,
    WhoAmIProbeController.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "ws-handler-it";
    }
  }
}
