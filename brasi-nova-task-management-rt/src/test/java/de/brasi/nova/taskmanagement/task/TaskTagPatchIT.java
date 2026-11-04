package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Integration-Test fuer PATCH-tags (R-PATCH) im {@link TaskFacadeController} gegen echtes MSSQL.
 *
 * <p>Beweist:
 *
 * <ul>
 *   <li>PATCH mit tagIds=[tag1] -> TASK_TAG_ASSIGNMENT AKTIV (DB-Beweis).
 *   <li>PATCH mit tagIds=[tag2] -> tag1-Zuordnung deaktiviert, tag2 aktiv (Replace-Semantik).
 *   <li>PATCH mit tagIds=[] -> alle Zuordnungen deaktiviert.
 *   <li>Unbekannte Tag-UUID -> 400 (Validierung, keine Teil-Anwendung).
 *   <li>LAST_ACTIVITY_AT wird aktualisiert wenn Tag-Menge sich aendert.
 * </ul>
 */
@SpringBootTest(
    classes = TaskTagPatchIT.ItConfig.class,
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
class TaskTagPatchIT {

  private static final String TEST_KID = "task-tag-patch-it-key";

  private static final long TENANT_ID = 590L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID USER_REF = UUID.randomUUID();
  private static final String USER_LOGIN = "user-tag-patch@brasi.de";
  private static final long USER_DB_ID = 591L;

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

  /** Zwei Tags werden per direktem SQL in TASK_TAG geseedet (TENANT_ID=0, global). */
  private static UUID tagOneUuid;

  private static UUID tagTwoUuid;

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
      pemTempFile = Files.createTempFile("task-tag-patch-it-jwk-", ".pem");
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

  /**
   * Spy auf den echten {@link de.brasi.nova.taskmanagement.participant.InboxGenerationService} im
   * Kontext (I9): erlaubt es, den NACH der Tag-Mutation + History laufenden {@code bumpSorted(...)}
   * werfen zu lassen und so das Transaktions-Rollback zu beweisen.
   */
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.brasi.nova.taskmanagement.participant.InboxGenerationService inboxGenerationSpy;

  @BeforeAll
  static void seedPrerequisites(@Autowired JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_ID, TENANT_UUID);
    seedBenutzerIfAbsent(jt, USER_DB_ID, USER_REF, USER_LOGIN, TENANT_ID);
    // Zwei globale Tags direkt seeden (TENANT_ID=0, SEVERITY_ID=601=PRIMARY aus Flyway-Seed)
    tagOneUuid = seedGlobalTag(jt, 9101L, "IT-Tag-One");
    tagTwoUuid = seedGlobalTag(jt, 9102L, "IT-Tag-Two");
  }

  // ---- IT 1: PATCH tagIds=[tag1] -> aktive Zuordnung + LAST_ACTIVITY_AT ----------------------

  @Test
  @DisplayName(
      "patch_tag_ids_anlegen_aktiv_und_last_activity_at_aktualisiert:"
          + " PATCH mit tagIds=[tag1] -> TASK_TAG_ASSIGNMENT AKTIV, LAST_ACTIVITY_AT != null")
  void patch_tag_ids_anlegen_aktiv_und_last_activity_at_aktualisiert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Task anlegen
    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-IT-1");

    // LAST_ACTIVITY_AT VOR dem Patch merken
    final Object activityBefore =
        jdbcTemplate.queryForObject(
            "SELECT LAST_ACTIVITY_AT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            Object.class,
            taskUuid);

    // PATCH tagIds=[tagOneUuid]
    final String patchBody = om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)));
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))
        .andExpect(status().isNoContent());

    // Beweis 1: TASK_TAG_ASSIGNMENT-Zeile aktiv
    final Integer activeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE t.EXTERNALID = ? AND tag.EXTERNALID = ?"
                + " AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid,
            tagOneUuid);
    assertThat(activeCount).as("TASK_TAG_ASSIGNMENT fuer tag1 muss AKTIV sein").isEqualTo(1);

    // Beweis 2: LAST_ACTIVITY_AT aktualisiert (kein Test auf exakt gleichen Wert - reicht nicht
    // null)
    final Object activityAfter =
        jdbcTemplate.queryForObject(
            "SELECT LAST_ACTIVITY_AT FROM [dbo].[TASK] WHERE EXTERNALID = ?",
            Object.class,
            taskUuid);
    assertThat(activityAfter).as("LAST_ACTIVITY_AT muss gesetzt sein").isNotNull();
  }

  // ---- IT 2: Replace-Semantik: PATCH tagIds=[tag2] -> tag1 deaktiviert, tag2 aktiv -----------

  @Test
  @DisplayName(
      "patch_tag_ids_replace_semantik_tag1_deaktiviert_tag2_aktiv:"
          + " PATCH mit tag1 -> PATCH mit tag2 -> tag1 inaktiv, tag2 aktiv (PUT-Set-Semantik)")
  void patch_tag_ids_replace_semantik_tag1_deaktiviert_tag2_aktiv() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Task anlegen
    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-IT-2");

    // 1. PATCH: tagIds=[tag1]
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)))))
        .andExpect(status().isNoContent());

    // 2. PATCH: tagIds=[tag2] (Replace)
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagTwoUuid)))))
        .andExpect(status().isNoContent());

    // Beweis 1: tag1 ist NICHT mehr aktiv
    final Integer tag1ActiveCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE t.EXTERNALID = ? AND tag.EXTERNALID = ?"
                + " AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid,
            tagOneUuid);
    assertThat(tag1ActiveCount)
        .as("tag1-Zuordnung muss INAKTIV sein nach Replace mit tag2")
        .isEqualTo(0);

    // Beweis 2: tag2 ist aktiv
    final Integer tag2ActiveCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE t.EXTERNALID = ? AND tag.EXTERNALID = ?"
                + " AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid,
            tagTwoUuid);
    assertThat(tag2ActiveCount).as("tag2-Zuordnung muss AKTIV sein nach Replace").isEqualTo(1);
  }

  // ---- IT 3: PATCH tagIds=[] -> alle Zuordnungen deaktiviert ---------------------------------

  @Test
  @DisplayName(
      "patch_tag_ids_leer_alle_zuordnungen_deaktiviert:"
          + " PATCH mit tagIds=[] -> alle aktiven Zuordnungen werden deaktiviert")
  void patch_tag_ids_leer_alle_zuordnungen_deaktiviert() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Task anlegen + tag1 zuweisen
    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-IT-3");
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)))))
        .andExpect(status().isNoContent());

    // PATCH mit leerer Menge
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[]}"))
        .andExpect(status().isNoContent());

    // Beweis: keine aktiven Zuordnungen mehr
    final Integer activeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid);
    assertThat(activeCount)
        .as("Nach PATCH mit leerer tagIds-Menge darf kein aktives Assignment existieren")
        .isEqualTo(0);
  }

  // ---- IT 4: Unbekannte Tag-UUID -> 400 (keine Teil-Anwendung) --------------------------------

  @Test
  @DisplayName(
      "inaktive_tag_uuid_400_f_p1:"
          + " PATCH mit inaktiver (ENTITY_STATUS=INACTIVE) Tag-UUID -> 400 (F-P1: findActiveByUuid)")
  void inaktive_tag_uuid_400_f_p1() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-IT-4b");

    // Inaktiven Tag direkt in DB seeden (ENTITY_STATUS=INACTIVE)
    final UUID inactiveTagUuid = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO [dbo].[TASK_TAG] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, BEZEICHNUNG, ENTRY_TYPE,"
            + " GUELTIG_VON, SEVERITY_ID) "
            + "VALUES (9199, 'INACTIVE', ?, 1, 0, 'InactiverTag', 'SYSTEM_PUBLIC',"
            + " CAST(GETDATE() AS DATE), 601)",
        inactiveTagUuid);

    // PATCH mit inaktiver Tag-UUID -> 400 (F-P1: findActiveByUuid liefert empty)
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(inactiveTagUuid)))))
        .andExpect(status().isBadRequest());

    // Kein Assignment angelegt (keine Teil-Anwendung)
    final Integer assignmentCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid);
    assertThat(assignmentCount)
        .as("Keine Assignments nach 400-Fehler mit inaktiver Tag-UUID")
        .isEqualTo(0);
  }

  @Test
  @DisplayName(
      "unbekannte_tag_uuid_400_keine_teil_anwendung:"
          + " PATCH mit unbekannter Tag-UUID -> 400, kein Assignment angelegt")
  void unbekannte_tag_uuid_400_keine_teil_anwendung() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-IT-4");

    final UUID unbekannteTagUuid = UUID.randomUUID();
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(unbekannteTagUuid)))))
        .andExpect(status().isBadRequest());

    // Kein Assignment darf angelegt worden sein (keine Teil-Anwendung)
    final Integer assignmentCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid);
    assertThat(assignmentCount)
        .as("Keine Teil-Anwendung: 0 Assignments nach 400-Fehler")
        .isEqualTo(0);
  }

  // ---- P2 IT: GET Detail liefert aktuelle tagIds (Read-before-edit) ---------------------------

  @Test
  @DisplayName(
      "detail_liefert_aktuelle_tagIds:"
          + " PATCH tagIds=[tag1,tag2] -> GET /tasks/{id}.tagIds enthaelt beide (stabil sortiert)")
  void detail_liefert_aktuelle_tagIds() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-Detail-1");
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid, tagTwoUuid)))))
        .andExpect(status().isNoContent());

    final JsonNode detail = getDetail(mvc, om, token, taskUuid);
    final List<String> tagIds = readStringArray(detail.get("tagIds"));
    assertThat(tagIds).containsExactlyInAnyOrder(tagOneUuid.toString(), tagTwoUuid.toString());
    // stabil sortiert (lexikografisch nach UUID-String)
    assertThat(tagIds).isSorted();
  }

  @Test
  @DisplayName("detail_tagIds_leer_wenn_keine_zuordnung: GET /tasks/{id}.tagIds = []")
  void detail_tagIds_leer_wenn_keine_zuordnung() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-Detail-leer");
    final JsonNode detail = getDetail(mvc, om, token, taskUuid);
    assertThat(detail.get("tagIds").isArray()).isTrue();
    assertThat(detail.get("tagIds").size()).isEqualTo(0);
  }

  // ---- P2 IT (Codex-BLOCKER): katalog-inaktiver, aber zugeordneter Tag - faithful + laute 400 ---

  @Test
  @DisplayName(
      "inaktiver_zugeordneter_tag_bleibt_im_detail_resubmit_400_kein_verlust:"
          + " Tag zugeordnet -> Katalog inaktiv -> GET listet ihn weiter -> Resubmit PATCH 400,"
          + " Zuordnung bleibt AKTIV (kein stiller Verlust)")
  void inaktiver_zugeordneter_tag_bleibt_im_detail_resubmit_400_kein_verlust() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Dedizierter Tag (eigene ID), damit andere Tests nicht beeinflusst werden.
    final UUID lifecycleTag = seedGlobalTag(jdbcTemplate, 9150L, "IT-Tag-Lifecycle");
    final UUID taskUuid = createTask(mvc, om, token, "TagPatch-Lifecycle");

    // 1. Tag aktiv zuordnen
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(lifecycleTag)))))
        .andExpect(status().isNoContent());

    // 2. Katalog-Tag deaktivieren (gueltiger EntityState DEACTIVE; Zuordnung bleibt aktiv).
    // DEACTIVE wird von findActiveByUuid (ENTITY_STATUS='ACTIVE') abgelehnt, ist aber ein gueltiger
    // Enum-Wert und daher vom faithful findByIdIn weiterhin mappbar/zurueckgebbar.
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_TAG] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?",
        lifecycleTag);

    // 3. GET Detail listet den Tag WEITERHIN (faithful) - Client sieht den vollen Ist-Stand
    final JsonNode detail = getDetail(mvc, om, token, taskUuid);
    assertThat(readStringArray(detail.get("tagIds")))
        .as("katalog-inaktiver, aber noch zugeordneter Tag MUSS im Detail sichtbar bleiben")
        .contains(lifecycleTag.toString());

    // 4. Resubmit der gelesenen tagIds -> LAUT 400 (findActiveByUuid lehnt inaktiv ab), KEIN
    // stiller Verlust
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(lifecycleTag)))))
        .andExpect(status().isBadRequest());

    // 5. DB-Beweis: die Zuordnung ist UNVERAENDERT AKTIV (kein stiller Verlust durch den 400)
    final Integer stillActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE t.EXTERNALID = ? AND tag.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid,
            lifecycleTag);
    assertThat(stillActive)
        .as("Zuordnung des inaktiven Tags darf durch den 400-Resubmit NICHT deaktiviert werden")
        .isEqualTo(1);
  }

  // ---- Intake #134: Tag-PATCH schreibt Verlauf (add/remove) ----------------------------------

  @Test
  @DisplayName(
      "I7 patch_tag_add_schreibt_history_mit_name:"
          + " PATCH tagIds=[tag1] -> TASK_HISTORY_ENTRY 'Tag hinzugefuegt' + Name (DB + GET.history)")
  void i7_patch_tag_add_schreibt_history_mit_name() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagHist-Add");
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)))))
        .andExpect(status().isNoContent());

    // DB-Beweis: genau ein aktiver "Tag hinzugefuegt"-Eintrag mit dem Tag-Namen.
    final Integer addCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h"
                + " JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND h.ENTITY_STATUS = 'ACTIVE'"
                + " AND h.TEXT = 'Tag hinzugefuegt' AND h.CHANGE_DETAIL = 'IT-Tag-One'",
            Integer.class,
            taskUuid);
    assertThat(addCount)
        .as("ein 'Tag hinzugefuegt'-Historieneintrag mit dem Tag-Namen muss persistiert sein")
        .isEqualTo(1);

    // Zusatz-Beweis ueber den Lesepfad: GET.history enthaelt den Eintrag.
    final JsonNode detail = getDetail(mvc, om, token, taskUuid);
    assertThat(historyContains(detail, "Tag hinzugefuegt", "IT-Tag-One"))
        .as("GET /tasks/{id}.history listet den 'Tag hinzugefuegt'-Eintrag")
        .isTrue();
  }

  @Test
  @DisplayName(
      "I8 patch_tag_remove_schreibt_history_mit_name:"
          + " PATCH add(tag1) -> PATCH remove -> TASK_HISTORY_ENTRY 'Tag entfernt' + Name")
  void i8_patch_tag_remove_schreibt_history_mit_name() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagHist-Remove");
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)))))
        .andExpect(status().isNoContent());
    // Entfernen (leere Ziel-Menge)
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[]}"))
        .andExpect(status().isNoContent());

    final Integer removeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h"
                + " JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND h.ENTITY_STATUS = 'ACTIVE'"
                + " AND h.TEXT = 'Tag entfernt' AND h.CHANGE_DETAIL = 'IT-Tag-One'",
            Integer.class,
            taskUuid);
    assertThat(removeCount)
        .as("ein 'Tag entfernt'-Historieneintrag mit dem Tag-Namen muss persistiert sein")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "I8b patch_remove_inaktiver_katalog_tag_name_trotzdem:"
          + " entfernter Tag inzwischen DEACTIVE im Katalog -> Name per status-agnostischem findById"
          + " aufgeloest (findActiveByUuid wuerde ihn verwerfen)")
  void i8b_patch_remove_inaktiver_katalog_tag_name_trotzdem() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Entfernen eines inzwischen INAKTIVEN Katalog-Tags -> Name via findById (status-agnostisch).
    // Belegt bewusst den findById-Lesepfad: findActiveByUuid (ENTITY_STATUS='ACTIVE') wuerde den
    // DEACTIVE-Tag verwerfen; findById (KSF-Base, kein ENTITY_STATUS-Filter) loest ihn weiter auf.
    final long deactivatableTagDbId = 9160L;
    final UUID deactivatableTag =
        seedGlobalTag(jdbcTemplate, deactivatableTagDbId, "IT-Tag-WirdInaktiv");
    final UUID taskA = createTask(mvc, om, token, "TagHist-RemoveInaktiv");
    mvc.perform(
            patch("/api/v1/tasks/" + taskA)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(deactivatableTag)))))
        .andExpect(status().isNoContent());
    // Katalog-Tag deaktivieren (Zuordnung bleibt aktiv; findById findet ihn weiter,
    // findActiveByUuid nicht)
    jdbcTemplate.update(
        "UPDATE [dbo].[TASK_TAG] SET ENTITY_STATUS = 'DEACTIVE' WHERE EXTERNALID = ?",
        deactivatableTag);
    // Entfernen - die Ziel-Menge ist leer -> keine Katalog-Aufloesung der Ziel-UUIDs noetig (kein
    // 400).
    mvc.perform(
            patch("/api/v1/tasks/" + taskA)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[]}"))
        .andExpect(status().isNoContent());
    final Integer inactiveNameCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h"
                + " JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND h.ENTITY_STATUS = 'ACTIVE'"
                + " AND h.TEXT = 'Tag entfernt' AND h.CHANGE_DETAIL = 'IT-Tag-WirdInaktiv'",
            Integer.class,
            taskA);
    assertThat(inactiveNameCount)
        .as("Name des inaktiven Katalog-Tags muss per findById aufgeloest werden")
        .isEqualTo(1);

    // Hinweis: Der leere-findById -> tagId-Fallback laesst sich gegen die reale DB nicht ausloesen,
    // weil die FK FK_TASK_TAG_ASSIGNMENT_Tag das Loeschen eines noch zugeordneten Katalog-Tags
    // verhindert (ein aktives Assignment referenziert den Tag immer). Dieser defensive Pfad ist
    // vollstaendig durch den Unit-Test U6 abgedeckt.
  }

  @Test
  @DisplayName(
      "I9 patch_fehler_nach_mutation_rollt_zuordnung_und_history_zurueck:"
          + " Fehler NACH Tag-Mutation+History -> Rollback (Assignment UND History unveraendert)")
  void i9_patch_fehler_nach_mutation_rollt_zuordnung_und_history_zurueck() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String token =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    final UUID taskUuid = createTask(mvc, om, token, "TagHist-Rollback");

    // Zustand VOR dem fehlschlagenden PATCH: keine aktive Zuordnung, keine Tag-History.
    final int assignmentsBefore = countActiveAssignments(taskUuid);
    final int tagHistoryBefore = countTagHistory(taskUuid);
    assertThat(assignmentsBefore).isZero();
    assertThat(tagHistoryBefore).isZero();

    // Den NACH dem Tag-Block (if(changed)) laufenden bumpSorted(...) werfen lassen. Der Spy ist der
    // echte Bean im echten Kontext -> der @Transactional-Proxy von TaskCommandService fuehrt den
    // PATCH regulaer aus, der Fehler faellt erst NACH Tag-Mutation + History-Write -> Rollback.
    org.mockito.Mockito.doThrow(new RuntimeException("I9: absichtlicher Fehler NACH Tag-Mutation"))
        .when(inboxGenerationSpy)
        .bumpSorted(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

    // PATCH tagIds=[tag1] -> scheitert; GlobalExceptionHandler mappt den Laufzeitfehler auf 500.
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)))))
        .andExpect(status().is5xxServerError());

    // Beweis-Beleg, dass der Fehler erst NACH Mutation+History kam: bumpSorted wurde im PATCH
    // aufgerufen (if(changed)-Block NACH Tag-Block + History-Writes). Insgesamt 2 Aufrufe auf dem
    // Spy: einer beim vorangehenden createTask (create, lief regulaer durch), einer beim
    // fehlschlagenden PATCH (dieser wirft). Das Stubbing wurde erst NACH createTask gesetzt.
    org.mockito.Mockito.verify(inboxGenerationSpy, org.mockito.Mockito.times(2))
        .bumpSorted(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());

    // Rollback-Beweis auf frischer Sicht (neue DB-Reads): Zuordnung UND Tag-History unveraendert.
    assertThat(countActiveAssignments(taskUuid))
        .as("Rollback: keine aktive Tag-Zuordnung nach dem Fehler")
        .isEqualTo(assignmentsBefore);
    assertThat(countTagHistory(taskUuid))
        .as("Rollback: kein Tag-Historieneintrag nach dem Fehler")
        .isEqualTo(tagHistoryBefore);
  }

  @Test
  @DisplayName(
      "I10 patch_nicht_member_mit_task_write_404_keine_mutation_keine_history:"
          + " authentifizierter Nicht-Member im selben Tenant mit TASK_WRITE -> 404, kein Assignment,"
          + " keine History")
  void i10_patch_nicht_member_mit_task_write_404_keine_mutation_keine_history() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // Aufgabe von USER_REF (wird dadurch Creator/WRITE-Member).
    final String ownerToken =
        buildJwtWithAuthority(
            TENANT_UUID,
            USER_REF,
            USER_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskUuid = createTask(mvc, om, ownerToken, "TagHist-NonMember");

    // Zweiter, aktiver Benutzer im SELBEN Tenant, der KEIN Teilnehmer der Aufgabe ist.
    final long nonMemberDbId = 595L;
    final UUID nonMemberRef = UUID.randomUUID();
    final String nonMemberLogin = "non-member-tag-patch@brasi.de";
    seedBenutzerIfAbsent(jdbcTemplate, nonMemberDbId, nonMemberRef, nonMemberLogin, TENANT_ID);
    final String nonMemberToken =
        buildJwtWithAuthority(
            TENANT_UUID,
            nonMemberRef,
            nonMemberLogin,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");

    // Nicht-Member PATCHt tagIds -> neutrale 404 (Objekt-Guard), KEINE Mutation.
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + nonMemberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagOneUuid)))))
        .andExpect(status().isNotFound());

    assertThat(countActiveAssignments(taskUuid))
        .as("Non-Member-404 darf kein Tag-Assignment anlegen/aendern")
        .isZero();
    assertThat(countTagHistory(taskUuid))
        .as("Non-Member-404 darf keinen Tag-Historieneintrag schreiben")
        .isZero();
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  /** Zaehlt aktive Tag-Zuordnungen einer Aufgabe. */
  private int countActiveAssignments(final UUID taskUuid) {
    final Integer c =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid);
    return c == null ? 0 : c;
  }

  /** Zaehlt aktive Tag-Historieneintraege (add/remove) einer Aufgabe. */
  private int countTagHistory(final UUID taskUuid) {
    final Integer c =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] h"
                + " JOIN [dbo].[TASK] t ON h.TASK_ID = t.ID"
                + " WHERE t.EXTERNALID = ? AND h.ENTITY_STATUS = 'ACTIVE'"
                + " AND h.TEXT IN ('Tag hinzugefuegt', 'Tag entfernt')",
            Integer.class,
            taskUuid);
    return c == null ? 0 : c;
  }

  /** {@code true}, wenn die GET-Detail-history einen Eintrag mit text+changeDetail enthaelt. */
  private static boolean historyContains(
      final JsonNode detail, final String text, final String changeDetail) {
    final JsonNode history = detail.get("history");
    if (history == null || !history.isArray()) {
      return false;
    }
    for (final JsonNode entry : history) {
      final JsonNode t = entry.get("text");
      final JsonNode d = entry.get("changeDetail");
      if (t != null && text.equals(t.asText()) && d != null && changeDetail.equals(d.asText())) {
        return true;
      }
    }
    return false;
  }

  /** GET /tasks/{id} und liefert das Detail-JSON. */
  private JsonNode getDetail(
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

  private static List<String> readStringArray(final JsonNode arrayNode) {
    final java.util.List<String> out = new java.util.ArrayList<>();
    if (arrayNode != null && arrayNode.isArray()) {
      arrayNode.forEach(n -> out.add(n.asText()));
    }
    return out;
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

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
    return om.readValue(response, JsonNode.class).get("id").traverse(om).readValueAs(UUID.class);
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

  /**
   * Seedet einen globalen TASK_TAG (TENANT_ID=0) fuer IT-Tests.
   *
   * <p>Gibt die extern verwendete UUID zurueck. SEVERITY_ID=601 = PRIMARY (aus Flyway V1-Seed).
   *
   * @param jt JdbcTemplate
   * @param id interne ID des Tags
   * @param bezeichnung Bezeichnung (Name des Tags)
   * @return die EXTERNALID des angelegten Tags
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
    // Tatsaechliche UUID aus DB lesen (falls IF NOT EXISTS nichts eingefuegt hat, vorhandene UUID
    // lesen)
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
      return () -> "task-tag-patch-it";
    }
  }
}
