package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.Task;
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

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
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
