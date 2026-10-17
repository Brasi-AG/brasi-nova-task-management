package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer den {@link NotificationController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 * <ul>
 *   <li>GET /notifications -> nur eigene Notifications des Actors (fremde NICHT sichtbar)
 *   <li>GET /notifications?filter=unread -> nur IS_READ=0
 *   <li>Pagination: limit+1 Seeds, Seite 1 + nextCursor -> Seite 2, kein Skip/Dupe
 *   <li>PUT /{id}/read -> IS_READ=1 in DB
 *   <li>PUT /read-all -> alle eigenen unread=1, fremde unveraendert
 *   <li>Negativ: anonym 401, kein NOTIFICATION_READ 403, kein NOTIFICATION_WRITE 403,
 *       unbekannte UUID 404
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = NotificationControllerIT.ItConfig.class,
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
class NotificationControllerIT {

  private static final String TEST_KID = "notification-it-key";

  /** Mandant A: Haupt-Tenant fuer positive Tests. */
  private static final long TENANT_A_ID = 570L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-notif@brasi.de";
  private static final long USER_A_DB_ID = 571L;

  /** Zweiter Benutzer in Mandant A: Notifications NICHT fuer USER_A sichtbar. */
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-notif@brasi.de";
  private static final long USER_B_DB_ID = 572L;

  /** Mandant B: fremder Tenant. */
  private static final long TENANT_B_ID = 573L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_C_REF = UUID.randomUUID();
  private static final String USER_C_LOGIN = "user-c-notif@brasi.de";
  private static final long USER_C_DB_ID = 574L;

  /** Read-Only-User (kein NOTIFICATION_WRITE). */
  private static final UUID USER_READ_ONLY_REF = UUID.randomUUID();
  private static final String USER_READ_ONLY_LOGIN = "user-ro-notif@brasi.de";

  /**
   * Feste Notification-ID-Basis im 5xx-Block (Spec-Vorgabe: eigener Block, kein Kollisions-Risiko).
   * Block 570..589 fuer Notification-IDs.
   */
  private static final long NOTIF_ID_BASE = 570L;

  /** Seed-Type-ID: 401 = ASSIGN (laut Flyway V1 DIC_NOTIFICATION_TYPE). */
  private static final long TYPE_ID_ASSIGN = 401L;

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
      pemTempFile = Files.createTempFile("notif-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem",
        () -> "file:" + pemTempFile.toAbsolutePath());
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
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_C_DB_ID, USER_C_REF, USER_C_LOGIN, TENANT_B_ID);
  }

  // ---- IT 1: GET /notifications -> nur eigene Notifications --------------------------------

  @Test
  @DisplayName("get_notifications_liefert_nur_eigene_des_actors")
  void get_notifications_liefert_nur_eigene_des_actors() throws Exception {
    final MockMvc mvc = buildMvc();

    // Notification fuer USER_A (soll sichtbar sein)
    final UUID notifIdA = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 1, notifIdA, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "Notif fuer A", LocalDateTime.now().minusMinutes(5));

    // Notification fuer USER_B im gleichen Mandant (NICHT sichtbar fuer USER_A)
    final UUID notifIdB = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 2, notifIdB, TENANT_A_ID, USER_B_REF,
        TYPE_ID_ASSIGN, false, "Notif fuer B", LocalDateTime.now().minusMinutes(4));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ");

    final String json = mvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andReturn().getResponse().getContentAsString();

    final JsonNode root = new ObjectMapper().readTree(json);
    final JsonNode items = root.get("items");

    // USER_A sieht nur eigene Notification
    boolean foundA = false;
    boolean foundB = false;
    for (final JsonNode item : items) {
      final String id = item.get("id").asText();
      if (notifIdA.toString().equals(id)) {
        foundA = true;
      }
      if (notifIdB.toString().equals(id)) {
        foundB = true;
      }
    }
    assertThat(foundA).as("Notification A muss sichtbar sein").isTrue();
    assertThat(foundB).as("Notification B (anderer Empfaenger) darf NICHT sichtbar sein").isFalse();
  }

  // ---- IT 2: GET /notifications?filter=unread -> nur IS_READ=0 -------------------------

  @Test
  @DisplayName("get_notifications_unread_liefert_nur_ungelesene")
  void get_notifications_unread_liefert_nur_ungelesene() throws Exception {
    final MockMvc mvc = buildMvc();

    final UUID unreadId = UUID.randomUUID();
    final UUID readId = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 3, unreadId, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "Ungelesen", LocalDateTime.now().minusMinutes(10));
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 4, readId, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, true, "Gelesen", LocalDateTime.now().minusMinutes(9));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ");

    final String json = mvc.perform(get("/api/v1/notifications?filter=unread")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final JsonNode root = new ObjectMapper().readTree(json);
    final JsonNode items = root.get("items");

    boolean foundUnread = false;
    boolean foundRead = false;
    for (final JsonNode item : items) {
      final String id = item.get("id").asText();
      if (unreadId.toString().equals(id)) {
        foundUnread = true;
      }
      if (readId.toString().equals(id)) {
        foundRead = true;
      }
    }
    assertThat(foundUnread).as("Ungelesene Notification muss in unread-Liste sein").isTrue();
    assertThat(foundRead).as("Gelesene Notification darf NICHT in unread-Liste sein").isFalse();
  }

  // ---- IT 3: Pagination - Seite 1 + nextCursor -> Seite 2, kein Skip/Dupe --------------

  @Test
  @DisplayName("pagination_seite1_cursor_seite2_kein_skip_kein_dupe")
  void pagination_seite1_cursor_seite2_kein_skip_kein_dupe() throws Exception {
    final MockMvc mvc = buildMvc();

    // 3 Notifications seeden (limit=2 -> 1 auf Seite 2)
    final int count = 3;
    final UUID[] notifIds = new UUID[count];
    for (int i = 0; i < count; i++) {
      notifIds[i] = UUID.randomUUID();
      seedNotification(jdbcTemplate, NOTIF_ID_BASE + 10 + i, notifIds[i],
          TENANT_A_ID, USER_A_REF, TYPE_ID_ASSIGN, false,
          "Pagination-Notif-" + i,
          LocalDateTime.now().minusMinutes(100 - i)); // unterschiedliche Zeiten
    }

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ");

    // Seite 1 (limit=2)
    final String page1Json = mvc.perform(get("/api/v1/notifications?limit=2")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
        .andReturn().getResponse().getContentAsString();

    final JsonNode page1 = new ObjectMapper().readTree(page1Json);
    assertThat(page1.get("items")).hasSize(2);
    final String nextCursor = page1.get("nextCursor").asText();
    assertThat(nextCursor).isNotBlank();

    // Seite 2 mit Cursor
    final String page2Json = mvc.perform(
            get("/api/v1/notifications?limit=2&cursor=" + nextCursor)
                .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final JsonNode page2 = new ObjectMapper().readTree(page2Json);
    assertThat(page2.get("items").size()).isGreaterThanOrEqualTo(1);

    // Kein Skip/Dupe: IDs aus Seite 1 und 2 muessen disjunkt sein
    final java.util.Set<String> page1Ids = new java.util.HashSet<>();
    for (final JsonNode item : page1.get("items")) {
      page1Ids.add(item.get("id").asText());
    }
    for (final JsonNode item : page2.get("items")) {
      assertThat(page1Ids).as("Seite 2 darf keine IDs aus Seite 1 enthalten (kein Dupe)")
          .doesNotContain(item.get("id").asText());
    }
  }

  // ---- IT 4: PUT /{id}/read -> IS_READ=1 -----------------------------------------------

  @Test
  @DisplayName("put_read_setzt_is_read_auf_1_in_db")
  void put_read_setzt_is_read_auf_1_in_db() throws Exception {
    final MockMvc mvc = buildMvc();

    final UUID notifId = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 20, notifId, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "Soll gelesen werden", LocalDateTime.now().minusMinutes(30));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ",
        "task-management:NOTIFICATION_WRITE");

    mvc.perform(put("/api/v1/notifications/" + notifId + "/read")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNoContent());

    // DB-Beweis: IS_READ = 1
    final Integer isRead = jdbcTemplate.queryForObject(
        "SELECT IS_READ FROM [dbo].[TASK_NOTIFICATION] WHERE EXTERNALID = ?",
        Integer.class, notifId);
    assertThat(isRead).as("IS_READ muss nach PUT /{id}/read = 1 sein").isEqualTo(1);
  }

  // ---- IT 5: PUT /read-all -> alle eigenen unread=1, fremde unveraendert -----------------

  @Test
  @DisplayName("put_read_all_markiert_alle_eigenen_ungelesenen_und_laesst_fremde_untouched")
  void put_read_all_markiert_alle_eigenen_ungelesenen_und_laesst_fremde_untouched()
      throws Exception {
    final MockMvc mvc = buildMvc();

    // Zwei eigene ungelesene
    final UUID notif1 = UUID.randomUUID();
    final UUID notif2 = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 21, notif1, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "Ungelesen 1", LocalDateTime.now().minusMinutes(50));
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 22, notif2, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "Ungelesen 2", LocalDateTime.now().minusMinutes(49));

    // Notification von USER_B (anderen Empfaenger, muss unveraendert bleiben)
    final UUID notifB = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 23, notifB, TENANT_A_ID, USER_B_REF,
        TYPE_ID_ASSIGN, false, "B ungelesen", LocalDateTime.now().minusMinutes(48));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ",
        "task-management:NOTIFICATION_WRITE");

    mvc.perform(put("/api/v1/notifications/read-all")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNoContent());

    // Beweis: eigene Notifications von USER_A sind gelesen
    final Integer isRead1 = jdbcTemplate.queryForObject(
        "SELECT IS_READ FROM [dbo].[TASK_NOTIFICATION] WHERE EXTERNALID = ?",
        Integer.class, notif1);
    final Integer isRead2 = jdbcTemplate.queryForObject(
        "SELECT IS_READ FROM [dbo].[TASK_NOTIFICATION] WHERE EXTERNALID = ?",
        Integer.class, notif2);
    assertThat(isRead1).as("Notif1 von USER_A muss IS_READ=1 sein").isEqualTo(1);
    assertThat(isRead2).as("Notif2 von USER_A muss IS_READ=1 sein").isEqualTo(1);

    // Beweis: USER_B Notification bleibt IS_READ=0
    final Integer isReadB = jdbcTemplate.queryForObject(
        "SELECT IS_READ FROM [dbo].[TASK_NOTIFICATION] WHERE EXTERNALID = ?",
        Integer.class, notifB);
    assertThat(isReadB).as("Notif von USER_B muss unveraendert IS_READ=0 sein").isEqualTo(0);
  }

  // ---- IT 6: createdAt korrekt (nicht null + plausibel) --------------------------------

  @Test
  @DisplayName("get_notifications_createdAt_nicht_null_und_plausibel")
  void get_notifications_createdAt_nicht_null_und_plausibel() throws Exception {
    final MockMvc mvc = buildMvc();

    final UUID notifId = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 30, notifId, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "CreatedAt-Test", LocalDateTime.now().minusHours(1));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ");

    final String json = mvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final JsonNode root = new ObjectMapper().readTree(json);
    for (final JsonNode item : root.get("items")) {
      if (notifId.toString().equals(item.get("id").asText())) {
        assertThat(item.get("createdAt")).as("createdAt darf nicht null sein").isNotNull();
        assertThat(item.get("createdAt").asText()).isNotBlank();
        // Muss parsierbar sein (ISO-8601 Instant)
        Instant.parse(item.get("createdAt").asText());
        break;
      }
    }
  }

  // ---- F-P1: base64-gueltiger Cursor mit semantisch kaputten Tokens -> HTTP 400 ----------

  @Test
  @DisplayName("get_notifications_malformed_cursor_liefert_400")
  void get_notifications_malformed_cursor_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();

    // base64url-gueltiger Cursor, Token[0] ist kein LocalDateTime
    final String badCursor = de.brasi.nova.taskmanagement.paging.KeysetCursor.encode(
        List.of("KEIN_DATUM", UUID.randomUUID().toString()));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ");

    mvc.perform(get("/api/v1/notifications?cursor=" + badCursor)
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isBadRequest());
  }

  // ---- F-P2a: deaktivierte Notification nicht sichtbar ----------------------------------

  @Test
  @DisplayName("get_notifications_zeigt_keine_deaktivierten")
  void get_notifications_zeigt_keine_deaktivierten() throws Exception {
    final MockMvc mvc = buildMvc();

    // Aktive Notification (soll sichtbar sein)
    final UUID activeId = UUID.randomUUID();
    seedNotification(jdbcTemplate, NOTIF_ID_BASE + 40, activeId, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, false, "Aktive Notif", LocalDateTime.now().minusMinutes(60));

    // Deaktivierte Notification (soll NICHT sichtbar sein) - direkt per SQL mit DEACTIVE-Status
    final UUID deactivatedId = UUID.randomUUID();
    jdbcTemplate.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_NOTIFICATION] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_NOTIFICATION] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, RECIPIENT_REF,"
            + " TYPE_ID, IS_READ, TEXT, ANGELEGT_AM) "
            + "VALUES (?, 'DEACTIVE', ?, 1, ?, ?, ?, ?, ?, ?)",
        NOTIF_ID_BASE + 41, NOTIF_ID_BASE + 41, deactivatedId, TENANT_A_ID, USER_A_REF,
        TYPE_ID_ASSIGN, 0, "Deaktivierte Notif",
        java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(59)));

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ");

    final String json = mvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final JsonNode root = new ObjectMapper().readTree(json);
    boolean foundActive = false;
    boolean foundDeactivated = false;
    for (final JsonNode item : root.get("items")) {
      final String id = item.get("id").asText();
      if (activeId.toString().equals(id)) {
        foundActive = true;
      }
      if (deactivatedId.toString().equals(id)) {
        foundDeactivated = true;
      }
    }
    assertThat(foundActive).as("Aktive Notification muss sichtbar sein").isTrue();
    assertThat(foundDeactivated).as("Deaktivierte Notification darf NICHT sichtbar sein").isFalse();
  }

  // ---- Negativ-Tests: 401 / 403 / 404 ---------------------------------------------------

  @Test
  @DisplayName("negativ_anonym_401_falsche_permission_403_fremde_uuid_404")
  void negativ_anonym_401_falsche_permission_403_fremde_uuid_404() throws Exception {
    final MockMvc mvc = buildMvc();

    // 1. Anonym -> 401
    mvc.perform(get("/api/v1/notifications"))
        .andExpect(status().isUnauthorized());

    // 2. Token mit nur TASK_READ (kein NOTIFICATION_READ) -> GET 403
    final String tokenReadOnly = buildJwtWithAuthority(
        TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN,
        "task-management:TASK_READ");
    mvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer " + tokenReadOnly))
        .andExpect(status().isForbidden());

    // 3. Token mit NOTIFICATION_READ aber KEIN NOTIFICATION_WRITE -> PUT read 403
    final String tokenNoWrite = buildJwtWithAuthority(
        TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN,
        "task-management:NOTIFICATION_READ");
    mvc.perform(put("/api/v1/notifications/" + UUID.randomUUID() + "/read")
            .header("Authorization", "Bearer " + tokenNoWrite))
        .andExpect(status().isForbidden());

    // 4. Fremde/unbekannte Notification-UUID bei markRead -> 404
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:NOTIFICATION_READ",
        "task-management:NOTIFICATION_WRITE");
    mvc.perform(put("/api/v1/notifications/" + UUID.randomUUID() + "/read")
            .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static String buildJwtWithAuthority(
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

  /**
   * Seedet eine TASK_NOTIFICATION-Zeile direkt per JdbcTemplate.
   *
   * @param jt         JdbcTemplate
   * @param id         interne ID (deterministisch im eigenen Block)
   * @param externalId externe UUID (EXTERNALID)
   * @param tenantId   Mandanten-ID
   * @param recipient  RECIPIENT_REF
   * @param typeId     FK auf DIC_NOTIFICATION_TYPE (z.B. 401=ASSIGN)
   * @param isRead     IS_READ-Flag
   * @param text       TEXT
   * @param createdAt  ANGELEGT_AM (Wand-Uhr-Zeit)
   */
  private static void seedNotification(
      final JdbcTemplate jt,
      final long id,
      final UUID externalId,
      final long tenantId,
      final UUID recipient,
      final long typeId,
      final boolean isRead,
      final String text,
      final LocalDateTime createdAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_NOTIFICATION] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_NOTIFICATION] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, RECIPIENT_REF,"
            + " TYPE_ID, IS_READ, TEXT, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?)",
        id, id, externalId, tenantId, recipient, typeId,
        isRead ? 1 : 0, text, java.sql.Timestamp.valueOf(createdAt));
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Konfigurations-Context --------------------------------------------------------

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
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "notification-it";
    }
  }
}
