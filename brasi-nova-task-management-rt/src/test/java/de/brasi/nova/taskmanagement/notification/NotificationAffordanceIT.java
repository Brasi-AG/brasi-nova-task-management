package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.jsonwebtoken.Jwts;
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
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Affordance-Integration-Test (PLAN #141 Slice 5, Teil A): {@code GET /api/v1/notifications}
 * traegt permission-gegatete HATEOAS-{@code _links} - OHNE die bestehende Enforcement
 * ({@code @RequiresPermission(NOTIFICATION_WRITE)}) oder den fachlichen Wire-Vertrag zu aendern.
 *
 * <p>Rel-Matrix (SPEC #141 Slice 5): Item {@code mark-read}, Collection {@code mark-all-read};
 * beide NOTIFICATION_WRITE-gegatet. Cross-User-Sicherheitsnetz: ohne NOTIFICATION_WRITE keine
 * Affordance-Rels. Leere-Liste-Edge (B1): ohne Eintraege emittiert die Collection kein
 * {@code mark-all-read} (tatsaechliches Verhalten bei leerer Rule-Owner-Collection).
 *
 * <p>Bootet - wie {@code TagAffordanceIT} - den VOLLEN Spring-Boot-Autoconfig-Graph,
 * damit die reale KSF-Web-Security-Filterchain (JWT, Tenant-Binding) scharf laeuft.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = NotificationAffordanceIT.ItConfig.class,
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
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
      "ksf.web.dev.enabled=true",
      "logging.level.io.keel.framework.web.mvc=DEBUG",
      "logging.level.io.keel.framework.web.hateoas=DEBUG",
      "logging.level.io.keel.framework.web.enrichment=DEBUG",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(OrderAnnotation.class)
class NotificationAffordanceIT {

  private static final String TEST_KID = "notif-affordance-it-key";

  /** Mandant fuer Affordance-Tests (590er-Block, kein Konflikt mit NotificationControllerIT 570-574). */
  private static final long TENANT_A_ID = 590L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();

  /** NOTIFICATION_WRITE-Nutzer: bekommt mark-read/mark-all-read. */
  private static final UUID USER_WRITE_REF = UUID.randomUUID();

  private static final String USER_WRITE_LOGIN = "notif-aff-write@brasi.de";
  private static final long USER_WRITE_DB_ID = 591L;

  /** Nutzer OHNE NOTIFICATION_WRITE (nur NOTIFICATION_READ): keine write-Rels. */
  private static final UUID USER_READ_ONLY_REF = UUID.randomUUID();

  private static final String USER_READ_ONLY_LOGIN = "notif-aff-ro@brasi.de";
  private static final long USER_READ_ONLY_DB_ID = 592L;

  /** Seed-Type-ID: 401 = ASSIGN (laut Flyway V1 DIC_NOTIFICATION_TYPE). */
  private static final long TYPE_ID_ASSIGN = 401L;

  /** ID-Block fuer Notifications in diesem IT (59xx-Block). */
  private static final long NOTIF_ID_BASE = 5900L;

  private static final KeyPair RSA_KEY_PAIR;

  static {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (final NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static Path pemTempFile;

  @Autowired private WebApplicationContext webApplicationContext;

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
      pemTempFile = Files.createTempFile("notif-affordance-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
  }

  @BeforeAll
  static void seedPrerequisites(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, USER_WRITE_DB_ID, USER_WRITE_REF, USER_WRITE_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(
        jt, USER_READ_ONLY_DB_ID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN, TENANT_A_ID);

    // FINDING 2 (Slice 1): der KSF-Laufzeit-Regelresolver liest system_controller_regel;
    // fehlt die Tabelle, verschluckt der fail-safe-catch die GESAMTE Link-Anreicherung.
    // RefSchemaItDb legt die leere Struktur-Kopie an (= reales Deploy-Provisioning); hier nur
    // beweisen, dass das gegriffen hat.
    final Integer tableExists =
        jt.queryForObject(
            "SELECT COUNT(*) FROM sys.objects"
                + " WHERE object_id = OBJECT_ID('dbo.system_controller_regel') AND type = 'U'",
            Integer.class);
    assertThat(tableExists)
        .as(
            "system_controller_regel muss als leere Struktur-Kopie existieren (RefSchemaItDb);"
                + " nur dann beweisen die _links den realen Deploy-Pfad.")
        .isEqualTo(1);
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
        } catch (final IOException ignored) {
          // best-effort cleanup
        }
      }
    }
  }

  // ---- B1-Edge: LEERE /notifications (NOTIFICATION_WRITE) -> mark-all-read DA (Rule-Owner) -----
  //
  // EMPIRISCH (2026-11-09): NotificationController ist @EnableNovaHateoas(TaskNotification)-
  // Rule-Owner. KSF emittiert Collection-Rels vom Handler-Kontext, NICHT nur aus Items.
  // Daher traegt auch eine leere Collection mark-all-read (wenn NOTIFICATION_WRITE vorhanden).
  // Das ist das tatsaechliche und gewollte Verhalten - leere Liste mit mark-all-read ist korrekt.

  @Test
  @Order(1)
  @DisplayName(
      "empty_collection_mark_all_read_da_bei_write_nutzer:"
          + " frische DB OHNE Notifications; GET /notifications als NOTIFICATION_WRITE -> 200;"
          + " leere Collection traegt mark-all-read (Rule-Owner emittiert Collection-Rel auf"
          + " Handler-Ebene, unabhaengig von Items; empirisch verifiziert 2026-11-09)")
  void empty_collection_mark_all_read_da_bei_write_nutzer() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writer =
        buildJwt(
            USER_WRITE_REF,
            USER_WRITE_LOGIN,
            "task-management:NOTIFICATION_READ",
            "task-management:NOTIFICATION_WRITE");

    final String body =
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + writer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[NOTIF-AFF-empty] GET /notifications (leer) -> " + body);

    // Leere Collection: kein _embedded.notifications oder leeres Array.
    final JsonNode embedded = root.path("_embedded");
    final boolean hasItems =
        !embedded.isMissingNode()
            && !embedded.isNull()
            && embedded.has("notifications")
            && embedded.path("notifications").isArray()
            && embedded.path("notifications").size() > 0;
    assertThat(hasItems)
        .as("frische DB -> keine Notification-Items erwartet. Body=" + body)
        .isFalse();
    // B1-empirisch: leere Collection traegt mark-all-read (Rule-Owner emittiert auf Handler-Ebene).
    assertThat(root.path("_links").path("mark-all-read").path("href").isTextual())
        .as(
            "B1-empirisch: leere Collection traegt mark-all-read (NOTIFICATION_WRITE-Nutzer,"
                + " Rule-Owner-Handler). Body="
                + body)
        .isTrue();
    // mark-all-read zielt auf PUT .../read-all.
    assertThat(root.path("_links").path("mark-all-read").path("href").asText())
        .as("mark-all-read-href zielt auf PUT /api/v1/notifications/read-all")
        .endsWith("/read-all");
  }

  // ---- NOTIFICATION_WRITE: Items tragen mark-read + Collection traegt mark-all-read -----------

  @Test
  @Order(2)
  @DisplayName(
      "write_nutzer_item_und_collection_rels:"
          + " NOTIFICATION_WRITE-Nutzer hat Notification; GET /notifications -> 200; Item traegt"
          + " mark-read (_embedded.notifications[]._links.mark-read), Collection traegt mark-all-read"
          + " (top-level _links.mark-all-read); bestehender Wire-Vertrag unveraendert")
  void write_nutzer_item_und_collection_rels(@Autowired final JdbcTemplate jt) throws Exception {
    // Notification fuer den Write-Nutzer direkt in DB seeden.
    seedNotification(
        jt,
        NOTIF_ID_BASE + 1L,
        UUID.randomUUID(),
        TENANT_A_ID,
        USER_WRITE_REF,
        TYPE_ID_ASSIGN,
        false,
        "Testeintrag fuer Affordance",
        LocalDateTime.now().minusMinutes(5));

    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writer =
        buildJwt(
            USER_WRITE_REF,
            USER_WRITE_LOGIN,
            "task-management:NOTIFICATION_READ",
            "task-management:NOTIFICATION_WRITE");

    final String body =
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + writer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[NOTIF-AFF-write] GET /notifications -> " + body);

    // Items liegen unter _embedded.notifications (@Relation collectionRelation="notifications").
    final JsonNode items = root.path("_embedded").path("notifications");
    assertThat(items.isArray())
        .as("BLOCKER: GET /notifications liefert HAL _embedded.notifications. Body=" + body)
        .isTrue();
    assertThat(items.size())
        .as("mindestens 1 Notification-Item nach Seed vorhanden")
        .isGreaterThan(0);

    // Item-Rel: mark-read (NOTIFICATION_WRITE).
    final JsonNode item = items.get(0);
    final JsonNode itemLinks = item.path("_links");
    assertThat(itemLinks.path("mark-read").path("href").isTextual())
        .as(
            "BLOCKER: Item-_links.mark-read fehlt (NOTIFICATION_WRITE-gegatet). Item=" + item)
        .isTrue();
    // mark-read-href enthaelt /read (PUT .../notifications/{id}/read).
    assertThat(itemLinks.path("mark-read").path("href").asText())
        .as("mark-read-href zielt auf PUT /api/v1/notifications/{id}/read")
        .contains("/read");

    // Collection-Rel: mark-all-read (top-level _links).
    assertThat(root.path("_links").path("mark-all-read").path("href").isTextual())
        .as(
            "BLOCKER: Collection-_links.mark-all-read fehlt"
                + " (NotificationController ist Rule-Owner fuer TaskNotification). Body="
                + body)
        .isTrue();
    // mark-all-read zielt auf PUT /api/v1/notifications/read-all.
    assertThat(root.path("_links").path("mark-all-read").path("href").asText())
        .as("mark-all-read-href zielt auf PUT /api/v1/notifications/read-all")
        .endsWith("/read-all");
  }

  // ---- Cross-User: ohne NOTIFICATION_WRITE keine Affordance-Rels ----------------------------

  @Test
  @Order(3)
  @DisplayName(
      "read_only_nutzer_keine_write_rels:"
          + " NOTIFICATION_READ ABER OHNE NOTIFICATION_WRITE; GET /notifications -> 200; Items OHNE"
          + " mark-read, Collection OHNE mark-all-read (conditions greifen in beide Richtungen)")
  void read_only_nutzer_keine_write_rels(@Autowired final JdbcTemplate jt) throws Exception {
    // Notification fuer den Read-only-Nutzer seeden.
    seedNotification(
        jt,
        NOTIF_ID_BASE + 2L,
        UUID.randomUUID(),
        TENANT_A_ID,
        USER_READ_ONLY_REF,
        TYPE_ID_ASSIGN,
        false,
        "ReadOnly Affordance Test",
        LocalDateTime.now().minusMinutes(10));

    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String reader =
        buildJwt(USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN, "task-management:NOTIFICATION_READ");

    final String body =
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + reader))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[NOTIF-AFF-readonly] GET /notifications -> " + body);

    // Collection: KEIN mark-all-read (kein NOTIFICATION_WRITE).
    assertThat(root.path("_links").has("mark-all-read"))
        .as(
            "ohne NOTIFICATION_WRITE darf die Collection KEIN mark-all-read tragen. Body=" + body)
        .isFalse();

    // Item: KEIN mark-read.
    final JsonNode items = root.path("_embedded").path("notifications");
    if (items.isArray() && items.size() > 0) {
      final JsonNode item = items.get(0);
      assertThat(item.path("_links").has("mark-read"))
          .as("ohne NOTIFICATION_WRITE kein mark-read-Rel am Item. Item=" + item)
          .isFalse();
    }
    // Fachlicher Wire bleibt vollstaendig (der Reader sieht die Notifications inhaltlich).
  }

  // ---- Hilfen --------------------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private static String buildJwt(
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
        id,
        id,
        externalId,
        id);
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
        id,
        id,
        externalId,
        tenantId,
        loginName);
  }

  /**
   * Seedet eine TASK_NOTIFICATION-Zeile direkt per JdbcTemplate.
   *
   * @param jt         JdbcTemplate
   * @param id         interne ID (deterministisch im eigenen Block)
   * @param externalId externe UUID (EXTERNALID)
   * @param tenantId   Mandanten-ID
   * @param recipient  RECIPIENT_REF (UUID des Empfaengers, analog NotificationControllerIT)
   * @param typeId     FK auf DIC_NOTIFICATION_TYPE (401=ASSIGN)
   * @param isRead     IS_READ-Flag
   * @param text       TEXT
   * @param createdAt  ANGELEGT_AM
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
        id,
        id,
        externalId,
        tenantId,
        recipient,
        typeId,
        isRead ? 1 : 0,
        text,
        java.sql.Timestamp.valueOf(createdAt));
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Context: voller Autoconfig-Graph (identisch zu TagAffordanceIT.ItConfig) -----

  @SpringBootConfiguration
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
      exclude = {
        org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class
      })
  @org.springframework.boot.autoconfigure.AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ComponentScan(
      basePackages = {
        "de.brasi.nova.taskmanagement.resolver",
        "de.brasi.nova.taskmanagement.task",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.security",
        "de.brasi.nova.taskmanagement.time",
      },
      excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "de\\.brasi\\.nova\\.taskmanagement\\.security\\.it\\..*")
      })
  @EnableTransactionManagement
  @io.keel.framework.persistence.factory.EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @org.springframework.context.annotation.Import({
    de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig.class,
  })
  static class ItConfig {

    @Bean
    io.keel.framework.api.security.AuditorProvider notifAffordanceAuditorProvider() {
      return () -> "notif-affordance-it";
    }
  }
}
