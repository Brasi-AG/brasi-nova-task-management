package de.brasi.nova.taskmanagement.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Integration-Test fuer den {@link TagController} und {@link TagCatalogService} gegen echtes MSSQL.
 *
 * <p>Beweist:
 * <ul>
 *   <li>Global-Tag-Sichtbarkeit: Tag aus Tenant A ist ueber GET /tags fuer Tenant B sichtbar
 *       (R-TAG: TENANT_ID=0, ENTRY_TYPE='SYSTEM_PUBLIC').
 *   <li>PATCH-Tags: TASK_TAG_ASSIGNMENT mit TENANT_ID=A (Tenant-scoped, globaler Tag-Katalog).
 *   <li>Tenant-Isolation: Zuordnungen sind Tenant-scoped (Tenant B sieht Tenant-A-Zuordnungen nicht).
 *   <li>R-CHECK: Ablehnung von TENANT_ID!=0 per CHECK-Constraint auf TASK_TAG.
 *   <li>Negativ: anonym 401, TASK_READ ohne TAG_ADMIN bei POST 403, GET mit TASK_READ 200.
 * </ul>
 */
@SpringBootTest(
    classes = TagCatalogIT.ItConfig.class,
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
class TagCatalogIT {

  private static final String TEST_KID = "tag-catalog-it-key";

  /** Mandant A: Haupt-Tenant fuer positive Tests. */
  private static final long TENANT_A_ID = 570L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-tagcat@brasi.de";
  private static final long USER_A_DB_ID = 571L;

  /** Mandant B: fremder Tenant fuer globale Sichtbarkeits-Pruefung. */
  private static final long TENANT_B_ID = 572L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-tagcat@brasi.de";
  private static final long USER_B_DB_ID = 573L;

  /** User mit nur TASK_READ (kein TAG_ADMIN) -> 403 bei POST /tags. */
  private static final UUID USER_READ_ONLY_REF = UUID.randomUUID();
  private static final String USER_READ_ONLY_LOGIN = "read-only-tagcat@brasi.de";

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
      pemTempFile = Files.createTempFile("tag-catalog-it-jwk-", ".pem");
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
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
  }

  // ---- IT 1: globaler Tag + Tenant-Sichtbarkeit + PATCH-Zuordnung + Tenant-Isolation ------------

  @Test
  @DisplayName(
      "global_tag_fuer_alle_tenants_sichtbar_assignment_isoliert:"
          + " TAG_ADMIN (Tenant A) legt Tag an -> Tenant B sieht ihn via GET;"
          + " PATCH task mit tagIds -> DB-ASSIGNMENT mit TENANT_ID=A;"
          + " Tenant B hat keine Zuordnung")
  void global_tag_fuer_alle_tenants_sichtbar_assignment_isoliert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ",
            "task-management:TAG_ADMIN");

    // 1. Tag anlegen (Tenant A, TAG_ADMIN)
    final String tagCreateBody = om.writeValueAsString(Map.of("name", "Bug-IT", "severityCode", "DANGER"));
    final String tagCreateResponse =
        mvc.perform(
                post("/api/v1/tags")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(tagCreateBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final UUID tagId =
        om.readValue(tagCreateResponse, JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(tagId).isNotNull();

    // Beweis 1: DB-Eintrag TASK_TAG mit TENANT_ID=0 (global)
    final Integer tagRowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] WHERE EXTERNALID = ? AND TENANT_ID = 0"
                + " AND ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            tagId);
    assertThat(tagRowCount)
        .as("TASK_TAG-Zeile muss TENANT_ID=0 haben (global)")
        .isEqualTo(1);

    // R-CHECK: TENANT_ID!=0 wird durch CHECK-Constraint auf TASK_TAG abgelehnt
    // (CHECK([TENANT_ID]=0) existiert in V1-DDL -> Direktinsert mit TENANT_ID!=0 muss fehlschlagen)
    boolean constraintViolation = false;
    try {
      jdbcTemplate.update(
          "INSERT INTO [dbo].[TASK_TAG]"
              + " (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, BEZEICHNUNG,"
              + "  ENTRY_TYPE, GUELTIG_VON, SEVERITY_ID)"
              + " VALUES (9001, 'ACTIVE', ?, 1, 999, 'Constraint-Test', 'SYSTEM_PUBLIC',"
              + "  CAST(GETDATE() AS DATE), 601)",
          UUID.randomUUID());
    } catch (Exception e) {
      constraintViolation = true;
    }
    assertThat(constraintViolation)
        .as("CHECK-Constraint CHK_TASK_TAG_GLOBAL muss TENANT_ID!=0 ablehnen")
        .isTrue();

    // 2. Tenant B sieht den globalen Tag via GET /tags (R-TAG: SYSTEM_PUBLIC + TENANT_ID=0)
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_B_UUID, USER_B_REF, USER_B_LOGIN,
            "task-management:TASK_READ");
    final String listResponse =
        mvc.perform(
                get("/api/v1/tags")
                    .header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode tagArray = om.readValue(listResponse, JsonNode.class);
    assertThat(tagArray.isArray()).isTrue();
    boolean tenantBSeesTag = false;
    for (JsonNode node : tagArray) {
      if (tagId.toString().equals(node.get("id").asText())) {
        tenantBSeesTag = true;
        break;
      }
    }
    assertThat(tenantBSeesTag)
        .as("Tenant B muss den global angelegten Tag in GET /api/v1/tags sehen")
        .isTrue();

    // 3. Task in Tenant A anlegen
    final String createTaskBody = om.writeValueAsString(Map.of("title", "TagCatalog-IT-Task"));
    final String createTaskResponse =
        mvc.perform(
                post("/api/v1/tasks")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createTaskBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskUuid =
        om.readValue(createTaskResponse, JsonNode.class)
            .get("id")
            .traverse(om)
            .readValueAs(UUID.class);
    assertThat(taskUuid).isNotNull();

    // 4. PATCH /api/v1/tasks/{id} mit tagIds=[tagId] (Tenant A)
    final String patchBody = om.writeValueAsString(Map.of("tagIds", List.of(tagId)));
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))
        .andExpect(status().isNoContent());

    // Beweis: TASK_TAG_ASSIGNMENT-Zeile mit TENANT_ID=A (Tenant-scoped)
    final Integer assignmentCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE t.EXTERNALID = ? AND tag.EXTERNALID = ?"
                + " AND a.TENANT_ID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid,
            tagId,
            TENANT_A_ID);
    assertThat(assignmentCount)
        .as("TASK_TAG_ASSIGNMENT muss mit TENANT_ID=TENANT_A existieren")
        .isEqualTo(1);

    // Tenant-Isolation: Tenant B hat keine Zuordnung fuer diese Tag-UUID
    final Integer tenantBAssignmentCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE tag.EXTERNALID = ? AND a.TENANT_ID = ?",
            Integer.class,
            tagId,
            TENANT_B_ID);
    assertThat(tenantBAssignmentCount)
        .as("Tenant B darf keine TASK_TAG_ASSIGNMENT-Zeile fuer Tenant-A-Task haben")
        .isEqualTo(0);
  }

  // ---- IT F-P2c: Gueltigkeitspraedikat (future-dated Tag) -------------------------------------

  @Test
  @DisplayName(
      "future_dated_tag_nicht_in_list_f_p2c:"
          + " Tag mit GUELTIG_VON morgen taucht NICHT in GET /tags auf (F-P2c: GUELTIG_VON-Praedikat)")
  void future_dated_tag_nicht_in_list_f_p2c() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // Future-dated Tag direkt in DB seeden (GUELTIG_VON = morgen)
    final UUID futureDatedTagUuid = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_TAG] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, BEZEICHNUNG, ENTRY_TYPE,"
            + " GUELTIG_VON, SEVERITY_ID) "
            + "VALUES (9299, 'ACTIVE', ?, 1, 0, 'FutureDatedTag', 'SYSTEM_PUBLIC',"
            + " CAST(DATEADD(day, 1, GETDATE()) AS DATE), 601)",
        futureDatedTagUuid);

    final String tokenA =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
            "task-management:TASK_READ");

    final String listResponse =
        mvc.perform(
                get("/api/v1/tags")
                    .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final com.fasterxml.jackson.databind.JsonNode tagArray =
        om.readValue(listResponse, com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(tagArray.isArray()).isTrue();

    boolean futureDatedTagVisible = false;
    for (com.fasterxml.jackson.databind.JsonNode node : tagArray) {
      if (futureDatedTagUuid.toString().equals(node.get("id").asText())) {
        futureDatedTagVisible = true;
        break;
      }
    }
    assertThat(futureDatedTagVisible)
        .as("Future-dated Tag (GUELTIG_VON=morgen) darf NICHT in GET /tags erscheinen")
        .isFalse();
  }

  // ---- Negativ-ITs: 401 / 403 / 200 -----------------------------------------------------------

  @Test
  @DisplayName("anonym_401_task_read_kein_tag_admin_403_get_tags_mit_task_read_200")
  void anonym_401_task_read_kein_tag_admin_403_get_tags_mit_task_read_200() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // 1. Anonym -> 401
    mvc.perform(
            post("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", "X", "severityCode", "WARN"))))
        .andExpect(status().isUnauthorized());

    // 2. TASK_READ (kein TAG_ADMIN) -> 403 bei POST /tags
    final String tokenReadOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN,
            "task-management:TASK_READ");
    mvc.perform(
            post("/api/v1/tags")
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", "Test", "severityCode", "WARN"))))
        .andExpect(status().isForbidden());

    // 3. GET /tags mit TASK_READ -> 200 (jeder Task-Nutzer darf Tags lesen, R-TAGLIST-PERM)
    mvc.perform(
            get("/api/v1/tags").header("Authorization", "Bearer " + tokenReadOnly))
        .andExpect(status().isOk());

    // 4. Slice 4: GET /tags ist authenticated-only. Ein angemeldeter Nutzer OHNE jede Authority
    // (grant-frei) darf den Katalog lesen -> 200. POST /tags bleibt davon unberuehrt TAG_ADMIN-gated.
    final String tokenGrantFree =
        buildJwtWithAuthority(TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN);
    mvc.perform(
            get("/api/v1/tags").header("Authorization", "Bearer " + tokenGrantFree))
        .andExpect(status().isOk());
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
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.dictionary",
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
    // Slice 4 (Standard-Selfservice): die member-scoped Services haengen jetzt am objekt-level
    // TaskMembershipGuard (@Component im nicht-gescannten Paket ...security).
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "tag-catalog-it";
    }
  }
}
