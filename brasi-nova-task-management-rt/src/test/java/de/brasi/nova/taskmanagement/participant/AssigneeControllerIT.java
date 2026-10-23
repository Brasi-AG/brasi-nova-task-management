package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import io.jsonwebtoken.Jwts;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
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
import java.util.Set;
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
 * Integration-Test fuer den {@link AssigneeController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 * <ul>
 *   <li>PUT /assignees -> ASSIGNEE_FLAG=1 + TASK_USER_STATE angelegt
 *   <li>POST /exit HANDOVER -> EXIT_KIND='HANDOVER' persistiert + Task in findArchiveLeftPage
 *   <li>Anonym -> 401; falsche Permission -> 403; fremder Tenant -> 404
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = AssigneeControllerIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Flyway: Schema via V1 aufbauen
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      // KSF: Dialekt explizit + kein DDL-Auto
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      // KSF Filterchain (FILTER-Modus)
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      // Modul-Registrierung deaktivieren
      "ksf.module-registration.enabled=false",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class AssigneeControllerIT {

  private static final String TEST_KID = "assignee-it-key";

  /** Mandant A: Haupt-Tenant fuer positive Tests. */
  private static final long TENANT_A_ID = 510L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-assignee@brasi.de";
  private static final long USER_A_DB_ID = 511L;

  /** Assignee: wird als Assignee zugewiesen. */
  private static final UUID ASSIGNEE_REF = UUID.randomUUID();
  private static final String ASSIGNEE_LOGIN = "assignee@brasi.de";
  private static final long ASSIGNEE_DB_ID = 512L;

  /** BE-7: Uebergabeziel im SELBEN Mandanten A (muss aktiver Tenant-A-Benutzer sein). */
  private static final UUID HANDOVER_TARGET_REF = UUID.randomUUID();
  private static final String HANDOVER_TARGET_LOGIN = "handover-target@brasi.de";
  private static final long HANDOVER_TARGET_DB_ID = 515L;

  /** Mandant B: fremder Tenant fuer 404-Test. */
  private static final long TENANT_B_ID = 513L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-assignee@brasi.de";
  private static final long USER_B_DB_ID = 514L;

  /** User mit READ-Only-Recht (kein TASK_WRITE -> 403). */
  private static final UUID USER_READ_ONLY_REF = UUID.randomUUID();
  private static final String USER_READ_ONLY_LOGIN = "read-only-assignee@brasi.de";

  /** Slice 4: grant-freier aktiver Mandanten-Nutzer (KEINE task-management:*-Authority). */
  private static final UUID GRANTFREE_REF = UUID.randomUUID();
  private static final String GRANTFREE_LOGIN = "grantfree-member@brasi.de";
  private static final long GRANTFREE_DB_ID = 516L;

  /** Slice 4: zweiter grant-freier aktiver Mandanten-Nutzer (Handover-Ziel-WRITE-Member). */
  private static final UUID GRANTFREE2_REF = UUID.randomUUID();
  private static final String GRANTFREE2_LOGIN = "grantfree-member-2@brasi.de";
  private static final long GRANTFREE2_DB_ID = 517L;

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
      pemTempFile = Files.createTempFile("assignee-it-jwk-", ".pem");
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
  @Autowired private TaskKeysetQueryRepository keysetQueryRepository;
  @Autowired private DictionaryCodeResolver dictionaryCodeResolver;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    // PROD_FIRMA: Mandant A + B
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    // PROD_BENUTZER: User A, Assignee, User B
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, ASSIGNEE_DB_ID, ASSIGNEE_REF, ASSIGNEE_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
    seedBenutzerIfAbsent(
        jt, HANDOVER_TARGET_DB_ID, HANDOVER_TARGET_REF, HANDOVER_TARGET_LOGIN, TENANT_A_ID);
    // Slice 4: grant-freie Mandant-A-Nutzer.
    seedBenutzerIfAbsent(jt, GRANTFREE_DB_ID, GRANTFREE_REF, GRANTFREE_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, GRANTFREE2_DB_ID, GRANTFREE2_REF, GRANTFREE2_LOGIN, TENANT_A_ID);
  }

  // ---- Positiv-IT: Assignee zuweisen -> ASSIGNEE_FLAG + TASK_USER_STATE -------------------------

  @Test
  @DisplayName(
      "put_assignees_legt_participant_und_userstate_an: PUT /assignees -> ASSIGNEE_FLAG=1"
          + " + TASK_USER_STATE angelegt")
  void put_assignees_legt_participant_und_userstate_an() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Erst: Task anlegen
    final String createBody = om.writeValueAsString(Map.of("title", "Assignee-IT-Task"));
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(taskId).isNotNull();

    // Assignee zuweisen
    final String assignBody =
        om.writeValueAsString(Map.of("assignees", Set.of(ASSIGNEE_REF.toString())));
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignBody))
        .andExpect(status().isNoContent());

    // Beweis: TASK_PARTICIPANT mit ASSIGNEE_FLAG=1 fuer ASSIGNEE_REF in DB
    final Integer assigneeParticipantCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.ASSIGNEE_FLAG = 1 AND p.EXIT_KIND IS NULL",
            Integer.class,
            taskId,
            TENANT_A_ID,
            ASSIGNEE_REF);
    assertThat(assigneeParticipantCount)
        .as("ASSIGNEE_FLAG=1 muss fuer ASSIGNEE_REF in DB vorhanden sein")
        .isEqualTo(1);

    // Beweis: TASK_USER_STATE fuer ASSIGNEE_REF angelegt
    final Integer userStateCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
            Integer.class,
            taskId,
            ASSIGNEE_REF);
    assertThat(userStateCount)
        .as("TASK_USER_STATE muss fuer ASSIGNEE_REF angelegt sein")
        .isGreaterThanOrEqualTo(1);
  }

  // ---- Positiv-IT: exit HANDOVER -> EXIT_KIND persistiert + Task in findArchiveLeftPage ----------

  @Test
  @DisplayName(
      "exit_handover_persistiert_und_archiv_left: POST /exit HANDOVER (Nicht-Creator)"
          + " -> EXIT_KIND='HANDOVER' + Task in findArchiveLeftPage")
  void exit_handover_persistiert_und_archiv_left() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    // Der Austretende ist bewusst NICHT der Creator (P2-4: Creator kann nicht austreten).
    final String tokenAssignee = buildJwtWithAuthority(
        TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Erst: Task anlegen (USER_A = Creator) und ASSIGNEE_REF als Nicht-Creator-Assignee zuweisen
    final String createBody = om.writeValueAsString(Map.of("title", "Exit-HANDOVER-IT-Task"));
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(taskId).isNotNull();

    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Exit HANDOVER (ASSIGNEE_REF verlaesst den Task)
    final String exitBody = om.writeValueAsString(Map.of("exitKind", ExitKind.HANDOVER.name()));
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(exitBody))
        .andExpect(status().isNoContent());

    // Beweis: TASK_PARTICIPANT.EXIT_KIND='HANDOVER' fuer ASSIGNEE_REF in DB
    final Integer exitKindCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.EXIT_KIND = 'HANDOVER'",
            Integer.class,
            taskId,
            TENANT_A_ID,
            ASSIGNEE_REF);
    assertThat(exitKindCount)
        .as("EXIT_KIND='HANDOVER' muss in TASK_PARTICIPANT persistiert sein")
        .isEqualTo(1);

    // Beweis: Task erscheint in findArchiveLeftPage fuer ASSIGNEE_REF
    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      final LocalDateTime asOf = LocalDateTime.now().plusSeconds(5);
      final java.util.List<de.brasi.nova.taskmanagement.entity.Task> leftTasks =
          keysetQueryRepository.findArchiveLeftPage(
              ASSIGNEE_REF,
              asOf,
              asOf,
              UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
              10);
      final boolean taskFound =
          leftTasks.stream().anyMatch(t -> taskId.equals(t.getUuid()));
      assertThat(taskFound)
          .as("Task muss nach HANDOVER in findArchiveLeftPage sichtbar sein")
          .isTrue();
    } finally {
      TenantContextHolder.clear();
    }
  }

  // ---- MAJOR 1 IT: Creator-Schutz - Creator bleibt nach PUT ohne Creator aktiv ---------------

  @Test
  @DisplayName(
      "creator_bleibt_nach_put_ohne_creator: PUT mit leerer Menge -> Creator kein EXIT_KIND,"
          + " Task in findActivePage fuer Creator")
  void creator_bleibt_nach_put_ohne_creator() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Task anlegen (USER_A = Creator)
    final String createBody = om.writeValueAsString(Map.of("title", "Creator-Schutz-IT-Task"));
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // PUT ohne Creator (leeres Array)
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isNoContent());

    // Beweis 1: Creator-Participant hat KEIN EXIT_KIND
    final Integer creatorExitCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.CREATOR_FLAG = 1 AND p.EXIT_KIND IS NOT NULL",
            Integer.class,
            taskId,
            TENANT_A_ID,
            USER_A_REF);
    assertThat(creatorExitCount)
        .as("Creator-Participant darf kein EXIT_KIND haben")
        .isEqualTo(0);

    // Beweis 2: Creator-UserState hat KEIN UNSUBSCRIBED_AT
    final Integer creatorUnsubCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            Integer.class,
            taskId,
            USER_A_REF);
    assertThat(creatorUnsubCount)
        .as("Creator-UserState darf kein UNSUBSCRIBED_AT haben")
        .isEqualTo(0);

    // Beweis 3: Task ist in findActivePage fuer Creator
    final Long openStatusId;
    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      openStatusId = dictionaryCodeResolver.resolveTaskStatusOpen();
    } finally {
      TenantContextHolder.clear();
    }

    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      final LocalDateTime asOf = LocalDateTime.now().plusSeconds(5);
      final List<de.brasi.nova.taskmanagement.entity.Task> activeTasks =
          keysetQueryRepository.findActivePage(
              USER_A_REF,
              openStatusId,
              asOf,
              asOf,
              UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
              10);
      assertThat(activeTasks.stream().anyMatch(t -> taskId.equals(t.getUuid())))
          .as("Creator-Task muss nach PUT-ohne-Creator in findActivePage sichtbar bleiben")
          .isTrue();
    } finally {
      TenantContextHolder.clear();
    }
  }

  // ---- MAJOR 2 IT: HANDOVER-Exit -> Task NICHT in findActivePage, aber in findArchiveLeftPage -

  @Test
  @DisplayName(
      "handover_exit_nicht_in_active_page: nach exit(HANDOVER) eines Nicht-Creators Task NICHT"
          + " in findActivePage, aber UNSUBSCRIBED_AT gesetzt")
  void handover_exit_nicht_in_active_page() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    // Der Austretende ist bewusst NICHT der Creator (P2-4: Creator kann nicht austreten).
    final String tokenAssignee = buildJwtWithAuthority(
        TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Task anlegen (USER_A = Creator) + ASSIGNEE_REF zuweisen (Nicht-Creator -> hat UserState)
    final String createBody = om.writeValueAsString(Map.of("title", "HANDOVER-ActivePage-IT-Task"));
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Exit HANDOVER (ASSIGNEE_REF)
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "HANDOVER"))))
        .andExpect(status().isNoContent());

    // Beweis 1: UNSUBSCRIBED_AT im UserState gesetzt (MAJOR 2)
    final Integer unsubCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            Integer.class,
            taskId,
            ASSIGNEE_REF);
    assertThat(unsubCount)
        .as("UNSUBSCRIBED_AT muss nach HANDOVER gesetzt sein")
        .isEqualTo(1);

    // Beweis 2: Task ist NICHT in findActivePage fuer ASSIGNEE_REF (MAJOR 2)
    final Long openStatusId;
    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      openStatusId = dictionaryCodeResolver.resolveTaskStatusOpen();
    } finally {
      TenantContextHolder.clear();
    }

    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      final LocalDateTime asOf = LocalDateTime.now().plusSeconds(5);
      final List<de.brasi.nova.taskmanagement.entity.Task> activeTasks =
          keysetQueryRepository.findActivePage(
              ASSIGNEE_REF,
              openStatusId,
              asOf,
              asOf,
              UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
              10);
      assertThat(activeTasks.stream().anyMatch(t -> taskId.equals(t.getUuid())))
          .as("Task darf nach HANDOVER NICHT in findActivePage erscheinen")
          .isFalse();
    } finally {
      TenantContextHolder.clear();
    }
  }

  // ---- MAJOR 3 IT: Reassign -> UserState reaktiviert, Task wieder in findActivePage ----------

  @Test
  @DisplayName(
      "reassign_reaktiviert_userstate: assign -> remove -> reassign"
          + " -> Task wieder in findActivePage, UNSUBSCRIBED_AT null")
  void reassign_reaktiviert_userstate() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Task anlegen
    final String createBody = om.writeValueAsString(Map.of("title", "Reassign-Reaktivierung-IT-Task"));
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // Schritt 1: ASSIGNEE_REF zuweisen
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Schritt 2: ASSIGNEE_REF abwaehlen (Creator USER_A bleibt)
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isNoContent());

    // Zwischenstand: ASSIGNEE_REF hat UNSUBSCRIBED_AT gesetzt
    final Integer midUnsubCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            Integer.class,
            taskId,
            ASSIGNEE_REF);
    assertThat(midUnsubCount)
        .as("ASSIGNEE_REF muss nach Abwahl UNSUBSCRIBED_AT gesetzt haben")
        .isEqualTo(1);

    // Schritt 3: ASSIGNEE_REF erneut zuweisen (Reassign)
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Beweis 1: UNSUBSCRIBED_AT nach Reassign wieder null
    final Integer afterUnsubCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NULL",
            Integer.class,
            taskId,
            ASSIGNEE_REF);
    assertThat(afterUnsubCount)
        .as("UNSUBSCRIBED_AT muss nach Reassign wieder null sein")
        .isEqualTo(1);

    // Beweis 2: EXIT_KIND am Participant null (reaktiviert)
    final Integer exitKindNullCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.ASSIGNEE_FLAG = 1 AND p.EXIT_KIND IS NULL",
            Integer.class,
            taskId,
            TENANT_A_ID,
            ASSIGNEE_REF);
    assertThat(exitKindNullCount)
        .as("EXIT_KIND muss nach Reassign null sein (Participant reaktiviert)")
        .isEqualTo(1);

    // Beweis 3: Task wieder in findActivePage fuer ASSIGNEE_REF
    final Long openStatusId;
    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      openStatusId = dictionaryCodeResolver.resolveTaskStatusOpen();
    } finally {
      TenantContextHolder.clear();
    }

    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      final LocalDateTime asOf = LocalDateTime.now().plusSeconds(5);
      final List<de.brasi.nova.taskmanagement.entity.Task> activeTasks =
          keysetQueryRepository.findActivePage(
              ASSIGNEE_REF,
              openStatusId,
              asOf,
              asOf,
              UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
              10);
      assertThat(activeTasks.stream().anyMatch(t -> taskId.equals(t.getUuid())))
          .as("Task muss nach Reassign wieder in findActivePage fuer ASSIGNEE_REF erscheinen")
          .isTrue();
    } finally {
      TenantContextHolder.clear();
    }
  }

  // ---- Final-Review P1-2: unbekannte/fremde Assignee-UUID -> 400 -------------------------------

  @Test
  @DisplayName(
      "assignee_unbekannte_uuid_liefert_400:"
          + " PUT /assignees mit UUID ohne aktiven Mandanten-Benutzer -> 400, kein Participant")
  void assignee_unbekannte_uuid_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final String postResponse =
        mvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "P1-2-Assignee-Test"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id").traverse(om).readValueAs(UUID.class);

    // Unbekannte UUID (kein aktiver PROD_BENUTZER im Mandanten) -> 400, bevor etwas angelegt wird
    final UUID unknown = UUID.randomUUID();
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", Set.of(unknown.toString())))))
        .andExpect(status().isBadRequest());

    final Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
        Integer.class, taskId, unknown);
    assertThat(count).as("kein Participant fuer die unbekannte UUID").isZero();
  }

  // ---- P2-4 IT: Creator-Exit wird abgelehnt (409), Creator bleibt aktiv ----------------------

  @Test
  @DisplayName(
      "creator_exit_wird_abgelehnt_409: Creator ruft POST /exit auf -> 409,"
          + " Creator-Participant ohne EXIT_KIND, Task bleibt in findActivePage")
  void creator_exit_wird_abgelehnt_409() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Task anlegen (USER_A = Creator + Assignee)
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", "Creator-Exit-409-IT-Task"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // Creator (USER_A) versucht zu exiten -> 409 (Geschaeftsregel: Ersteller kann nicht verlassen)
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "UNSUBSCRIBED"))))
        .andExpect(status().isConflict());

    // Beweis: Creator-Participant hat KEIN EXIT_KIND
    final Integer creatorExitCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.CREATOR_FLAG = 1 AND p.EXIT_KIND IS NOT NULL",
            Integer.class,
            taskId,
            TENANT_A_ID,
            USER_A_REF);
    assertThat(creatorExitCount)
        .as("Creator-Participant darf nach abgelehntem Exit kein EXIT_KIND haben")
        .isEqualTo(0);

    // Beweis: Creator-UserState hat KEIN UNSUBSCRIBED_AT
    final Integer creatorUnsubCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            Integer.class,
            taskId,
            USER_A_REF);
    assertThat(creatorUnsubCount)
        .as("Creator-UserState darf nach abgelehntem Exit kein UNSUBSCRIBED_AT haben")
        .isEqualTo(0);
  }

  // ---- P2-5 IT: Doppel-Exit wird abgelehnt (400), keine zweite HANDOVER-Notification ----------

  @Test
  @DisplayName(
      "doppel_exit_wird_abgelehnt_404: Nicht-Creator exitet (204) und erneut (404, Slice 4:"
          + " ausgetretener = kein WRITE-Member -> Membership-Guard 404 statt 400) ->"
          + " genau ein EXIT_AT, keine zweite HANDOVER-Notification")
  void doppel_exit_wird_abgelehnt_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenAssignee = buildJwtWithAuthority(
        TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Task anlegen (USER_A = Creator) + ASSIGNEE_REF zuweisen (Nicht-Creator-Participant)
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", "Doppel-Exit-400-IT-Task"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Erster Exit (HANDOVER) durch den Assignee -> 204
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "HANDOVER"))))
        .andExpect(status().isNoContent());

    // Notification-Stand des Assignees nach dem ersten (gueltigen) Exit festhalten
    final Integer notifAfterFirst =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
            Integer.class,
            taskId,
            ASSIGNEE_REF);

    // Zweiter Exit durch denselben (bereits ausgetretenen) Assignee -> 404 (Slice 4: der
    // Membership-Guard greift GANZ VORNE; ein ausgetretener Teilnehmer ist kein WRITE-Member mehr
    // und wird neutral 404-maskiert statt mit 400 ueber die interne Teilnehmerpruefung).
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "HANDOVER"))))
        .andExpect(status().isNotFound());

    // Beweis 1: genau ein Participant mit EXIT_KIND='HANDOVER' (kein zweites EXIT)
    final Integer exitRowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.EXIT_KIND = 'HANDOVER'",
            Integer.class,
            taskId,
            TENANT_A_ID,
            ASSIGNEE_REF);
    assertThat(exitRowCount)
        .as("Es darf genau eine HANDOVER-Austrittszeile fuer den Assignee geben")
        .isEqualTo(1);

    // Beweis 2: keine zweite Notification durch den abgelehnten zweiten Exit
    final Integer notifAfterSecond =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
            Integer.class,
            taskId,
            ASSIGNEE_REF);
    assertThat(notifAfterSecond)
        .as("Der abgelehnte zweite Exit darf keine weitere Notification erzeugen")
        .isEqualTo(notifAfterFirst);
  }

  // ---- MINOR IT: null/fehlendes assignees -> 400 -----------------------------------------------

  @Test
  @DisplayName("null_assignees_liefert_400: PUT ohne assignees-Feld oder mit null -> HTTP 400")
  void null_assignees_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Beliebige Task-UUID (Validierung schlaegt vor Task-Lookup fehl - DTO-Ebene)
    // Aber: Task muss existieren, damit die Validierung ueberhaupt greift.
    // Wir testen mit einem Task, den wir anlegen:
    final String createBody = om.writeValueAsString(Map.of("title", "MINOR-Validation-IT-Task"));
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // 1. PUT mit fehlendem assignees-Feld -> 400
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    // 2. PUT mit assignees=null -> 400
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignees\":null}"))
        .andExpect(status().isBadRequest());

    // 3. PUT mit leerem Array [] -> OK (204)
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isNoContent());
  }

  // ---- Negativ-IT: 401 / 403 / 404 -----------------------------------------------------------

  @Test
  @DisplayName("anonym_401_falsche_permission_403_fremder_tenant_404")
  void anonym_401_falsche_permission_403_fremder_tenant_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // 1. Anonym PUT /assignees -> 401
    mvc.perform(
            put("/api/v1/tasks/" + UUID.randomUUID() + "/assignees")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    // 2. Anonym POST /exit -> 401
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/exit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    // 3. Falsche Permission (TASK_READ statt TASK_WRITE) -> 403 bei PUT /assignees
    final String tokenReadOnly = buildJwtWithAuthority(
        TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN,
        "task-management:TASK_READ");
    mvc.perform(
            put("/api/v1/tasks/" + UUID.randomUUID() + "/assignees")
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isForbidden());

    // 4. Fremder Tenant: User A legt Task an, User B versucht PUT /assignees -> 404
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final String postResp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", "Fremd-Tenant-Test"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    final String tokenB = buildJwtWithAuthority(
        TENANT_B_UUID, USER_B_REF, USER_B_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isNotFound());
  }

  // ============================================================================================
  // BE-6: POST /{id}/participants (additiv, idempotent, nur Teilnehmer-Rolle) - real-MSSQL
  // ============================================================================================

  @Test
  @DisplayName(
      "post_participants_legt_teilnehmer_an: POST /participants -> 204, TASK_PARTICIPANT"
          + " (PARTICIPANT_FLAG=1, ASSIGNEE_FLAG=0, CREATOR_FLAG=0, EXIT_KIND null) + TASK_USER_STATE")
  void post_participants_legt_teilnehmer_an() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Participants-Add-IT-Task");

    mvc.perform(post("/api/v1/tasks/" + taskId + "/participants")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("participantIds", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    final Integer participantRow = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
            + " AND p.PARTICIPANT_FLAG = 1 AND p.ASSIGNEE_FLAG = 0 AND p.CREATOR_FLAG = 0"
            + " AND p.EXIT_KIND IS NULL",
        Integer.class, taskId, TENANT_A_ID, ASSIGNEE_REF);
    assertThat(participantRow).as("reiner Teilnehmer-Row (nicht Assignee/Creator)").isEqualTo(1);

    final Integer userState = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
        Integer.class, taskId, ASSIGNEE_REF);
    assertThat(userState).as("TASK_USER_STATE fuer Teilnehmer angelegt").isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName(
      "post_participants_idempotent: zweites POST desselben Ref -> 204, weiterhin genau 1 aktive Zeile")
  void post_participants_idempotent() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Participants-Idempotent-IT-Task");
    final String body =
        om.writeValueAsString(Map.of("participantIds", List.of(ASSIGNEE_REF.toString())));

    mvc.perform(post("/api/v1/tasks/" + taskId + "/participants")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/v1/tasks/" + taskId + "/participants")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());

    final Integer activeRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
            + " AND p.ENTITY_STATUS = 'ACTIVE' AND p.EXIT_KIND IS NULL",
        Integer.class, taskId, TENANT_A_ID, ASSIGNEE_REF);
    assertThat(activeRows).as("genau 1 aktive Zeile trotz Doppel-Add (idempotent)").isEqualTo(1);
  }

  @Test
  @DisplayName("post_participants_unbekannt_400: unbekannter Ref -> 400, keine Zeile")
  void post_participants_unbekannt_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Participants-Unknown-IT-Task");
    final UUID unknown = UUID.randomUUID();

    mvc.perform(post("/api/v1/tasks/" + taskId + "/participants")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("participantIds", List.of(unknown.toString())))))
        .andExpect(status().isBadRequest());

    final Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
        Integer.class, taskId, unknown);
    assertThat(count).as("keine Zeile fuer unbekannten Ref").isZero();
  }

  @Test
  @DisplayName(
      "post_participants_ex_assignee_clears_assignee_flag (D4-BLOCKER): abgewaehlter Ex-Assignee ->"
          + " POST participants reaktiviert als REINER Teilnehmer (ASSIGNEE_FLAG=0)")
  void post_participants_ex_assignee_clears_assignee_flag() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Participants-ExAssignee-IT-Task");

    // ASSIGNEE_REF zuweisen (ASSIGNEE_FLAG=1) ...
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());
    // ... dann abwaehlen (EXIT_KIND=UNSUBSCRIBED; ASSIGNEE_FLAG bleibt 1 auf der ausgetretenen Zeile).
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of()))))
        .andExpect(status().isNoContent());

    // POST participants [ASSIGNEE_REF] -> reaktiviert als reiner Teilnehmer, ASSIGNEE_FLAG=0.
    mvc.perform(post("/api/v1/tasks/" + taskId + "/participants")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("participantIds", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    final Integer pureParticipant = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
            + " AND p.PARTICIPANT_FLAG = 1 AND p.ASSIGNEE_FLAG = 0 AND p.EXIT_KIND IS NULL",
        Integer.class, taskId, TENANT_A_ID, ASSIGNEE_REF);
    assertThat(pureParticipant)
        .as("Ex-Assignee als reiner Teilnehmer reaktiviert (ASSIGNEE_FLAG=0, nicht wieder Assignee)")
        .isEqualTo(1);

    // Gegenprobe: NICHT als aktiver Assignee gefuehrt.
    final Integer stillAssignee = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
            + " AND p.ASSIGNEE_FLAG = 1 AND p.EXIT_KIND IS NULL",
        Integer.class, taskId, TENANT_A_ID, ASSIGNEE_REF);
    assertThat(stillAssignee).as("darf nicht wieder aktiver Assignee sein").isZero();
  }

  // ============================================================================================
  // BE-7: Handover (handedOverToIds + shouldStay) - Facade-ITs IT1-IT5, IT8 (real-MSSQL)
  // ============================================================================================

  @Test
  @DisplayName(
      "be7_it1_handover_mit_zielen: POST /exit HANDOVER mit Zielen -> 204; Ziel-ASSIGNEE-Rows +"
          + " Event + Targets + Notifications; Akteur exited + ASSIGNEE_FLAG=false;"
          + " LAST_ACTIVITY_AT gebumpt")
  void be7_it1_handover_mit_zielen() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "BE7-IT1-Handover");
    // ASSIGNEE_REF ist Nicht-Creator-Assignee und uebergibt an USER_B (Ziel).
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Exakter Bump-Nachweis: LAST_ACTIVITY_AT auf einen festen Vergangenheitswert zwingen, damit der
    // Handover-Bump auf now() UNZWEIDEUTIG (strikt groesser, nicht nur >=/Sekunde) belegbar ist.
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK] SET LAST_ACTIVITY_AT = '2000-01-01T00:00:00' WHERE EXTERNALID = ?"
            + " AND TENANT_ID = ?",
        taskId.toString(), TENANT_A_ID);
    final Integer ticksBefore = lastActivityTicks(taskId);

    // Enges App-Uhr-Fenster um den Handover-Aufruf: der Service setzt LAST_ACTIVITY_AT=now() in
    // DERSELBEN JVM wie der Test -> der persistierte Wert muss im Intervall [vor, nach] des POST
    // liegen (kein DB-Uhr-Versatz noetig; exakter now()-Nachweis statt grober 24h-Toleranz).
    final long appBeforeMillis = System.currentTimeMillis();
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "HANDOVER", "handedOverToIds", List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isNoContent());
    final long appAfterMillis = System.currentTimeMillis();

    // Ziel ist aktiver Assignee.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND p.PARTICIPANT_REF=? AND p.ASSIGNEE_FLAG=1 AND p.EXIT_KIND IS NULL",
            taskId, HANDOVER_TARGET_REF))
        .as("Ziel ist aktiver Assignee").isEqualTo(1);
    // Akteur exited + ASSIGNEE_FLAG=false.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND p.PARTICIPANT_REF=? AND p.EXIT_KIND='HANDOVER' AND p.ASSIGNEE_FLAG=0",
            taskId, ASSIGNEE_REF))
        .as("Akteur exited (HANDOVER) + de-assignt").isEqualTo(1);
    // Event + Target persistiert.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] h JOIN [dbo].[TASK] t ON h.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND h.FROM_REF=? AND h.SHOULD_STAY=0",
            taskId, ASSIGNEE_REF))
        .as("1 TASK_HANDOVER-Ereignis").isEqualTo(1);
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER_TARGET] tgt"
                + " JOIN [dbo].[TASK_HANDOVER] h ON tgt.HANDOVER_ID=h.ID"
                + " JOIN [dbo].[TASK] t ON h.TASK_ID=t.ID WHERE t.EXTERNALID=? AND tgt.TO_REF=?",
            taskId, HANDOVER_TARGET_REF))
        .as("1 TASK_HANDOVER_TARGET fuer das Ziel").isEqualTo(1);
    // Ziel-Notification (HANDOVER): GENAU EINE (exakte Zahl, nicht "mindestens eine").
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t ON n.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND n.RECIPIENT_REF=?",
            taskId, HANDOVER_TARGET_REF))
        .as("genau eine Ziel-Notification").isEqualTo(1);
    // LAST_ACTIVITY_AT wurde EXAKT auf now() gebumpt: strikt ueber dem erzwungenen Vergangenheitswert
    // UND nahe der aktuellen DB-Zeit (Toleranz fuer App/DB-Uhr-Versatz), nicht nur >=/Sekunde.
    assertThat(lastActivityTicks(taskId))
        .as("LAST_ACTIVITY_AT strikt ueber den Vergangenheitswert gebumpt").isGreaterThan(ticksBefore);
    final java.sql.Timestamp storedLastActivity = jdbcTemplate.queryForObject(
        "SELECT LAST_ACTIVITY_AT FROM [dbo].[TASK] WHERE EXTERNALID = ? AND TENANT_ID = ?",
        java.sql.Timestamp.class, taskId, TENANT_A_ID);
    assertThat(storedLastActivity.getTime())
        .as("LAST_ACTIVITY_AT liegt im engen App-Aufruf-Fenster (= now() des Handover), nicht 2000")
        .isBetween(appBeforeMillis - 2000L, appAfterMillis + 2000L);
  }

  @Test
  @DisplayName(
      "be7_it2_should_stay: HANDOVER shouldStay=true -> Akteur aktiv + ASSIGNEE_FLAG=false;"
          + " Ziel Assignee; Event mit SHOULD_STAY=1")
  void be7_it2_should_stay() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "BE7-IT2-Stay");
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of(
                "exitKind", "HANDOVER", "shouldStay", true, "handedOverToIds",
                List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isNoContent());

    // Akteur bleibt aktiv (kein EXIT), aber ASSIGNEE_FLAG=false.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND p.PARTICIPANT_REF=? AND p.EXIT_KIND IS NULL"
                + " AND p.ASSIGNEE_FLAG=0 AND p.PARTICIPANT_FLAG=1",
            taskId, ASSIGNEE_REF))
        .as("Akteur bleibt aktiver Teilnehmer, de-assignt").isEqualTo(1);
    // UNSUBSCRIBED_AT NICHT gesetzt.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND s.USER_REF=? AND s.UNSUBSCRIBED_AT IS NOT NULL",
            taskId, ASSIGNEE_REF))
        .as("kein UNSUBSCRIBED_AT bei shouldStay").isZero();
    // Ziel Assignee + Event SHOULD_STAY=1.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND p.PARTICIPANT_REF=? AND p.ASSIGNEE_FLAG=1 AND p.EXIT_KIND IS NULL",
            taskId, HANDOVER_TARGET_REF))
        .as("Ziel ist Assignee").isEqualTo(1);
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] h JOIN [dbo].[TASK] t ON h.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND h.FROM_REF=? AND h.SHOULD_STAY=1",
            taskId, ASSIGNEE_REF))
        .as("Event mit SHOULD_STAY=1").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "be7_it3_creator_stay_204_sonst_409: Creator HANDOVER shouldStay=true -> 204 (de-assignt,"
          + " bleibt aktiv); shouldStay=false -> 409")
  void be7_it3_creator_stay_204_sonst_409() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Fall A: Creator shouldStay=false -> 409.
    final UUID taskA = createTask(mvc, om, tokenA, "BE7-IT3-Creator-409");
    mvc.perform(post("/api/v1/tasks/" + taskA + "/exit")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "HANDOVER", "handedOverToIds", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isConflict());

    // Fall B: Creator shouldStay=true -> 204, de-assignt, bleibt aktiv.
    final UUID taskB = createTask(mvc, om, tokenA, "BE7-IT3-Creator-204");
    mvc.perform(post("/api/v1/tasks/" + taskB + "/exit")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of(
                "exitKind", "HANDOVER", "shouldStay", true,
                "handedOverToIds", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND p.PARTICIPANT_REF=? AND p.CREATOR_FLAG=1"
                + " AND p.ASSIGNEE_FLAG=0 AND p.EXIT_KIND IS NULL",
            taskB, USER_A_REF))
        .as("Creator de-assignt, bleibt aktiv (kein EXIT)").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "be7_it4_read_handedOverToIds: GET /tasks/{id} liefert exit.handedOverToIds fuer ausgetretenen"
          + " Akteur; aktiver Assignee -> exit=null")
  void be7_it4_read_handedOverToIds() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "BE7-IT4-Read");
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // Vor Handover: aktiver Assignee -> exit=null.
    final String activeDetail =
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenAssignee))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(om.readTree(activeDetail).get("exit").isNull())
        .as("aktiver Assignee -> exit=null").isTrue();

    // Handover an das Tenant-A-Ziel.
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "HANDOVER", "handedOverToIds", List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isNoContent());

    final String exitedDetail =
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenAssignee))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    final com.fasterxml.jackson.databind.JsonNode exit = om.readTree(exitedDetail).get("exit");
    assertThat(exit.isNull()).as("ausgetretener Akteur -> exit gesetzt").isFalse();
    assertThat(exit.get("kind").asText()).isEqualTo("handover");
    assertThat(exit.get("handedOverToIds").isArray()).isTrue();
    assertThat(exit.get("handedOverToIds").get(0).asText()).isEqualTo(HANDOVER_TARGET_REF.toString());

    // MAJOR (IT4-Archiv): auch die ARCHIV-Ausgabe (filter=left) des ausgetretenen Akteurs muss
    // exit.handedOverToIds liefern - nicht nur das Detail (SPEC F6 / A6: Batch-Enrichment im
    // InboxSnapshotService innerhalb des Generation-Brackets).
    final String archiveJson =
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/tasks/archive?filter=left")
                .header("Authorization", "Bearer " + tokenAssignee))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    final com.fasterxml.jackson.databind.JsonNode items = om.readTree(archiveJson).get("items");
    com.fasterxml.jackson.databind.JsonNode archivedTask = null;
    for (final com.fasterxml.jackson.databind.JsonNode item : items) {
      if (taskId.toString().equals(item.get("id").asText())) {
        archivedTask = item;
        break;
      }
    }
    assertThat(archivedTask)
        .as("ausgetretener Akteur sieht die uebergebene Aufgabe im left-Archiv").isNotNull();
    final com.fasterxml.jackson.databind.JsonNode archiveExit = archivedTask.get("exit");
    assertThat(archiveExit.isNull()).as("Archiv-Eintrag traegt exit").isFalse();
    assertThat(archiveExit.get("kind").asText()).isEqualTo("handover");
    assertThat(archiveExit.get("handedOverToIds").isArray()).isTrue();
    assertThat(archiveExit.get("handedOverToIds").get(0).asText())
        .as("Archiv-exit.handedOverToIds enthaelt das Ziel").isEqualTo(HANDOVER_TARGET_REF.toString());
  }

  @Test
  @DisplayName(
      "be7_it5_validierung_400: self / unknown / UNSUBSCRIBED-mit-Zielen / leer-stay -> 400")
  void be7_it5_validierung_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "BE7-IT5-Validation");
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // self-Ziel -> 400
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "HANDOVER", "handedOverToIds", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isBadRequest());
    // unknown-Ziel -> 400
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "HANDOVER", "handedOverToIds", List.of(UUID.randomUUID().toString())))))
        .andExpect(status().isBadRequest());
    // UNSUBSCRIBED mit Zielen -> 400
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "UNSUBSCRIBED", "handedOverToIds", List.of(USER_B_REF.toString())))))
        .andExpect(status().isBadRequest());
    // leer-stay -> 400
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("exitKind", "HANDOVER", "shouldStay", true))))
        .andExpect(status().isBadRequest());

    // Keine der 400-Antworten darf einen Austritt/Handover erzeugt haben.
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND p.PARTICIPANT_REF=? AND p.EXIT_KIND IS NOT NULL",
            taskId, ASSIGNEE_REF))
        .as("Akteur weiterhin ohne Austritt nach 400ern").isZero();
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] h JOIN [dbo].[TASK] t ON h.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=?",
            taskId))
        .as("kein TASK_HANDOVER-Ereignis nach 400ern").isZero();
  }

  @Test
  @DisplayName(
      "be7_it8_asof_tiebreak: zweiter Handover (andere Ziele) ueberschreibt die sichtbaren"
          + " handedOverToIds (juengstes Ereignis, Tie-Break)")
  void be7_it8_asof_tiebreak() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN,
            "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "BE7-IT8-Tiebreak");
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());

    // 1. Stay-Handover an das Tenant-A-Ziel (Akteur bleibt, kann erneut handovern).
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of(
                "exitKind", "HANDOVER", "shouldStay", true, "handedOverToIds",
                List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isNoContent());
    // 2. Finaler Handover (Austritt) an USER_A (Creator ist aktiv; USER_A als Ziel zulaessig).
    mvc.perform(post("/api/v1/tasks/" + taskId + "/exit")
            .header("Authorization", "Bearer " + tokenAssignee).contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(
                Map.of("exitKind", "HANDOVER", "handedOverToIds", List.of(USER_A_REF.toString())))))
        .andExpect(status().isNoContent());

    // Codex-Re-Gate-Auflage: ECHTEN ID-DESC-Tie-Break erzwingen. Beide Ereignisse des Akteurs auf
    // SHOULD_STAY=0 UND IDENTISCHEN ANGELEGT_AM setzen -> die ANGELEGT_AM-Ordnung ist nicht mehr
    // eindeutig, NUR die hoehere ID (= das finale Ereignis an USER_A) darf das sichtbare Ereignis
    // bestimmen. So wird der Sekundaer-Tie-Break ID DESC end-to-end (bis in die Detail-Anreicherung)
    // geprueft, nicht nur die ANGELEGT_AM-Reihenfolge.
    jdbcTemplate.update(
        "UPDATE h SET h.SHOULD_STAY = 0, h.ANGELEGT_AM = '2026-06-01T12:00:00'"
            + " FROM [dbo].[TASK_HANDOVER] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND h.FROM_REF = ?",
        taskId.toString(), ASSIGNEE_REF);
    assertThat(count(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] h JOIN [dbo].[TASK] t ON h.TASK_ID=t.ID"
                + " WHERE t.EXTERNALID=? AND h.FROM_REF=? AND h.SHOULD_STAY=0"
                + " AND h.ANGELEGT_AM='2026-06-01T12:00:00'",
            taskId, ASSIGNEE_REF))
        .as("zwei sichtbare Gleichstand-Ereignisse (ANGELEGT_AM identisch)").isEqualTo(2);

    // Bei ANGELEGT_AM-Gleichstand entscheidet ID DESC -> das Ereignis mit der hoeheren ID (der finale
    // Handover an USER_A) ist sichtbar -> handedOverToIds = [USER_A], nicht USER_B.
    final String detail =
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenAssignee))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    final com.fasterxml.jackson.databind.JsonNode exit = om.readTree(detail).get("exit");
    assertThat(exit.get("kind").asText()).isEqualTo("handover");
    assertThat(exit.get("handedOverToIds")).hasSize(1);
    assertThat(exit.get("handedOverToIds").get(0).asText())
        .as("Tie-Break ID DESC bei ANGELEGT_AM-Gleichstand -> USER_A, nicht USER_B")
        .isEqualTo(USER_A_REF.toString());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  private Integer count(final String sql, final Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  /** Grobe LAST_ACTIVITY_AT-Ordnungssonde (Millisekunden seit Epoche als Integer-Vergleichswert). */
  private Integer lastActivityTicks(final UUID taskUuid) {
    return jdbcTemplate.queryForObject(
        "SELECT DATEDIFF(SECOND, '2026-01-01', LAST_ACTIVITY_AT) FROM [dbo].[TASK]"
            + " WHERE EXTERNALID = ? AND TENANT_ID = ?",
        Integer.class, taskUuid, TENANT_A_ID);
  }

  private UUID createTask(
      final MockMvc mvc, final ObjectMapper om, final String token, final String title)
      throws Exception {
    final String resp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(resp, com.fasterxml.jackson.databind.JsonNode.class)
        .get("id")
        .traverse(om)
        .readValueAs(UUID.class);
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  /** JWT OHNE jede task-management-Authority (authenticated-only, grant-frei). */
  private static String buildGrantFreeJwt(
      final UUID tenantUuid, final UUID userUuid, final String loginName) {
    return buildJwtWithAuthority(tenantUuid, userUuid, loginName);
  }

  /** Interne TASK.ID zur externen UUID (fuer die direkten TASK_PARTICIPANT-Inserts). */
  private long taskDbId(final UUID taskExternalId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskExternalId);
  }

  /**
   * Fuegt dem Akteur eine aktive WRITE-Member-Teilnehmerzeile (PERSON, EXIT_KIND null) direkt per
   * JDBC hinzu - ohne das Gate-behaftete {@code PUT /assignees}. So kann ein grant-freier Member
   * seine Membership erhalten, ohne dass der Test TASK_WRITE braucht.
   */
  private void insertActiveMember(
      final long partId, final long taskId, final UUID ref, final long participantTenantId) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG,"
            + " ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, 'ACTIVE', 'it', GETDATE(), NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 1, 1,"
            + " NULL, NULL)",
        partId, TENANT_A_ID, taskId, ref, participantTenantId);
  }

  /**
   * Fuegt eine TASK_PARTICIPANT-Zeile mit explizitem Kind/Tenant/Status/Exit direkt per JDBC hinzu
   * (Anomalie-/Reaktivierungs-/409-Szenarien). {@code baseTenantId} = Nova-Basisspalte TENANT_ID,
   * {@code participantTenantId} = fachliche Spalte PARTICIPANT_TENANT_ID.
   */
  private void insertParticipantRow(
      final long partId,
      final long taskId,
      final UUID ref,
      final String kind,
      final long baseTenantId,
      final long participantTenantId,
      final String entityStatus,
      final boolean assignee,
      final String exitKind) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG,"
            + " ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, ?, 'it', GETDATE(), NEWID(), 1, ?, ?, ?, ?, ?, 0, ?, 1, ?,"
            + " CASE WHEN ? IS NULL THEN NULL ELSE GETDATE() END)",
        partId,
        entityStatus,
        baseTenantId,
        taskId,
        ref,
        kind,
        participantTenantId,
        assignee ? 1 : 0,
        exitKind,
        exitKind);
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

  // ============================================================================================
  // Slice 4 (Standard-Selfservice): exit authenticated-only + Membership-Guard-404 + Handover-Grenze
  // + ensureAssignee-Auswahlvertrag (real-MSSQL)
  // ============================================================================================

  @Test
  @DisplayName(
      "s4_exit_grantfrei_unsubscribed_204: grant-freier aktiver WRITE-Member verlaesst die Aufgabe"
          + " (UNSUBSCRIBED) -> 204 (kein TASK_WRITE noetig), EXIT_KIND='UNSUBSCRIBED' persistiert")
  void s4_exit_grantfrei_unsubscribed_204() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-grantfrei-unsub");
    final long dbId = taskDbId(taskId);
    // Grant-freier Member erhaelt seine aktive WRITE-Member-Zeile direkt (ohne TASK_WRITE-Gate).
    insertActiveMember(520001L, dbId, GRANTFREE_REF, TENANT_A_ID);

    final String tokenGrantFree = buildGrantFreeJwt(TENANT_A_UUID, GRANTFREE_REF, GRANTFREE_LOGIN);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenGrantFree)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "UNSUBSCRIBED"))))
        .andExpect(status().isNoContent());

    final Integer exited =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?"
                + " AND EXIT_KIND = 'UNSUBSCRIBED'",
            Integer.class, dbId, GRANTFREE_REF);
    assertThat(exited).as("grant-frei UNSUBSCRIBED persistiert EXIT_KIND").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "s4_exit_non_member_404: grant-freier Nicht-Member exit -> 404 (nicht 400); gueltige +"
          + " ungueltige Ziele -> kein Ziel-Leak, keine Mutation")
  void s4_exit_non_member_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-non-member-404");

    // GRANTFREE2 ist KEIN Member dieser Aufgabe -> 404 (neutral), VOR jeder Zielvalidierung.
    final String tokenNonMember =
        buildGrantFreeJwt(TENANT_A_UUID, GRANTFREE2_REF, GRANTFREE2_LOGIN);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenNonMember)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(UUID.randomUUID().toString())))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "s4_exit_non_member_malformter_body_400: grant-freier Nicht-Member exit mit malformtem Body"
          + " (handedOverToIds mit null-Element) -> 400 (Jackson/Record-Binding), NICHT 404; keine"
          + " Mutation")
  void exit_non_member_malformter_body_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-non-member-malformed-400");
    final long dbId = taskDbId(taskId);

    // GRANTFREE2 ist KEIN Member der Aufgabe. Der Request-Body ist malformt: handedOverToIds traegt
    // ein null-Element. Der kompakte ExitInput-Konstruktor lehnt das null-Element ab
    // (EntityValidationException) -> Jackson wickelt das als ValueInstantiation/
    // HttpMessageNotReadableException -> GlobalApiExceptionHandler mappt beide Pfade auf 400. Der
    // Binding-400 entsteht VOR dem AssigneeService/TaskMembershipGuard; der Membership-404-Vertrag
    // greift nur fuer BINDBARE Requests (Codex-Final-Gate-Abgrenzung). Rohes JSON noetig, da weder
    // Map.of noch List.of null-Elemente zulassen.
    final String malformedBody =
        "{\"exitKind\":\"HANDOVER\",\"handedOverToIds\":[\"" + USER_B_REF + "\",null]}";
    final String tokenNonMember =
        buildGrantFreeJwt(TENANT_A_UUID, GRANTFREE2_REF, GRANTFREE2_LOGIN);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenNonMember)
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedBody))
        .andExpect(status().isBadRequest());

    // KEINE Mutation: der Akteur (Nicht-Member) hat keine Zeile erhalten und keinen Austritt
    // ausgeloest; kein Handover-Ereignis.
    final Integer actorRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?",
            Integer.class, dbId, GRANTFREE2_REF);
    assertThat(actorRows).as("Nicht-Member-Akteur hat keine Teilnehmerzeile (keine Mutation)")
        .isZero();
    final Integer handoverEvents =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] WHERE TASK_ID = ?",
            Integer.class, dbId);
    assertThat(handoverEvents).as("kein Handover-Ereignis beim Binding-400").isZero();
  }

  @Test
  @DisplayName(
      "s4_exit_anonym_401: exit ohne JWT -> 401 (authenticated-only via KSF-Filter-Chain, Gate"
          + " entfernt)")
  void s4_exit_anonym_401() throws Exception {
    final MockMvc mvc = buildMvc();
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/exit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exitKind\":\"UNSUBSCRIBED\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName(
      "s4_handover_an_write_member_grantfrei_204: grant-frei HANDOVER an ein bereits aktives"
          + " WRITE-Member-Ziel -> 204 (kein TASK_WRITE), Ziel bleibt aktiver Assignee")
  void s4_handover_an_write_member_grantfrei_204() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-handover-writemember");
    final long dbId = taskDbId(taskId);
    // Akteur (grant-frei) + Ziel sind BEIDE bereits aktive WRITE-Member.
    insertActiveMember(520101L, dbId, GRANTFREE_REF, TENANT_A_ID);
    insertActiveMember(520102L, dbId, GRANTFREE2_REF, TENANT_A_ID);

    final String tokenGrantFree = buildGrantFreeJwt(TENANT_A_UUID, GRANTFREE_REF, GRANTFREE_LOGIN);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenGrantFree)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(GRANTFREE2_REF.toString())))))
        .andExpect(status().isNoContent());

    final Integer targetAssignee =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?"
                + " AND ASSIGNEE_FLAG = 1 AND EXIT_KIND IS NULL AND ENTITY_STATUS = 'ACTIVE'",
            Integer.class, dbId, GRANTFREE2_REF);
    assertThat(targetAssignee).as("WRITE-Member-Ziel bleibt aktiver Assignee").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "s4_handover_an_nicht_member_grantfrei_403_keine_mutation: grant-frei HANDOVER an ein"
          + " Nicht-WRITE-Member-Ziel -> 403, KEINE Mutation (kein Handover-Ereignis, kein Austritt)")
  void s4_handover_an_nicht_member_grantfrei_403_keine_mutation() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-handover-nonmember-403");
    final long dbId = taskDbId(taskId);
    insertActiveMember(520201L, dbId, GRANTFREE_REF, TENANT_A_ID);

    // HANDOVER_TARGET_REF ist ein gueltiger aktiver Mandant-A-Nutzer, aber KEIN Member der Aufgabe.
    final String tokenGrantFree = buildGrantFreeJwt(TENANT_A_UUID, GRANTFREE_REF, GRANTFREE_LOGIN);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenGrantFree)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isForbidden());

    // KEINE Mutation: kein Handover-Ereignis, Akteur weiterhin aktiv (nicht ausgetreten), Ziel nicht angelegt.
    final Integer handoverEvents =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] WHERE TASK_ID = ? AND FROM_REF = ?",
            Integer.class, dbId, GRANTFREE_REF);
    assertThat(handoverEvents).as("kein Handover-Ereignis (keine Mutation)").isZero();
    final Integer actorStillActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?"
                + " AND EXIT_KIND IS NULL",
            Integer.class, dbId, GRANTFREE_REF);
    assertThat(actorStillActive).as("Akteur bleibt aktiv (kein Austritt)").isEqualTo(1);
    final Integer targetRow =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?",
            Integer.class, dbId, HANDOVER_TARGET_REF);
    assertThat(targetRow).as("Nicht-Member-Ziel wurde NICHT angelegt").isZero();
  }

  @Test
  @DisplayName(
      "s4_handover_an_nicht_member_mit_task_write_204: HANDOVER an Nicht-WRITE-Member MIT TASK_WRITE"
          + " -> 204, Ziel wird neuer aktiver Assignee")
  void s4_handover_an_nicht_member_mit_task_write_204() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // Akteur ist Creator (TASK_WRITE) + Nicht-Creator-Austritt verboten -> stattdessen separaten
    // Nicht-Creator-Member mit TASK_WRITE nutzen: USER_A legt an, weist ASSIGNEE_REF zu, ASSIGNEE_REF
    // hat TASK_WRITE und uebergibt an HANDOVER_TARGET_REF (Nicht-Member).
    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-handover-grant-204");
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenCreator)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());
    final long dbId = taskDbId(taskId);

    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isNoContent());

    final Integer targetAssignee =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?"
                + " AND ASSIGNEE_FLAG = 1 AND EXIT_KIND IS NULL AND PARTICIPANT_KIND = 'PERSON'",
            Integer.class, dbId, HANDOVER_TARGET_REF);
    assertThat(targetAssignee).as("neues Ziel ist aktiver Assignee (mit TASK_WRITE)").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "s4_ensureAssignee_reaktiviert_inaktive_exited_person_active: HANDOVER an eine inaktive +"
          + " ausgetretene PERSON-Zeile (gleicher Unique-Key) -> 204, Zeile VOLL auf ACTIVE/Assignee")
  void s4_ensureAssignee_reaktiviert_inaktive_exited_person_active() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-reactivate-exited");
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenCreator)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());
    final long dbId = taskDbId(taskId);

    // HANDOVER_TARGET_REF: inaktive (DEACTIVE) + ausgetretene (EXIT_KIND) PERSON-Zeile, task-tenant.
    insertParticipantRow(
        520301L, dbId, HANDOVER_TARGET_REF, "PERSON", TENANT_A_ID, TENANT_A_ID, "DEACTIVE",
        false, "UNSUBSCRIBED");

    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isNoContent());

    final Integer reactivated =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ? AND ENTITY_STATUS = 'ACTIVE'"
                + " AND ASSIGNEE_FLAG = 1 AND PARTICIPANT_FLAG = 1 AND EXIT_KIND IS NULL",
            Integer.class, 520301L);
    assertThat(reactivated)
        .as("inaktive/exited PERSON-Zeile voll auf ACTIVE reaktiviert (gleicher Unique-Key)")
        .isEqualTo(1);
    // Genau EIN aktiver PERSON-Assignee fuer das Ziel (keine Dublette per Insert).
    final Integer activeAssignees =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID = ? AND PARTICIPANT_REF = ?"
                + " AND ASSIGNEE_FLAG = 1 AND EXIT_KIND IS NULL AND ENTITY_STATUS = 'ACTIVE'",
            Integer.class, dbId, HANDOVER_TARGET_REF);
    assertThat(activeAssignees).as("genau EIN aktiver Assignee").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "s4_ensureAssignee_team_zeile_409_keine_mutation: HANDOVER-Ziel hat TEAM-Zeile (gleicher"
          + " Unique-Key) -> mit TASK_WRITE 409, KEINE Mutation (voller Rollback)")
  void s4_ensureAssignee_team_zeile_409_keine_mutation() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-team-409");
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenCreator)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(ASSIGNEE_REF.toString())))))
        .andExpect(status().isNoContent());
    final long dbId = taskDbId(taskId);

    // HANDOVER_TARGET_REF: TEAM-Zeile (task-tenant). Grant-frei waere 403 (Nicht-WRITE-Member); erst
    // MIT TASK_WRITE erreicht ensureAssignee die TEAM-Zeile -> 409, keine Mutation.
    insertParticipantRow(
        520401L, dbId, HANDOVER_TARGET_REF, "TEAM", TENANT_A_ID, TENANT_A_ID, "ACTIVE", false, null);

    final String tokenAssignee =
        buildJwtWithAuthority(
            TENANT_A_UUID, ASSIGNEE_REF, ASSIGNEE_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenAssignee)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(HANDOVER_TARGET_REF.toString())))))
        .andExpect(status().isConflict());

    // Keine Mutation: TEAM bleibt TEAM, kein Assignee-Flip, kein Handover-Ereignis (voller Rollback).
    final Integer teamUnchanged =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ? AND PARTICIPANT_KIND = 'TEAM'"
                + " AND ASSIGNEE_FLAG = 0",
            Integer.class, 520401L);
    assertThat(teamUnchanged).as("TEAM-Zeile unveraendert (fail-closed 409)").isEqualTo(1);
    final Integer handoverEvents =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] WHERE TASK_ID = ? AND FROM_REF = ?",
            Integer.class, dbId, ASSIGNEE_REF);
    assertThat(handoverEvents).as("kein Handover-Ereignis (voller Rollback)").isZero();
  }

  @Test
  @DisplayName(
      "s4_exit_akteur_zwei_zeilen_nur_task_tenant_zeile_mutiert: Akteur hat task-tenant-Zeile +"
          + " fremd-PARTICIPANT_TENANT_ID-Zeile -> grant-frei UNSUBSCRIBED 204; nur task-tenant-Zeile"
          + " traegt EXIT_KIND, fremde Zeile unveraendert")
  void s4_exit_akteur_zwei_zeilen_nur_task_tenant_zeile_mutiert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenCreator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskId = createTask(mvc, om, tokenCreator, "S4-zwei-zeilen-akteur");
    final long dbId = taskDbId(taskId);

    // Zeile 1: gueltige task-tenant WRITE-Member-Zeile (PARTICIPANT_TENANT_ID = TENANT_A).
    insertActiveMember(520501L, dbId, GRANTFREE_REF, TENANT_A_ID);
    // Zeile 2: zweite Zeile desselben Akteurs mit fremdem PARTICIPANT_TENANT_ID (eigener Unique-Key).
    insertParticipantRow(
        520502L, dbId, GRANTFREE_REF, "PERSON", TENANT_A_ID, TENANT_B_ID, "ACTIVE", true, null);

    final String tokenGrantFree = buildGrantFreeJwt(TENANT_A_UUID, GRANTFREE_REF, GRANTFREE_LOGIN);
    mvc.perform(
            post("/api/v1/tasks/" + taskId + "/exit")
                .header("Authorization", "Bearer " + tokenGrantFree)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("exitKind", "UNSUBSCRIBED"))))
        .andExpect(status().isNoContent());

    // Nur die task-tenant-Zeile (520501) traegt EXIT_KIND.
    final String taskTenantExit =
        jdbcTemplate.queryForObject(
            "SELECT EXIT_KIND FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?", String.class, 520501L);
    assertThat(taskTenantExit).as("task-tenant-Zeile ausgetreten").isEqualTo("UNSUBSCRIBED");
    // Die fremd-tenant-Zeile (520502) bleibt UNVERAENDERT (EXIT_KIND NULL).
    final Integer foreignUnchanged =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ? AND EXIT_KIND IS NULL"
                + " AND PARTICIPANT_TENANT_ID = ?",
            Integer.class, 520502L, TENANT_B_ID);
    assertThat(foreignUnchanged).as("fremd-tenant-Zeile unveraendert (deterministische Row-Wahl)")
        .isEqualTo(1);
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
        "de.brasi.nova.taskmanagement.time",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
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
    // Slice 4: AssigneeService braucht den TaskMembershipGuard (member-scoped exit-Guard). Der
    // PermissionEvaluator (programmatischer TASK_WRITE-Check) kommt aus KsfSecurityAutoConfiguration.
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "assignee-it";
    }
  }
}
