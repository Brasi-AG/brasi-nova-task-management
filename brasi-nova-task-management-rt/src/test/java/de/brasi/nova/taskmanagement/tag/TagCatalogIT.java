package de.brasi.nova.taskmanagement.tag;

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
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityColors;
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

    final JsonNode tagArray = tagItems(listResponse, om);
    assertThat(tagArray.isArray()).as("GET /tags liefert _embedded.tags als Array").isTrue();
    boolean tenantBSeesTag = false;
    for (JsonNode node : tagArray) {
      if (tagId.toString().equals(node.path("id").asText())) {
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

    final com.fasterxml.jackson.databind.JsonNode tagArray = tagItems(listResponse, om);
    assertThat(tagArray.isArray()).as("GET /tags liefert _embedded.tags als Array").isTrue();

    boolean futureDatedTagVisible = false;
    for (com.fasterxml.jackson.databind.JsonNode node : tagArray) {
      if (futureDatedTagUuid.toString().equals(node.path("id").asText())) {
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

  // ---- IT Intake #135 Phase 1 Slice 3: color/severity optional (create/read/PUT/snapshot) -----

  @Test
  @DisplayName(
      "ac1_color_only_create_201_db_color_uppercase_severity_null_tenant0:"
          + " POST /tags {name, color:'#f87171'} ohne severityCode (TAG_ADMIN) -> 201;"
          + " DB TASK_TAG COLOR='#F87171' (uppercase), SEVERITY_ID NULL, TENANT_ID=0")
  void ac1_color_only_create_201_db_color_uppercase_severity_null_tenant0() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // AC1: color-only Create (lowercase Hex -> wird normalisiert auf Grossbuchstaben)
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC1-ColorOnly-Tag\",\"color\":\"#f87171\"}");

    // DB: COLOR='#F87171' (uppercase normalisiert), SEVERITY_ID NULL, TENANT_ID=0, aktiv
    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG]"
                + " WHERE EXTERNALID = ? AND TENANT_ID = 0 AND ENTITY_STATUS = 'ACTIVE'"
                + " AND COLOR = '#F87171' AND SEVERITY_ID IS NULL",
            Integer.class,
            tagId);
    assertThat(rowCount)
        .as("color-only-Tag: COLOR='#F87171' uppercase, SEVERITY_ID NULL, TENANT_ID=0")
        .isEqualTo(1);

    // Zusatzbeweis Uppercase-Normalisierung case-sensitiv (MSSQL-Collation ist case-insensitiv, der
    // DB-Vergleich oben beweist die Grossschreibung daher nicht zuverlaessig): GET-Response mit
    // case-sensitivem Java-Vergleich belegt '#F87171' (nicht '#f87171').
    final ObjectMapper om = new ObjectMapper();
    final String listResponse =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode arr = tagItems(listResponse, om);
    JsonNode node = null;
    for (JsonNode n : arr) {
      if (tagId.toString().equals(n.path("id").asText())) {
        node = n;
        break;
      }
    }
    assertThat(node).as("AC1-Tag muss in GET /tags erscheinen").isNotNull();
    assertThat(node.get("color").asText())
        .as("AC1: color uppercase-normalisiert (case-sensitiver Java-Vergleich via GET-Response)")
        .isEqualTo("#F87171");
  }

  @Test
  @DisplayName(
      "ac2_severity_derive_create_201_db_color_abgeleitet_severity_gesetzt:"
          + " POST /tags {name, severityCode:'DANGER'} ohne color -> 201;"
          + " DB COLOR='#F87171' (aus severity abgeleitet), SEVERITY_ID (DANGER) gesetzt")
  void ac2_severity_derive_create_201_db_color_abgeleitet_severity_gesetzt() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // AC2: severity-only Create -> Farbe wird aus der severity abgeleitet
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC2-SeverityDerive-Tag\",\"severityCode\":\"DANGER\"}");

    // DB: COLOR aus DANGER abgeleitet (#F87171); SEVERITY_ID gesetzt und zeigt via JOIN auf CODE=DANGER
    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] t"
                + " JOIN [dbo].[DIC_TAG_SEVERITY] s ON s.ID = t.SEVERITY_ID"
                + " WHERE t.EXTERNALID = ? AND t.TENANT_ID = 0 AND t.ENTITY_STATUS = 'ACTIVE'"
                + " AND t.COLOR = ? AND t.SEVERITY_ID IS NOT NULL AND s.CODE = 'DANGER'",
            Integer.class,
            tagId,
            TagSeverityColors.DANGER);
    assertThat(rowCount)
        .as("severity-derive: COLOR aus DANGER abgeleitet, SEVERITY_ID gesetzt (CODE=DANGER)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac2b_neutral_create_201_db_color_null_severity_null:"
          + " POST /tags {name} ohne color/severity -> 201; DB COLOR NULL, SEVERITY_ID NULL")
  void ac2b_neutral_create_201_db_color_null_severity_null() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // AC2b: neutraler Tag (weder color noch severity)
    final UUID tagId = createTag(mvc, tokenA, "{\"name\":\"AC2b-Neutral-Tag\"}");

    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG]"
                + " WHERE EXTERNALID = ? AND TENANT_ID = 0 AND ENTITY_STATUS = 'ACTIVE'"
                + " AND COLOR IS NULL AND SEVERITY_ID IS NULL",
            Integer.class,
            tagId);
    assertThat(rowCount)
        .as("neutraler Tag: COLOR NULL, SEVERITY_ID NULL")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac3_read_null_safe_color_only_tag_in_get_tags_color_gesetzt_severitycode_null_kein_500:"
          + " GET /tags (TASK_READ) -> 200; color-only-Tag traegt color, severityCode null; kein 500")
  void ac3_read_null_safe_color_only_tag_in_get_tags_color_gesetzt_severitycode_null_kein_500()
      throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = adminTokenA();

    // color-only Tag anlegen
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC3-Read-ColorOnly\",\"color\":\"#4ade80\"}");

    // GET mit reinem TASK_READ -> 200 (kein 500 trotz null-severity)
    final String tokenRead =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    final String listResponse =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + tokenRead))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode tagArray = tagItems(listResponse, om);
    assertThat(tagArray.isArray()).as("GET /tags liefert _embedded.tags als Array").isTrue();
    JsonNode found = null;
    for (JsonNode node : tagArray) {
      if (tagId.toString().equals(node.path("id").asText())) {
        found = node;
        break;
      }
    }
    assertThat(found).as("color-only-Tag muss in GET /tags erscheinen").isNotNull();
    assertThat(found.get("color").asText())
        .as("color-only-Tag: color-Feld gesetzt (uppercase normalisiert)")
        .isEqualTo(TagSeverityColors.SUCCESS);
    // severityCode null/weggelassen (null-safe Mapping; kein Reverse-Resolver-Aufruf)
    assertThat(found.hasNonNull("severityCode"))
        .as("color-only-Tag: severityCode ist null/weggelassen")
        .isFalse();
  }

  @Test
  @DisplayName(
      "ac8_read_severity_tag_severitycode_nonnull_in_response:"
          + " severity-behafteter Tag traegt in GET /tags einen NICHT-null severityCode (DANGER)"
          + " + abgeleitete color (AC8 backward-compat, positiver Read-Fall)")
  void ac8_read_severity_tag_severitycode_nonnull_in_response() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = adminTokenA();

    // severity-behafteter Tag (DANGER) -> Response muss severityCode='DANGER' (nicht-null) tragen
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC8-SeverityRead\",\"severityCode\":\"DANGER\"}");

    final String tokenRead =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    final String listResponse =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + tokenRead))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode arr = tagItems(listResponse, om);
    JsonNode found = null;
    for (JsonNode n : arr) {
      if (tagId.toString().equals(n.path("id").asText())) {
        found = n;
        break;
      }
    }
    assertThat(found).as("severity-Tag muss in GET /tags erscheinen").isNotNull();
    assertThat(found.hasNonNull("severityCode"))
        .as("AC8: severityCode ist NICHT null fuer einen severity-behafteten Tag")
        .isTrue();
    assertThat(found.get("severityCode").asText())
        .as("AC8: severityCode-Response = 'DANGER' (Resolver-Rueckweg, case-sensitiv)")
        .isEqualTo("DANGER");
    assertThat(found.get("color").asText())
        .as("severity-Tag: color aus DANGER abgeleitet im Response")
        .isEqualTo(TagSeverityColors.DANGER);
  }

  @Test
  @DisplayName(
      "ac5_put_color_present_null_neutralisiert_color_und_severity:"
          + " PUT /tags/{id} {\"color\":null} auf color+severity-Tag -> 204;"
          + " DB COLOR NULL UND SEVERITY_ID NULL (voll neutralisiert)")
  void ac5_put_color_present_null_neutralisiert_color_und_severity() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // Tag mit color+severity anlegen (DANGER -> COLOR abgeleitet, SEVERITY_ID gesetzt)
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC5i-Neutralize\",\"severityCode\":\"DANGER\"}");

    // PUT {"color":null} -> 204; neutralisiert COLOR und SEVERITY_ID
    mvc.perform(
            put("/api/v1/tags/" + tagId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"color\":null}"))
        .andExpect(status().isNoContent());

    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG]"
                + " WHERE EXTERNALID = ? AND COLOR IS NULL AND SEVERITY_ID IS NULL",
            Integer.class,
            tagId);
    assertThat(rowCount)
        .as("PUT color=null: COLOR UND SEVERITY_ID auf NULL (voll neutralisiert)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac5_put_name_only_laesst_color_und_severity_unveraendert:"
          + " PUT /tags/{id} {\"name\":\"X\"} auf Tag mit color -> 204;"
          + " DB COLOR/SEVERITY_ID unveraendert, BEZEICHNUNG='X'")
  void ac5_put_name_only_laesst_color_und_severity_unveraendert() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // Tag mit color+severity anlegen (DANGER)
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC5ii-NamePatch\",\"severityCode\":\"DANGER\"}");

    // reiner Namens-Patch -> 204
    mvc.perform(
            put("/api/v1/tags/" + tagId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"AC5ii-Renamed\"}"))
        .andExpect(status().isNoContent());

    // DB: COLOR unveraendert (#F87171), SEVERITY_ID unveraendert (CODE=DANGER), BEZEICHNUNG neu
    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] t"
                + " JOIN [dbo].[DIC_TAG_SEVERITY] s ON s.ID = t.SEVERITY_ID"
                + " WHERE t.EXTERNALID = ? AND t.BEZEICHNUNG = 'AC5ii-Renamed'"
                + " AND t.COLOR = ? AND t.SEVERITY_ID IS NOT NULL AND s.CODE = 'DANGER'",
            Integer.class,
            tagId,
            TagSeverityColors.DANGER);
    assertThat(rowCount)
        .as("Namens-Patch: BEZEICHNUNG neu, COLOR/SEVERITY_ID unveraendert (DANGER)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac5_put_severitycode_auf_color_null_tag_setzt_severity_und_leitet_color_ab:"
          + " PUT /tags/{id} {\"severityCode\":\"WARN\"} auf Tag mit COLOR NULL -> 204;"
          + " DB SEVERITY_ID=WARN, COLOR abgeleitet '#FBBF24'")
  void ac5_put_severitycode_auf_color_null_tag_setzt_severity_und_leitet_color_ab()
      throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // neutraler Tag (COLOR NULL, SEVERITY_ID NULL)
    final UUID tagId = createTag(mvc, tokenA, "{\"name\":\"AC5iii-SeverityOnNullColor\"}");

    // severityCode-Patch -> 204; severity gesetzt, color aus WARN abgeleitet (Write-Invariante)
    mvc.perform(
            put("/api/v1/tags/" + tagId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"severityCode\":\"WARN\"}"))
        .andExpect(status().isNoContent());

    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] t"
                + " JOIN [dbo].[DIC_TAG_SEVERITY] s ON s.ID = t.SEVERITY_ID"
                + " WHERE t.EXTERNALID = ? AND t.SEVERITY_ID IS NOT NULL AND s.CODE = 'WARN'"
                + " AND t.COLOR = ?",
            Integer.class,
            tagId,
            TagSeverityColors.WARN);
    assertThat(rowCount)
        .as("severity-Patch auf COLOR-NULL-Tag: SEVERITY_ID=WARN, COLOR aus WARN abgeleitet '#FBBF24'")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac5_put_color_null_und_severitycode_zugleich_400_tag_unveraendert:"
          + " PUT /tags/{id} {\"color\":null,\"severityCode\":\"DANGER\"} -> 400; DB Tag unveraendert")
  void ac5_put_color_null_und_severitycode_zugleich_400_tag_unveraendert() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // Ausgangs-Tag mit bekannter Farbe (SUCCESS -> COLOR='#4ADE80')
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC5iv-Conflict\",\"severityCode\":\"SUCCESS\"}");

    // Konflikt color=null + severityCode zugleich -> 400
    mvc.perform(
            put("/api/v1/tags/" + tagId)
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"color\":null,\"severityCode\":\"DANGER\"}"))
        .andExpect(status().isBadRequest());

    // DB: Tag unveraendert (COLOR weiterhin #4ADE80, SEVERITY_ID zeigt auf SUCCESS)
    final Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] t"
                + " JOIN [dbo].[DIC_TAG_SEVERITY] s ON s.ID = t.SEVERITY_ID"
                + " WHERE t.EXTERNALID = ? AND t.COLOR = ? AND s.CODE = 'SUCCESS'",
            Integer.class,
            tagId,
            TagSeverityColors.SUCCESS);
    assertThat(rowCount)
        .as("Konflikt -> 400: Tag unveraendert (COLOR='#4ADE80', SEVERITY_ID=SUCCESS)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac5_put_ohne_tag_admin_nur_task_read_403:"
          + " PUT /tags/{id} mit nur TASK_READ (kein TAG_ADMIN) -> 403")
  void ac5_put_ohne_tag_admin_nur_task_read_403() throws Exception {
    final MockMvc mvc = buildMvc();
    final String tokenA = adminTokenA();

    // Ziel-Tag anlegen (mit Admin)
    final UUID tagId = createTag(mvc, tokenA, "{\"name\":\"AC5v-AuthzTarget\"}");

    // PUT ohne TAG_ADMIN -> 403
    final String tokenReadOnly =
        buildJwtWithAuthority(
            TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN, "task-management:TASK_READ");
    mvc.perform(
            put("/api/v1/tags/" + tagId)
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"AC5v-Denied\"}"))
        .andExpect(status().isForbidden());

    // verweigerter PUT (403) darf den Tag NICHT veraendert haben (BEZEICHNUNG bleibt original)
    final Integer unchanged =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG]"
                + " WHERE EXTERNALID = ? AND BEZEICHNUNG = 'AC5v-AuthzTarget'",
            Integer.class,
            tagId);
    assertThat(unchanged)
        .as("403: verweigerter PUT laesst den Tag unveraendert (BEZEICHNUNG original)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "ac4_snapshot_null_safe_color_only_tag_im_katalog_kein_500:"
          + " color-only-Tag anlegen -> GET /api/v1/tasks/snapshot (gueltiges Token) -> 200;"
          + " snapshot.tags enthaelt den color-only-Tag mit color gesetzt und severityCode null")
  void ac4_snapshot_null_safe_color_only_tag_im_katalog_kein_500() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = adminTokenA();

    // color-only Tag anlegen (SECONDARY-Farbe, aber OHNE severity -> severityCode bleibt null)
    final UUID tagId =
        createTag(mvc, tokenA, "{\"name\":\"AC4-Snapshot-ColorOnly\",\"color\":\"#cbd5e1\"}");

    // Inbox-Snapshot abrufen (authenticated-only, TASK_READ reicht; kein 500 trotz null-severity)
    final String snapshotResponse =
        mvc.perform(
                get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode snapshot = om.readValue(snapshotResponse, JsonNode.class);
    final JsonNode tags = snapshot.get("tags");
    assertThat(tags).as("snapshot.tags muss vorhanden sein").isNotNull();
    assertThat(tags.isArray()).isTrue();

    JsonNode found = null;
    for (JsonNode node : tags) {
      if (tagId.toString().equals(node.get("id").asText())) {
        found = node;
        break;
      }
    }
    assertThat(found)
        .as("color-only-Tag muss im snapshot.tags-Katalog erscheinen (kein 500)")
        .isNotNull();
    assertThat(found.get("color").asText())
        .as("snapshot: color-only-Tag traegt color gesetzt")
        .isEqualTo(TagSeverityColors.SECONDARY);
    assertThat(found.hasNonNull("severityCode"))
        .as("snapshot: color-only-Tag hat severityCode null/weggelassen")
        .isFalse();
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  /** Admin-Token (Tenant A) mit TASK_WRITE/TASK_READ/TAG_ADMIN fuer Create/PUT/Task-Operationen. */
  private static String adminTokenA() {
    return buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE",
        "task-management:TASK_READ",
        "task-management:TAG_ADMIN");
  }

  /**
   * Legt per POST /api/v1/tags (roher JSON-Body) einen Tag an, erwartet 201 und liefert die UUID
   * aus {@code $.id} zurueck. Roher JSON-String erlaubt explizites {@code null} und absente Felder.
   *
   * @param mvc       MockMvc-Instanz
   * @param token     Bearer-Token (muss TAG_ADMIN tragen)
   * @param jsonBody  roher JSON-Request-Body
   * @return externe UUID des angelegten Tags
   */
  private static UUID createTag(final MockMvc mvc, final String token, final String jsonBody)
      throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final String response =
        mvc.perform(
                post("/api/v1/tags")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(response, JsonNode.class).get("id").traverse(om).readValueAs(UUID.class);
  }

  /**
   * Extrahiert die Tag-Items aus einem GET /api/v1/tags-Response.
   *
   * <p>PLAN #141 Slice 2: GET /tags liefert ein HAL-CollectionModel; die Items liegen unter {@code
   * _embedded.tags} (eine leere Collection traegt KEIN {@code _embedded} -&gt; {@code path(...)}
   * liefert einen MissingNode, ueber den sich leer iterieren laesst).
   *
   * @param listResponse roher JSON-Body von GET /api/v1/tags
   * @param om ObjectMapper
   * @return die Tag-Items unter {@code _embedded.tags} (leer, wenn keine Tags)
   */
  private static JsonNode tagItems(final String listResponse, final ObjectMapper om)
      throws Exception {
    return extractTagItems(listResponse, om);
  }

  /**
   * Extrahiert die Tag-Items aus GET /api/v1/tags, robust gegen die drei beobachteten
   * {@code CollectionModel}-Wire-Formen (PLAN #141 Slice 2):
   *
   * <ul>
   *   <li><b>HAL</b> ({@code _embedded.tags}) - wenn der KSF-HAL-Konverter aktiv ist (Produktion,
   *       {@code NovaHalAutoConfiguration}; so bewiesen in {@code TagAffordanceIT}).
   *   <li><b>plain {@code CollectionModel}</b> ({@code {"links":[],"content":[...]}}) - wenn der
   *       IT-Context die HAL-Autoconfig NICHT importiert (dieser {@code TagCatalogIT}/
   *       {@code TagLifecycleIT}-Context mit handverlesener Autoconfig-Liste).
   *   <li><b>flaches Array</b> - defensiver Fallback.
   * </ul>
   *
   * @param listResponse roher JSON-Body von GET /api/v1/tags
   * @param om ObjectMapper
   * @return die Tag-Items (leer, wenn keine Tags)
   */
  static JsonNode extractTagItems(final String listResponse, final ObjectMapper om)
      throws Exception {
    final JsonNode root = om.readValue(listResponse, JsonNode.class);
    if (root.isArray()) {
      return root;
    }
    final JsonNode embedded = root.path("_embedded").path("tags");
    if (embedded.isArray()) {
      return embedded;
    }
    // plain CollectionModel (ohne HAL): {"links":[],"content":[...]}
    if (root.path("content").isArray()) {
      return root.path("content");
    }
    // Legitimer Leer-Fall: leeres Objekt bzw. nur Links; alles andere ist eine unbekannte Wire-Form.
    if (root.isObject() && (root.isEmpty() || root.has("links") || root.has("_links"))) {
      return om.createArrayNode();
    }
    throw new AssertionError("Unbekannte Tag-Collection-Wire-Form: " + root);
  }

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
