package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.ApiError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer den {@link CommentController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 *
 * <ul>
 *   <li>POST /comments -> TASK_COMMENT persistiert, COMMENT_COUNT++, LAST_ACTIVITY_AT aktualisiert,
 *       MENTION-Notification fuer erwaehnte Benutzer vorhanden
 *   <li>POST /comments mit parentCommentId -> PARENT_COMMENT_ID gesetzt, REPLY-Notification
 *       emittiert
 *   <li>Anonym -> 401; falsche Permission -> 403; unbekannte Task-UUID -> 404
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = CommentControllerIT.ItConfig.class,
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
class CommentControllerIT {

  private static final String TEST_KID = "comment-it-key";

  /** Mandant A: Haupt-Tenant fuer positive Tests. */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-comment@brasi.de";
  private static final long USER_A_DB_ID = 561L;

  /** Zweiter Benutzer: wird als @mention-Ziel und Eltern-Kommentar-Autor eingesetzt. */
  private static final UUID USER_B_REF = UUID.randomUUID();

  private static final String USER_B_LOGIN = "user-b-comment";
  private static final long USER_B_DB_ID = 562L;

  /** User mit READ-Only-Recht (kein TASK_WRITE -> 403). */
  private static final UUID USER_READ_ONLY_REF = UUID.randomUUID();

  private static final String USER_READ_ONLY_LOGIN = "read-only-comment@brasi.de";

  /** Mandant B: fremder Tenant fuer 404-Test. */
  private static final long TENANT_B_ID = 563L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_C_REF = UUID.randomUUID();
  private static final String USER_C_LOGIN = "user-c-comment@brasi.de";
  private static final long USER_C_DB_ID = 564L;

  /**
   * Weitere aktive Nutzer des Mandanten A fuer die Aufnahme per Erwaehnung (Intake #156): D ist das
   * erwaehnte Nicht-Mitglied, E ein erwaehntes Mitglied.
   */
  private static final UUID USER_D_REF = UUID.randomUUID();

  private static final String USER_D_LOGIN = "user-d-comment@brasi.de";
  private static final long USER_D_DB_ID = 565L;
  private static final UUID USER_E_REF = UUID.randomUUID();
  private static final long USER_E_DB_ID = 566L;

  /** Inaktiver Benutzer des Mandanten A (ENTITY_STATUS != ACTIVE): nie ein Erwaehnungsziel. */
  private static final UUID USER_INACTIVE_REF = UUID.randomUUID();

  private static final long USER_INACTIVE_DB_ID = 567L;

  /** Die Aufgaben-UUID in den OpenAPI-Beispielen (wird durch die verwendete ersetzt). */
  private static final String EXAMPLE_TASK_ID = CommentApiExamples.TASK_REF;

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
      pemTempFile = Files.createTempFile("comment-it-jwk-", ".pem");
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
  @Autowired private GlobalApiExceptionHandler apiExceptionHandler;
  @Autowired private ObjectMapper contextObjectMapper;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_C_DB_ID, USER_C_REF, USER_C_LOGIN, TENANT_B_ID);
    seedBenutzerIfAbsent(jt, USER_D_DB_ID, USER_D_REF, USER_D_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_E_DB_ID, USER_E_REF, "user-e-comment@brasi.de", TENANT_A_ID);
    seedBenutzerIfAbsent(
        jt, USER_INACTIVE_DB_ID, USER_INACTIVE_REF, "user-inactive-comment@brasi.de", TENANT_A_ID);
    jt.update(
        "UPDATE [dbo].[PROD_BENUTZER] SET ENTITY_STATUS = 'INACTIVE' WHERE ID = ?",
        USER_INACTIVE_DB_ID);
  }

  // ---- IT 1: POST comment -> persistiert + COMMENT_COUNT++ + LAST_ACTIVITY_AT +
  // MENTION-Notification

  @Test
  @DisplayName(
      "post_comment_persistiert_zaehlt_hoch_und_benachrichtigt:"
          + " POST -> TASK_COMMENT-Zeile + COMMENT_COUNT+1 + LAST_ACTIVITY_AT aktualisiert"
          + " + MENTION-Notification fuer @user-b-comment")
  void post_comment_persistiert_zaehlt_hoch_und_benachrichtigt() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Task anlegen
    final String createBody = om.writeValueAsString(Map.of("title", "Comment-IT-Task-1"));
    final String postTaskResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final UUID taskUuid =
        om.readValue(postTaskResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(taskUuid).isNotNull();

    // Slice 4 (Auflage 4): Mention-Empfaenger sind auf die WRITE-Member-Menge begrenzt. Damit die
    // MENTION-Notification fuer USER_B tatsaechlich entsteht, muss USER_B aktiver WRITE-Member
    // sein.
    insertActiveMember(560101L, taskDbId(taskUuid), USER_B_REF);

    // COMMENT_COUNT vor dem Kommentar
    final Integer countBefore =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);

    // Kommentar mit STRUKTURIERTER Mention posten (Ziel-GUID, NICHT aus dem Text geparst).
    final String commentBody =
        om.writeValueAsString(
            Map.of("text", "Hallo, bitte pruefen.", "mentions", List.of(USER_B_REF)));
    final String commentResponse =
        mvc.perform(
                post("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(commentBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.taskId").value(taskUuid.toString()))
            .andExpect(jsonPath("$.edited").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(commentResponse).isNotBlank();

    // Beweis 1: TASK_COMMENT-Zeile existiert
    final Integer commentCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c"
                + " JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND c.AUTHOR_REF = ?"
                + " AND c.PARENT_COMMENT_ID IS NULL",
            Integer.class,
            taskUuid,
            TENANT_A_ID,
            USER_A_REF);
    assertThat(commentCount)
        .as("TASK_COMMENT-Zeile muss fuer AUTHOR_REF=USER_A_REF existieren")
        .isGreaterThanOrEqualTo(1);

    // Beweis 2: COMMENT_COUNT wurde um 1 erhoeht
    final Integer countAfter =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);
    assertThat(countAfter).as("COMMENT_COUNT muss um 1 gestiegen sein").isEqualTo(countBefore + 1);

    // Beweis 3: LAST_ACTIVITY_AT wurde aktualisiert (nicht null)
    final Object lastActivity =
        jdbcTemplate.queryForObject(
            "SELECT LAST_ACTIVITY_AT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            Object.class,
            taskUuid);
    assertThat(lastActivity).as("LAST_ACTIVITY_AT muss gesetzt sein").isNotNull();

    // Beweis 4: MENTION-Notification fuer USER_B_REF (TYPE_ID=404=MENTION)
    final Integer mentionNotifCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND n.TYPE_ID = 404",
            Integer.class,
            taskUuid,
            USER_B_REF);
    assertThat(mentionNotifCount)
        .as("MENTION-Notification fuer USER_B_REF muss existieren")
        .isGreaterThanOrEqualTo(1);
  }

  // ---- IT I1 (Intake #136): Top-Level-Kommentar -> COMMENT-Notification fuer Assignee, NICHT
  // Autor

  @Test
  @DisplayName(
      "i1_top_level_comment_benachrichtigt_assignee_nicht_autor (Intake #136):"
          + " USER_A (Autor) postet Top-Level-Kommentar, USER_B ist aktiver Assignee"
          + " -> TASK_NOTIFICATION Typ COMMENT (TYPE_ID=409) fuer USER_B, KEIN COMMENT fuer USER_A")
  void i1_top_level_comment_benachrichtigt_assignee_nicht_autor() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-136-Assignee");
    final long taskId = taskDbId(taskUuid);

    // USER_B als aktiven Assignee (ASSIGNEE_FLAG=1, EXIT_KIND null) direkt per JDBC setzen.
    insertActiveMember(560501L, taskId, USER_B_REF);

    // Top-Level-Kommentar OHNE jede Mention -> COMMENT entsteht rein aus der Assignee-Regel.
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Top-Level, bitte ansehen."))))
        .andExpect(status().isCreated());

    // COMMENT-Notification (TYPE_ID=409) fuer den Assignee USER_B.
    final Integer commentNotifB =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND n.TYPE_ID = 409",
            Integer.class,
            taskUuid,
            USER_B_REF);
    assertThat(commentNotifB)
        .as("COMMENT-Notification fuer Assignee USER_B muss existieren")
        .isEqualTo(1);

    // KEIN COMMENT fuer den Autor USER_A (Self-Exclusion), auch falls er selbst Assignee ist.
    final Integer commentNotifA =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND n.TYPE_ID = 409",
            Integer.class,
            taskUuid,
            USER_A_REF);
    assertThat(commentNotifA)
        .as("Autor USER_A darf KEINE COMMENT-Notification erhalten (Self-Exclusion)")
        .isEqualTo(0);
  }

  // ---- IT Intake #158: stummgeschaltete Aufgabe -> kein COMMENT/DONE, aber MENTION und REPLY ---

  @Test
  @DisplayName(
      "i158_stumme_aufgabe_unterdrueckt_comment_und_done_nicht_mention_und_reply (Intake #158):"
          + " USER_B (Assignee) schaltet stumm, USER_READ_ONLY (Assignee ohne UserState) bleibt"
          + " laut -> Top-Level-Kommentar und Schliessen benachrichtigen nur den lauten, die"
          + " @-Erwaehnung und die Antwort erreichen USER_B trotzdem")
  void i158_stumme_aufgabe_unterdrueckt_comment_und_done_nicht_mention_und_reply()
      throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_B_REF,
            USER_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-158-Mute");
    final long taskId = taskDbId(taskUuid);

    // USER_B ueber den echten Zuweisungspfad (Participant + TaskUserState) zuweisen, dann
    // stummschalten ueber den Marker-Endpunkt.
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(USER_B_REF)))))
        .andExpect(status().isNoContent());
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/mute")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().isNoContent());
    // Lauter Diskriminator: aktiver Assignee OHNE TaskUserState (fehlender State = nicht stumm).
    insertActiveMember(560581L, taskId, USER_READ_ONLY_REF);

    // (1) Top-Level-Kommentar ohne Mention: COMMENT nur fuer den lauten Assignee.
    postComment(mvc, om, tokenA, taskUuid, "Top-Level an alle.", null);
    assertThat(countNotifications(taskUuid, USER_READ_ONLY_REF, "COMMENT"))
        .as("lauter Assignee erhaelt COMMENT (Positivkontrolle)")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("stummer Assignee USER_B darf KEIN COMMENT erhalten")
        .isZero();

    // (2) @-Erwaehnung von USER_B: MENTION kommt trotz Mute an.
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of("text", "@user-b bitte ansehen.", "mentions", List.of(USER_B_REF)))))
        .andExpect(status().isCreated());
    assertThat(countNotifications(taskUuid, USER_B_REF, "MENTION"))
        .as("MENTION erreicht den stummen USER_B")
        .isEqualTo(1);

    // (3) Antwort auf einen eigenen Kommentar von USER_B: REPLY kommt trotz Mute an.
    final UUID ownComment = postComment(mvc, om, tokenB, taskUuid, "Eigener Kommentar B.", null);
    postComment(mvc, om, tokenA, taskUuid, "Antwort an B.", ownComment);
    assertThat(countNotifications(taskUuid, USER_B_REF, "REPLY"))
        .as("REPLY erreicht den stummen USER_B")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("auch nach weiteren Kommentaren kein COMMENT fuer USER_B")
        .isZero();

    // (4) Schliessen (DONE): nur der laute Beteiligte wird benachrichtigt.
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", "DONE"))))
        .andExpect(status().is2xxSuccessful());
    assertThat(countNotifications(taskUuid, USER_READ_ONLY_REF, "DONE"))
        .as("lauter Beteiligter erhaelt DONE (Positivkontrolle)")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_B_REF, "DONE"))
        .as("stummer USER_B darf KEIN DONE erhalten")
        .isZero();

    // (5) Stummschaltung gilt je Aufgabe: die Query liest nur die stummen States der EIGENEN
    // Aufgabe (ohne Empfaengerliste, TASK_ID ist der einzige Diskriminator). Auf einer anderen
    // Aufgabe bleibt USER_B laut.
    final UUID otherTaskUuid = createTask(mvc, om, tokenA, "Comment-IT-158-Other");
    mvc.perform(
            put("/api/v1/tasks/" + otherTaskUuid + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(USER_B_REF)))))
        .andExpect(status().isNoContent());
    postComment(mvc, om, tokenA, otherTaskUuid, "Top-Level auf anderer Aufgabe.", null);
    assertThat(countNotifications(otherTaskUuid, USER_B_REF, "COMMENT"))
        .as("USER_B hat nur die erste Aufgabe stummgeschaltet und erhaelt COMMENT auf der anderen")
        .isEqualTo(1);
  }

  // ---- IT Intake #158 (Folge): eine NEUE Zuweisung hebt den Mute auf --------------------------

  @Test
  @DisplayName(
      "i158_neue_zuweisung_per_put_hebt_mute_auf_unveraenderte_nicht (Intake #158, Folge):"
          + " USER_B (stumm, ausgetreten) wird per PUT neu zugewiesen -> entstummt, ein Top-Level-"
          + " Kommentar erreicht ihn; nach erneutem Stummschalten laesst ein PUT ohne Aenderung den"
          + " Mute stehen")
  void i158_neue_zuweisung_per_put_hebt_mute_auf_unveraenderte_nicht() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_B_REF,
            USER_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-158-Reassign");

    // (1) USER_B zuweisen und stummschalten: Top-Level-Kommentar erreicht ihn nicht.
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    putMute(mvc, tokenB, taskUuid, true);
    assertThat(isMuted(taskUuid, USER_B_REF)).as("USER_B ist stumm").isTrue();
    postComment(mvc, om, tokenA, taskUuid, "Top-Level vor der Neuzuweisung.", null);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("stummer USER_B erhaelt KEIN COMMENT (Positivkontrolle der #158-Regel)")
        .isZero();

    // (2) USER_B faellt aus der Ziel-Menge (Austritt, State bleibt stumm) und wird dann neu
    // zugewiesen: der Mute ist aufgehoben.
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF);
    assertThat(isMuted(taskUuid, USER_B_REF))
        .as("der Austritt ueber PUT aendert den Mute-Marker nicht")
        .isTrue();
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    assertThat(isMuted(taskUuid, USER_B_REF)).as("neue Zuweisung hebt den Mute auf").isFalse();

    // (3) Der Top-Level-Kommentar erreicht USER_B jetzt.
    postComment(mvc, om, tokenA, taskUuid, "Top-Level nach der Neuzuweisung.", null);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("entstummter USER_B erhaelt COMMENT")
        .isEqualTo(1);

    // (4) Erneut stummschalten: ein PUT mit unveraendert bleibender Ziel-Menge laesst den Mute
    // stehen, es entsteht kein weiteres COMMENT.
    putMute(mvc, tokenB, taskUuid, true);
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    assertThat(isMuted(taskUuid, USER_B_REF)).as("unveraenderter Assignee bleibt stumm").isTrue();
    postComment(mvc, om, tokenA, taskUuid, "Top-Level nach dem unveraenderten PUT.", null);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("weiterhin stummer USER_B erhaelt kein weiteres COMMENT")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i158_uebergabe_hebt_mute_des_ziels_auf (Intake #158, Folge): USER_B (stumm, ausgetreten)"
          + " ist HANDOVER-Ziel -> entstummt, ein Top-Level-Kommentar erreicht ihn; ist USER_B"
          + " schon aktiver Assignee und stumm, laesst eine Uebergabe den Mute stehen")
  void i158_uebergabe_hebt_mute_des_ziels_auf() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_B_REF,
            USER_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-158-Handover");
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    putMute(mvc, tokenB, taskUuid, true);
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF);
    assertThat(isMuted(taskUuid, USER_B_REF)).as("USER_B ist ausgetreten und stumm").isTrue();

    // Der Ersteller uebergibt an USER_B und bleibt als Teilnehmer.
    handoverStay(mvc, om, tokenA, taskUuid, USER_B_REF);

    assertThat(isMuted(taskUuid, USER_B_REF)).as("Uebergabe hebt den Mute auf").isFalse();
    postComment(mvc, om, tokenA, taskUuid, "Top-Level nach der Uebergabe.", null);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("USER_B als entstummtes Uebergabeziel erhaelt COMMENT")
        .isEqualTo(1);

    // USER_B ist jetzt aktiver Assignee. Schaltet er stumm und der Ersteller uebergibt erneut an
    // ihn, ist das keine neue Zuweisung: der Mute bleibt, ein Top-Level-Kommentar erreicht ihn
    // nicht (HANDOVER selbst kommt trotzdem an).
    putMute(mvc, tokenB, taskUuid, true);
    final int handoverBefore = countNotifications(taskUuid, USER_B_REF, "HANDOVER");
    handoverStay(mvc, om, tokenA, taskUuid, USER_B_REF);
    assertThat(isMuted(taskUuid, USER_B_REF))
        .as("Uebergabe an einen bereits aktiven Assignee laesst den Mute stehen")
        .isTrue();
    assertThat(countNotifications(taskUuid, USER_B_REF, "HANDOVER"))
        .as("HANDOVER kommt trotz Mute an")
        .isEqualTo(handoverBefore + 1);
    postComment(mvc, om, tokenA, taskUuid, "Top-Level nach der zweiten Uebergabe.", null);
    assertThat(countNotifications(taskUuid, USER_B_REF, "COMMENT"))
        .as("weiterhin stummer USER_B erhaelt kein weiteres COMMENT")
        .isEqualTo(1);
  }

  // ---- IT Intake #147: POST /participants benachrichtigt neu hinzugefuegte Personen -----------

  @Test
  @DisplayName(
      "i147_teilnehmer_hinzufuegen_benachrichtigt_neue_person_genau_einmal_nicht_den_akteur"
          + " (Intake #147): USER_A fuegt USER_B per POST /participants hinzu -> genau eine"
          + " PARTICIPANT-Benachrichtigung fuer USER_B (Schwere INFO, Vorschau = Titel, Akteur ="
          + " USER_A), keine fuer USER_A; erneutes Hinzufuegen und Mengen mit aktiven Mitgliedern"
          + " erzeugen keine weitere")
  void i147_add_participant_notifies_new_person_once_never_the_actor() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-147-Neu");

    postParticipants(mvc, om, tokenA, taskUuid, USER_B_REF);

    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT"))
        .as("die neu hinzugefuegte Person erhaelt genau eine PARTICIPANT-Benachrichtigung")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_A_REF, "PARTICIPANT"))
        .as("der Akteur erhaelt keine PARTICIPANT-Benachrichtigung")
        .isZero();
    final Map<String, Object> row = notificationRow(taskUuid, USER_B_REF, "PARTICIPANT");
    assertThat(row.get("TEXT")).isEqualTo("Du wurdest als Beteiligter zur Aufgabe hinzugefuegt.");
    assertThat(row.get("PREVIEW")).as("Vorschau = Aufgabentitel").isEqualTo("Comment-IT-147-Neu");
    assertThat(row.get("SEVERITY")).isEqualTo("INFO");
    assertThat(String.valueOf(row.get("ACTOR_REF"))).isEqualToIgnoringCase(USER_A_REF.toString());
    assertThat(row.get("IS_READ")).as("ungelesen").isEqualTo(false);

    // Erneutes Hinzufuegen (idempotent), auch zusammen mit dem Akteur (Ersteller, aktiv): keine
    // weitere Benachrichtigung, weder fuer USER_B noch fuer USER_A.
    postParticipants(mvc, om, tokenA, taskUuid, USER_B_REF);
    postParticipants(mvc, om, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT"))
        .as("schon aktives Mitglied wird nicht erneut benachrichtigt")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_A_REF, "PARTICIPANT")).isZero();

    // Auch ein aktiver Assignee (hier: USER_B auf einer anderen Aufgabe) ist schon Mitglied.
    final UUID assignedTask = createTask(mvc, om, tokenA, "Comment-IT-147-Assignee");
    putAssignees(mvc, om, tokenA, assignedTask, USER_A_REF, USER_B_REF);
    postParticipants(mvc, om, tokenA, assignedTask, USER_B_REF);
    assertThat(countNotifications(assignedTask, USER_B_REF, "PARTICIPANT"))
        .as("aktiver Assignee ist schon Mitglied: kein PARTICIPANT")
        .isZero();
    assertThat(countNotifications(assignedTask, USER_B_REF, "ASSIGN"))
        .as("die Zuweisung selbst wurde benachrichtigt (Gegenprobe)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i147_stummer_ausgetretener_teilnehmer_wird_reaktiviert_und_benachrichtigt_mute_bleibt"
          + " (Intake #147): USER_B (stumm, per PUT ausgetreten) wird per POST /participants"
          + " reaktiviert -> bekommt PARTICIPANT trotz Mute, der Mute bleibt stehen (kein DONE"
          + " danach), ein zweites Hinzufuegen loest nichts aus; nach erneutem Austritt kommt eine"
          + " neue Benachrichtigung")
  void i147_muted_exited_participant_is_reactivated_and_notified_mute_stays() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_B_REF,
            USER_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-147-Mute");
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF, USER_B_REF);
    putMute(mvc, tokenB, taskUuid, true);
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF);
    assertThat(isMuted(taskUuid, USER_B_REF)).as("USER_B ist ausgetreten und stumm").isTrue();
    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT")).isZero();

    postParticipants(mvc, om, tokenA, taskUuid, USER_B_REF);

    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT"))
        .as("PARTICIPANT kommt trotz Mute an (direkt persoenlich wie ASSIGN und HANDOVER)")
        .isEqualTo(1);
    assertThat(isMuted(taskUuid, USER_B_REF))
        .as("das Hinzufuegen als Beteiligter hebt den Mute nicht auf")
        .isTrue();
    assertThat(countActiveParticipantRows(taskUuid, USER_B_REF))
        .as("USER_B ist wieder aktives Mitglied (reiner Teilnehmer)")
        .isEqualTo(1);

    // Der Mute wirkt weiter: Schliessen (DONE) erreicht den stummen Teilnehmer nicht.
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", "DONE"))))
        .andExpect(status().is2xxSuccessful());
    assertThat(countNotifications(taskUuid, USER_B_REF, "DONE"))
        .as("stummer Teilnehmer erhaelt kein DONE")
        .isZero();

    // Idempotent: ein zweites Hinzufuegen erzeugt keine weitere Benachrichtigung.
    postParticipants(mvc, om, tokenA, taskUuid, USER_B_REF);
    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT")).isEqualTo(1);

    // Tritt USER_B aus (UNSUBSCRIBED) und wird erneut hinzugefuegt, ist das eine neue Aufnahme.
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/exit")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exitKind\":\"UNSUBSCRIBED\"}"))
        .andExpect(status().isNoContent());
    assertThat(countActiveParticipantRows(taskUuid, USER_B_REF)).isZero();
    postParticipants(mvc, om, tokenA, taskUuid, USER_B_REF);
    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT"))
        .as("erneute Aufnahme nach dem Austritt benachrichtigt wieder")
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "i147_unbekannte_person_in_der_menge_400_keine_benachrichtigung (Intake #147): alles oder"
          + " nichts, bei 400 entsteht weder ein Teilnehmer noch eine PARTICIPANT-Benachrichtigung"
          + " (auch nicht fuer die gueltigen Personen der Menge)")
  void i147_unknown_person_in_set_returns_400_without_notification() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-147-400");

    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/participants")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of("participantIds", List.of(USER_B_REF, UUID.randomUUID())))))
        .andExpect(status().isBadRequest());

    assertThat(countNotifications(taskUuid, USER_B_REF, "PARTICIPANT")).isZero();
    assertThat(countActiveParticipantRows(taskUuid, USER_B_REF)).isZero();
  }

  // ---- Intake #156: Erwaehnung nimmt Nicht-Mitglieder als Beteiligte auf ------------------------

  @Test
  @DisplayName(
      "i156_erwaehnung_nimmt_nichtmitglied_als_beteiligten_auf_und_meldet_nur_mention (Intake"
          + " #156): USER_A kommentiert und erwaehnt USER_D (aktiver Nutzer des Mandanten, kein"
          + " Mitglied) -> D ist reiner Teilnehmer (nicht Assignee), hat genau eine MENTION und"
          + " keine PARTICIPANT, die Aufgabe steht in Ds Inbox, Ds Generation ist gebumpt; ein"
          + " spaeteres POST /participants mit D ist ein No-Op ohne PARTICIPANT")
  void i156_mention_adds_non_member_as_participant_and_notifies_only_mention() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenD = buildGrantFreeJwt(TENANT_A_UUID, USER_D_REF, USER_D_LOGIN);
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-Aufnahme");

    assertThat(participantRowCount(taskUuid, USER_D_REF)).as("D ist kein Mitglied").isZero();
    assertThat(snapshotTaskIds(mvc, om, tokenD)).doesNotContain(taskUuid);
    final long generationBefore = inboxGeneration(USER_D_REF);

    postCommentWithMentions(mvc, om, tokenA, taskUuid, "Bitte ansehen.", USER_D_REF);

    final Map<String, Object> row = participantRow(taskUuid, USER_D_REF);
    assertThat(row.get("PARTICIPANT_FLAG")).as("Beteiligter").isEqualTo(true);
    assertThat(row.get("ASSIGNEE_FLAG")).as("nie Assignee").isEqualTo(false);
    assertThat(row.get("CREATOR_FLAG")).isEqualTo(false);
    assertThat(row.get("PARTICIPANT_KIND")).isEqualTo("PERSON");
    assertThat(row.get("EXIT_KIND")).as("aktives Mitglied").isNull();
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION"))
        .as("genau eine MENTION")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT"))
        .as("keine zusaetzliche PARTICIPANT-Benachrichtigung")
        .isZero();
    assertThat(countNotifications(taskUuid, USER_D_REF, "COMMENT"))
        .as("reiner Teilnehmer ist kein COMMENT-Empfaenger")
        .isZero();
    final Map<String, Object> mention = notificationRow(taskUuid, USER_D_REF, "MENTION");
    assertThat(mention.get("TEXT")).isEqualTo("Du wurdest in einem Kommentar erwaehnt.");
    assertThat(String.valueOf(mention.get("ACTOR_REF")))
        .isEqualToIgnoringCase(USER_A_REF.toString());
    assertThat(snapshotTaskIds(mvc, om, tokenD))
        .as("die Aufgabe steht in Ds Inbox")
        .contains(taskUuid);
    assertThat(inboxGeneration(USER_D_REF))
        .as("Ds Generation ist gebumpt (alte Cursor werden mit 410 abgelehnt)")
        .isGreaterThan(generationBefore);

    // D ist jetzt volles Mitglied: er darf lesen und kommentieren, ohne ein Recht zu haben.
    mvc.perform(
            get("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenD))
        .andExpect(status().isOk());
    postComment(mvc, om, tokenD, taskUuid, "Danke, ich schaue es mir an.", null);

    // Ein spaeteres POST /participants mit D ist ein No-Op (D ist schon aktives Mitglied).
    postParticipants(mvc, om, tokenA, taskUuid, USER_D_REF);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT")).isZero();
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION")).isEqualTo(1);
    assertThat(countActiveParticipantRows(taskUuid, USER_D_REF)).isEqualTo(1);

    // Wird D erneut erwaehnt, ist er Mitglied: wieder eine MENTION, keine neue Aufnahme.
    postCommentWithMentions(mvc, om, tokenA, taskUuid, "Noch einmal an D.", USER_D_REF);
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION")).isEqualTo(2);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT")).isZero();
    assertThat(participantRowCount(taskUuid, USER_D_REF)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i156_kommentierender_ohne_hinzufuegen_recht_verwirft_nichtmitglieder_still (Intake #156):"
          + " USER_B (Mitglied, nur TASK_READ) erwaehnt USER_D (kein Mitglied) und USER_E"
          + " (Mitglied) -> 201, D bleibt Nicht-Mitglied ohne Benachrichtigung, E bekommt seine"
          + " MENTION; dieselbe Erwaehnung durch USER_A (mit TASK_WRITE) nimmt D auf")
  void i156_commenter_without_add_right_discards_non_members_silently() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenBReadOnly =
        buildJwtWithAuthority(TENANT_A_UUID, USER_B_REF, USER_B_LOGIN, "task-management:TASK_READ");
    final String tokenD = buildGrantFreeJwt(TENANT_A_UUID, USER_D_REF, USER_D_LOGIN);
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-OhneRecht");
    final long taskId = taskDbId(taskUuid);
    insertActiveMember(560701L, taskId, USER_B_REF);
    insertActiveMember(560702L, taskId, USER_E_REF);

    postCommentWithMentions(
        mvc, om, tokenBReadOnly, taskUuid, "Bitte pruefen.", USER_D_REF, USER_E_REF);

    assertThat(participantRowCount(taskUuid, USER_D_REF))
        .as("D wurde nicht aufgenommen (kein Recht, keine Zeile, auch keine ausgetretene)")
        .isZero();
    assertThat(userStateRowCount(taskUuid, USER_D_REF)).as("kein persoenlicher State").isZero();
    assertThat(notificationCountOfRecipient(taskUuid, USER_D_REF))
        .as("keine Benachrichtigung fuer D")
        .isZero();
    assertThat(snapshotTaskIds(mvc, om, tokenD)).doesNotContain(taskUuid);
    assertThat(countNotifications(taskUuid, USER_E_REF, "MENTION"))
        .as("das Mitglied E wird weiter benachrichtigt")
        .isEqualTo(1);

    // Gegenprobe: dieselbe Erwaehnung durch einen Kommentierenden mit TASK_WRITE nimmt D auf.
    postCommentWithMentions(mvc, om, tokenA, taskUuid, "Bitte pruefen, D.", USER_D_REF);
    assertThat(countActiveParticipantRows(taskUuid, USER_D_REF)).isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION")).isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT")).isZero();
  }

  @Test
  @DisplayName(
      "i156_unbekannte_inaktive_und_fremde_refs_werden_still_verworfen (Intake #156): eine"
          + " unbekannte UUID, ein inaktiver Nutzer und ein Nutzer eines fremden Mandanten in"
          + " mentions -> 201 (kein 400), keine Teilnehmerzeile, kein State, keine"
          + " Benachrichtigung; gueltige Personen derselben Anfrage werden trotzdem aufgenommen")
  void i156_unknown_inactive_and_foreign_refs_are_discarded_silently() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-Verworfen");
    final UUID unknown = UUID.randomUUID();
    final Integer commentsBefore =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);

    postCommentWithMentions(
        mvc, om, tokenA, taskUuid, "Nur Unbekannte.", unknown, USER_INACTIVE_REF, USER_C_REF);

    for (final UUID ref : List.of(unknown, USER_INACTIVE_REF, USER_C_REF)) {
      assertThat(participantRowCount(taskUuid, ref))
          .as("keine Teilnehmerzeile fuer %s", ref)
          .isZero();
      assertThat(userStateRowCount(taskUuid, ref)).as("kein State fuer %s", ref).isZero();
      assertThat(notificationCountOfRecipient(taskUuid, ref))
          .as("keine Benachrichtigung fuer %s", ref)
          .isZero();
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .as("der Kommentar selbst wurde gespeichert")
        .isEqualTo(commentsBefore + 1);

    // Gemischt: die gueltige Person wird aufgenommen, die ungueltigen fallen weiter weg.
    postCommentWithMentions(mvc, om, tokenA, taskUuid, "Gemischt.", unknown, USER_D_REF);
    assertThat(countActiveParticipantRows(taskUuid, USER_D_REF)).isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION")).isEqualTo(1);
    assertThat(participantRowCount(taskUuid, unknown)).isZero();
  }

  @Test
  @DisplayName(
      "i156_ausgetretener_wird_per_erwaehnung_als_reiner_teilnehmer_reaktiviert (Intake #156):"
          + " USER_D war Assignee, hat die Aufgabe stummgeschaltet und wurde per PUT"
          + " abgewaehlt -> die Erwaehnung reaktiviert ihn als reinen Teilnehmer (nicht wieder"
          + " Assignee), er bekommt MENTION trotz Mute, keine PARTICIPANT, der Mute bleibt, die"
          + " Aufgabe steht wieder in seiner Inbox; ein Kommentar ohne Erwaehnung loest fuer ihn kein"
          + " COMMENT aus")
  void i156_exited_person_is_reactivated_as_pure_participant_by_mention() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenD = buildGrantFreeJwt(TENANT_A_UUID, USER_D_REF, USER_D_LOGIN);
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-Ausgetreten");

    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF, USER_D_REF);
    putMute(mvc, tokenD, taskUuid, true);
    putAssignees(mvc, om, tokenA, taskUuid, USER_A_REF);
    assertThat(countActiveParticipantRows(taskUuid, USER_D_REF)).as("D ist ausgetreten").isZero();
    assertThat(isMuted(taskUuid, USER_D_REF)).as("und stumm").isTrue();
    assertThat(snapshotTaskIds(mvc, om, tokenD)).doesNotContain(taskUuid);
    final long generationBefore = inboxGeneration(USER_D_REF);

    postCommentWithMentions(mvc, om, tokenA, taskUuid, "D, bitte wieder dabei sein.", USER_D_REF);

    final Map<String, Object> row = participantRow(taskUuid, USER_D_REF);
    assertThat(row.get("EXIT_KIND")).as("wieder aktiv").isNull();
    assertThat(row.get("EXIT_AT")).isNull();
    assertThat(row.get("PARTICIPANT_FLAG")).isEqualTo(true);
    assertThat(row.get("ASSIGNEE_FLAG"))
        .as("reiner Teilnehmer: das alte Assignee-Flag ist geloescht")
        .isEqualTo(false);
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION"))
        .as("MENTION kommt trotz Mute an")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT")).isZero();
    assertThat(countNotifications(taskUuid, USER_D_REF, "ASSIGN"))
        .as("nur die fruehere Zuweisung, keine neue")
        .isEqualTo(1);
    assertThat(isMuted(taskUuid, USER_D_REF)).as("die Erwaehnung hebt den Mute nicht auf").isTrue();
    assertThat(snapshotTaskIds(mvc, om, tokenD)).as("wieder in der Inbox").contains(taskUuid);
    assertThat(inboxGeneration(USER_D_REF)).isGreaterThan(generationBefore);

    // Kein Assignee: ein Top-Level-Kommentar ohne Erwaehnung loest fuer D kein COMMENT aus.
    postComment(mvc, om, tokenA, taskUuid, "Ohne Erwaehnung.", null);
    assertThat(countNotifications(taskUuid, USER_D_REF, "COMMENT")).isZero();
  }

  @Test
  @DisplayName(
      "i156_deactive_person_zeile_wird_per_erwaehnung_reaktiviert_nur_mention (Review 2):"
          + " USER_D hat eine DEACTIVE-PERSON-Zeile (Unique-Key belegt, vom ACTIVE-Lookup nicht"
          + " gesehen). Ein Kommentar mit Erwaehnung -> 201 (kein 409 durch den Unique-Key), dieselbe"
          + " Zeile ist wieder ACTIVE und reiner Teilnehmer (altes Assignee-Flag geloescht), D hat"
          + " genau eine MENTION und keine PARTICIPANT, die Aufgabe steht in Ds Inbox")
  void i156_deactive_person_row_is_reactivated_by_mention_with_mention_only() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenD = buildGrantFreeJwt(TENANT_A_UUID, USER_D_REF, USER_D_LOGIN);
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-Deactive-Kommentar");
    insertDeactiveParticipant(560801L, taskDbId(taskUuid), USER_D_REF);
    assertThat(participantRow(taskUuid, USER_D_REF).get("ENTITY_STATUS")).isEqualTo("DEACTIVE");
    assertThat(snapshotTaskIds(mvc, om, tokenD)).doesNotContain(taskUuid);
    final long generationBefore = inboxGeneration(USER_D_REF);

    postCommentWithMentions(mvc, om, tokenA, taskUuid, "D, bitte wieder dabei sein.", USER_D_REF);

    assertThat(participantRowCount(taskUuid, USER_D_REF))
        .as("dieselbe Zeile reaktiviert, kein zweiter Insert")
        .isEqualTo(1);
    final Map<String, Object> row = participantRow(taskUuid, USER_D_REF);
    assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(row.get("PARTICIPANT_FLAG")).isEqualTo(true);
    assertThat(row.get("ASSIGNEE_FLAG")).as("nie Assignee").isEqualTo(false);
    assertThat(row.get("EXIT_KIND")).isNull();
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION")).isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT")).isZero();
    assertThat(snapshotTaskIds(mvc, om, tokenD)).as("in Ds Inbox").contains(taskUuid);
    assertThat(inboxGeneration(USER_D_REF)).isGreaterThan(generationBefore);
  }

  @Test
  @DisplayName(
      "i156_post_participants_reaktiviert_deactive_person_zeile_mit_participant (Review 2):"
          + " POST /participants fuer USER_D mit DEACTIVE-PERSON-Zeile -> 204 (kein 409), dieselbe"
          + " Zeile ist wieder ACTIVE und reiner Teilnehmer, D bekommt genau eine PARTICIPANT und"
          + " keine MENTION; ein zweiter Aufruf ist ein No-Op")
  void i156_post_participants_reactivates_deactive_person_row_with_participant() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenD = buildGrantFreeJwt(TENANT_A_UUID, USER_D_REF, USER_D_LOGIN);
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-Deactive-Participants");
    insertDeactiveParticipant(560802L, taskDbId(taskUuid), USER_D_REF);

    postParticipants(mvc, om, tokenA, taskUuid, USER_D_REF);

    assertThat(participantRowCount(taskUuid, USER_D_REF)).as("kein zweiter Insert").isEqualTo(1);
    final Map<String, Object> row = participantRow(taskUuid, USER_D_REF);
    assertThat(row.get("ENTITY_STATUS")).isEqualTo("ACTIVE");
    assertThat(row.get("PARTICIPANT_FLAG")).isEqualTo(true);
    assertThat(row.get("ASSIGNEE_FLAG")).as("nie Assignee").isEqualTo(false);
    assertThat(row.get("EXIT_KIND")).isNull();
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT"))
        .as("die reaktivierte Person wird wie jede neu Aufgenommene benachrichtigt")
        .isEqualTo(1);
    assertThat(countNotifications(taskUuid, USER_D_REF, "MENTION")).isZero();
    assertThat(snapshotTaskIds(mvc, om, tokenD)).as("in Ds Inbox").contains(taskUuid);

    postParticipants(mvc, om, tokenA, taskUuid, USER_D_REF);
    assertThat(countNotifications(taskUuid, USER_D_REF, "PARTICIPANT")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "i156_team_zeile_blockiert_aufnahme_409_ohne_mutation (Review 2): hat USER_D unter dem"
          + " Unique-Key eine TEAM-Zeile, ist die Aufnahme nicht moeglich (ein PERSON-Insert braeche"
          + " den Unique-Key, ueberschrieben wird nicht) -> Kommentar mit Erwaehnung und POST"
          + " /participants liefern 409 und aendern nichts (kein Kommentar, keine Zeile, keine"
          + " Benachrichtigung); so ist es in der 409-Beschreibung der OpenAPI dokumentiert")
  void i156_team_row_blocks_adding_with_409_and_no_mutation() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-156-Team-Zeile");
    insertParticipantRow(560803L, taskDbId(taskUuid), USER_D_REF, "TEAM", "ACTIVE");
    final Integer commentsBefore =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);

    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "text",
                            "D steht als TEAM-Zeile da.",
                            "mentions",
                            List.of(USER_D_REF)))))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/participants")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("participantIds", List.of(USER_D_REF)))))
        .andExpect(status().isConflict());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .as("der Kommentar wurde zurueckgerollt")
        .isEqualTo(commentsBefore);
    assertThat(participantRow(taskUuid, USER_D_REF).get("PARTICIPANT_KIND"))
        .as("die TEAM-Zeile blieb unveraendert")
        .isEqualTo("TEAM");
    assertThat(notificationCountOfRecipient(taskUuid, USER_D_REF)).isZero();
    assertThat(userStateRowCount(taskUuid, USER_D_REF)).isZero();
  }

  @Test
  @DisplayName(
      "i156_kommentar_antworten_entsprechen_den_openapi_beispielen (Intake #156): die"
          + " Live-Antworten von POST /comments (201 ohne und mit Eltern-Kommentar, 400 Eltern-"
          + " Kommentar und Text fehlt, 401, 404, 409) und GET /comments (200, 400, 401, 404) haben"
          + " dieselben Felder und stabilen Werte wie die Beispiele der Operationen")
  void i156_comment_responses_match_openapi_examples() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskId = createTask(mvc, om, tokenA, "Comment-IT-156-Beispiele");
    final String path = "/api/v1/tasks/" + taskId + "/comments";
    final SoftAssertions soft = new SoftAssertions();

    // 201 ohne Eltern-Kommentar: parentCommentId ist null (Feld vorhanden), sonst wie im Beispiel.
    final JsonNode topLevel =
        om.readTree(
            mvc.perform(
                    post(path)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", "Eltern-Kommentar."))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertCommentMatchesExample(
        soft, topLevel, om.readTree(CommentApiExamples.CREATED_TOP_LEVEL), taskId);
    final UUID parent = UUID.fromString(topLevel.get("id").asText());

    // 201: Antwort auf einen Kommentar; Felder und Formen wie im Beispiel.
    final String createdJson =
        mvc.perform(
                post(path)
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(
                            Map.of("text", "Erledigt, danke.", "parentCommentId", parent))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode created = om.readTree(createdJson);
    assertCommentMatchesExample(
        soft, created, om.readTree(CommentApiExamples.CREATED_REPLY), taskId);
    soft.assertThat(created.get("parentCommentId").asText()).isEqualTo(parent.toString());

    // GET /comments: 200 mit den beiden Kommentaren chronologisch aufsteigend, Elemente wie im
    // Beispiel (der erste ohne, der zweite mit Eltern-Kommentar).
    final JsonNode list =
        om.readTree(
            mvc.perform(get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    final JsonNode listExample = om.readTree(CommentApiExamples.LIST_COMMENTS);
    soft.assertThat(list.size()).isEqualTo(listExample.size());
    for (int i = 0; i < Math.min(list.size(), listExample.size()); i++) {
      assertCommentMatchesExample(soft, list.get(i), listExample.get(i), taskId);
    }

    // GET /comments 400: keine UUID im Pfad.
    assertMatchesExample(
        soft,
        om,
        mvc.perform(
                get("/api/v1/tasks/keine-uuid/comments")
                    .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        CommentApiExamples.BAD_REQUEST_PATH,
        taskId);

    // GET /comments 401 (problem+json der Filterkette) und 404 (unbekannte Aufgabe, maskiert).
    soft.assertThat(
            om.readTree(
                mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andReturn()
                    .getResponse()
                    .getContentAsString()))
        .isEqualTo(om.readTree(CommentApiExamples.UNAUTHORIZED));
    final UUID unknownForGet = UUID.randomUUID();
    assertMatchesExample(
        soft,
        om,
        mvc.perform(
                get("/api/v1/tasks/" + unknownForGet + "/comments")
                    .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        CommentApiExamples.NOT_FOUND,
        unknownForGet);

    // 400: Eltern-Kommentar unbekannt (Service-Validierung).
    assertMatchesExample(
        soft,
        om,
        mvc.perform(
                post(path)
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(
                            Map.of("text", "Antwort.", "parentCommentId", UUID.randomUUID()))))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        CommentApiExamples.BAD_REQUEST_PARENT,
        taskId);

    // 400: Text fehlt, leer oder nur Leerraum (Validierung beim Lesen des Bodys).
    for (final String body : List.of("{}", "{\"text\":null}", "{\"text\":\"  \"}")) {
      assertMatchesExample(
          soft,
          om,
          mvc.perform(
                  post(path)
                      .header("Authorization", "Bearer " + tokenA)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse()
              .getContentAsString(),
          CommentApiExamples.BAD_REQUEST_BODY,
          taskId);
    }

    // 400: weitere in der Beschreibung genannte Faelle (null-Element, mehr als 100 Eintraege,
    // kein gueltiges JSON, keine UUID im Pfad).
    mvc.perform(
            post(path)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\",\"mentions\":[null]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(path)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "text",
                            "x",
                            "mentions",
                            java.util.stream.Stream.generate(UUID::randomUUID)
                                .limit(101)
                                .toList()))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(path)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/tasks/keine-uuid/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CommentApiExamples.REQUEST_TOP_LEVEL))
        .andExpect(status().isBadRequest());

    // 401: ohne Anmeldung antwortet die Filterkette mit problem+json.
    final JsonNode unauthorized =
        om.readTree(
            mvc.perform(
                    post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CommentApiExamples.REQUEST_TOP_LEVEL))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString());
    soft.assertThat(unauthorized).isEqualTo(om.readTree(CommentApiExamples.UNAUTHORIZED));

    // 404: unbekannte Aufgabe (neutral maskiert).
    final UUID unknownTask = UUID.randomUUID();
    assertMatchesExample(
        soft,
        om,
        mvc.perform(
                post("/api/v1/tasks/" + unknownTask + "/comments")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CommentApiExamples.REQUEST_TOP_LEVEL))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        CommentApiExamples.NOT_FOUND,
        unknownTask);

    // 409: der Versionskonflikt beim Speichern (KSF: BusinessRuleException mit
    // OptimisticLockingFailureException als Ursache) laeuft durch den echten Fehler-Handler des
    // Kontexts; ein echter Versionskonflikt ist ueber HTTP nicht deterministisch zu erzeugen
    // (Nebenlaeufigkeitsbeweis mit Rollback: CommentConcurrencyIT).
    final String conflictMessage = "Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).";
    final ApiError conflict =
        apiExceptionHandler
            .handleNovaBusinessRule(
                new BusinessRuleException(
                    conflictMessage, new OptimisticLockingFailureException(conflictMessage)),
                new MockHttpServletRequest("POST", path))
            .getBody();
    assertMatchesExample(
        soft,
        om,
        contextObjectMapper.writeValueAsString(conflict),
        CommentApiExamples.CONFLICT,
        taskId);
    soft.assertAll();
  }

  /**
   * Vergleicht einen Kommentar der Live-Antwort mit dem Beispiel: gleiche Feldmenge (auch ein
   * {@code null}-Wert von parentCommentId steht als Feld da), gleiche Form von parentCommentId
   * (null oder UUID), edited und createdAt im Format UTC-Instant, taskId die verwendete.
   */
  private static void assertCommentMatchesExample(
      final SoftAssertions soft, final JsonNode live, final JsonNode example, final UUID taskId) {
    final Set<String> liveFields = new java.util.TreeSet<>();
    live.fieldNames().forEachRemaining(liveFields::add);
    final Set<String> exampleFields = new java.util.TreeSet<>();
    example.fieldNames().forEachRemaining(exampleFields::add);
    soft.assertThat(liveFields).as("Felder des Kommentars: %s", live).isEqualTo(exampleFields);
    soft.assertThat(live.get("taskId").asText()).isEqualTo(taskId.toString());
    soft.assertThat(live.get("parentCommentId").isNull())
        .as("parentCommentId null wie im Beispiel: %s", live)
        .isEqualTo(example.get("parentCommentId").isNull());
    soft.assertThat(live.get("edited").asBoolean()).isEqualTo(example.get("edited").asBoolean());
    soft.assertThat(live.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z");
    soft.assertThat(example.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z");
  }

  /**
   * Vergleicht eine Live-Antwort mit einem OpenAPI-Beispiel: gleiche Feldmenge und gleiche Werte,
   * ausser {@code timestamp} und {@code traceId} (je Aufruf neu). Die Aufgaben-UUID im {@code path}
   * des Beispiels wird durch die verwendete ersetzt.
   */
  private static void assertMatchesExample(
      final SoftAssertions soft,
      final ObjectMapper om,
      final String liveJson,
      final String exampleJson,
      final UUID taskId)
      throws Exception {
    final JsonNode live = om.readTree(liveJson);
    final JsonNode example = om.readTree(exampleJson.replace(EXAMPLE_TASK_ID, taskId.toString()));
    final Set<String> exampleFields = new java.util.TreeSet<>();
    example.fieldNames().forEachRemaining(exampleFields::add);
    final Set<String> liveFields = new java.util.TreeSet<>();
    live.fieldNames().forEachRemaining(liveFields::add);
    soft.assertThat(liveFields).as("Felder der Antwort: %s", live).isEqualTo(exampleFields);
    for (final String stable :
        List.of("status", "error", "message", "path", "errorKey", "details")) {
      soft.assertThat(live.get(stable))
          .as("Feld %s der Antwort: %s", stable, live)
          .isEqualTo(example.get(stable));
    }
  }

  // ---- IT I3 (Intake #136): reiner Participant + ausgetretener Assignee -> KEIN COMMENT --------

  @Test
  @DisplayName(
      "i3_comment_filter_reiner_participant_und_ex_assignee_kein_comment (Intake #136):"
          + " ein reiner Participant (ASSIGNEE_FLAG=0) UND ein ausgetretener Assignee (EXIT_KIND"
          + " gesetzt) erhalten bei Top-Level-Kommentar KEIN COMMENT (findActiveAssigneesByTaskId-Filter)")
  void i3_comment_filter_reiner_participant_und_ex_assignee_kein_comment() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-136-Filter");
    final long taskId = taskDbId(taskUuid);

    // (a) reiner Participant: PERSON, aktiv, aber ASSIGNEE_FLAG=0 (USER_B).
    insertPureParticipant(560601L, taskId, USER_B_REF);
    // (b) ausgetretener Assignee: ASSIGNEE_FLAG=1, aber EXIT_KIND=UNSUBSCRIBED (USER_C_REF nur als
    //     neutrale UUID verwendet; die Zeile ist ohnehin im Task-Mandanten PERSON-aktiv-mit-exit).
    insertExitedAssignee(560602L, taskId, USER_C_REF);

    // Top-Level-Kommentar OHNE Mention.
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Nur fuer aktive Assignees."))))
        .andExpect(status().isCreated());

    // Weder der reine Participant noch der ausgetretene Assignee erhalten ein COMMENT.
    final Integer commentNotifs =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.TYPE_ID = 409"
                + " AND n.RECIPIENT_REF IN (?, ?)",
            Integer.class,
            taskUuid,
            USER_B_REF,
            USER_C_REF);
    assertThat(commentNotifs)
        .as("reiner Participant + ausgetretener Assignee duerfen KEIN COMMENT erhalten")
        .isEqualTo(0);
  }

  // ---- IT 2: Reply -> PARENT_COMMENT_ID gesetzt + REPLY-Notification ---------------------------

  @Test
  @DisplayName(
      "reply_setzt_parent_und_benachrichtigt_parent_autor:"
          + " POST mit parentCommentId -> PARENT_COMMENT_ID gesetzt"
          + " + REPLY-Notification fuer Eltern-Autor")
  void reply_setzt_parent_und_benachrichtigt_parent_autor() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_B_REF,
            USER_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Task anlegen (USER_A)
    final String createBody = om.writeValueAsString(Map.of("title", "Comment-IT-Task-2"));
    final String postTaskResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid =
        om.readValue(postTaskResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // Slice 4: USER_B muss aktiver WRITE-Member sein, um einen Kommentar/Reply posten zu duerfen
    // (member-scoped POST). Der Eltern-Autor USER_A ist als Creator ohnehin WRITE-Member und bleibt
    // damit zulaessiger REPLY-Empfaenger (Auflage 4).
    insertActiveMember(560201L, taskDbId(taskUuid), USER_B_REF);

    // Eltern-Kommentar von USER_A
    final String parentBody = om.writeValueAsString(Map.of("text", "Ausgangsfrage"));
    final String parentResponse =
        mvc.perform(
                post("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(parentBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID parentCommentId =
        om.readValue(parentResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(parentCommentId).isNotNull();

    // Reply von USER_B auf den Eltern-Kommentar
    final String replyBody =
        om.writeValueAsString(
            Map.of("text", "Antwort auf den Kommentar.", "parentCommentId", parentCommentId));
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replyBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.parentCommentId").value(parentCommentId.toString()));

    // Beweis 1: Reply-Kommentar hat PARENT_COMMENT_ID gesetzt
    final Integer replyWithParentCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c"
                + " JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND c.AUTHOR_REF = ? AND c.PARENT_COMMENT_ID IS NOT NULL",
            Integer.class,
            taskUuid,
            USER_B_REF);
    assertThat(replyWithParentCount)
        .as("Reply-Kommentar muss PARENT_COMMENT_ID gesetzt haben")
        .isGreaterThanOrEqualTo(1);

    // Beweis 2: REPLY-Notification fuer USER_A (= Eltern-Autor, TYPE_ID=402=REPLY)
    final Integer replyNotifCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND n.TYPE_ID = 402",
            Integer.class,
            taskUuid,
            USER_A_REF);
    assertThat(replyNotifCount)
        .as("REPLY-Notification fuer USER_A_REF (Eltern-Autor) muss existieren")
        .isGreaterThanOrEqualTo(1);
  }

  // ---- IT 3: fremd-tenant Mention-GUID -> KEINE Notification (cross-tenant dicht) ---------------

  @Test
  @DisplayName(
      "fremd_tenant_mention_keine_notification:"
          + " mentions=[USER_C(Tenant B)] -> 201, KEIN TASK_NOTIFICATION-Satz fuer USER_C")
  void fremd_tenant_mention_keine_notification() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-FremdMention");

    // GUID aus Tenant B -> loest in Tenant A NICHT auf (findActiveByUuids mandanten-scoped)
    final String body =
        om.writeValueAsString(Map.of("text", "Fremd-Mention.", "mentions", List.of(USER_C_REF)));
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    final Integer notifForC =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
            Integer.class,
            taskUuid,
            USER_C_REF);
    assertThat(notifForC)
        .as("fremd-tenant Mention darf KEINE Notification erzeugen (cross-tenant dicht)")
        .isEqualTo(0);
  }

  // ---- IT 4: Email-Text OHNE mentions -> KEINE MENTION (kein Text-Parsing mehr) -----------------

  @Test
  @DisplayName(
      "email_text_ohne_mentions_keine_notification:"
          + " Text mit @email aber OHNE mentions-Feld -> 201, KEINE MENTION-Notification")
  void email_text_ohne_mentions_keine_notification() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-EmailText");

    // Text enthaelt Email-Erwaehnungen, aber KEIN strukturiertes mentions-Feld -> kein
    // Text-Parsing.
    final String body =
        om.writeValueAsString(
            Map.of("text", "Bitte pruefen @" + USER_B_LOGIN + "@brasi.de und foo@bar.de danke."));
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    final Integer mentionNotifs =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
                + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND n.TYPE_ID = 404",
            Integer.class,
            taskUuid);
    assertThat(mentionNotifs)
        .as("ohne strukturierte mentions darf KEINE MENTION aus dem Text entstehen")
        .isEqualTo(0);
  }

  // ---- IT 5: mentions=[null] -> HTTP 400 -------------------------------------------------------

  @Test
  @DisplayName("mentions_null_element_400: {\"mentions\":[null]} -> 400")
  void mentions_null_element_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-IT-NullMention");

    // null-Element im mentions-Array -> CommentCreateInput-Konstruktor wirft bei der
    // Deserialisierung.
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Test\",\"mentions\":[null]}"))
        .andExpect(status().isBadRequest());
  }

  // ---- IT 6: langer Kommentartext > 255 Zeichen -> 201 (nvarchar max, kein 255-Validierungscap)
  // --

  @Test
  @DisplayName(
      "langer_kommentartext_ueber_255_wird_akzeptiert:"
          + " TEXT mit > 255 Zeichen -> 201 (TaskComment.TEXT ist nvarchar(max))")
  void langer_kommentartext_ueber_255_wird_akzeptiert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "LangText-Kommentar-Task");
    final String longText = "L".repeat(300); // > 255

    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", longText))))
        .andExpect(status().isCreated());

    // DB-Beweis: der volle Text wurde persistiert (nicht gekappt/abgewiesen).
    final Integer longCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c"
                + " JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND LEN(c.TEXT) = 300",
            Integer.class,
            taskUuid);
    assertThat(longCount)
        .as("Kommentar mit 300 Zeichen muss vollstaendig persistiert sein")
        .isEqualTo(1);
  }

  // ---- Negativ-IT: 401 / 403 / 404 -----------------------------------------------------------

  @Test
  @DisplayName(
      "post_comment_anonym_401_nicht_member_404_unbekannte_task_404 (Slice 4): POST /comments ist"
          + " jetzt member-scoped (kein @RequiresPermission mehr) -> anonym 401; Grant-Holder ohne"
          + " Membership bzw. unbekannte Task -> neutral 404")
  void anonym_401_falsche_permission_403_unbekannte_task_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // 1. Anonym -> 401
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Test"))))
        .andExpect(status().isUnauthorized());

    // 2. Slice 4: POST /comments ist nicht mehr permission-gated. Ein TASK_READ-Grant-Holder OHNE
    // Membership erreicht eine real existierende Fremd-Task nicht -> neutral 404 (statt frueher
    // 403).
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-POST-authz");
    final Integer commentsBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskUuid);
    final Integer notificationsBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskUuid);
    final Integer historyBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskUuid);
    final Integer commentCountBefore =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);
    final String tokenReadOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN, "task-management:TASK_READ");
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Test Non-Member"))))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(commentsBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(notificationsBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(historyBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(commentCountBefore);

    // 3. Member-Token (USER_A), aber unbekannte Task-UUID -> 404
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Test fuer unbekannte Task"))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "Grantfreier Member schreibt Kommentar; Ex-Member und fremder Tenant lesen oder schreiben nicht")
  void comment_membership_guard_wire_und_keine_verweigerten_writes() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String creator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final UUID taskUuid = createTask(mvc, om, creator, "Comment-Membership-Wire");
    insertActiveMember(560401L, taskDbId(taskUuid), USER_B_REF);
    final String member = buildGrantFreeJwt(TENANT_A_UUID, USER_B_REF, USER_B_LOGIN);

    final Integer commentsBefore =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);
    final UUID commentUuid = postComment(mvc, om, member, taskUuid, "Grantfreier Kommentar", null);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ? AND c.EXTERNALID = ? AND c.AUTHOR_REF = ?",
                Integer.class,
                taskUuid,
                commentUuid,
                USER_B_REF))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(commentsBefore + 1);

    final Integer commentRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskUuid);
    final Integer notificationRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskUuid);
    final Integer historyRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskUuid);
    final Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?", Integer.class, taskUuid);

    final String foreign = buildGrantFreeJwt(TENANT_B_UUID, USER_C_REF, USER_C_LOGIN);
    mvc.perform(
            get("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + foreign))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + foreign)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of("text", "Fremder Kommentar", "mentions", List.of(USER_A_REF)))))
        .andExpect(status().isNotFound());

    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET EXIT_KIND = 'UNSUBSCRIBED', EXIT_AT = GETDATE()"
            + " WHERE TASK_ID = ? AND TENANT_ID = ? AND PARTICIPANT_REF = ?",
        taskDbId(taskUuid),
        TENANT_A_ID,
        USER_B_REF);
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + member)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of("text", "Ex-Member Kommentar", "mentions", List.of(USER_A_REF)))))
        .andExpect(status().isNotFound());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_COMMENT] c JOIN [dbo].[TASK] t ON c.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(commentRows);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(notificationRows);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(historyRows);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(count);
  }

  // ---- BE-2 GET /tasks/{id}/comments ---------------------------------------------------------

  @Test
  @DisplayName(
      "get_comments_flache_liste_chronologisch_parent_resolved:"
          + " GET -> 200 flache Liste chronologisch; Reply traegt parentCommentId = Eltern-UUID")
  void get_comments_flache_liste_chronologisch_parent_resolved() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-GET-flat");
    final UUID c1 = postComment(mvc, om, tokenA, taskUuid, "C1-top", null);
    final UUID c2 = postComment(mvc, om, tokenA, taskUuid, "C2-reply", c1);

    final String resp =
        mvc.perform(
                get("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode arr = om.readTree(resp);
    assertThat(arr.isArray()).isTrue();
    assertThat(arr.size()).isEqualTo(2);
    assertThat(arr.get(0).get("id").asText())
        .as("chronologisch: C1 zuerst")
        .isEqualTo(c1.toString());
    assertThat(arr.get(0).get("parentCommentId").isNull()).as("Top-Level -> null").isTrue();
    assertThat(arr.get(1).get("id").asText()).isEqualTo(c2.toString());
    assertThat(arr.get(1).get("parentCommentId").asText())
        .as("Reply -> Eltern-UUID aufgeloest")
        .isEqualTo(c1.toString());
    assertThat(arr.get(0).get("taskId").asText()).isEqualTo(taskUuid.toString());
    assertThat(arr.get(0).get("authorRef").asText()).isEqualTo(USER_A_REF.toString());
  }

  @Test
  @DisplayName(
      "get_comments_deaktivierter_elternkommentar_parent_null:"
          + " Eltern-Kommentar DEACTIVE -> Reply erscheint mit parentCommentId=null (R1)")
  void get_comments_deaktivierter_elternkommentar_parent_null() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-GET-dangling");
    final UUID c1 = postComment(mvc, om, tokenA, taskUuid, "C1-parent", null);
    final UUID c2 = postComment(mvc, om, tokenA, taskUuid, "C2-reply", c1);

    // Eltern-Kommentar deaktivieren -> nicht mehr im aktiven Set
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_COMMENT] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?", c1);

    final String resp =
        mvc.perform(
                get("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode arr = om.readTree(resp);
    assertThat(arr.size()).as("nur der aktive Reply bleibt").isEqualTo(1);
    assertThat(arr.get(0).get("id").asText()).isEqualTo(c2.toString());
    assertThat(arr.get(0).get("parentCommentId").isNull())
        .as("deaktivierter Eltern-Kommentar -> parentCommentId=null")
        .isTrue();
  }

  @Test
  @DisplayName("get_comments_leere_aufgabe_200_empty: Aufgabe ohne Kommentare -> 200 []")
  void get_comments_leere_aufgabe_200_empty() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-GET-empty");

    mvc.perform(
            get("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName(
      "get_comments_404_unbekannt_und_deaktivierte_task:"
          + " unbekannte Task-UUID -> 404; DEACTIVE Task -> 404 (neutral maskiert)")
  void get_comments_404_unbekannt_und_deaktivierte_task() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // unbekannte UUID -> 404
    mvc.perform(
            get("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound());

    // DEACTIVE Task -> 404
    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-GET-deactive-task");
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?", taskUuid);
    mvc.perform(
            get("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound());

    // EXISTIERENDE Aufgabe eines FREMDEN Mandanten (Tenant B) -> 404 (Tenant-Maske;
    // Codex-IMPL-Gate).
    final String tokenC =
        buildJwtWithAuthority(
            TENANT_B_UUID,
            USER_C_REF,
            USER_C_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID foreignTaskUuid = createTask(mvc, om, tokenC, "Comment-GET-foreign-tenant");
    mvc.perform(
            get("/api/v1/tasks/" + foreignTaskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "get_comments_member_200_non_member_404 (Slice 4, Auflage 3/6):"
          + " grant-freier aktiver WRITE-Member liest Kommentare (200); ein TASK_READ-Grant-Holder"
          + " im gleichen Mandanten OHNE Teilnehmer-Rolle -> 404 (objekt-level Membership-Guard)")
  void get_comments_d1_nicht_teilnehmer_gleicher_tenant_200() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "Comment-GET-d1");
    postComment(mvc, om, tokenA, taskUuid, "nur-Mitglieder-sichtbar", null);

    // (a) USER_B: gleicher Mandant, GRANT-FREI, aber aktiver WRITE-Member -> 200 (Happy Path).
    insertActiveMember(560301L, taskDbId(taskUuid), USER_B_REF);
    final String tokenMember = buildGrantFreeJwt(TENANT_A_UUID, USER_B_REF, USER_B_LOGIN);
    mvc.perform(
            get("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenMember))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // (b) USER_READ_ONLY: gleicher Mandant, TASK_READ-Grant, aber KEIN Teilnehmer -> neutral 404.
    final String tokenNonMember =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN, "task-management:TASK_READ");
    mvc.perform(
            get("/api/v1/tasks/" + taskUuid + "/comments")
                .header("Authorization", "Bearer " + tokenNonMember))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "get_comments_401_anon_404_non_member (Slice 4): GET /comments ist member-scoped (kein"
          + " @RequiresPermission mehr) -> anonym 401; ein Grant-Holder ohne Membership auf eine"
          + " unbekannte/fremde Task -> neutral 404 (kein 403)")
  void get_comments_401_anon_403_ohne_task_read() throws Exception {
    final MockMvc mvc = buildMvc();

    // anonym -> 401
    mvc.perform(get("/api/v1/tasks/" + UUID.randomUUID() + "/comments"))
        .andExpect(status().isUnauthorized());

    // Slice 4: GET /comments ist nicht mehr TASK_READ-gated. Ein beliebig berechtigter Nutzer auf
    // eine unbekannte Task -> neutral 404 (statt frueher 403 mangels TASK_READ).
    final String tokenWriteOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            get("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .header("Authorization", "Bearer " + tokenWriteOnly))
        .andExpect(status().isNotFound());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  /** Postet einen Kommentar via POST und liefert dessen externe UUID. */
  private UUID postComment(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskUuid,
      final String text,
      final UUID parentCommentId)
      throws Exception {
    final Map<String, Object> body =
        parentCommentId == null
            ? Map.of("text", text)
            : Map.of("text", text, "parentCommentId", parentCommentId);
    final String resp =
        mvc.perform(
                post("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(resp, JsonNode.class).get("id").traverse(om).readValueAs(UUID.class);
  }

  /** Setzt die Assignee-Menge einer Aufgabe per PUT (erwartet 204). */
  private void putAssignees(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskUuid,
      final UUID... assignees)
      throws Exception {
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/assignees")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(assignees)))))
        .andExpect(status().isNoContent());
  }

  /**
   * Uebergibt die Aufgabe per POST /exit (HANDOVER, shouldStay=true) an ein Ziel (erwartet 204).
   */
  private void handoverStay(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskUuid,
      final UUID target)
      throws Exception {
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/exit")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "exitKind",
                            "HANDOVER",
                            "handedOverToIds",
                            List.of(target),
                            "shouldStay",
                            true))))
        .andExpect(status().isNoContent());
  }

  /** Fuegt Personen per POST /participants hinzu (erwartet 204). */
  private void postParticipants(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskUuid,
      final UUID... participantIds)
      throws Exception {
    mvc.perform(
            post("/api/v1/tasks/" + taskUuid + "/participants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("participantIds", List.of(participantIds)))))
        .andExpect(status().isNoContent());
  }

  /** Setzt den persoenlichen Mute-Marker des Token-Inhabers per PUT (erwartet 204). */
  private void putMute(
      final MockMvc mvc, final String token, final UUID taskUuid, final boolean value)
      throws Exception {
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/mute")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":" + value + "}"))
        .andExpect(status().isNoContent());
  }

  /** Liest den persoenlichen Mute-Marker (IS_MUTED) eines Benutzers fuer eine Aufgabe direkt. */
  private boolean isMuted(final UUID taskUuid, final UUID userRef) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT s.IS_MUTED FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
            Boolean.class,
            taskUuid,
            userRef));
  }

  /** Postet einen Kommentar mit Erwaehnungen (erwartet 201) und liefert dessen externe UUID. */
  private UUID postCommentWithMentions(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskUuid,
      final String text,
      final UUID... mentions)
      throws Exception {
    final String resp =
        mvc.perform(
                post("/api/v1/tasks/" + taskUuid + "/comments")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(Map.of("text", text, "mentions", List.of(mentions)))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(resp, JsonNode.class).get("id").traverse(om).readValueAs(UUID.class);
  }

  /** Die einzige Teilnehmerzeile einer Person an einer Aufgabe (Rollenflags und Austritt). */
  private Map<String, Object> participantRow(final UUID taskUuid, final UUID userRef) {
    return jdbcTemplate.queryForMap(
        "SELECT p.ENTITY_STATUS, p.PARTICIPANT_FLAG, p.ASSIGNEE_FLAG, p.CREATOR_FLAG,"
            + " p.PARTICIPANT_KIND, p.EXIT_KIND, p.EXIT_AT FROM [dbo].[TASK_PARTICIPANT] p"
            + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
        taskUuid,
        userRef);
  }

  /** Anzahl aller Teilnehmerzeilen einer Person an einer Aufgabe (auch ausgetretene). */
  private int participantRowCount(final UUID taskUuid, final UUID userRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
            + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
        Integer.class,
        taskUuid,
        userRef);
  }

  /** Anzahl der persoenlichen State-Zeilen einer Person an einer Aufgabe. */
  private int userStateRowCount(final UUID taskUuid, final UUID userRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
            + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
        Integer.class,
        taskUuid,
        userRef);
  }

  /** Anzahl aller Benachrichtigungen (jeden Typs) eines Empfaengers an einer Aufgabe. */
  private int notificationCountOfRecipient(final UUID taskUuid, final UUID recipientRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
        Integer.class,
        taskUuid,
        recipientRef);
  }

  /** Inbox-Generation einer Person im Mandanten A; 0, solange noch keine Zeile existiert. */
  private long inboxGeneration(final UUID userRef) {
    final List<Long> generations =
        jdbcTemplate.queryForList(
            "SELECT GENERATION FROM [dbo].[TASK_INBOX_GENERATION]"
                + " WHERE TENANT_ID = ? AND USER_REF = ?",
            Long.class,
            TENANT_A_ID,
            userRef);
    return generations.isEmpty() ? 0L : generations.get(0);
  }

  /** Die Aufgaben-UUIDs der ersten Seite des Inbox-Snapshots einer Person. */
  private Set<UUID> snapshotTaskIds(final MockMvc mvc, final ObjectMapper om, final String token)
      throws Exception {
    final String json =
        mvc.perform(get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final Set<UUID> ids = new java.util.HashSet<>();
    for (final JsonNode item : om.readTree(json).get("tasks").get("items")) {
      ids.add(UUID.fromString(item.get("id").asText()));
    }
    return ids;
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  /** Legt eine Aufgabe via POST /api/v1/tasks an und liefert ihre externe UUID. */
  private UUID createTask(
      final MockMvc mvc, final ObjectMapper om, final String token, final String title)
      throws Exception {
    final String response =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(response, com.fasterxml.jackson.databind.JsonNode.class)
        .get("id")
        .traverse(om)
        .readValueAs(UUID.class);
  }

  /** JWT OHNE jede task-management-Authority (authenticated-only, grant-frei). */
  private static String buildGrantFreeJwt(
      final UUID tenantUuid, final UUID userUuid, final String loginName) {
    return buildJwtWithAuthority(tenantUuid, userUuid, loginName);
  }

  /** Anzahl der Benachrichtigungen eines Typ-CODEs fuer einen Empfaenger an einer Aufgabe. */
  private int countNotifications(final UUID taskUuid, final UUID recipientRef, final String code) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
            + " JOIN [dbo].[DIC_NOTIFICATION_TYPE] d ON n.TYPE_ID = d.ID"
            + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND d.CODE = ?",
        Integer.class,
        taskUuid,
        recipientRef,
        code);
  }

  /** Die einzige Benachrichtigung eines Typs fuer einen Empfaenger an einer Aufgabe (Spalten). */
  private Map<String, Object> notificationRow(
      final UUID taskUuid, final UUID recipientRef, final String code) {
    return jdbcTemplate.queryForMap(
        "SELECT n.TEXT, n.PREVIEW, n.IS_READ, n.ACTOR_REF, s.CODE AS SEVERITY"
            + " FROM [dbo].[TASK_NOTIFICATION] n"
            + " JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
            + " JOIN [dbo].[DIC_NOTIFICATION_TYPE] d ON n.TYPE_ID = d.ID"
            + " LEFT JOIN [dbo].[DIC_NOTIFICATION_SEVERITY] s ON n.SEVERITY_ID = s.ID"
            + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ? AND d.CODE = ?",
        taskUuid,
        recipientRef,
        code);
  }

  /** Anzahl der aktiven (nicht ausgetretenen) Teilnehmerzeilen einer Person an einer Aufgabe. */
  private int countActiveParticipantRows(final UUID taskUuid, final UUID userRef) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
            + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ? AND p.EXIT_KIND IS NULL",
        Integer.class,
        taskUuid,
        userRef);
  }

  /** Interne TASK.ID zur externen UUID (fuer die direkten TASK_PARTICIPANT-Inserts). */
  private long taskDbId(final UUID taskExternalId) {
    return jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskExternalId);
  }

  /**
   * Fuegt dem Akteur eine aktive WRITE-Member-Teilnehmerzeile (PERSON, EXIT_KIND null) direkt per
   * JDBC hinzu - ohne das (ehemalige) Gate. So kann ein grant-freier/anderer Mandanten-Nutzer
   * Membership erhalten, damit der objekt-level Guard (Slice 4) greift. {@code partId} = eindeutige
   * TASK_PARTICIPANT.ID, {@code taskId} = interne TASK.ID.
   */
  private void insertActiveMember(final long partId, final long taskId, final UUID ref) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG,"
            + " ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, 'ACTIVE', 'it', GETDATE(), NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 1, 1,"
            + " NULL, NULL)",
        partId,
        TENANT_A_ID,
        taskId,
        ref,
        TENANT_A_ID);
  }

  /**
   * Fuegt eine inaktive PERSON-Teilnehmerzeile ({@code ENTITY_STATUS='DEACTIVE'}, ehemaliger
   * Assignee, nicht ausgetreten) direkt per JDBC hinzu (Review 2 zu Intake #156): der ACTIVE-Lookup
   * sieht sie nicht, der V3-Unique-Key ist aber belegt.
   */
  private void insertDeactiveParticipant(final long partId, final long taskId, final UUID ref) {
    insertParticipantRow(partId, taskId, ref, "PERSON", "DEACTIVE");
  }

  /**
   * Fuegt eine nicht ausgetretene Teilnehmerzeile mit der gegebenen Art ({@code PERSON}/{@code
   * TEAM}) und dem gegebenen {@code ENTITY_STATUS} direkt per JDBC hinzu (ehemaliger Assignee).
   */
  private void insertParticipantRow(
      final long partId,
      final long taskId,
      final UUID ref,
      final String kind,
      final String entityStatus) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG,"
            + " ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, ?, 'it', GETDATE(), NEWID(), 1, ?, ?, ?, ?, ?, 0, 1, 1, NULL, NULL)",
        partId,
        entityStatus,
        TENANT_A_ID,
        taskId,
        ref,
        kind,
        TENANT_A_ID);
  }

  /**
   * Fuegt einen aktiven REINEN Teilnehmer (PERSON, EXIT_KIND null, ASSIGNEE_FLAG=0) hinzu (Intake
   * #136, I3): belegt, dass {@code findActiveAssigneesByTaskId} reine Participants ausschliesst.
   */
  private void insertPureParticipant(final long partId, final long taskId, final UUID ref) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG,"
            + " ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, 'ACTIVE', 'it', GETDATE(), NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1,"
            + " NULL, NULL)",
        partId,
        TENANT_A_ID,
        taskId,
        ref,
        TENANT_A_ID);
  }

  /**
   * Fuegt einen AUSGETRETENEN Assignee (PERSON, ASSIGNEE_FLAG=1, EXIT_KIND=UNSUBSCRIBED) hinzu
   * (Intake #136, I3): belegt, dass {@code findActiveAssigneesByTaskId} (EXIT_KIND IS NULL)
   * ausgetretene Assignees ausschliesst.
   */
  private void insertExitedAssignee(final long partId, final long taskId, final UUID ref) {
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, ANGELEGT_VON, ANGELEGT_AM, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG,"
            + " ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT) "
            + "VALUES (?, 'ACTIVE', 'it', GETDATE(), NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 1, 1,"
            + " 'UNSUBSCRIBED', GETDATE())",
        partId,
        TENANT_A_ID,
        taskId,
        ref,
        TENANT_A_ID);
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
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.refentity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.time",
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
    // Slice 4 (Standard-Selfservice): CommentService/CommentQueryService haengen jetzt am
    // objekt-level
    // TaskMembershipGuard (@Component im nicht-gescannten Paket ...security).
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "comment-it";
    }
  }
}
