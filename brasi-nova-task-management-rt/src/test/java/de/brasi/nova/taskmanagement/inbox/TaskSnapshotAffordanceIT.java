package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHateoas;
import io.jsonwebtoken.Jwts;
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
 * Affordance-Integration-Test (PLAN #141 Slice 5, Teil B): {@code GET /api/v1/tasks/snapshot}
 * liefert verschachtelte Task-Items mit {@code _links.self} nach
 * {@code /api/v1/tasks/{id}} - OHNE weitere Action-Rels am Snapshot-Item-Level.
 *
 * <p>Technischer Hintergrund (Codex-BLOCKER-1-Fix): Der Snapshot-Handler wurde von
 * {@code InboxSnapshotController} in den {@code TaskFacadeController} verschoben, damit er
 * im Kontext des {@code @EnableNovaHateoas(Task.class)}-Rule-Owners laeuft. Der
 * {@link io.keel.framework.web.hateoas.internal.SelfLinkEnricher} traversiert via reflektiven
 * Feldzugriff, benoetigt aber (a) {@code InfoModel}-Body UND (b) laufenden Rule-Owner-Controller.
 *
 * <p>Wire-Vertrag-Beweis:
 * <ul>
 *   <li>Response-Body ist ein {@code InfoModel} ({@code InboxSnapshotModel extends InfoModel}).
 *   <li>Verschachtelte Items ({@code tasks.items[]}) tragen je ein {@code _links.self}.
 *   <li>{@code self.href} = {@code /api/v1/tasks/{taskId}} (kein /snapshot o.ae.).
 *   <li>KEINE Action-Rels an Snapshot-Items (z.B. {@code update}, {@code assign}) - nur self.
 *   <li>Top-level des Snapshots: kein self-Link (InboxSnapshotModel.getId() @JsonIgnore).
 * </ul>
 *
 * <p>Bootet - wie {@code TagAffordanceIT} - den VOLLEN Spring-Boot-Autoconfig-Graph.
 * Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskSnapshotAffordanceIT.ItConfig.class,
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
      // Reale KSF-Web-Security-Filterchain (FILTER-Modus, JWT) - identisch zu TagAffordanceIT.
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
      // Dev-Modus: HATEOAS-Self-Check meldet Regelprobleme laut statt still.
      "ksf.web.dev.enabled=true",
      "logging.level.io.keel.framework.web.mvc=DEBUG",
      "logging.level.io.keel.framework.web.hateoas=DEBUG",
      "logging.level.io.keel.framework.web.enrichment=DEBUG",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(OrderAnnotation.class)
class TaskSnapshotAffordanceIT {

  private static final String TEST_KID = "snapshot-aff-it-key";

  /**
   * Mandant fuer Affordance-Tests (593er-Block, kein Konflikt mit
   * InboxSnapshotControllerIT 560-569 oder NotificationAffordanceIT 590-592).
   */
  private static final long TENANT_ID = 593L;

  private static final UUID TENANT_UUID = UUID.randomUUID();

  /** Nutzer fuer Snapshot-Tests (TASK_READ). */
  private static final UUID USER_REF = UUID.randomUUID();

  private static final String USER_LOGIN = "snapshot-aff@brasi.de";
  private static final long USER_DB_ID = 594L;

  /** Deterministischer ID-Block fuer Tasks in diesem IT (594xx-Block). */
  private static final long ID_BASE = 59400L;

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
  @Autowired private JdbcTemplate jdbcTemplate;

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
      pemTempFile = Files.createTempFile("snapshot-aff-it-jwk-", ".pem");
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
    seedFirmaIfAbsent(jt, TENANT_ID, TENANT_UUID);
    seedBenutzerIfAbsent(jt, USER_DB_ID, USER_REF, USER_LOGIN, TENANT_ID);

    // FINDING 2 (Slice 1): der KSF-Laufzeit-Regelresolver liest system_controller_regel.
    final Integer tableExists =
        jt.queryForObject(
            "SELECT COUNT(*) FROM sys.objects"
                + " WHERE object_id = OBJECT_ID('dbo.system_controller_regel') AND type = 'U'",
            Integer.class);
    assertThat(tableExists)
        .as("system_controller_regel muss als leere Struktur-Kopie existieren (RefSchemaItDb).")
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
          // Cleanup best-effort.
        }
      }
    }
  }

  // ---- A: snapshot-Items tragen self-link -> /api/v1/tasks/{id} -----------------------------

  @Test
  @Order(1)
  @DisplayName(
      "snapshot_task_items_tragen_self_link:"
          + " GET /tasks/snapshot mit TASK_READ -> Tasks-Items enthalten je _links.self;"
          + " self.href = /api/v1/tasks/{taskId}; kein action-Rel (Codex-BLOCKER-1-Fix: handler"
          + " unter TaskFacadeController = Rule-Owner, InfoModel-Body = SelfLinkEnricher aktiv).")
  void snapshot_task_items_tragen_self_link() throws Exception {
    // Task fuer USER seeden (OPEN, Participant Creator).
    final long statusId = statusId("OPEN");
    final long priorityId = priorityId("NORMAL");
    final long directionId = directionId("CUSTOMER_INTERNAL");

    final long taskId = ID_BASE + 1;
    final UUID taskUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, taskId, taskUuid, TENANT_ID, statusId, priorityId, directionId,
        LocalDateTime.now().minusMinutes(10), "Snapshot-Affordance-Task");
    seedParticipant(jdbcTemplate, ID_BASE + 2, taskId, USER_REF, TENANT_ID, true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 3, taskId, USER_REF, TENANT_ID, null);

    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwt(USER_REF, USER_LOGIN, "task-management:TASK_READ");

    final String body =
        mvc.perform(get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[SNAP-AFF-selflink] GET /tasks/snapshot -> " + body);

    // Task-Items liegen unter .tasks.items (plain KeysetPage, kein HAL-Wrapping).
    final JsonNode taskItems = root.path("tasks").path("items");
    assertThat(taskItems.isArray())
        .as("BLOCKER: snapshot.tasks.items muss ein Array sein. Body=" + body)
        .isTrue();
    assertThat(taskItems.size())
        .as("mindestens 1 Task-Item nach Seed vorhanden. Body=" + body)
        .isGreaterThan(0);

    // Das gecreate Task-Item finden.
    JsonNode targetItem = null;
    for (final JsonNode item : taskItems) {
      if (taskUuid.toString().equals(item.path("id").asText())) {
        targetItem = item;
        break;
      }
    }
    assertThat(targetItem)
        .as("BLOCKER: gecreater Task muss in snapshot.tasks.items sichtbar sein. Body=" + body)
        .isNotNull();

    // self-Link vorhanden (SelfLinkEnricher via InfoModel + Rule-Owner-Handler).
    final JsonNode selfHref = targetItem.path("_links").path("self").path("href");
    assertThat(selfHref.isTextual())
        .as(
            "BLOCKER (Codex-BLOCKER-1-Fix): _links.self fehlt am Snapshot-Task-Item."
                + " Item=" + targetItem)
        .isTrue();

    // self.href zielt auf /api/v1/tasks/{taskId} (nicht /snapshot).
    assertThat(selfHref.asText())
        .as("self.href muss auf /api/v1/tasks/{taskId} zeigen. href=" + selfHref.asText())
        .contains("/api/v1/tasks/")
        .contains(taskUuid.toString());
    assertThat(selfHref.asText())
        .as("self.href darf NICHT auf /snapshot zeigen")
        .doesNotContain("/snapshot");
  }

  // ---- B: Snapshot-Items tragen KEINE Action-Rels (nur self) --------------------------------

  @Test
  @Order(2)
  @DisplayName(
      "snapshot_task_items_keine_action_rels:"
          + " Snapshot-Items tragen NUR _links.self (keine der realen Task-Item-Action-Rels);"
          + " Action-Rels gehoeren nur auf GET /tasks/{id} (Slice 1/3), nicht in den Snapshot.")
  void snapshot_task_items_keine_action_rels() throws Exception {
    // Bereits in Order(1) geseedeter Task - einfach abfragen (gleicher DB-Scope).
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwt(USER_REF, USER_LOGIN, "task-management:TASK_READ", "task-management:TASK_WRITE");

    final String body =
        mvc.perform(get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[SNAP-AFF-noaction] GET /tasks/snapshot (TASK_WRITE) -> " + body);

    final JsonNode taskItems = root.path("tasks").path("items");
    assertThat(taskItems.isArray()).as("tasks.items muss Array sein").isTrue();
    assertThat(taskItems.size())
        .as("mindestens 1 Snapshot-Task-Item erwartet (sonst laeuft der Leak-Guard leer)")
        .isGreaterThan(0);

    // Scharfer Leak-Guard: KEINE der REALEN Task-Rels (Item- + Collection-Rels, die der
    // TaskFacadeController als Rule-Owner deklariert) darf an einem Snapshot-Item erscheinen -
    // AUSSER self. Die Liste wird aus den TaskHateoas.Rels-Konstanten gebildet (anti-drift:
    // ein neu deklariertes Task-Rel erweitert automatisch den Guard).
    final List<String> verboteneRels =
        List.of(
            TaskHateoas.Rels.UPDATE,
            TaskHateoas.Rels.COMMENTS,
            TaskHateoas.Rels.ADD_COMMENT,
            TaskHateoas.Rels.ASSIGNEES,
            TaskHateoas.Rels.PARTICIPANTS,
            TaskHateoas.Rels.EXIT,
            TaskHateoas.Rels.PIN,
            TaskHateoas.Rels.FLAG,
            TaskHateoas.Rels.MUTE,
            TaskHateoas.Rels.CREATE,
            TaskHateoas.Rels.CREATE_FROM_SOURCE);

    for (final JsonNode item : taskItems) {
      final JsonNode links = item.path("_links");
      if (links.isMissingNode() || links.isNull()) {
        // Kein _links -> auch keine Action-Rels; ok.
        continue;
      }
      // self ist erlaubt (und bewiesen in Order(1)); alle realen Action-Rels nicht.
      for (final String rel : verboteneRels) {
        assertThat(links.has(rel))
            .as("Snapshot-Item DARF KEIN '" + rel + "'-Action-Rel tragen (nur self). Item=" + item)
            .isFalse();
      }
    }
  }

  // ---- C: Top-Level-Snapshot hat kein self ------------------------------------------------

  @Test
  @Order(3)
  @DisplayName(
      "snapshot_toplevel_kein_self:"
          + " GET /tasks/snapshot: das top-level InboxSnapshotModel traegt kein _links.self"
          + " (InboxSnapshotModel.getId() @JsonIgnore -> SelfLinkEnricher suppressed).")
  void snapshot_toplevel_kein_self() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwt(USER_REF, USER_LOGIN, "task-management:TASK_READ");

    final String body =
        mvc.perform(get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[SNAP-AFF-notoplevel] GET /tasks/snapshot (top-level) -> " + body);

    // Top-Level des Snapshots: kein _links.self.
    final JsonNode topLinks = root.path("_links");
    if (!topLinks.isMissingNode() && !topLinks.isNull()) {
      assertThat(topLinks.has("self"))
          .as(
              "Top-Level des InboxSnapshots darf KEIN self-Link tragen"
                  + " (InboxSnapshotModel.getId()=@JsonIgnore). Body="
                  + body)
          .isFalse();
    }
    // Snapshot-Struktur-Vertragstest (asOf, tasks, notifications, tags vorhanden).
    assertThat(root.has("asOf")).as("snapshot.asOf fehlt").isTrue();
    assertThat(root.has("tasks")).as("snapshot.tasks fehlt").isTrue();
    // ISO-parsebar
    Instant.parse(root.path("asOf").asText());
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
        .claim("lastActiveTenantId", TENANT_UUID.toString())
        .claim("profile", profileJson)
        .claim("authorities", List.of(authorities))
        .claim("global", List.of())
        .claim("byTenant", Map.of())
        .signWith(RSA_KEY_PAIR.getPrivate())
        .compact();
  }

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
            + "VALUES (?, 'ACTIVE', 'snapshot-aff-it', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0)",
        id, id, Timestamp.valueOf(lastActivityAt), externalId, tenantId, title,
        statusId, priorityId, directionId, tenantId, Timestamp.valueOf(lastActivityAt));
  }

  private static void seedParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID participantRef,
      final long tenantId,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final String exitKind) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND) "
            + "VALUES (?, 'ACTIVE', 'snapshot-aff-it', getdate(), ?, 1, ?, ?, ?, 'PERSON', ?, ?, ?, ?, ?)",
        id, id, UUID.randomUUID(), tenantId, taskId, participantRef, tenantId,
        creator ? 1 : 0, assignee ? 1 : 0, participant ? 1 : 0, exitKind);
  }

  private static void seedUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID userRef,
      final long tenantId,
      final LocalDateTime unsubscribedAt) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) "
            + "VALUES (?, 'ACTIVE', 'snapshot-aff-it', getdate(), ?, 1, ?, ?, ?, ?, 0, 0, 0, ?)",
        id, id, UUID.randomUUID(), tenantId, taskId, userRef, tenantId,
        unsubscribedAt == null ? null : Timestamp.valueOf(unsubscribedAt));
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  // ---- IT-Context: voller Autoconfig-Graph (identisch zu TagAffordanceIT.ItConfig) -----------

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
    io.keel.framework.api.security.AuditorProvider snapshotAffordanceAuditorProvider() {
      return () -> "snapshot-affordance-it";
    }
  }
}
