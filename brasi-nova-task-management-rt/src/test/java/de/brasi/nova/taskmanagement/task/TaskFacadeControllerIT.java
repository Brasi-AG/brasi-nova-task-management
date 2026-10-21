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
import de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig;
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
 * Integration-Test fuer den {@link TaskFacadeController} gegen echtes MSSQL (Wegwerf-DB).
 *
 * <p>Beweist:
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
    // PROD_BENUTZER: User A (Mandant A) + User B (Mandant B)
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_A2_DB_ID, USER_A2_REF, USER_A2_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
  }

  // ---- Positiv-IT: POST -> PATCH -> GET Detail -----------------------------------------------

  @Test
  @DisplayName(
      "post_patch_detail_history_markRead: POST legt Task an, PATCH aendert Titel,"
          + " GET liefert Detail mit History + markRead")
  void post_patch_detail_history_markRead() throws Exception {
    final MockMvc mvc = buildMvc();

    // 1. POST /api/v1/tasks -> 201 + {"id": "<uuid>"}
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
    final ObjectMapper om = new ObjectMapper();
    om.registerModule(new JavaTimeModule());

    final String createBody = om.writeValueAsString(
        Map.of("title", "IT-Testtitel", "description", "IT-Beschreibung"));

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

    final UUID taskId = om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
        .get("id").traverse(om).readValueAs(UUID.class);
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
        mvc.perform(
                get("/api/v1/tasks/" + taskId)
                    .header("Authorization", "Bearer " + tokenA))
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
    final com.fasterxml.jackson.databind.JsonNode detailNode =
        om.readTree(detailResponse);
    assertThat(detailNode.get("history").size())
        .as("History muss mindestens 1 Eintrag enthalten")
        .isGreaterThanOrEqualTo(1);

    // READ_AT: nach GET muss TASK_USER_STATE.READ_AT gesetzt sein
    final Integer readAtSet = jdbcTemplate.queryForObject(
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
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskId = createTask(mvc, om, tokenA, "Assignee-Detail-Test");

    // PUT assignees=[USER_A (Creator), USER_A2] - beide Tenant A (findActiveByUuids mandanten-scoped).
    mvc.perform(put("/api/v1/tasks/" + taskId + "/assignees")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("assignees", List.of(USER_A_REF, USER_A2_REF)))))
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
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final String longDesc = "B".repeat(300); // > 255
    final String postResp =
        mvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "LangDesc", "description", longDesc))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final UUID taskId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id").traverse(om).readValueAs(UUID.class);

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
    final String tokenReadOnly = buildJwtWithAuthority(
        TENANT_A_UUID, USER_READ_ONLY_REF, USER_READ_ONLY_LOGIN,
        "task-management:TASK_READ" /* kein TASK_WRITE */);
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenReadOnly)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Test"))))
        .andExpect(status().isForbidden());

    // 3. Fremder Tenant: User B versucht Task von Tenant A aufzurufen -> 404
    // Erst: User A legt Task an
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
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

    final UUID taskId = om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
        .get("id").traverse(om).readValueAs(UUID.class);

    // User B (Tenant B) versucht GET auf Task von Tenant A -> 404 (fremder Mandant)
    final String tokenB = buildJwtWithAuthority(TENANT_B_UUID, USER_B_REF, USER_B_LOGIN,
        "task-management:TASK_READ");
    mvc.perform(
            get("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());
  }

  // ---- MAJOR 1: PATCH auf fremde/nicht-existente UUID -> 404 ------------------------------------

  @Test
  @DisplayName("patch_nicht_existent_und_fremd_liefert_404: beide Faelle liefern 404, ununterscheidbar")
  void patch_nicht_existent_und_fremd_liefert_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // (a) Zufaellige UUID (existiert nicht) -> 404
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE");
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
    final String tokenB = buildJwtWithAuthority(TENANT_B_UUID, USER_B_REF, USER_B_LOGIN,
        "task-management:TASK_WRITE");
    mvc.perform(
            patch("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Fremder Zugriff"))))
        .andExpect(status().isNotFound());
  }

  // ---- MAJOR (Re-Gate): GET Detail nicht-existent + fremd -> 404, identische neutrale Meldung ----

  @Test
  @DisplayName("detail_nicht_existent_und_fremd_liefert_404_identisch: GET maskiert Existenz")
  void detail_nicht_existent_und_fremd_liefert_404_identisch() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // (a) Zufaellige UUID (existiert nicht) -> 404
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");
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

    final String tokenB = buildJwtWithAuthority(TENANT_B_UUID, USER_B_REF, USER_B_LOGIN,
        "task-management:TASK_READ");
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
        .as("Fremd-Tenant-404 muss dieselbe neutrale Meldung + errorKey liefern wie Nicht-gefunden-404")
        .contains("\"message\":\"Aufgabe nicht gefunden.\"")
        .contains("\"errorKey\":\"NOVA_NOT_FOUND\"");
  }

  // ---- MAJOR 3: ungueltige Client-Codes -> 400 -------------------------------------------------

  @Test
  @DisplayName("post_unbekannter_priorityCode_liefert_400: unbekannter priorityCode -> 400")
  void post_unbekannter_priority_code_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE");
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(
                    Map.of("title", "400-Test", "priorityCode", "GIBTS_NICHT"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("patch_unbekannter_statusCode_liefert_400: unbekannter statusCode -> 400")
  void patch_unbekannter_status_code_liefert_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // Erst Task anlegen
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE");
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
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

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
        .as("Genau ein Creator-Participant (CREATOR_FLAG=1/ASSIGNEE_FLAG=1) fuer den erzeugten Task")
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
          activeTasks.stream()
              .anyMatch(t -> taskExternalId.equals(t.getUuid()));
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
    final String tokenA = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Aktiven Task anlegen ...
    final String postResponse =
        mvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "Deaktivierungs-Test"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    final UUID taskId =
        om.readValue(postResponse, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id").traverse(om).readValueAs(UUID.class);

    // ... dann logisch deaktivieren (ENTITY_STATUS != 'ACTIVE')
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?", taskId);

    // GET Detail -> 404 (neutrale Maskierung wie fremder Tenant)
    mvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound());

    // PATCH -> 404 (keine Mutation deaktivierter Aufgaben)
    mvc.perform(patch("/api/v1/tasks/" + taskId)
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
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

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
        TENANT_A_ID, internalId, USER_A2_REF, TENANT_A_ID);

    // Actor-Marker fuer USER_A setzen (Upsert ueber den Marker-Endpunkt).
    mvc.perform(put("/api/v1/tasks/" + taskUuid + "/pin")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"value\":true}"))
        .andExpect(status().is2xxSuccessful());
    mvc.perform(put("/api/v1/tasks/" + taskUuid + "/mute")
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

    // Zweiter Actor USER_A2 (Tenant A, TASK_READ): 200, createdById gleich, aber eigene Marker false.
    final String tokenA2 = buildJwtWithAuthority(
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

    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final String body = om.writeValueAsString(Map.of(
        "title", title,
        "assignees", List.of(USER_A2_REF),
        "tagIds", List.of(tag),
        "isPinned", Boolean.TRUE,
        "isFlagged", Boolean.TRUE));

    // Generation VOR dem POST festhalten (Delta-Beweis fuer den Bump DURCH diesen Create).
    final long creatorGenBefore = readGeneration(USER_A_REF);
    final long assigneeGenBefore = readGeneration(USER_A2_REF);

    final String postResp =
        mvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    final UUID taskExternalId =
        om.readValue(postResp, com.fasterxml.jackson.databind.JsonNode.class)
            .get("id").traverse(om).readValueAs(UUID.class);

    final Long internalTaskId = jdbcTemplate.queryForObject(
        "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskExternalId);

    // (a) Assignee-Participant: ASSIGNEE_FLAG=1, CREATOR_FLAG=0, PARTICIPANT_REF=USER_A2
    final Integer assigneeParticipant = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT]"
            + " WHERE TASK_ID = ? AND PARTICIPANT_REF = ? AND ASSIGNEE_FLAG = 1 AND CREATOR_FLAG = 0",
        Integer.class, internalTaskId, USER_A2_REF);
    assertThat(assigneeParticipant)
        .as("Assignee-Participant fuer USER_A2 (ASSIGNEE, nicht Creator)").isEqualTo(1);

    // (b) Tag-Assignment aktiv
    final Integer tagActive = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
            + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
            + " WHERE a.TASK_ID = ? AND tag.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
        Integer.class, internalTaskId, tag);
    assertThat(tagActive).as("Tag-Assignment fuer den Initial-Tag muss AKTIV sein").isEqualTo(1);

    // (c) Creator-UserState IS_PINNED=1 UND IS_FLAGGED=1 (D5)
    final Integer creatorMarkers = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE]"
            + " WHERE TASK_ID = ? AND USER_REF = ? AND IS_PINNED = 1 AND IS_FLAGGED = 1",
        Integer.class, internalTaskId, USER_A_REF);
    assertThat(creatorMarkers).as("Creator-UserState IS_PINNED=1 und IS_FLAGGED=1").isEqualTo(1);

    // (c2) Assignee-UserState existiert (ensure -> inbox-faehig)
    final Integer assigneeState = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID = ? AND USER_REF = ?",
        Integer.class, internalTaskId, USER_A2_REF);
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
    final Integer assignNotif = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION]"
            + " WHERE TASK_ID = ? AND RECIPIENT_REF = ? AND TYPE_ID = ?",
        Integer.class, internalTaskId, USER_A2_REF, assignTypeId);
    assertThat(assignNotif).as("ASSIGN-Notification fuer USER_A2").isGreaterThanOrEqualTo(1);

    // (e) genau EINE Aufgabe mit diesem Titel (kein Teil-/Doppel-Insert)
    final Integer taskCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ? AND TENANT_ID = ?",
        Integer.class, title, TENANT_A_ID);
    assertThat(taskCount).as("genau eine Aufgabe angelegt").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "post_unbekannter_assignee_400_kein_task: unbekannte Assignee-UUID -> 400 + KEINE Aufgabe")
  void post_unbekannter_assignee_400_kein_task() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String title = "BE4-Unknown-Assignee";
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE");

    final String body = om.writeValueAsString(Map.of(
        "title", title, "assignees", List.of(UUID.randomUUID())));

    mvc.perform(post("/api/v1/tasks")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());

    final Integer taskCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ? AND TENANT_ID = ?",
        Integer.class, title, TENANT_A_ID);
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
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE");

    final String body = om.writeValueAsString(Map.of(
        "title", title,
        "assignees", List.of(USER_A2_REF),
        "tagIds", List.of(UUID.randomUUID())));

    mvc.perform(post("/api/v1/tasks")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());

    final Integer taskCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK] WHERE TITLE = ? AND TENANT_ID = ?",
        Integer.class, title, TENANT_A_ID);
    assertThat(taskCount).as("KEINE Aufgabe nach 400 (All-or-Nothing, auch mit gueltigem Assignee)")
        .isEqualTo(0);

    final Integer assigneeParticipant = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] p"
            + " JOIN [dbo].[TASK] t ON p.TASK_ID = t.ID"
            + " WHERE t.TENANT_ID = ? AND p.PARTICIPANT_REF = ? AND t.TITLE = ?",
        Integer.class, TENANT_A_ID, USER_A2_REF, title);
    assertThat(assigneeParticipant).as("kein Assignee-Participant nach 400").isEqualTo(0);
  }

  @Test
  @DisplayName(
      "detail_be3_ausgetretener_actor_exit_gesetzt:"
          + " Actor mit EXIT_KIND=HANDOVER -> detail.exit.kind=handover (Live-Read)")
  void detail_be3_ausgetretener_actor_exit_gesetzt() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, tokenA, "BE3-Detail-Exit");
    final Long internalId =
        jdbcTemplate.queryForObject(
            "SELECT ID FROM [dbo].[TASK] WHERE EXTERNALID = ?", Long.class, taskUuid);

    // Ersteller-Row von USER_A als ausgetreten markieren (HANDOVER, EXIT_AT in der Vergangenheit).
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET EXIT_KIND = 'HANDOVER', EXIT_AT = '2020-01-01T00:00:00' "
            + "WHERE TASK_ID = ? AND PARTICIPANT_REF = ? AND TENANT_ID = ?",
        internalId, USER_A_REF, TENANT_A_ID);

    final com.fasterxml.jackson.databind.JsonNode detail = getDetail(mvc, om, tokenA, taskUuid);
    assertThat(detail.get("exit").isNull()).as("ausgetretener Actor -> exit gesetzt").isFalse();
    assertThat(detail.get("exit").get("kind").asText()).isEqualTo("handover");
    // Ersteller bleibt aufgeloest (createdById unabhaengig vom Austritt).
    assertThat(detail.get("createdById").asText()).isEqualTo(USER_A_REF.toString());
  }

  @Test
  @DisplayName(
      "detail_be3_nicht_teilnehmer_actor_200:"
          + " TASK_READ-Nutzer im gleichen Mandanten OHNE Teilnehmer-Rolle -> 200,"
          + " createdById gesetzt, Marker false, exit null (kein Membership-Gate, D1)")
  void detail_be3_nicht_teilnehmer_actor_200() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE", "task-management:TASK_READ");

    // Aufgabe von USER_A; USER_A2 ist WEDER Ersteller NOCH Teilnehmer dieser Aufgabe.
    final UUID taskUuid = createTask(mvc, om, tokenA, "BE3-Detail-NonMember");

    final String tokenA2 = buildJwtWithAuthority(
        TENANT_A_UUID, USER_A2_REF, USER_A2_LOGIN, "task-management:TASK_READ");
    final com.fasterxml.jackson.databind.JsonNode detail = getDetail(mvc, om, tokenA2, taskUuid);

    assertThat(detail.get("createdById").asText())
        .as("Ersteller sichtbar auch fuer Nicht-Teilnehmer")
        .isEqualTo(USER_A_REF.toString());
    assertThat(readStringArray(detail.get("participantIds"))).isEmpty();
    assertThat(detail.get("isPinned").asBoolean()).isFalse();
    assertThat(detail.get("isFlagged").asBoolean()).isFalse();
    assertThat(detail.get("isMuted").asBoolean()).isFalse();
    assertThat(detail.get("exit").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "post_mehr_als_100_ids_400_auch_bei_duplikaten (Codex-MAJOR): 101 Eintraege (auch identische"
          + " UUIDs) in assignees bzw. tagIds -> 400 auf ROHER Array-Laenge, KEINE Aufgabe")
  void post_mehr_als_100_ids_400_auch_bei_duplikaten() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String tokenA = buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN,
        "task-management:TASK_WRITE");

    // 101x dieselbe (gueltige) Assignee-UUID -> Dedup wuerde auf 1 kollabieren; die ROHE Laenge 101
    // muss dennoch zu 400 fuehren (sonst Umgehung der MAX_IDS-Grenze).
    final String titleA = "BE4-TooMany-Assignees-Dup";
    final String bodyAssignees = om.writeValueAsString(Map.of(
        "title", titleA,
        "assignees", java.util.Collections.nCopies(de.brasi.nova.taskmanagement.task.model.TaskCreateInput.MAX_IDS + 1, USER_A2_REF)));
    mvc.perform(post("/api/v1/tasks")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyAssignees))
        .andExpect(status().isBadRequest());

    final String titleB = "BE4-TooMany-Tags-Dup";
    final String bodyTags = om.writeValueAsString(Map.of(
        "title", titleB,
        "tagIds", java.util.Collections.nCopies(de.brasi.nova.taskmanagement.task.model.TaskCreateInput.MAX_IDS + 1, UUID.randomUUID())));
    mvc.perform(post("/api/v1/tasks")
            .header("Authorization", "Bearer " + tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyTags))
        .andExpect(status().isBadRequest());

    final Integer taskCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM [dbo].[TASK] WHERE (TITLE = ? OR TITLE = ?) AND TENANT_ID = ?",
        Integer.class, titleA, titleB, TENANT_A_ID);
    assertThat(taskCount).as("KEINE Aufgabe nach 400 (MAX_IDS-Grenze auf roher Laenge)").isEqualTo(0);
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
        mvc.perform(post("/api/v1/tasks")
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
    final List<Long> rows = jdbcTemplate.queryForList(
        "SELECT GENERATION FROM [dbo].[TASK_INBOX_GENERATION] WHERE TENANT_ID = ? AND USER_REF = ?",
        Long.class, TENANT_A_ID, userRef);
    return rows.isEmpty() ? 0L : rows.get(0);
  }

  /**
   * Baut einen signierten JWT mit einer oder mehreren Authorities.
   *
   * @param tenantUuid   Tenant-UUID (claim {@code lastActiveTenantId})
   * @param userUuid     User-UUID (claim {@code profile.userId})
   * @param loginName    Login-Name
   * @param authorities  Liste der vollen Authorities
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
        id, id, externalId, bezeichnung);
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
    NoOpInboxSignalConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "task-facade-it";
    }
  }
}
