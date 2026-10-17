package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Integration-Test fuer den {@link CommentController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 * <ul>
 *   <li>POST /comments -> TASK_COMMENT persistiert, COMMENT_COUNT++, LAST_ACTIVITY_AT aktualisiert,
 *       MENTION-Notification fuer erwaehnte Benutzer vorhanden
 *   <li>POST /comments mit parentCommentId -> PARENT_COMMENT_ID gesetzt, REPLY-Notification emittiert
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

  // ---- IT 1: POST comment -> persistiert + COMMENT_COUNT++ + LAST_ACTIVITY_AT + MENTION-Notification

  @Test
  @DisplayName(
      "post_comment_persistiert_zaehlt_hoch_und_benachrichtigt:"
          + " POST -> TASK_COMMENT-Zeile + COMMENT_COUNT+1 + LAST_ACTIVITY_AT aktualisiert"
          + " + MENTION-Notification fuer @user-b-comment")
  void post_comment_persistiert_zaehlt_hoch_und_benachrichtigt() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

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

    // COMMENT_COUNT vor dem Kommentar
    final Integer countBefore =
        jdbcTemplate.queryForObject(
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            Integer.class,
            taskUuid);

    // Kommentar mit @-Mention posten
    final String commentText = "Hallo @" + USER_B_LOGIN + ", bitte pruefen.";
    final String commentBody = om.writeValueAsString(Map.of("text", commentText));
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
            "SELECT COMMENT_COUNT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            Integer.class,
            taskUuid);
    assertThat(countAfter)
        .as("COMMENT_COUNT muss um 1 gestiegen sein")
        .isEqualTo(countBefore + 1);

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

  // ---- IT 2: Reply -> PARENT_COMMENT_ID gesetzt + REPLY-Notification ---------------------------

  @Test
  @DisplayName(
      "reply_setzt_parent_und_benachrichtigt_parent_autor:"
          + " POST mit parentCommentId -> PARENT_COMMENT_ID gesetzt"
          + " + REPLY-Notification fuer Eltern-Autor")
  void reply_setzt_parent_und_benachrichtigt_parent_autor() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    final String tokenB = buildJwtWithAuthority(
        TENANT_A_UUID, USER_B_REF, USER_B_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

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

  // ---- Negativ-IT: 401 / 403 / 404 -----------------------------------------------------------

  @Test
  @DisplayName("anonym_401_falsche_permission_403_unbekannte_task_404")
  void anonym_401_falsche_permission_403_unbekannte_task_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // 1. Anonym -> 401
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Test"))))
        .andExpect(status().isUnauthorized());

    // 2. Falsche Permission (nur TASK_READ, kein TASK_WRITE) -> 403
    final String tokenReadOnly = buildJwtWithAuthority(
        TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN,
        "task-management:TASK_READ");
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Test"))))
        .andExpect(status().isForbidden());

    // 3. TASK_WRITE-Token aber unbekannte Task-UUID -> 404
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    mvc.perform(
            post("/api/v1/tasks/" + UUID.randomUUID() + "/comments")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("text", "Test fuer unbekannte Task"))))
        .andExpect(status().isNotFound());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

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
