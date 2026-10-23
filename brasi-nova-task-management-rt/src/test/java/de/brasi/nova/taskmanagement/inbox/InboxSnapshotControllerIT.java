package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer {@link InboxSnapshotController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist (arch-v2 §2.1/§3, plan-v3 Task 14):
 * <ul>
 *   <li>GET /tasks/snapshot -> user-scoped aktive Tasks + Notifications + Tags + participantSummaries
 *       + archiveTotals; fremde Tasks NICHT sichtbar; asOf gesetzt + ISO-parsebar.
 *   <li>GET /tasks?cursor=&limit= -> Keyset-Fortsetzung Seite1+nextCursor->Seite2 kein Skip/Dupe.
 *   <li>GET /tasks/archive?filter=all|done|left -> user-spezifische Archiv-Semantik; Totals korrekt.
 *   <li>Creator-Invariante-Endpoint-IT (T8-IMPL-Auflage): POST /tasks -> GET /snapshot zeigt den Task.
 *   <li>Negativ: anonym 401, ohne TASK_READ 403; malformed Cursor 400.
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = InboxSnapshotControllerIT.ItConfig.class,
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
class InboxSnapshotControllerIT {

  private static final String TEST_KID = "inbox-it-key";

  /** Mandant A (Klon-Scope 500-599). */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();

  /** USER_A: Snapshot + aktive Pagination. */
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-inbox@brasi.de";
  private static final long USER_A_DB_ID = 561L;

  /** USER_B: fremder Benutzer (Tasks NICHT sichtbar fuer A). */
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-inbox@brasi.de";
  private static final long USER_B_DB_ID = 562L;

  /** USER_ARCH: isolierte Archiv-Totals. */
  private static final UUID USER_ARCH_REF = UUID.randomUUID();
  private static final String USER_ARCH_LOGIN = "user-arch-inbox@brasi.de";
  private static final long USER_ARCH_DB_ID = 565L;

  /** USER_POST: Creator-Invariante (POST -> snapshot). */
  private static final UUID USER_POST_REF = UUID.randomUUID();
  private static final String USER_POST_LOGIN = "user-post-inbox@brasi.de";
  private static final long USER_POST_DB_ID = 566L;

  /** USER_DEL: Nachweis Entity-Status-Filter (deaktivierte Tasks/Memberships). */
  private static final UUID USER_DEL_REF = UUID.randomUUID();
  private static final String USER_DEL_LOGIN = "user-del-inbox@brasi.de";
  private static final long USER_DEL_DB_ID = 568L;

  /** Read-Only-User ohne TASK_READ. */
  private static final UUID USER_RO_REF = UUID.randomUUID();
  private static final String USER_RO_LOGIN = "user-ro-inbox@brasi.de";

  /** USER_NOTIF: Snapshot-Notification-Gating (NOTIFICATION_READ). */
  private static final UUID USER_NOTIF_REF = UUID.randomUUID();
  private static final String USER_NOTIF_LOGIN = "user-notif-inbox@brasi.de";
  private static final long USER_NOTIF_DB_ID = 569L;

  /** Feste Dictionary-Seed-ID aus Flyway V1 DIC_NOTIFICATION_TYPE: 401 = ASSIGN. */
  private static final long TYPE_ID_ASSIGN = 401L;

  /** Deterministischer ID-Block fuer Task/State/Participant (eigener 56xx-Block). */
  private static final long ID_BASE = 5600L;

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
      pemTempFile = Files.createTempFile("inbox-it-jwk-", ".pem");
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
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_ARCH_DB_ID, USER_ARCH_REF, USER_ARCH_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_POST_DB_ID, USER_POST_REF, USER_POST_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_DEL_DB_ID, USER_DEL_REF, USER_DEL_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_NOTIF_DB_ID, USER_NOTIF_REF, USER_NOTIF_LOGIN, TENANT_A_ID);
  }

  // ---- IT 1: snapshot user-scoped + Bausteine -----------------------------------------------

  @Test
  @DisplayName("snapshot_user_scoped_aktive_tasks_participants_totals")
  void snapshot_user_scoped_aktive_tasks_participants_totals() throws Exception {
    final MockMvc mvc = buildMvc();
    final long openId = statusId("OPEN");
    final long normalId = priorityId("NORMAL");
    final long directionId = directionId("CUSTOMER_INTERNAL");

    // Aktiver Task fuer A (sichtbar) + Creator-Participant A + aktiver UserState A
    final long taskAId = ID_BASE + 1;
    final UUID taskAUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, taskAId, taskAUuid, TENANT_A_ID, openId, normalId, directionId,
        LocalDateTime.now().minusMinutes(5), "Sichtbarer A-Task");
    seedParticipant(jdbcTemplate, ID_BASE + 2, taskAId, USER_A_REF, TENANT_A_ID, true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 3, taskAId, USER_A_REF, TENANT_A_ID, null);

    // Aktiver Task fuer B (NICHT sichtbar fuer A)
    final long taskBId = ID_BASE + 4;
    final UUID taskBUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, taskBId, taskBUuid, TENANT_A_ID, openId, normalId, directionId,
        LocalDateTime.now().minusMinutes(4), "Fremder B-Task");
    seedParticipant(jdbcTemplate, ID_BASE + 5, taskBId, USER_B_REF, TENANT_A_ID, true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 6, taskBId, USER_B_REF, TENANT_A_ID, null);

    final String token = jwt(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");

    final String json = mvc.perform(get("/api/v1/tasks/snapshot")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.tasks.items").isArray())
        .andExpect(jsonPath("$.notifications.items").isArray())
        .andExpect(jsonPath("$.tags").isArray())
        .andExpect(jsonPath("$.participantSummaries").isArray())
        .andExpect(jsonPath("$.archiveTotals.all").exists())
        .andReturn().getResponse().getContentAsString();

    final JsonNode root = new ObjectMapper().readTree(json);
    // asOf ISO-8601-parsebar
    Instant.parse(root.get("asOf").asText());
    assertThat(root.get("asOf").asText()).isEqualTo(root.get("tasks").get("asOf").asText());

    boolean foundA = false;
    boolean foundB = false;
    for (final JsonNode item : root.get("tasks").get("items")) {
      if (taskAUuid.toString().equals(item.get("id").asText())) {
        foundA = true;
        assertThat(item.get("statusCode").asText()).isEqualTo("OPEN");
        assertThat(item.get("priorityCode").asText()).isEqualTo("NORMAL");
      }
      if (taskBUuid.toString().equals(item.get("id").asText())) {
        foundB = true;
      }
    }
    assertThat(foundA).as("A-Task muss sichtbar sein").isTrue();
    assertThat(foundB).as("B-Task (fremder User) darf NICHT sichtbar sein").isFalse();

    // participantSummaries enthaelt A (Seiten-Teilnehmer), nicht B
    boolean partA = false;
    for (final JsonNode p : root.get("participantSummaries")) {
      if (USER_A_REF.toString().equals(p.get("userRef").asText())) {
        partA = true;
        assertThat(p.get("loginName").asText()).isEqualTo(USER_A_LOGIN);
      }
      assertThat(p.get("userRef").asText())
          .as("B darf nicht als Seiten-Teilnehmer erscheinen")
          .isNotEqualTo(USER_B_REF.toString());
    }
    assertThat(partA).as("A muss Seiten-Teilnehmer sein").isTrue();
  }

  // ---- IT 2: aktive Keyset-Fortsetzung Seite1 -> Seite2 kein Skip/Dupe -----------------------

  @Test
  @DisplayName("aktive_pagination_seite1_cursor_seite2_kein_skip_kein_dupe")
  void aktive_pagination_seite1_cursor_seite2_kein_skip_kein_dupe() throws Exception {
    final MockMvc mvc = buildMvc();
    final long openId = statusId("OPEN");
    final long normalId = priorityId("NORMAL");
    final long directionId = directionId("CUSTOMER_INTERNAL");

    // 3 aktive Tasks fuer A mit unterschiedlichen LAST_ACTIVITY_AT
    for (int i = 0; i < 3; i++) {
      final long taskId = ID_BASE + 20 + i;
      final UUID uuid = UUID.randomUUID();
      seedTask(jdbcTemplate, taskId, uuid, TENANT_A_ID, openId, normalId, directionId,
          LocalDateTime.now().minusMinutes(30 - i), "Pag-Task-" + i);
      seedParticipant(jdbcTemplate, ID_BASE + 30 + i, taskId, USER_A_REF, TENANT_A_ID,
          true, true, true, null);
      seedUserState(jdbcTemplate, ID_BASE + 40 + i, taskId, USER_A_REF, TENANT_A_ID, null);
    }

    final String token = jwt(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");

    final String page1Json = mvc.perform(get("/api/v1/tasks?limit=2")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
        .andReturn().getResponse().getContentAsString();

    final JsonNode page1 = new ObjectMapper().readTree(page1Json);
    assertThat(page1.get("items")).hasSize(2);
    final String nextCursor = page1.get("nextCursor").asText();

    final String page2Json = mvc.perform(get("/api/v1/tasks?limit=2&cursor=" + nextCursor)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    final JsonNode page2 = new ObjectMapper().readTree(page2Json);
    assertThat(page2.get("items").size()).isGreaterThanOrEqualTo(1);

    final java.util.Set<String> page1Ids = new java.util.HashSet<>();
    for (final JsonNode item : page1.get("items")) {
      page1Ids.add(item.get("id").asText());
    }
    for (final JsonNode item : page2.get("items")) {
      assertThat(page1Ids)
          .as("Seite 2 darf keine IDs aus Seite 1 enthalten (kein Dupe)")
          .doesNotContain(item.get("id").asText());
    }
  }

  // ---- IT: Snapshot-Rejection (Finding 2 / Option B) - echte Exit-Mutation -> Folge-Cursor 410 --

  /**
   * Beweist die VOLLSTAENDIGE Kette end-to-end gegen echtes MSSQL: First-Page stempelt Generation 0
   * in den Cursor; eine ECHTE Membership-Mutation ueber {@code POST /tasks/{id}/exit}
   * ({@code AssigneeService.exit}) bumpt die Generation ATOMAR in derselben Transaktion (0 -> 1);
   * der alte Cursor liefert daraufhin HTTP 410 (ResponseStatusException-Pfad durch den
   * KSF-GlobalApiExceptionHandler); ein frischer Bootstrap (ohne Cursor) liefert wieder 200.
   */
  @Test
  @DisplayName("exit_mutation_bumpt_generation_dann_alter_cursor_410_und_frischer_bootstrap_200")
  void exit_mutation_bumpt_generation_dann_alter_cursor_410() throws Exception {
    final MockMvc mvc = buildMvc();
    final long openId = statusId("OPEN");
    final long normalId = priorityId("NORMAL");
    final long directionId = directionId("CUSTOMER_INTERNAL");

    // Dedizierter Benutzer, damit die Mutation keine anderen Tests beeinflusst.
    final UUID genUser = UUID.fromString("00000050-0aaa-0050-0aaa-000000000701");
    final String genLogin = "gen-410-user@brasi.de";

    // 2 aktive Tasks, genUser als NICHT-Creator-Assignee (darf austreten) -> limit=1 => hasMore.
    final UUID exitTaskUuid = UUID.randomUUID();
    for (int i = 0; i < 2; i++) {
      final long taskId = ID_BASE + 700 + i;
      final UUID uuid = (i == 0) ? exitTaskUuid : UUID.randomUUID();
      seedTask(jdbcTemplate, taskId, uuid, TENANT_A_ID, openId, normalId, directionId,
          LocalDateTime.now().minusMinutes(20 - i), "Gen410-Task-" + i);
      seedParticipant(jdbcTemplate, ID_BASE + 710 + i, taskId, genUser, TENANT_A_ID,
          false, true, true, null);
      seedUserState(jdbcTemplate, ID_BASE + 720 + i, taskId, genUser, TENANT_A_ID, null);
    }

    final String token = jwt(TENANT_A_UUID, genUser, genLogin,
        "task-management:TASK_READ", "task-management:TASK_WRITE");

    // First-Page: Generation 0 im Cursor (noch keine Generationszeile fuer den Benutzer).
    final String page1Json = mvc.perform(get("/api/v1/tasks?limit=1")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    final String nextCursor = new ObjectMapper().readTree(page1Json).get("nextCursor").asText();

    // ECHTE destruktive Membership-Mutation: genUser verlaesst einen Task -> AssigneeService.exit
    // bumpt die Generation ATOMAR in derselben Transaktion (0 -> 1).
    mvc.perform(post("/api/v1/tasks/" + exitTaskUuid + "/exit")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"exitKind\":\"UNSUBSCRIBED\"}"))
        .andExpect(status().isNoContent());

    // Folge-Read mit altem Cursor (Generation 0) -> Mismatch mit aktueller Generation 1 -> 410.
    mvc.perform(get("/api/v1/tasks?limit=1&cursor=" + nextCursor)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isGone());

    // Frischer Bootstrap ohne Cursor -> 200 (neuer Snapshot mit aktueller Generation).
    mvc.perform(get("/api/v1/tasks?limit=1")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  // ---- IT: PATCH statusCode=DONE bumpt Generation -> alter aktiver Cursor 410 + wandert ins Archiv -

  /**
   * Codex-Auflage #5 (Cursor-Invalidierung durch statusCode-PATCH). Beweist end-to-end gegen echtes
   * MSSQL: Eine aktive Keyset-Seite stempelt die Generation in den Cursor; ein ECHTER
   * {@code PATCH {id} {"statusCode":"DONE"}} ({@code TaskCommandService.patch}) bumpt die Generation
   * JEDES aktiven Teilnehmers ATOMAR in derselben Transaktion; der alte Cursor liefert daraufhin
   * HTTP 410 (SnapshotExpired). Zusaetzlich wandert der Task aus der frischen aktiven Seite heraus
   * und wird in {@code archiveTotals.done} gezaehlt (Status-Change OPEN -&gt; DONE).
   *
   * <p>TDD-Rigor: OHNE den Generation-Bump in {@code patch} ist der Folge-Read weiterhin 200 (stale
   * pagination) - dieser Test faellt dann an {@code isGone()}. MIT dem Bump -> 410 (gruen).
   */
  @Test
  @DisplayName("patch_statuscode_done_bumpt_generation_alter_cursor_410_und_wandert_ins_archiv")
  void patch_statuscode_done_bumpt_generation_alter_cursor_410() throws Exception {
    final MockMvc mvc = buildMvc();
    final long openId = statusId("OPEN");
    final long normalId = priorityId("NORMAL");
    final long directionId = directionId("CUSTOMER_INTERNAL");

    // Dedizierter Benutzer, damit die Mutation keine anderen Tests beeinflusst.
    final UUID patchUser = UUID.fromString("00000050-0aaa-0050-0aaa-000000000801");
    final String patchLogin = "patch-410-user@brasi.de";
    seedBenutzerIfAbsent(jdbcTemplate, ID_BASE + 800, patchUser, patchLogin, TENANT_A_ID);

    // 2 aktive Tasks, patchUser als Creator-Assignee (darf patchen). limit=1 => hasMore => Cursor.
    // Der erste Task (patchTaskUuid) wird spaeter auf DONE gepatcht.
    final UUID patchTaskUuid = UUID.randomUUID();
    for (int i = 0; i < 2; i++) {
      final long taskId = ID_BASE + 810 + i;
      final UUID uuid = (i == 0) ? patchTaskUuid : UUID.randomUUID();
      seedTask(jdbcTemplate, taskId, uuid, TENANT_A_ID, openId, normalId, directionId,
          LocalDateTime.now().minusMinutes(20 - i), "Patch410-Task-" + i);
      seedParticipant(jdbcTemplate, ID_BASE + 820 + i, taskId, patchUser, TENANT_A_ID,
          true, true, true, null);
      seedUserState(jdbcTemplate, ID_BASE + 830 + i, taskId, patchUser, TENANT_A_ID, null);
    }

    final String token = jwt(TENANT_A_UUID, patchUser, patchLogin,
        "task-management:TASK_READ", "task-management:TASK_WRITE");

    // First-Page: Generation 0 im Cursor (noch keine Generationszeile fuer den Benutzer).
    final String page1Json = mvc.perform(get("/api/v1/tasks?limit=1")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    final String nextCursor = new ObjectMapper().readTree(page1Json).get("nextCursor").asText();

    // ECHTE Status-Mutation: PATCH statusCode=DONE -> TaskCommandService.patch bumpt die Generation
    // JEDES aktiven Teilnehmers ATOMAR in derselben Transaktion (0 -> 1).
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .patch("/api/v1/tasks/" + patchTaskUuid)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"statusCode\":\"DONE\"}"))
        .andExpect(status().isNoContent());

    // Folge-Read mit altem Cursor (Generation 0) -> Mismatch mit aktueller Generation 1 -> 410.
    mvc.perform(get("/api/v1/tasks?limit=1&cursor=" + nextCursor)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isGone());

    // Frischer Bootstrap ohne Cursor -> 200; der DONE-Task ist NICHT mehr in der aktiven Seite.
    final JsonNode freshActive = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks?limit=50")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(idsOf(freshActive))
        .as("der auf DONE gepatchte Task darf nicht mehr in der aktiven Seite erscheinen")
        .doesNotContain(patchTaskUuid.toString());

    // archiveTotals.done zaehlt den gepatchten Task jetzt (OPEN -> DONE).
    final JsonNode snapshot = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(snapshot.get("archiveTotals").get("done").asLong())
        .as("der DONE-Task muss in archiveTotals.done gezaehlt werden")
        .isGreaterThanOrEqualTo(1L);
    boolean doneInArchive = false;
    final JsonNode donePage = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/archive?filter=done")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    for (final String id : idsOf(donePage)) {
      if (patchTaskUuid.toString().equals(id)) {
        doneInArchive = true;
      }
    }
    assertThat(doneInArchive).as("der DONE-Task muss im done-Archiv erscheinen").isTrue();
  }

  // ---- IT 3: Archiv je Filter + Totals -------------------------------------------------------

  @Test
  @DisplayName("archive_filter_done_left_all_user_spezifisch_und_totals")
  void archive_filter_done_left_all_user_spezifisch_und_totals() throws Exception {
    final MockMvc mvc = buildMvc();
    final long openId = statusId("OPEN");
    final long doneId = statusId("DONE");
    final long normalId = priorityId("NORMAL");
    final long directionId = directionId("CUSTOMER_INTERNAL");

    // DONE-Task (Status DONE + aktiver UserState) -> done + all
    final long doneTaskId = ID_BASE + 60;
    final UUID doneUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, doneTaskId, doneUuid, TENANT_A_ID, doneId, normalId, directionId,
        LocalDateTime.now().minusMinutes(20), "Erledigter Task");
    seedParticipant(jdbcTemplate, ID_BASE + 61, doneTaskId, USER_ARCH_REF, TENANT_A_ID,
        true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 62, doneTaskId, USER_ARCH_REF, TENANT_A_ID, null);

    // LEFT-Task via UserState UNSUBSCRIBED_AT (Status OPEN) -> left + all
    final long leftTaskId = ID_BASE + 63;
    final UUID leftUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, leftTaskId, leftUuid, TENANT_A_ID, openId, normalId, directionId,
        LocalDateTime.now().minusMinutes(19), "Verlassener Task (unsub)");
    seedParticipant(jdbcTemplate, ID_BASE + 64, leftTaskId, USER_ARCH_REF, TENANT_A_ID,
        true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 65, leftTaskId, USER_ARCH_REF, TENANT_A_ID,
        LocalDateTime.now().minusMinutes(10));

    final String token =
        jwt(TENANT_A_UUID, USER_ARCH_REF, USER_ARCH_LOGIN, "task-management:TASK_READ");

    // done
    final JsonNode donePage = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/archive?filter=done")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(idsOf(donePage)).contains(doneUuid.toString()).doesNotContain(leftUuid.toString());

    // left
    final JsonNode leftPage = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/archive?filter=left")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(idsOf(leftPage)).contains(leftUuid.toString()).doesNotContain(doneUuid.toString());

    // all (Vereinigung)
    final JsonNode allPage = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/archive?filter=all")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(idsOf(allPage)).contains(doneUuid.toString(), leftUuid.toString());

    // Totals via Snapshot (asOf-gebunden, user-scoped): done=1, left=1, all=2
    final JsonNode snapshot = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(snapshot.get("archiveTotals").get("done").asLong()).isEqualTo(1L);
    assertThat(snapshot.get("archiveTotals").get("left").asLong()).isEqualTo(1L);
    assertThat(snapshot.get("archiveTotals").get("all").asLong()).isEqualTo(2L);
  }

  // ---- IT 4: Creator-Invariante (T8-Auflage): POST -> snapshot zeigt Task --------------------

  @Test
  @DisplayName("post_dann_snapshot_zeigt_creator_task")
  void post_dann_snapshot_zeigt_creator_task() throws Exception {
    final MockMvc mvc = buildMvc();

    final String token = jwt(TENANT_A_UUID, USER_POST_REF, USER_POST_LOGIN,
        "task-management:TASK_READ", "task-management:TASK_WRITE");

    // POST /tasks -> legt Task + Creator-Participant + aktiven UserState in DERSELBEN Tx an
    mvc.perform(post("/api/v1/tasks")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Creator-Invariante-Task\"}"))
        .andExpect(status().is2xxSuccessful());

    // GET /snapshot als Creator -> Task erscheint in der aktiven Seite + Creator als Teilnehmer
    final JsonNode snapshot = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

    boolean foundTitle = false;
    for (final JsonNode item : snapshot.get("tasks").get("items")) {
      if ("Creator-Invariante-Task".equals(item.get("title").asText())) {
        foundTitle = true;
      }
    }
    assertThat(foundTitle).as("Neu angelegter Task muss in der eigenen Inbox erscheinen").isTrue();

    boolean creatorIsParticipant = false;
    for (final JsonNode p : snapshot.get("participantSummaries")) {
      if (USER_POST_REF.toString().equals(p.get("userRef").asText())) {
        creatorIsParticipant = true;
      }
    }
    assertThat(creatorIsParticipant).as("Creator muss Seiten-Teilnehmer sein").isTrue();
  }

  // ---- IT 5: Entity-Status-Filter (Codex-P1): deaktivierte Rows nicht in Reads/Totals -------

  @Test
  @DisplayName("deaktivierte_tasks_und_memberships_nicht_in_reads_und_totals")
  void deaktivierte_tasks_und_memberships_nicht_in_reads_und_totals() throws Exception {
    final MockMvc mvc = buildMvc();
    final long openId = statusId("OPEN");
    final long doneId = statusId("DONE");
    final long normalId = priorityId("NORMAL");
    final long dir = directionId("CUSTOMER_INTERNAL");

    // (a) aktiver erledigter Task + aktiver UserState -> in done-Archiv + Totals
    final long activeDoneId = ID_BASE + 80;
    final UUID activeDoneUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, activeDoneId, activeDoneUuid, TENANT_A_ID, doneId, normalId, dir,
        LocalDateTime.now().minusMinutes(15), "Aktiv erledigt");
    seedParticipant(jdbcTemplate, ID_BASE + 81, activeDoneId, USER_DEL_REF, TENANT_A_ID,
        true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 82, activeDoneId, USER_DEL_REF, TENANT_A_ID, null);

    // (b) DEAKTIVIERTER erledigter Task (ENTITY_STATUS='DEACTIVE') -> NICHT sichtbar/gezaehlt
    final long deadTaskId = ID_BASE + 83;
    final UUID deadUuid = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " LAST_ACTIVITY_AT, COMMENT_COUNT) "
            + "VALUES (?, 'DEACTIVE', 'inbox-it', getdate(), ?, 1, ?, ?, ?, ?, ?, ?, ?, 0)",
        deadTaskId, deadUuid, TENANT_A_ID, "Deaktiviert erledigt", doneId, normalId, dir,
        TENANT_A_ID, Timestamp.valueOf(LocalDateTime.now().minusMinutes(14)));
    seedParticipant(jdbcTemplate, ID_BASE + 84, deadTaskId, USER_DEL_REF, TENANT_A_ID,
        true, true, true, null);
    seedUserState(jdbcTemplate, ID_BASE + 85, deadTaskId, USER_DEL_REF, TENANT_A_ID, null);

    // (c) aktiver OPEN-Task mit DEAKTIVIERTEM UserState -> NICHT in aktiver Seite (Membership-Filter)
    final long openTaskId = ID_BASE + 86;
    final UUID openUuid = UUID.randomUUID();
    seedTask(jdbcTemplate, openTaskId, openUuid, TENANT_A_ID, openId, normalId, dir,
        LocalDateTime.now().minusMinutes(13), "Open mit totem UserState");
    seedParticipant(jdbcTemplate, ID_BASE + 87, openTaskId, USER_DEL_REF, TENANT_A_ID,
        true, true, true, null);
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_USER_STATE] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, UNSUBSCRIBED_AT) "
            + "VALUES (?, 'DEACTIVE', 'inbox-it', getdate(), ?, 1, ?, ?, ?, ?, 0, 0, 0, NULL)",
        ID_BASE + 88, UUID.randomUUID(), TENANT_A_ID, openTaskId, USER_DEL_REF, TENANT_A_ID);

    final String token =
        jwt(TENANT_A_UUID, USER_DEL_REF, USER_DEL_LOGIN, "task-management:TASK_READ");

    // Archiv done: nur aktiver erledigter Task, NICHT der deaktivierte
    final JsonNode donePage = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/archive?filter=done")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(idsOf(donePage)).contains(activeDoneUuid.toString())
        .doesNotContain(deadUuid.toString());

    // Totals: done == 1 (deaktivierter Task NICHT mitgezaehlt)
    final JsonNode snapshot = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(snapshot.get("archiveTotals").get("done").asLong()).isEqualTo(1L);

    // Aktive Seite: OPEN-Task mit deaktiviertem UserState erscheint NICHT (Membership-Filter)
    final JsonNode active = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(idsOf(active)).doesNotContain(openUuid.toString());
  }

  // ---- IT: Snapshot-Notifications NOTIFICATION_READ-gated (echte Daten + Near-Miss) ----------

  /**
   * Beweist Auflage 1 des SPEC-Gates mit ECHTEM Datenbestand: fuer USER_NOTIF existiert eine echte
   * Notification. Nur mit {@code NOTIFICATION_READ} erscheint sie im Bootstrap-Snapshot; mit allein
   * {@code TASK_READ} ist die Notification-Seite LEER (konsistent zum dedizierten
   * {@code GET /api/v1/notifications}, das denselben Benutzer mit 403 ablehnt). Near-Miss-Authorities
   * (falsche Grossschreibung, {@code _COMPANY_<uuid>}-Suffix) gelten als FEHLEND (exakte Pruefung,
   * kein Stripping/Case - aspekt-treu zu {@code @RequiresPermission}).
   */
  @Test
  @DisplayName("snapshot_notifications_nur_mit_notification_read_sonst_leer_echte_daten")
  void snapshot_notifications_gated_by_notification_read() throws Exception {
    final MockMvc mvc = buildMvc();

    // Echte Notification seeden (leerer Bestand waere kein Sicherheitsnachweis).
    seedNotification(jdbcTemplate, ID_BASE + 900, USER_NOTIF_REF, TENANT_A_ID, TYPE_ID_ASSIGN,
        "Snapshot-Notif-Gate");

    // (1) Nur TASK_READ -> Snapshot 200, notifications LEER; dedizierter Endpunkt -> 403.
    final String tokenReadOnly =
        jwt(TENANT_A_UUID, USER_NOTIF_REF, USER_NOTIF_LOGIN, "task-management:TASK_READ");
    final JsonNode roSnap = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + tokenReadOnly))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(roSnap.get("notifications").get("items")).isEmpty();
    assertThat(roSnap.get("notifications").get("hasMore").asBoolean()).isFalse();
    assertThat(roSnap.get("notifications").get("nextCursor").isNull()).isTrue();
    mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + tokenReadOnly))
        .andExpect(status().isForbidden());

    // (2) Near-Miss-Authorities gelten als FEHLEND (exakt, kein Stripping/Case) -> weiter leer.
    final String tokenNearMiss = jwt(TENANT_A_UUID, USER_NOTIF_REF, USER_NOTIF_LOGIN,
        "task-management:TASK_READ",
        "task-management:notification_read",
        "task-management:NOTIFICATION_READ_COMPANY_" + UUID.randomUUID());
    final JsonNode nmSnap = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + tokenNearMiss))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    assertThat(nmSnap.get("notifications").get("items")).isEmpty();

    // (3) Mit NOTIFICATION_READ -> Notification sichtbar.
    final String tokenFull = jwt(TENANT_A_UUID, USER_NOTIF_REF, USER_NOTIF_LOGIN,
        "task-management:TASK_READ", "task-management:NOTIFICATION_READ");
    final JsonNode fullSnap = new ObjectMapper().readTree(
        mvc.perform(get("/api/v1/tasks/snapshot")
                .header("Authorization", "Bearer " + tokenFull))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    boolean foundNotif = false;
    for (final JsonNode n : fullSnap.get("notifications").get("items")) {
      if ("Snapshot-Notif-Gate".equals(n.get("text").asText())) {
        foundNotif = true;
      }
    }
    assertThat(foundNotif)
        .as("Mit NOTIFICATION_READ muss die Notification im Snapshot erscheinen")
        .isTrue();
  }

  // ---- IT 6: Negativ 401 / 403 + malformed Cursor 400 ---------------------------------------

  @Test
  @DisplayName("negativ_anonym_401_ohne_task_read_403_malformed_cursor_400")
  void negativ_anonym_401_ohne_task_read_403_malformed_cursor_400() throws Exception {
    final MockMvc mvc = buildMvc();

    // 1. anonym -> 401
    mvc.perform(get("/api/v1/tasks/snapshot")).andExpect(status().isUnauthorized());

    // 2. Token ohne TASK_READ (nur NOTIFICATION_READ) -> 403 auf allen drei Reads
    final String noRead = jwt(TENANT_A_UUID, USER_RO_REF, USER_RO_LOGIN,
        "task-management:NOTIFICATION_READ");
    mvc.perform(get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + noRead))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/tasks").header("Authorization", "Bearer " + noRead))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/tasks/archive").header("Authorization", "Bearer " + noRead))
        .andExpect(status().isForbidden());

    // 3. malformed Cursor (2 Token statt 4) -> 400
    final String token = jwt(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    final String badCursor =
        KeysetCursor.encode(List.of(LocalDateTime.now().toString(), UUID.randomUUID().toString()));
    mvc.perform(get("/api/v1/tasks?cursor=" + badCursor)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private java.util.List<String> idsOf(final JsonNode page) {
    final java.util.List<String> ids = new java.util.ArrayList<>();
    for (final JsonNode item : page.get("items")) {
      ids.add(item.get("id").asText());
    }
    return ids;
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
            + "VALUES (?, 'ACTIVE', 'inbox-it', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0)",
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
            + "VALUES (?, 'ACTIVE', 'inbox-it', getdate(), ?, 1, ?, ?, ?, 'PERSON', ?, ?, ?, ?, ?)",
        id, id, UUID.randomUUID(), tenantId, taskId, participantRef, tenantId,
        creator ? 1 : 0, assignee ? 1 : 0, participant ? 1 : 0, exitKind);
  }

  private static void seedNotification(
      final JdbcTemplate jt,
      final long id,
      final UUID recipientRef,
      final long tenantId,
      final long typeId,
      final String text) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_NOTIFICATION] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_NOTIFICATION] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, RECIPIENT_REF,"
            + " TYPE_ID, IS_READ, TEXT, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, 0, ?, getdate())",
        id, id, UUID.randomUUID(), tenantId, recipientRef, typeId, text);
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
            + "VALUES (?, 'ACTIVE', 'inbox-it', getdate(), ?, 1, ?, ?, ?, ?, 0, 0, 0, ?)",
        id, id, UUID.randomUUID(), tenantId, taskId, userRef, tenantId,
        unsubscribedAt == null ? null : Timestamp.valueOf(unsubscribedAt));
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
      return () -> "inbox-it";
    }
  }
}
