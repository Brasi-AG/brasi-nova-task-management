package de.brasi.nova.taskmanagement.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Integration-Test fuer den Tag-Lebenszyklus (Intake #138) gegen echtes MSSQL: Retire (POST
 * /api/v1/tags/{id}/deactivate) und fail-closed DELETE (DELETE /api/v1/tags/{id}).
 *
 * <p>Beweist:
 *
 * <ul>
 *   <li><b>deactivate</b>: TAG_ADMIN retire-t einen aktiven Tag -> 204, ENTITY_STATUS=DEACTIVE, Tag
 *       weg aus GET /tags + snapshot.tags, bestehende Zuordnung UNVERAENDERT aktiv; bereits
 *       DEACTIVE -> 404; ohne TAG_ADMIN (nur TASK_READ) -> 403; unbekannt -> 404.
 *   <li><b>delete</b>: unreferenzierter Tag -> 204 (DEACTIVE, weg aus GET /tags); referenzierter
 *       Tag (auch Referenz in ANDEREM Mandanten) -> 409 mit klarer Meldung + errorKey, Tag bleibt
 *       ACTIVE; ohne TAG_ADMIN -> 403; unbekannt/bereits DEACTIVE -> 404; nach DELETE nicht per
 *       PATCH addierbar (neuer Add -> 400).
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TagLifecycleIT.ItConfig.class,
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
class TagLifecycleIT {

  private static final String TEST_KID = "tag-lifecycle-it-key";

  /** Mandant A: Haupt-Tenant (TAG_ADMIN, Tasks). */
  private static final long TENANT_A_ID = 560L;

  private static final UUID TENANT_A_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final String USER_A_LOGIN = "user-a-taglife@brasi.de";
  private static final long USER_A_DB_ID = 561L;

  /** Mandant B: fremder Tenant fuer die Cross-Tenant-Referenz des DELETE-Guards. */
  private static final long TENANT_B_ID = 562L;

