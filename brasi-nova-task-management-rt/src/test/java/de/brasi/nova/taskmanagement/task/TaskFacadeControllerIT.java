package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import io.jsonwebtoken.Jwts;
import io.keel.framework.api.exception.EntityNotFoundException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer den {@link TaskFacadeController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
 *
 * <ul>
 *   <li>POST -> PATCH -> GET Detail enthaelt History-Eintrag und markRead gesetzt
 *   <li>Anonym -> 401; falsche Permission -> 403; fremder Tenant -> 404
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TaskFacadeControllerIT.ItConfig.class,
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
class TaskFacadeControllerIT {

  private static final String TEST_KID = "task-facade-it-key";

  /** Mandant A: Haupt-Tenant fuer positive Tests. */
  private static final long TENANT_A_ID = 500L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final UUID FIXTURE_SOURCE_REF = UUID.randomUUID();
  private static final UUID FIXTURE_ALT_SOURCE_REF = UUID.randomUUID();
  private static final AtomicBoolean FIXTURE_SOURCE_READABLE = new AtomicBoolean(true);
  private static final AtomicBoolean FIXTURE_SOURCE_UNAVAILABLE = new AtomicBoolean(false);
  private static final AtomicReference<CyclicBarrier> FIXTURE_SOURCE_BARRIER =
      new AtomicReference<>();
  private static final AtomicInteger FIXTURE_BARRIER_ENTRIES = new AtomicInteger();
  private static final Set<Integer> FIXTURE_SOURCE_SPIDS = ConcurrentHashMap.newKeySet();
  private static JdbcTemplate sourceProofJdbcTemplate;
  private static final String USER_A_LOGIN = "user-a@brasi.de";
  private static final long USER_A_DB_ID = 501L;

  /** Mandant B: fremder Tenant fuer Negativ-Test (404 beim Detail). */
  private static final long TENANT_B_ID = 502L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b@brasi.de";
  private static final long USER_B_DB_ID = 503L;

  /** Zweiter Tenant-A-User fuer den Assignee-Read-Contract (P2). */
  private static final UUID USER_A2_REF = UUID.randomUUID();

  private static final String USER_A2_LOGIN = "user-a2@brasi.de";
  private static final long USER_A2_DB_ID = 504L;

  /** User mit READ-Only-Recht (kein TASK_WRITE -> 403 bei POST). */
  private static final UUID USER_READ_ONLY_REF = UUID.randomUUID();

  private static final String USER_READ_ONLY_LOGIN = "read-only@brasi.de";

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
      pemTempFile = Files.createTempFile("task-facade-it-jwk-", ".pem");
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
  @Autowired private TaskKeysetQueryRepository keysetQueryRepository;
  @Autowired private DictionaryCodeResolver dictionaryCodeResolver;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    sourceProofJdbcTemplate = jt;
    // PROD_FIRMA: Mandant A + B
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    // PROD_BENUTZER: User A (Mandant A) + User B (Mandant B)
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_A2_DB_ID, USER_A2_REF, USER_A2_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
  }

  // ---- Positiv-IT: POST -> PATCH -> GET Detail -----------------------------------------------

  @Test
  void sourceCreatePersistsTupleAndReturnsItWithoutUnprovenLink() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_CREATE",
            "task-management:TASK_READ");
    final String title = "Fixture source " + UUID.randomUUID();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String response =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid = UUID.fromString(om.readTree(response).get("id").asText());

    final Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT SOURCE_MODULE, SOURCE_ENTITY_TYPE, SOURCE_ENTITY_REF, ORIGIN_TENANT_ID"
                + " FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            taskUuid);
    assertThat(row.get("SOURCE_MODULE")).isEqualTo("fixture");
    assertThat(row.get("SOURCE_ENTITY_TYPE")).isEqualTo("item");
    assertThat(row.get("SOURCE_ENTITY_REF").toString())
        .isEqualToIgnoringCase(FIXTURE_SOURCE_REF.toString());
    assertThat(((Number) row.get("ORIGIN_TENANT_ID")).longValue()).isEqualTo(TENANT_A_ID);
    final String detail =
        mvc.perform(get("/api/v1/tasks/" + taskUuid).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(om.readTree(detail).path("source").path("entityRef").asText())
        .isEqualTo(FIXTURE_SOURCE_REF.toString());
    assertThat(om.readTree(detail).path("source").path("href").isNull()).isTrue();

    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "source",
                            Map.of(
                                "module",
                                "fixture",
                                "entityType",
                                "item",
                                "entityRef",
                                UUID.randomUUID())))))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT SOURCE_ENTITY_REF FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                UUID.class,
                taskUuid))
        .isEqualTo(FIXTURE_SOURCE_REF);
  }

  @Test
  void sourceCreateRequiresIdempotencyKeyWithoutPersistingTask() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Missing key " + UUID.randomUUID();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isZero();
  }

  @Test
  void sourceCreateRejectsInvalidIdempotencyKeys() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Invalid keys " + UUID.randomUUID();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    for (final String key : List.of("", "x".repeat(129), "non-ascii-ä")) {
      mvc.perform(
              post("/api/v1/tasks/from-source")
                  .header("Authorization", "Bearer " + token)
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isZero();
  }

  @Test
  void taskCreateAuthorityCannotUseManualRoute() throws Exception {
    final MockMvc mvc = buildMvc();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Create-only manual " + UUID.randomUUID();

    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + createOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isForbidden());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isZero();
  }

  @Test
  void taskWriteAuthorityCannotCreateFromSource() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String writeOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final String title = "Write-only source " + UUID.randomUUID();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + writeOnly)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + writeOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isZero();
  }

  @Test
  void contextRouteRequiresSource() throws Exception {
    final MockMvc mvc = buildMvc();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Context without source " + UUID.randomUUID();

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isZero();
  }

  @Test
  void sourceCreateRetryReturnsSameUuidWithoutDuplicateRows() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Idempotent source " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String retry =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid = UUID.fromString(om.readTree(first).path("id").asText());
    assertThat(om.readTree(retry).path("id").asText()).isEqualTo(taskUuid.toString());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_SOURCE_CREATE_RECEIPT] WHERE TASK_UUID = ?",
                Integer.class,
                taskUuid))
        .isEqualTo(1);
  }

  @Test
  void sourceCreateRetryCanonicalizesEffectiveDefaultsAndAssigneeSet() throws Exception {
    final MockMvc mvc = buildMvc();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[DIC_TASK_PRIORITY] WHERE CODE = ? AND CODE = ?",
                Integer.class,
                "NORMAL",
                "normal"))
        .isEqualTo(1);
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Canonical source " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final long creatorGenerationBefore = readGeneration(USER_A_REF);
    final long assigneeGenerationBefore = readGeneration(USER_A2_REF);
    final Map<String, Object> source =
        Map.of("module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF);
    final String firstBody =
        om.writeValueAsString(
            Map.of(
                "title", "  " + title + "  ", "source", source, "assignees", List.of(USER_A2_REF)));
    final String equivalentBody =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                source,
                "priorityCode",
                "NORMAL",
                "isPinned",
                false,
                "isFlagged",
                false,
                "assignees",
                List.of(USER_A_REF, USER_A2_REF, USER_A2_REF)));
    final String samePriorityDifferentCase =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                source,
                "priorityCode",
                "normal",
                "assignees",
                List.of(USER_A2_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(firstBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String retry =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(equivalentBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String priorityCaseRetry =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(samePriorityDifferentCase))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(om.readTree(retry).path("id").asText())
        .isEqualTo(om.readTree(first).path("id").asText());
    assertThat(om.readTree(priorityCaseRetry).path("id").asText())
        .isEqualTo(om.readTree(first).path("id").asText());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
    final UUID taskUuid = UUID.fromString(om.readTree(first).path("id").asText());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?",
                Integer.class,
                taskUuid,
                USER_A2_REF))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
                Integer.class,
                taskUuid,
                USER_A2_REF))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t ON n.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
                Integer.class,
                taskUuid,
                USER_A2_REF))
        .isEqualTo(1);
    assertThat(readGeneration(USER_A_REF)).isEqualTo(creatorGenerationBefore + 1);
    assertThat(readGeneration(USER_A2_REF)).isEqualTo(assigneeGenerationBefore + 1);
  }

  @Test
  void sourceCreateKeyCannotBeReusedForDifferentPayload() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Conflict source " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final Map<String, Object> source =
        Map.of("module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF);
    final String firstBody = om.writeValueAsString(Map.of("title", title, "source", source));
    final String differentBody =
        om.writeValueAsString(Map.of("title", title + " changed", "source", source));

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(differentBody))
        .andExpect(status().isConflict());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE IN (?, ?)",
                Integer.class,
                title,
                title + " changed"))
        .isEqualTo(1);
  }

  @Test
  void sourceCreateKeyIsScopedToCurrentActor() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String firstActor =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String secondActor =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN, "task-management:TASK_CREATE");
    final String title = "Scoped key " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + firstActor)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String second =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + secondActor)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(om.readTree(second).path("id").asText())
        .isNotEqualTo(om.readTree(first).path("id").asText());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(2);
  }

  @Test
  void failedSourceCreateDoesNotReserveKeyOrLeavePartialRows() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Failed source " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final Map<String, Object> source =
        Map.of("module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF);
    final String invalidBody =
        om.writeValueAsString(
            Map.of("title", title, "source", source, "assignees", List.of(UUID.randomUUID())));
    final String validBody = om.writeValueAsString(Map.of("title", title, "source", source));

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_SOURCE_CREATE_RECEIPT]"
                    + " WHERE TENANT_UUID = ? AND ACTOR_REF = ? AND IDEMPOTENCY_KEY = ?",
                Integer.class,
                TENANT_A_UUID,
                USER_A_REF,
                key))
        .isZero();

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
        .andExpect(status().isCreated());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
  }

  @Test
  void sourceCreateRetryRechecksCurrentSourceRead() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Revoked source " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));
    final String changedSourceBody =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module",
                    "fixture",
                    "entityType",
                    "item",
                    "entityRef",
                    FIXTURE_ALT_SOURCE_REF)));

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    FIXTURE_SOURCE_READABLE.set(false);
    try {
      mvc.perform(
              post("/api/v1/tasks/from-source")
                  .header("Authorization", "Bearer " + createOnly)
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isNotFound());
      mvc.perform(
              post("/api/v1/tasks/from-source")
                  .header("Authorization", "Bearer " + createOnly)
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(changedSourceBody))
          .andExpect(status().isNotFound());
    } finally {
      FIXTURE_SOURCE_READABLE.set(true);
    }
    FIXTURE_SOURCE_UNAVAILABLE.set(true);
    try {
      mvc.perform(
              post("/api/v1/tasks/from-source")
                  .header("Authorization", "Bearer " + createOnly)
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(changedSourceBody))
          .andExpect(status().isServiceUnavailable());
    } finally {
      FIXTURE_SOURCE_UNAVAILABLE.set(false);
    }
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK] SET SOURCE_MODULE = ? WHERE TITLE = ?", "unregistered", title);
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
  }

  @Test
  void sourceCreateRetryDoesNotRevealTaskAfterMembershipRevocation() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Revoked member " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid = UUID.fromString(om.readTree(first).path("id").asText());
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET ENTITY_STATUS = 'DEACTIVE'"
            + " WHERE TASK_ID = (SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?)"
            + " AND PARTICIPANT_REF = ?",
        taskUuid,
        USER_A_REF);

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
    final String changedBody =
        om.writeValueAsString(
            Map.of(
                "title",
                title + " changed",
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(changedBody))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
  }

  @Test
  void sourceCreateRetryDoesNotRevealTaskAfterCreatorExit() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Exited creator replay " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid = UUID.fromString(om.readTree(first).path("id").asText());
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET EXIT_KIND = 'UNSUBSCRIBED',"
            + " EXIT_AT = SYSDATETIME() WHERE TASK_ID ="
            + " (SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?) AND PARTICIPANT_REF = ?",
        taskUuid,
        USER_A_REF);

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
    final String changedBody =
        om.writeValueAsString(
            Map.of(
                "title",
                title + " changed",
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(changedBody))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
  }

  @Test
  void sourceCreateRetryAllowsActivePersonAfterAssigneeDowngrade() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Downgraded creator replay " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid = UUID.fromString(om.readTree(first).path("id").asText());
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET ASSIGNEE_FLAG = 0"
            + " WHERE TASK_ID = (SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?)"
            + " AND PARTICIPANT_REF = ?",
        taskUuid,
        USER_A_REF);

    final String replay =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(om.readTree(replay).path("id").asText()).isEqualTo(taskUuid.toString());
  }

  @Test
  void sourceCreateRetryDoesNotRevealInactiveTask() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Inactive replay " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of(
                    "module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF)));

    final String first =
        mvc.perform(
                post("/api/v1/tasks/from-source")
                    .header("Authorization", "Bearer " + createOnly)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid = UUID.fromString(om.readTree(first).path("id").asText());
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?", taskUuid);

    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + createOnly)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
        .isEqualTo(1);
  }

  @Test
  void concurrentSourceCreateUsesOneTaskParticipantAndState() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String createOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_CREATE");
    final String title = "Concurrent source " + UUID.randomUUID();
    final String key = UUID.randomUUID().toString();
    final long creatorGenerationBefore = readGeneration(USER_A_REF);
    final long assigneeGenerationBefore = readGeneration(USER_A2_REF);
    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "source",
                Map.of("module", "fixture", "entityType", "item", "entityRef", FIXTURE_SOURCE_REF),
                "assignees",
                List.of(USER_A2_REF)));
    final CyclicBarrier barrier = new CyclicBarrier(2);
    FIXTURE_BARRIER_ENTRIES.set(0);
    FIXTURE_SOURCE_SPIDS.clear();
    FIXTURE_SOURCE_BARRIER.set(barrier);
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      final java.util.concurrent.Callable<MvcResult> request =
          () ->
              mvc.perform(
                      post("/api/v1/tasks/from-source")
                          .header("Authorization", "Bearer " + createOnly)
                          .header("Idempotency-Key", key)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(body))
                  .andReturn();
      final Future<MvcResult> first = pool.submit(request);
      final Future<MvcResult> second = pool.submit(request);
      final MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
      final MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
      assertThat(
              List.of(
                  firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
          .containsExactlyInAnyOrder(201, 200);
      assertThat(FIXTURE_SOURCE_SPIDS)
          .as("zwei unabhaengige MSSQL-Connections im Claim-TX")
          .hasSize(2);
      final UUID firstUuid =
          UUID.fromString(
              om.readTree(firstResult.getResponse().getContentAsString()).path("id").asText());
      final UUID secondUuid =
          UUID.fromString(
              om.readTree(secondResult.getResponse().getContentAsString()).path("id").asText());
      assertThat(secondUuid).isEqualTo(firstUuid);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ?", Integer.class, title))
          .isEqualTo(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                      + " WHERE t.EXTERNALID = ?",
                  Integer.class,
                  firstUuid))
          .isEqualTo(2);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                      + " WHERE t.EXTERNALID = ?",
                  Integer.class,
                  firstUuid))
          .isEqualTo(2);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] n JOIN [dbo].[TASK] t"
                      + " ON n.TASK_ID = t.ID WHERE t.EXTERNALID = ? AND n.RECIPIENT_REF = ?",
                  Integer.class,
                  firstUuid,
                  USER_A2_REF))
          .isEqualTo(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM [dbo].[TASK_SOURCE_CREATE_RECEIPT]"
                      + " WHERE TENANT_UUID = ? AND ACTOR_REF = ? AND IDEMPOTENCY_KEY = ?",
                  Integer.class,
                  TENANT_A_UUID,
                  USER_A_REF,
                  key))
          .isEqualTo(1);
      assertThat(readGeneration(USER_A_REF)).isEqualTo(creatorGenerationBefore + 1);
      assertThat(readGeneration(USER_A2_REF)).isEqualTo(assigneeGenerationBefore + 1);
    } finally {
      FIXTURE_SOURCE_BARRIER.set(null);
      pool.shutdownNow();
    }
  }

  @Test
  void unregisteredOrUnreadableSourceLeavesNoTaskRows() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_CREATE",
            "task-management:TASK_READ");
    final String unknownTitle = "Unknown source " + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "title",
                            unknownTitle,
                            "source",
                            Map.of(
                                "module",
                                "application",
                                "entityType",
                                "customer",
                                "entityRef",
                                UUID.randomUUID())))))
        .andExpect(status().isBadRequest());
    final String deniedTitle = "Denied source " + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "title",
                            deniedTitle,
                            "source",
                            Map.of(
                                "module",
                                "fixture",
                                "entityType",
                                "item",
                                "entityRef",
                                UUID.randomUUID())))))
        .andExpect(status().isNotFound());
    final String injectedTitle = "Injected source " + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/tasks/from-source")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of(
                            "title",
                            injectedTitle,
                            "source",
                            Map.of(
                                "module",
                                "fixture",
                                "entityType",
                                "item",
                                "entityRef",
                                FIXTURE_SOURCE_REF,
                                "href",
                                "https://evil.invalid")))))
        .andExpect(status().isBadRequest());
    final String rootAliasTitle = "Root source alias " + UUID.randomUUID();
    final String writeToken =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + writeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of("title", rootAliasTitle, "source_url", "https://evil.invalid"))))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE IN (?, ?, ?, ?)",
                Integer.class,
                unknownTitle,
                deniedTitle,
                injectedTitle,
                rootAliasTitle))
        .isZero();
  }

  @Test
  @DisplayName(
      "post_patch_detail_history_markRead: POST legt Task an, PATCH aendert Titel,"
          + " GET liefert Detail mit History + markRead")
  void post_patch_detail_history_markRead() throws Exception {
    final MockMvc mvc = buildMvc();

    // 1. POST /api/v1/tasks -> 201 + {"id": "<uuid>"}
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final ObjectMapper om = new ObjectMapper();
    om.registerModule(new JavaTimeModule());

    final String createBody =
        om.writeValueAsString(Map.of("title", "IT-Testtitel", "description", "IT-Beschreibung"));

    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(taskId).as("POST muss eine UUID zurueckgeben").isNotNull();

    // 2. PATCH /api/v1/tasks/{id} -> 204
    // PatchValueDeserializer: Wert direkt als JSON-Wert (kein Wrapper-Objekt).
    // Fehlende Felder -> PatchValue.absent(); vorhandene Felder -> PatchValue.of(value).
    final String patchBody = om.writeValueAsString(Map.of("title", "Geaenderter Titel"));
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))
        .andExpect(status().isNoContent());

    // 3. GET /api/v1/tasks/{id} -> 200 + TaskDetailModel
    final String detailResponse =
        mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(taskId.toString()))
            .andExpect(jsonPath("$.title").value("Geaenderter Titel"))
            .andExpect(jsonPath("$.statusCode").value("OPEN"))
            .andExpect(jsonPath("$.directionCode").value("CUSTOMER_INTERNAL"))
            .andExpect(jsonPath("$.history").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // History muss mindestens einen Eintrag enthalten (Titel-Aenderung)
    final com.fasterxml.jackson.databind.JsonNode detailNode = om.readTree(detailResponse);
    assertThat(detailNode.get("history").size())
        .as("History muss mindestens 1 Eintrag enthalten")
        .isGreaterThanOrEqualTo(1);

    // READ_AT: nach GET muss TASK_USER_STATE.READ_AT gesetzt sein
    final Integer readAtSet =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] "
                + "WHERE USER_REF = ? AND READ_AT IS NOT NULL",
            Integer.class,
            USER_A_REF);
    assertThat(readAtSet)
        .as("READ_AT muss nach GET fuer den Benutzer gesetzt sein")
        .isGreaterThanOrEqualTo(1);
  }

  // ---- P2 IT: GET Detail liefert aktuelle Assignees (Read-before-edit) -----------------------

  @Test
  @DisplayName(
      "detail_liefert_aktuelle_assignees:"
          + " PUT assignees -> GET /tasks/{id}.assignees enthaelt Creator + Assignee (stabil"
          + " sortiert), tagIds leer")
  void detail_liefert_aktuelle_assignees() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Assignee-Detail-Test");

    // PUT assignees=[USER_A (Creator), USER_A2] - beide Tenant A (findActiveByUuids
    // mandanten-scoped).
    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(Map.of("assignees", List.of(USER_A_REF, USER_A2_REF)))))
        .andExpect(status().is2xxSuccessful());

    final com.fasterxml.jackson.databind.JsonNode detail = getDetail(mvc, om, tokenA, taskId);
    final List<String> assignees = readStringArray(detail.get("assignees"));
    final List<String> expected =
        new java.util.ArrayList<>(List.of(USER_A_REF.toString(), USER_A2_REF.toString()));
    java.util.Collections.sort(expected);
    assertThat(assignees)
        .as("assignees muss Creator + hinzugefuegten Assignee stabil sortiert enthalten")
        .containsExactlyElementsOf(expected);
    assertThat(detail.get("tagIds").size()).as("keine Tags zugeordnet").isEqualTo(0);
  }

  // ---- IT: lange Beschreibung > 255 Zeichen -> 201 + GET zeigt vollen Text (nvarchar max) -------

  @Test
  @DisplayName(
      "lange_beschreibung_ueber_255_wird_akzeptiert:"
          + " description > 255 Zeichen -> 201, GET Detail liefert den vollen Text")
  void lange_beschreibung_ueber_255_wird_akzeptiert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final String longDesc = "B".repeat(300); // > 255
    final String postResp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(
                            Map.of("title", "LangDesc", "description", longDesc))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    final com.fasterxml.jackson.databind.JsonNode detail = getDetail(mvc, om, tokenA, taskId);
    assertThat(detail.get("description").asText())
        .as("volle Beschreibung (300 Zeichen) muss persistiert + ausgeliefert werden")
        .isEqualTo(longDesc);
  }

  // ---- Negativ-IT: 401 / 403 / 404 -----------------------------------------------------------

  @Test
  @DisplayName("anonym_401_falsche_permission_403_fremder_tenant_404")
  void anonym_401_falsche_permission_403_fremder_tenant_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // 1. Anonym -> 401
    mvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());

    // 2. Falsche Permission (TASK_READ statt TASK_WRITE) -> 403
    final String tokenReadOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_READ_ONLY_REF,
            USER_READ_ONLY_LOGIN,
            "task-management:TASK_READ" /* kein TASK_WRITE */);
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Test"))))
        .andExpect(status().isForbidden());

    // 3. Fremder Tenant: User B versucht Task von Tenant A aufzurufen -> 404
    // Erst: User A legt Task an
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final String createBody = om.writeValueAsString(Map.of("title", "Tenant-A-Task"));
    final String postResp =
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
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // User B (Tenant B) versucht GET auf Task von Tenant A -> 404 (fremder Mandant)
    final String tokenB =
        buildJwtWithAuthority(TENANT_B_UUID, USER_B_REF, USER_B_LOGIN, "task-management:TASK_READ");
    mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  // ---- MAJOR 1: PATCH auf fremde/nicht-existente UUID -> 404 ------------------------------------

  @Test
  @DisplayName(
      "patch_nicht_existent_und_fremd_liefert_404: beide Faelle liefern 404, ununterscheidbar")
  void patch_nicht_existent_und_fremd_liefert_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // (a) Zufaellige UUID (existiert nicht) -> 404
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            patch("/api/v1/tasks/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Irgendwas"))))
        .andExpect(status().isNotFound());

    // (b) Fremder Tenant: User A legt Task an, User B (Tenant B) versucht PATCH -> 404
    final String createBody = om.writeValueAsString(Map.of("title", "Tenant-A-Patch-Test"));
    final String postResp =
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
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // User B versucht PATCH auf Task von Tenant A -> 404 (nicht 409!)
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_B_UUID, USER_B_REF, USER_B_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Fremder Zugriff"))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "PATCH ist fuer grantfreie aktive Member erlaubt und fuer Nicht- oder Ex-Member ohne Task-Write verboten")
  void patch_membership_guard_wire_und_keine_verweigerten_writes() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String creator =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final String member = buildJwtWithAuthority(TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN);
    final String nonMember =
        buildJwtWithAuthority(TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN);
    final UUID taskId = createTask(mvc, om, creator, "Membership-PATCH-Original");

    final String originalActivity =
        jdbcTemplate.queryForObject(
            "SELECT CONVERT(varchar(33), LAST_ACTIVITY_AT, 126) FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            String.class,
            taskId);
    final Integer originalHistory =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskId);
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + nonMember)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"verboten\"}"))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT TITLE FROM [dbo].[TASK] WHERE EXTERNALID = ?", String.class, taskId))
        .isEqualTo("Membership-PATCH-Original");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT CONVERT(varchar(33), LAST_ACTIVITY_AT, 126) FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                String.class,
                taskId))
        .isEqualTo(originalActivity);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskId))
        .isEqualTo(originalHistory);

    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + creator)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("assignees", List.of(USER_A2_REF)))))
        .andExpect(status().isNoContent());
    assertThat(getDetail(mvc, om, member, taskId).get("title").asText())
        .isEqualTo("Membership-PATCH-Original");
    final Integer historyBeforeMemberPatch =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskId);
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + member)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Membership-PATCH-Erlaubt\"}"))
        .andExpect(status().isNoContent());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT TITLE FROM [dbo].[TASK] WHERE EXTERNALID = ?", String.class, taskId))
        .isEqualTo("Membership-PATCH-Erlaubt");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskId))
        .isGreaterThan(historyBeforeMemberPatch);

    mvc.perform(
            put("/api/v1/tasks/" + taskId + "/assignees")
                .header("Authorization", "Bearer " + creator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignees\":[]}"))
        .andExpect(status().isNoContent());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ? AND p.PARTICIPANT_REF = ?"
                    + " AND p.EXIT_KIND = 'UNSUBSCRIBED' AND p.EXIT_AT IS NOT NULL",
                Integer.class,
                taskId,
                USER_A2_REF))
        .isEqualTo(1);
    assertThat(getDetail(mvc, om, member, taskId).get("exit").get("kind").asText())
        .isEqualTo("unsubscribed");
    final String activityAfterExit =
        jdbcTemplate.queryForObject(
            "SELECT CONVERT(varchar(33), LAST_ACTIVITY_AT, 126) FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            String.class,
            taskId);
    final Integer historyAfterExit =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ?",
            Integer.class,
            taskId);
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + member)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Ex-Member-Verboten\"}"))
        .andExpect(status().isNotFound());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT TITLE FROM [dbo].[TASK] WHERE EXTERNALID = ?", String.class, taskId))
        .isEqualTo("Membership-PATCH-Erlaubt");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT CONVERT(varchar(33), LAST_ACTIVITY_AT, 126) FROM [dbo].[TASK] WHERE EXTERNALID = ?",
                String.class,
                taskId))
        .isEqualTo(activityAfterExit);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                    + " WHERE t.EXTERNALID = ?",
                Integer.class,
                taskId))
        .isEqualTo(historyAfterExit);
  }

  // ---- MAJOR (Re-Gate): GET Detail nicht-existent + fremd -> 404, identische neutrale Meldung
  // ----

  @Test
  @DisplayName("detail_nicht_existent_und_fremd_liefert_404_identisch: GET maskiert Existenz")
  void detail_nicht_existent_und_fremd_liefert_404_identisch() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // (a) Zufaellige UUID (existiert nicht) -> 404
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID randomId = UUID.randomUUID();
    final String bodyNotFound =
        mvc.perform(get("/api/v1/tasks/" + randomId).header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // (b) Fremder Tenant: User A legt Task an, User B (Tenant B) GET Detail -> 404
    final String postResp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", "Tenant-A-Detail-Test"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID foreignTaskId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    final String tokenB =
        buildJwtWithAuthority(TENANT_B_UUID, USER_B_REF, USER_B_LOGIN, "task-management:TASK_READ");
    final String bodyForeign =
        mvc.perform(
                get("/api/v1/tasks/" + foreignTaskId).header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Existenz-Maskierung: die UNTERSCHEIDENDEN Felder (message, errorKey) sind in beiden Faellen
    // identisch neutral. (Das Feld "path" echot bewusst den vom Client selbst angefragten URL-Pfad
    // und ist daher KEINE Existenz-Disclosure - der Client kennt die angefragte UUID bereits.)
    assertThat(bodyNotFound)
        .contains("\"message\":\"Aufgabe nicht gefunden.\"")
        .contains("\"errorKey\":\"NOVA_NOT_FOUND\"");
    assertThat(bodyForeign)
        .as(
            "Fremd-Tenant-404 muss dieselbe neutrale Meldung + errorKey liefern wie Nicht-gefunden-404")
        .contains("\"message\":\"Aufgabe nicht gefunden.\"")
        .contains("\"errorKey\":\"NOVA_NOT_FOUND\"");
  }

  // ---- MAJOR 3: ungueltige Client-Codes -> 400 -------------------------------------------------

  @Test
  @DisplayName("post_unbekannter_priorityCode_liefert_400: unbekannter priorityCode -> 400")
  void post_unbekannter_priority_code_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        Map.of("title", "400-Test", "priorityCode", "GIBTS_NICHT"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("patch_unbekannter_statusCode_liefert_400: unbekannter statusCode -> 400")
  void patch_unbekannter_status_code_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // Erst Task anlegen
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");
    final String postResp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", "Status-400-Test"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // PATCH mit unbekanntem statusCode -> 400
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", "GIBTS_NICHT"))))
        .andExpect(status().isBadRequest());
  }

  // ---- MINOR 2: Creator-Participant-Flags + Inbox-Sichtbarkeit ---------------------------------

  @Test
  @DisplayName(
      "post_creator_participant_flags_und_inbox_sichtbar: CREATOR_FLAG=1/ASSIGNEE_FLAG=1 in DB"
          + " und Task via findActivePage fuer Creator sichtbar")
  void post_creator_participant_flags_und_inbox_sichtbar() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    om.registerModule(new JavaTimeModule());

    // POST: neuen Task anlegen
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final String createBody = om.writeValueAsString(Map.of("title", "Minor2-Creator-Test"));
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

    final UUID taskExternalId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(taskExternalId).as("POST muss UUID zurueckgeben").isNotNull();

    // (a) CREATOR_FLAG=1 und ASSIGNEE_FLAG=1 EXAKT fuer den gerade erzeugten Task pruefen
    // (gebunden ueber TASK.EXTERNALID = taskExternalId + Tenant; genau ein Datensatz erwartet)
    final Integer creatorParticipantCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = ? AND p.PARTICIPANT_REF = ?"
                + " AND p.CREATOR_FLAG = 1 AND p.ASSIGNEE_FLAG = 1",
            Integer.class,
            taskExternalId,
            TENANT_A_ID,
            USER_A_REF);
    assertThat(creatorParticipantCount)
        .as(
            "Genau ein Creator-Participant (CREATOR_FLAG=1/ASSIGNEE_FLAG=1) fuer den erzeugten Task")
        .isEqualTo(1);

    // (b) Task via findActivePage (benutzerscoped, aufgeloeste OPEN-Id) fuer Creator sichtbar
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
      final List<Task> activeTasks =
          keysetQueryRepository.findActivePage(
              USER_A_REF,
              openStatusId,
              asOf,
              asOf,
              UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
              10);
      final boolean taskFound =
          activeTasks.stream().anyMatch(t -> taskExternalId.equals(t.getUuid()));
      assertThat(taskFound)
          .as("Task muss in der benutzerscoped Inbox-Seite fuer den Creator sichtbar sein")
          .isTrue();
    } finally {
      TenantContextHolder.clear();
    }
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  // ---- Final-Review P1-1: logisch deaktivierter Task nicht les-/patchbar -----------------------

  @Test
  @DisplayName(
      "deaktivierter_task_ist_nicht_les_oder_patchbar_404:"
          + " ENTITY_STATUS!='ACTIVE' -> GET Detail + PATCH liefern neutrale 404")
  void deaktivierter_task_ist_nicht_les_oder_patchbar_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Aktiven Task anlegen ...
    final String postResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("title", "Deaktivierungs-Test"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // ... dann logisch deaktivieren (ENTITY_STATUS != 'ACTIVE')
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?", taskId);

    // GET Detail -> 404 (neutrale Maskierung wie fremder Tenant)
    mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound());

    // PATCH -> 404 (keine Mutation deaktivierter Aufgaben)
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Neuer Titel"))))
        .andExpect(status().isNotFound());
  }

  // ---- BE-3: TaskDetailModel-Anreicherung (createdById/participantIds/Marker/exit) -----------

  @Test
  @DisplayName(
      "detail_be3_createdById_participantIds_marker_actor_scoped:"
          + " GET /tasks/{id} liefert createdById=Ersteller, participantIds, Actor-Marker;"
          + " zweiter Actor ohne Marker -> isPinned=false (actor-scoped), 200 (kein Membership-Gate)")
  void detail_be3_createdById_participantIds_marker_actor_scoped() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "BE3-Detail-Enrichment");
    final Long internalId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskUuid);

    // Reiner PERSON-Teilnehmer (participantFlag) USER_A2 direkt seeden (POST /participants = BE-6).
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (590900, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, NULL, NULL, "
            + " 'it-seed', '2020-01-01T00:00:00')",
        TENANT_A_ID,
        internalId,
        USER_A2_REF,
        TENANT_A_ID);

    // Actor-Marker fuer USER_A setzen (Upsert ueber den Marker-Endpunkt).
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/pin")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().is2xxSuccessful());
    mvc.perform(
            put("/api/v1/tasks/" + taskUuid + "/mute")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":true}"))
        .andExpect(status().is2xxSuccessful());

    final com.fasterxml.jackson.databind.JsonNode detail = getDetail(mvc, om, tokenA, taskUuid);
    assertThat(detail.get("createdById").asText())
        .as("Ersteller aus CREATOR_FLAG-Row")
        .isEqualTo(USER_A_REF.toString());
    assertThat(readStringArray(detail.get("participantIds")))
        .as("reiner Teilnehmer, ohne Ersteller/Assignees")
        .containsExactly(USER_A2_REF.toString());
    assertThat(detail.get("isPinned").asBoolean()).isTrue();
    assertThat(detail.get("isMuted").asBoolean()).isTrue();
    assertThat(detail.get("isFlagged").asBoolean()).isFalse();
    assertThat(detail.get("exit").isNull()).as("aktiver Ersteller -> exit null").isTrue();

    // Zweiter Actor USER_A2 (Tenant A, TASK_READ): 200, createdById gleich, aber eigene Marker
    // false.
    final String tokenA2 =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN, "task-management:TASK_READ");
    final com.fasterxml.jackson.databind.JsonNode detailA2 = getDetail(mvc, om, tokenA2, taskUuid);
    assertThat(detailA2.get("createdById").asText()).isEqualTo(USER_A_REF.toString());
    assertThat(detailA2.get("isPinned").asBoolean())
        .as("Marker sind actor-scoped: USER_A2 hat nicht gepinnt")
        .isFalse();
    assertThat(detailA2.get("exit").isNull()).isTrue();
  }

  // ---- BE-4: Create-Enrichment (assignees + tagIds + marker, All-or-Nothing) -------------------

  @Test
  @DisplayName(
      "post_mit_assignee_tag_marker_201_db_beweis: POST mit assignees=[A2]+tagIds=[tag]+isPinned"
          + " -> 201; Assignee-Participant, aktives Tag-Assignment, Creator IS_PINNED=1,"
          + " ASSIGN-Notification fuer A2, genau EINE Aufgabe")
  void post_mit_assignee_tag_marker_201_db_beweis() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    om.registerModule(new JavaTimeModule());

    final UUID tag = seedGlobalTag(jdbcTemplate, 9301L, "IT-Create-Tag");
    final String title = "BE4-Create-Enrichment";

    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final String body =
        om.writeValueAsString(
            Map.of(
                "title",
                title,
                "assignees",
                List.of(USER_A2_REF),
                "tagIds",
                List.of(tag),
                "isPinned",
                Boolean.TRUE,
                "isFlagged",
                Boolean.TRUE));

    // Generation VOR dem POST festhalten (Delta-Beweis fuer den Bump DURCH diesen Create).
    final long creatorGenBefore = readGeneration(USER_A_REF);
    final long assigneeGenBefore = readGeneration(USER_A2_REF);

    final String postResp =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskExternalId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    final Long internalTaskId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskExternalId);

    // (a) Assignee-Participant: ASSIGNEE_FLAG=1, CREATOR_FLAG=0, PARTICIPANT_REF=USER_A2
    final Integer assigneeParticipant =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT]"
                + " WHERE TASK_ID = ? AND PARTICIPANT_REF = ? AND ASSIGNEE_FLAG = 1 AND CREATOR_FLAG = 0",
            Integer.class,
            internalTaskId,
            USER_A2_REF);
    assertThat(assigneeParticipant)
        .as("Assignee-Participant fuer USER_A2 (ASSIGNEE, nicht Creator)")
        .isEqualTo(1);

    // (b) Tag-Assignment aktiv
    final Integer tagActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE a.TASK_ID = ? AND tag.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            internalTaskId,
            tag);
    assertThat(tagActive).as("Tag-Assignment fuer den Initial-Tag muss AKTIV sein").isEqualTo(1);

    // (c) Creator-UserState IS_PINNED=1 UND IS_FLAGGED=1 (D5)
    final Integer creatorMarkers =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE]"
                + " WHERE TASK_ID = ? AND USER_REF = ? AND IS_PINNED = 1 AND IS_FLAGGED = 1",
            Integer.class,
            internalTaskId,
            USER_A_REF);
    assertThat(creatorMarkers).as("Creator-UserState IS_PINNED=1 und IS_FLAGGED=1").isEqualTo(1);

    // (c2) Assignee-UserState existiert (ensure -> inbox-faehig)
    final Integer assigneeState =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID = ? AND USER_REF = ?",
            Integer.class,
            internalTaskId,
            USER_A2_REF);
    assertThat(assigneeState).as("Assignee-UserState angelegt (ensure)").isEqualTo(1);

    // (c3) Persistierte Generation-Bumps DURCH DIESEN POST (Delta == +1), scoped Tenant+User:
    // Creator (D3b) UND Assignee (D3) je GENAU einmal in derselben Tx gebumpt.
    assertThat(readGeneration(USER_A_REF))
        .as("Creator-Generation exakt +1 durch den Create (D3b)")
        .isEqualTo(creatorGenBefore + 1);
    assertThat(readGeneration(USER_A2_REF))
        .as("Assignee-Generation exakt +1 durch den Create (D3)")
        .isEqualTo(assigneeGenBefore + 1);

    // (d) ASSIGN-Notification fuer USER_A2 (TYPE_ID = ASSIGN)
    final Long assignTypeId;
    TenantContextHolder.setTenant(new TenantInfo(TENANT_A_UUID, TENANT_A_ID));
    try {
      assignTypeId = dictionaryCodeResolver.resolveNotificationType(NotificationTypeCodes.ASSIGN);
    } finally {
      TenantContextHolder.clear();
    }
    final Integer assignNotif =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION]"
                + " WHERE TASK_ID = ? AND RECIPIENT_REF = ? AND TYPE_ID = ?",
            Integer.class,
            internalTaskId,
            USER_A2_REF,
            assignTypeId);
    assertThat(assignNotif).as("ASSIGN-Notification fuer USER_A2").isGreaterThanOrEqualTo(1);

    // (e) genau EINE Aufgabe mit diesem Titel (kein Teil-/Doppel-Insert)
    final Integer taskCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ? AND TENANT_ID = ?",
            Integer.class,
            title,
            TENANT_A_ID);
    assertThat(taskCount).as("genau eine Aufgabe angelegt").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "post_unbekannter_assignee_400_kein_task: unbekannte Assignee-UUID -> 400 + KEINE Aufgabe")
  void post_unbekannter_assignee_400_kein_task() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String title = "BE4-Unknown-Assignee";
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");

    final String body =
        om.writeValueAsString(Map.of("title", title, "assignees", List.of(UUID.randomUUID())));

    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    final Integer taskCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ? AND TENANT_ID = ?",
            Integer.class,
            title,
            TENANT_A_ID);
    assertThat(taskCount).as("KEINE Aufgabe nach 400 (All-or-Nothing)").isEqualTo(0);
  }

  @Test
  @DisplayName(
      "post_unbekannter_tag_mit_gueltigem_assignee_400_kein_task: unbekannter Tag (trotz gueltigem"
          + " Assignee) -> 400 + KEINE Aufgabe, KEIN Assignee-Participant")
  void post_unbekannter_tag_mit_gueltigem_assignee_400_kein_task() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String title = "BE4-Unknown-Tag";
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");

    final String body =
        om.writeValueAsString(
            Map.of(
                "title", title,
                "assignees", List.of(USER_A2_REF),
                "tagIds", List.of(UUID.randomUUID())));

    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    final Integer taskCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ? AND TENANT_ID = ?",
            Integer.class,
            title,
            TENANT_A_ID);
    assertThat(taskCount)
        .as("KEINE Aufgabe nach 400 (All-or-Nothing, auch mit gueltigem Assignee)")
        .isEqualTo(0);

    final Integer assigneeParticipant =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
                + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
                + " WHERE t.TENANT_ID = ? AND p.PARTICIPANT_REF = ? AND t.TITLE = ?",
            Integer.class,
            TENANT_A_ID,
            USER_A2_REF,
            title);
    assertThat(assigneeParticipant).as("kein Assignee-Participant nach 400").isEqualTo(0);
  }

  @Test
  @DisplayName(
      "detail_be3_ausgetretener_actor_exit_gesetzt:"
          + " Actor mit EXIT_KIND=HANDOVER -> detail.exit.kind=handover (Live-Read)")
  void detail_be3_ausgetretener_actor_exit_gesetzt() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "BE3-Detail-Exit");
    final Long internalId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskUuid);

    // Ersteller-Row von USER_A als ausgetreten markieren (HANDOVER, EXIT_AT in der Vergangenheit).
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET EXIT_KIND = 'HANDOVER', EXIT_AT = '2020-01-01T00:00:00' "
            + "WHERE TASK_ID = ? AND PARTICIPANT_REF = ? AND TENANT_ID = ?",
        internalId,
        USER_A_REF,
        TENANT_A_ID);

    final com.fasterxml.jackson.databind.JsonNode detail = getDetail(mvc, om, tokenA, taskUuid);
    assertThat(detail.get("exit").isNull()).as("ausgetretener Actor -> exit gesetzt").isFalse();
    assertThat(detail.get("exit").get("kind").asText()).isEqualTo("handover");
    // Ersteller bleibt aufgeloest (createdById unabhaengig vom Austritt).
    assertThat(detail.get("createdById").asText()).isEqualTo(USER_A_REF.toString());
  }

  @Test
  @DisplayName(
      "detail_be3_nicht_teilnehmer_actor_404 (Slice 4, Auflage 6):"
          + " TASK_READ-Grant-Holder im gleichen Mandanten OHNE Teilnehmer-Rolle -> 404 (neutral,"
          + " objekt-level Membership-Guard entzieht den member-scoped Read; bewusste Policy)")
  void detail_be3_nicht_teilnehmer_actor_200() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Aufgabe von USER_A; USER_A2 ist WEDER Ersteller NOCH Teilnehmer dieser Aufgabe.
    final UUID taskUuid = createTask(mvc, om, tokenA, "BE3-Detail-NonMember");

    // Slice 4: Ein reiner TASK_READ-Grant-Holder OHNE Participant-Row ist kein READ-Member mehr ->
    // der Detail-Read wird neutral mit 404 maskiert (kein Existenz-Leak), KEIN READ_AT-Write.
    final String tokenA2 =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN, "task-management:TASK_READ");
    mvc.perform(get("/api/v1/tasks/" + taskUuid).header("Authorization", "Bearer " + tokenA2))
        .andExpect(status().isNotFound());

    // Kein Seiteneffekt: der Guard greift VOR markRead -> keine TASK_USER_STATE-Zeile fuer USER_A2
    // AUF DIESER Aufgabe (task-scoped, da USER_A2 in anderen Tests dieser Klasse States haben
    // kann).
    final Integer readState =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] s"
                + " JOIN [dbo].[TASK] t ON s.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND s.USER_REF = ?",
            Integer.class,
            taskUuid,
            USER_A2_REF);
    assertThat(readState).as("Non-Member -> kein READ_AT/State-Write auf dieser Aufgabe").isZero();
  }

  @Test
  @DisplayName(
      "post_mehr_als_100_ids_400_auch_bei_duplikaten (Codex-MAJOR): 101 Eintraege (auch identische"
          + " UUIDs) in assignees bzw. tagIds -> 400 auf ROHER Array-Laenge, KEINE Aufgabe")
  void post_mehr_als_100_ids_400_auch_bei_duplikaten() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_WRITE");

    // 101x dieselbe (gueltige) Assignee-UUID -> Dedup wuerde auf 1 kollabieren; die ROHE Laenge 101
    // muss dennoch zu 400 fuehren (sonst Umgehung der MAX_IDS-Grenze).
    final String titleA = "BE4-TooMany-Assignees-Dup";
    final String bodyAssignees =
        om.writeValueAsString(
            Map.of(
                "title",
                titleA,
                "assignees",
                java.util.Collections.nCopies(
                    de.brasi.nova.taskmanagement.task.model.TaskCreateInput.MAX_IDS + 1,
                    USER_A2_REF)));
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyAssignees))
        .andExpect(status().isBadRequest());

    final String titleB = "BE4-TooMany-Tags-Dup";
    final String bodyTags =
        om.writeValueAsString(
            Map.of(
                "title",
                titleB,
                "tagIds",
                java.util.Collections.nCopies(
                    de.brasi.nova.taskmanagement.task.model.TaskCreateInput.MAX_IDS + 1,
                    UUID.randomUUID())));
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyTags))
        .andExpect(status().isBadRequest());

    final Integer taskCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK] WHERE (TITLE = ? OR TITLE = ?) AND TENANT_ID = ?",
            Integer.class,
            titleA,
            titleB,
            TENANT_A_ID);
    assertThat(taskCount)
        .as("KEINE Aufgabe nach 400 (MAX_IDS-Grenze auf roher Laenge)")
        .isEqualTo(0);
  }

  // ---- BE-5: PATCH startDate (set / clear / absent) -------------------------------------------

  @Test
  @DisplayName(
      "patch_startdate_set_clear: PATCH startDate=2026-11-01 -> Detail.startDate gesetzt + History;"
          + " PATCH startDate=null -> Detail.startDate null (DB START_DATE null); absent = No-Op")
  void patch_startdate_set_clear() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "BE5-StartDate");

    // 1. Setzen
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("startDate", "2026-11-01"))))
        .andExpect(status().isNoContent());

    final com.fasterxml.jackson.databind.JsonNode afterSet = getDetail(mvc, om, tokenA, taskId);
    assertThat(afterSet.get("startDate").asText())
        .as("Detail.startDate gesetzt")
        .isEqualTo("2026-11-01");
    boolean startDateHistory = false;
    for (final com.fasterxml.jackson.databind.JsonNode h : afterSet.get("history")) {
      if ("Startdatum geaendert".equals(h.path("text").asText())
          && h.path("changeDetail").asText().contains("2026-11-01")) {
        startDateHistory = true;
        break;
      }
    }
    assertThat(startDateHistory)
        .as("History-Eintrag 'Startdatum geaendert' mit Neuwert 2026-11-01 vorhanden")
        .isTrue();

    // 2. Absent = No-Op: ein PATCH ohne startDate aendert das Startdatum nicht.
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "BE5-StartDate-umbenannt"))))
        .andExpect(status().isNoContent());
    assertThat(getDetail(mvc, om, tokenA, taskId).get("startDate").asText())
        .as("startDate durch startDate-absenten PATCH unveraendert")
        .isEqualTo("2026-11-01");

    // 3. Loeschen via rohem JSON (Map.of akzeptiert keinen null-Wert)
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDate\":null}"))
        .andExpect(status().isNoContent());

    assertThat(getDetail(mvc, om, tokenA, taskId).get("startDate").isNull())
        .as("Detail.startDate null nach Loeschen")
        .isTrue();
    final Integer startDateNull =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK] WHERE EXTERNALID = ? AND START_DATE IS NULL",
            Integer.class,
            taskId);
    assertThat(startDateNull).as("DB START_DATE ist NULL nach Loeschen").isEqualTo(1);
  }

  // ---- Regression: PATCH statusCode persistiert (FK @NovaForeignKey.updateable=true) ----------

  @Test
  @DisplayName(
      "patch_statuscode_done_cancelled_persistiert (FK-updateable-Regression): PATCH statusCode=DONE"
          + " -> GET.statusCode=DONE + DB TASK_STATUS_ID=102; danach CANCELLED -> GET.statusCode="
          + "CANCELLED + DB TASK_STATUS_ID=103. Deckt den Bug ab, dass der FK-Status still gedroppt"
          + " wurde (204 ohne Persistenz).")
  void patch_statuscode_done_cancelled_persistiert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "StatusCode-Regression");

    // Ausgangslage: Create setzt Status OPEN (serverseitig).
    assertThat(getDetail(mvc, om, tokenA, taskId).get("statusCode").asText())
        .as("frisch angelegte Aufgabe steht auf OPEN")
        .isEqualTo(TaskStatusCodes.OPEN);

    // 1. PATCH statusCode=DONE -> 204 (echter Wire-Pfad: JSON -> PatchValue<String>)
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", TaskStatusCodes.DONE))))
        .andExpect(status().isNoContent());

    // GET-Beweis: Status DONE ausgeliefert (vor dem Fix blieb hier OPEN stehen).
    assertThat(getDetail(mvc, om, tokenA, taskId).get("statusCode").asText())
        .as("nach PATCH statusCode=DONE muss GET Detail DONE liefern")
        .isEqualTo(TaskStatusCodes.DONE);

    // DB-Beweis: TASK_STATUS_ID zeigt auf den DONE-Seed (Flyway V1: ID=102).
    final Long statusIdAfterDone =
        jdbcTemplate.queryForObject(
            "SELECT TASK_STATUS_ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);
    assertThat(statusIdAfterDone)
        .as("FK TASK_STATUS_ID muss nach PATCH DONE persistiert sein (Seed-ID 102)")
        .isEqualTo(102L);

    // 2. PATCH statusCode=CANCELLED -> 204 (zweiter Wechsel DONE -> CANCELLED)
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", TaskStatusCodes.CANCELLED))))
        .andExpect(status().isNoContent());

    assertThat(getDetail(mvc, om, tokenA, taskId).get("statusCode").asText())
        .as("nach PATCH statusCode=CANCELLED muss GET Detail CANCELLED liefern")
        .isEqualTo(TaskStatusCodes.CANCELLED);

    final Long statusIdAfterCancelled =
        jdbcTemplate.queryForObject(
            "SELECT TASK_STATUS_ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);
    assertThat(statusIdAfterCancelled)
        .as("FK TASK_STATUS_ID muss nach PATCH CANCELLED persistiert sein (Seed-ID 103)")
        .isEqualTo(103L);
  }

  // ---- Codex #3: Reopen (DONE -> OPEN) ist erlaubt (kein Transition-Guard) ----------------------

  @Test
  @DisplayName(
      "patch_reopen_done_zu_open_erlaubt_und_persistiert (Codex #3 Transition-Semantik): PATCH"
          + " statusCode=DONE -> dann statusCode=OPEN -> 204, GET.statusCode=OPEN + DB"
          + " TASK_STATUS_ID=101; Creator-Generation wird durch den Reopen gebumpt (Delta +1).")
  void patch_reopen_done_zu_open_erlaubt_und_persistiert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Reopen-Transition");

    // 1. OPEN -> DONE (Vorbedingung fuer den Reopen).
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", TaskStatusCodes.DONE))))
        .andExpect(status().isNoContent());
    assertThat(getDetail(mvc, om, tokenA, taskId).get("statusCode").asText())
        .isEqualTo(TaskStatusCodes.DONE);

    // 2. Reopen DONE -> OPEN: ERLAUBT (keine terminalen Status, kein Transition-Guard) -> 204.
    final long creatorGenBeforeReopen = readGeneration(USER_A_REF);
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", TaskStatusCodes.OPEN))))
        .andExpect(status().isNoContent());

    // GET-Beweis: Status wieder OPEN.
    assertThat(getDetail(mvc, om, tokenA, taskId).get("statusCode").asText())
        .as("Reopen DONE -> OPEN muss persistiert sein (GET liefert OPEN)")
        .isEqualTo(TaskStatusCodes.OPEN);

    // DB-Beweis: TASK_STATUS_ID zeigt auf den OPEN-Seed (Flyway V1: ID=101).
    final Long statusIdAfterReopen =
        jdbcTemplate.queryForObject(
            "SELECT TASK_STATUS_ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskId);
    assertThat(statusIdAfterReopen)
        .as("FK TASK_STATUS_ID muss nach Reopen auf OPEN (Seed-ID 101) stehen")
        .isEqualTo(101L);

    // Bump-Beweis: der Reopen ist eine changed-Mutation -> Creator-Generation exakt +1.
    assertThat(readGeneration(USER_A_REF))
        .as("Reopen (changed-Patch) muss die Creator-Generation um genau 1 bumpen")
        .isEqualTo(creatorGenBeforeReopen + 1);
  }

  // ---- Codex #4: statusCode-PATCH bumpt die Inbox-Generation jedes aktiven Teilnehmers ----------

  @Test
  @DisplayName(
      "patch_statuscode_bumpt_generation_aktiver_teilnehmer (Codex #4 Delta-Beweis): PATCH"
          + " statusCode=DONE -> persistierte Generation von Creator UND Assignee exakt +1 (beide"
          + " aktive Teilnehmer der Aufgabe).")
  void patch_statuscode_bumpt_generation_aktiver_teilnehmer() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID,
            USER_A_REF,
            USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Aufgabe mit zwei aktiven Teilnehmern: Creator USER_A + Assignee USER_A2.
    final String createBody =
        om.writeValueAsString(
            Map.of("title", "Patch-Bump-Delta", "assignees", List.of(USER_A2_REF)));
    final String postResp =
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
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);

    // Generation NACH dem Create (Create bumpt bereits Creator+Assignee) als Baseline festhalten.
    final long creatorGenBefore = readGeneration(USER_A_REF);
    final long assigneeGenBefore = readGeneration(USER_A2_REF);

    // PATCH statusCode=DONE (changed) -> Bump JEDES aktiven Teilnehmers in derselben Tx.
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("statusCode", TaskStatusCodes.DONE))))
        .andExpect(status().isNoContent());

    assertThat(readGeneration(USER_A_REF))
        .as("Creator-Generation exakt +1 durch den statusCode-PATCH")
        .isEqualTo(creatorGenBefore + 1);
    assertThat(readGeneration(USER_A2_REF))
        .as("Assignee-Generation exakt +1 durch den statusCode-PATCH (jeder aktive Teilnehmer)")
        .isEqualTo(assigneeGenBefore + 1);
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

  /** GET /tasks/{id} und liefert das Detail-JSON. */
  private com.fasterxml.jackson.databind.JsonNode getDetail(
      final MockMvc mvc, final ObjectMapper om, final String token, final UUID taskUuid)
      throws Exception {
    final String body =
        mvc.perform(get("/api/v1/tasks/" + taskUuid).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readTree(body);
  }

  private static List<String> readStringArray(final com.fasterxml.jackson.databind.JsonNode node) {
    final List<String> out = new java.util.ArrayList<>();
    if (node != null && node.isArray()) {
      node.forEach(n -> out.add(n.asText()));
    }
    return out;
  }

  /**
   * Liest die persistierte Inbox-Generation eines Benutzers in Mandant A (scoped ueber die
   * UNIQUE(TENANT_ID, USER_REF)); 0, wenn noch keine Zeile existiert.
   */
  private long readGeneration(final UUID userRef) {
    final List<Long> rows =
        jdbcTemplate.queryForList(
            "SELECT GENERATION FROM [dbo].[TASK_INBOX_GENERATION] WHERE TENANT_ID = ? AND USER_REF = ?",
            Long.class,
            TENANT_A_ID,
            userRef);
    return rows.isEmpty() ? 0L : rows.get(0);
  }

  /**
   * Baut einen signierten JWT mit einer oder mehreren Authorities.
   *
   * @param tenantUuid Tenant-UUID (claim {@code lastActiveTenantId})
   * @param userUuid User-UUID (claim {@code profile.userId})
   * @param loginName Login-Name
   * @param authorities Liste der vollen Authorities
   * @return signierter JWT-String
   */
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

  /**
   * Seedet einen globalen {@code TASK_TAG} (TENANT_ID=0) und liefert dessen EXTERNALID.
   * SEVERITY_ID=601 = PRIMARY (aus Flyway V1-Seed), konsistent zu {@code TaskTagPatchIT}.
   */
  private static UUID seedGlobalTag(
      final JdbcTemplate jt, final long id, final String bezeichnung) {
    final UUID externalId = UUID.randomUUID();
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_TAG] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_TAG] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, BEZEICHNUNG, ENTRY_TYPE,"
            + " GUELTIG_VON, SEVERITY_ID) "
            + "VALUES (?, 'ACTIVE', ?, 1, 0, ?, 'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), 601)",
        id,
        id,
        externalId,
        bezeichnung);
    return jt.queryForObject(
        "SELECT EXTERNALID FROM [dbo].[TASK_TAG] WHERE ID = ?", UUID.class, id);
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
    // Slice 4 (Standard-Selfservice): die member-scoped Services (Detail/Command) haengen jetzt am
    // objekt-level TaskMembershipGuard (@Component im nicht-gescannten Paket ...security).
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
  })
  static class ItConfig {

    // Test-only source adapter. No Customer adapter is registered in production.
    @Bean
    TaskSourceAdapter fixtureSourceAdapter() {
      return new TaskSourceAdapter() {
        @Override
        public String module() {
          return "fixture";
        }

        @Override
        public String entityType() {
          return "item";
        }

        @Override
        public void requireReadable(final CurrentActor actor, final UUID entityRef) {
          final CyclicBarrier barrier = FIXTURE_SOURCE_BARRIER.get();
          if (barrier != null && FIXTURE_BARRIER_ENTRIES.getAndIncrement() < 2) {
            try {
              if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Source-Read lief ausserhalb der Claim-TX.");
              }
              FIXTURE_SOURCE_SPIDS.add(
                  sourceProofJdbcTemplate.queryForObject("SELECT @@SPID", Integer.class));
              barrier.await(10, TimeUnit.SECONDS);
            } catch (final Exception barrierFailure) {
              throw new IllegalStateException(
                  "Fixture source requests did not overlap.", barrierFailure);
            }
          }
          if (!TENANT_A_UUID.equals(actor.tenant().uuid())
              || !(USER_A_REF.equals(actor.user().id()) || USER_A2_REF.equals(actor.user().id()))
              || !(FIXTURE_SOURCE_REF.equals(entityRef)
                  ? FIXTURE_SOURCE_READABLE.get()
                  : FIXTURE_ALT_SOURCE_REF.equals(entityRef))) {
            throw new EntityNotFoundException("fixture hidden");
          }
          if (FIXTURE_SOURCE_UNAVAILABLE.get() && FIXTURE_SOURCE_REF.equals(entityRef)) {
            throw new IllegalStateException("fixture source unavailable");
          }
        }

        @Override
        public String href(final UUID tenantUuid, final UUID entityRef) {
          throw new IllegalStateException("fixture navigation unavailable");
        }
      };
    }

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "task-facade-it";
    }
  }
}