  private static final UUID TENANT_B_UUID = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final String USER_B_LOGIN = "user-b-taglife@brasi.de";
  private static final long USER_B_DB_ID = 563L;

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
      pemTempFile = Files.createTempFile("tag-lifecycle-it-jwk-", ".pem");
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
    seedFirmaIfAbsent(jt, TENANT_B_ID, TENANT_B_UUID);
    seedBenutzerIfAbsent(jt, USER_A_DB_ID, USER_A_REF, USER_A_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_B_DB_ID, USER_B_REF, USER_B_LOGIN, TENANT_B_ID);
  }

  // ---- deactivate (retire) -------------------------------------------------------------------

  @Test
  @DisplayName(
      "deactivate_retire_tag_204_deactive_weg_aus_katalog_zuordnung_bleibt:"
          + " POST deactivate -> 204, DEACTIVE, Tag weg aus GET /tags + snapshot.tags, Zuordnung"
          + " unveraendert aktiv (Intake #138)")
  void deactivate_retire_tag_204_deactive_weg_aus_katalog_zuordnung_bleibt() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String adminToken = adminTokenA();

    // Tag anlegen + einer Aufgabe zuordnen (so hat er eine aktive Zuordnung).
    final UUID tagId = createTag(mvc, adminToken, "{\"name\":\"Retire-MitZuordnung\"}");
    final UUID taskUuid = createTask(mvc, adminToken, "Retire-Task");
    patchTags(mvc, om, adminToken, taskUuid, List.of(tagId));

    // POST deactivate -> 204
    mvc.perform(
            post("/api/v1/tags/" + tagId + "/deactivate")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    // DB: ENTITY_STATUS=DEACTIVE
    final Integer deactiveCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] WHERE EXTERNALID = ? AND ENTITY_STATUS ="
                + " 'DEACTIVE'",
            Integer.class,
            tagId);
    assertThat(deactiveCount).as("Tag ist DEACTIVE nach deactivate").isEqualTo(1);

    // Tag weg aus GET /tags
    assertThat(tagInList(mvc, om, adminToken, tagId))
        .as("retire-ter Tag darf nicht mehr in GET /tags erscheinen")
        .isFalse();

    // Tag weg aus snapshot.tags
    assertThat(tagInSnapshot(mvc, om, adminToken, tagId))
        .as("retire-ter Tag darf nicht mehr in snapshot.tags erscheinen")
        .isFalse();

    // Zuordnung UNVERAENDERT aktiv
    final Integer assignmentActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
                + " JOIN [dbo].[TASK] t ON a.TASK_ID = t.ID"
                + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID"
                + " WHERE t.EXTERNALID = ? AND tag.EXTERNALID = ? AND a.ENTITY_STATUS = 'ACTIVE'",
            Integer.class,
            taskUuid,
            tagId);
    assertThat(assignmentActive)
        .as("Retire laesst bestehende Zuordnung unveraendert aktiv (Intake #138)")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "deactivate_bereits_deactive_404:"
          + " zweites deactivate auf denselben Tag -> 404 (terminal, idempotenz-neutral)")
  void deactivate_bereits_deactive_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String adminToken = adminTokenA();

    final UUID tagId = createTag(mvc, adminToken, "{\"name\":\"Retire-Zweimal\"}");
    mvc.perform(
            post("/api/v1/tags/" + tagId + "/deactivate")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());
    // zweites deactivate -> 404 (nicht mehr im aktiven Katalog)
    mvc.perform(
            post("/api/v1/tags/" + tagId + "/deactivate")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "deactivate_ohne_tag_admin_403_unbekannt_404:"
          + " nur TASK_READ -> 403; unbekannte UUID (mit TAG_ADMIN) -> 404")
  void deactivate_ohne_tag_admin_403_unbekannt_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String adminToken = adminTokenA();

    final UUID tagId = createTag(mvc, adminToken, "{\"name\":\"Retire-Authz\"}");

    // nur TASK_READ -> 403
    final String readOnly =
        buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    mvc.perform(
            post("/api/v1/tags/" + tagId + "/deactivate")
                .header("Authorization", "Bearer " + readOnly))
        .andExpect(status().isForbidden());

    // 403 darf den Tag NICHT retiren (bleibt ACTIVE)
    final Integer stillActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] WHERE EXTERNALID = ? AND ENTITY_STATUS ="
                + " 'ACTIVE'",
            Integer.class,
            tagId);
    assertThat(stillActive).as("403: Tag bleibt ACTIVE").isEqualTo(1);

    // unbekannte UUID (mit TAG_ADMIN) -> 404
    mvc.perform(
            post("/api/v1/tags/" + UUID.randomUUID() + "/deactivate")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound());
  }

  // ---- delete (fail-closed) ------------------------------------------------------------------

  @Test
  @DisplayName(
      "delete_unreferenziert_204_deactive_weg_aus_katalog:"
          + " DELETE eines unreferenzierten Tags -> 204, DEACTIVE, weg aus GET /tags")
  void delete_unreferenziert_204_deactive_weg_aus_katalog() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String adminToken = adminTokenA();

    final UUID tagId = createTag(mvc, adminToken, "{\"name\":\"Delete-Unreferenziert\"}");

    mvc.perform(delete("/api/v1/tags/" + tagId).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    final Integer deactiveCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] WHERE EXTERNALID = ? AND ENTITY_STATUS ="
                + " 'DEACTIVE'",
            Integer.class,
            tagId);
    assertThat(deactiveCount).as("unreferenzierter Tag ist nach DELETE DEACTIVE").isEqualTo(1);
    assertThat(tagInList(mvc, om, adminToken, tagId))
        .as("geloeschter Tag darf nicht mehr in GET /tags erscheinen")
        .isFalse();
  }

  @Test
  @DisplayName(
      "delete_referenziert_in_fremdem_mandant_409_klare_meldung_tag_bleibt_aktiv:"
          + " Referenz in Mandant B blockt DELETE (cross-tenant, System-Scope-Count) -> 409 mit"
          + " klarer Meldung + errorKey, Tag bleibt ACTIVE")
  void delete_referenziert_in_fremdem_mandant_409_klare_meldung_tag_bleibt_aktiv()
      throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String adminTokenA = adminTokenA();

    // Globaler Tag (von Admin in Mandant A angelegt).
    final UUID tagId = createTag(mvc, adminTokenA, "{\"name\":\"Delete-CrossTenant\"}");

    // Referenz NUR in Mandant B anlegen (Task in B + PATCH tagIds). B braucht TASK_WRITE/READ.
    final String tokenB =
        buildJwtWithAuthority(
            TENANT_B_UUID,
            USER_B_REF,
            USER_B_LOGIN,
            "task-management:TASK_WRITE",
            "task-management:TASK_READ");
    final UUID taskB = createTask(mvc, tokenB, "Delete-CrossTenant-TaskB");
    patchTags(mvc, om, tokenB, taskB, List.of(tagId));

    // DELETE durch Admin (Mandant A) -> 409, weil Mandant B den Tag referenziert
    // (System-Scope-Count).
    final String body =
        mvc.perform(
                delete("/api/v1/tags/" + tagId).header("Authorization", "Bearer " + adminTokenA))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode error = om.readTree(body);
    assertThat(error.toString())
        .as("409-Body traegt die klare Begruendung + Alternativen")
        .contains("referenziert")
        .contains("PATCH tagIds")
        .contains("deactivate");
    assertThat(error.path("errorKey").asText(error.path("code").asText("")))
        .as("errorKey NOVA_BUSINESS_RULE_VIOLATION: " + body)
        .isEqualTo("NOVA_BUSINESS_RULE_VIOLATION");

    // Tag bleibt ACTIVE (fail-closed).
    final Integer stillActive =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[TASK_TAG] WHERE EXTERNALID = ? AND ENTITY_STATUS ="
                + " 'ACTIVE'",
            Integer.class,
            tagId);
    assertThat(stillActive).as("409: referenzierter Tag bleibt ACTIVE").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "delete_ohne_tag_admin_403_unbekannt_404:"
          + " nur TASK_READ -> 403; unbekannte UUID (mit TAG_ADMIN) -> 404")
  void delete_ohne_tag_admin_403_unbekannt_404() throws Exception {
    final MockMvc mvc = buildMvc();
    final String adminToken = adminTokenA();

    final UUID tagId = createTag(mvc, adminToken, "{\"name\":\"Delete-Authz\"}");

    final String readOnly =
        buildJwtWithAuthority(TENANT_A_UUID, USER_A_REF, USER_A_LOGIN, "task-management:TASK_READ");
    mvc.perform(delete("/api/v1/tags/" + tagId).header("Authorization", "Bearer " + readOnly))
        .andExpect(status().isForbidden());

    mvc.perform(
            delete("/api/v1/tags/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "delete_dann_nicht_mehr_per_patch_addierbar_400:"
          + " nach DELETE (unreferenziert -> DEACTIVE) ist der Tag NEU nicht mehr per PATCH"
          + " hinzufuegbar (400, keine Toleranz)")
  void delete_dann_nicht_mehr_per_patch_addierbar_400() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String adminToken = adminTokenA();

    final UUID tagId = createTag(mvc, adminToken, "{\"name\":\"Delete-ThenPatch\"}");
    mvc.perform(delete("/api/v1/tags/" + tagId).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    // Task in Mandant A; NEU-Hinzufuegen des geloeschten (DEACTIVE) Tags -> 400 (keine Toleranz).
    final UUID taskUuid = createTask(mvc, adminToken, "Delete-ThenPatch-Task");
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", List.of(tagId)))))
        .andExpect(status().isBadRequest());
  }

  // ---- Hilfsmethoden -------------------------------------------------------------------------

  private static String adminTokenA() {
    return buildJwtWithAuthority(
        TENANT_A_UUID,
        USER_A_REF,
        USER_A_LOGIN,
        "task-management:TASK_WRITE",
        "task-management:TASK_READ",
        "task-management:TAG_ADMIN");
  }

  /** POST /api/v1/tags (roher JSON-Body), erwartet 201, liefert die UUID aus {@code $.id}. */
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

  /** POST /api/v1/tasks mit Titel, erwartet 201, liefert die Task-UUID. */
  private static UUID createTask(final MockMvc mvc, final String token, final String title)
      throws Exception {
    final ObjectMapper om = new ObjectMapper();
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

  /** PATCH /api/v1/tasks/{id} mit tagIds, erwartet 204. */
  private static void patchTags(
      final MockMvc mvc,
      final ObjectMapper om,
      final String token,
      final UUID taskUuid,
      final List<UUID> tagIds)
      throws Exception {
    mvc.perform(
            patch("/api/v1/tasks/" + taskUuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("tagIds", tagIds))))
        .andExpect(status().isNoContent());
  }

  /**
   * {@code true}, wenn GET /api/v1/tags den Tag mit der gegebenen id listet.
   *
   * <p>PLAN #141 Slice 2: GET /tags liefert ein {@code CollectionModel}. Die Wire-Form haengt vom
   * IT-Context ab: dieser Context importiert die KSF-HAL-Autoconfig NICHT, daher serialisiert plain
   * Jackson {@code CollectionModel} als {@code {"links":[],"content":[...]}}; mit aktiver
   * HAL-Autoconfig (Produktion) waere es {@code _embedded.tags} (so in {@code TagAffordanceIT}
   * bewiesen). {@link #tagItems} toleriert beide Formen (plus flaches Array als Fallback).
   */
  private static boolean tagInList(
      final MockMvc mvc, final ObjectMapper om, final String token, final UUID tagId)
      throws Exception {
    final String listResponse =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (final JsonNode node : tagItems(listResponse, om)) {
      if (tagId.toString().equals(node.path("id").asText())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Extrahiert die Tag-Items aus GET /api/v1/tags, robust gegen die drei beobachteten
   * {@code CollectionModel}-Wire-Formen: HAL ({@code _embedded.tags}), plain {@code CollectionModel}
   * ({@code content}) und flaches Array.
   */
  private static JsonNode tagItems(final String listResponse, final ObjectMapper om)
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

  /** {@code true}, wenn der Inbox-Snapshot den Tag im {@code tags}-Katalog listet. */
  private static boolean tagInSnapshot(
      final MockMvc mvc, final ObjectMapper om, final String token, final UUID tagId)
      throws Exception {
    final String snapshotResponse =
        mvc.perform(get("/api/v1/tasks/snapshot").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode tags = om.readValue(snapshotResponse, JsonNode.class).get("tags");
    if (tags == null || !tags.isArray()) {
      return false;
    }
    for (final JsonNode node : tags) {
      if (tagId.toString().equals(node.get("id").asText())) {
        return true;
      }
    }
    return false;
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
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    NoOpInboxSignalConfig.class,
    NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "tag-lifecycle-it";
    }
  }
}
