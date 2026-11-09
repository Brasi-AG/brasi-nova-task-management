package de.brasi.nova.taskmanagement.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.jsonwebtoken.Jwts;
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
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
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
 * Affordance-Integration-Test (PLAN #141 Slice 2): {@code GET /api/v1/tags} ist pipeline-angereichert
 * und traegt permission-gegatete HATEOAS-{@code _links} - OHNE die bestehende Enforcement
 * ({@code @RequiresPermission(TAG_ADMIN)} an jedem Mutations-Handler) oder den fachlichen Wire-Vertrag
 * von {@link de.brasi.nova.taskmanagement.tag.model.TagModel} zu veraendern. Die {@code _links} sind
 * rein additiv (Anzeige).
 *
 * <p>Wie {@code TaskDetailAffordanceIT} (Slice 1) bootet dieser IT den VOLLEN Spring-Boot-Autoconfig-
 * Graph: der globale {@code NovaFindResponseAdvice}, der {@code @EnableNovaHateoas}-Scan und die
 * Collection-/Item-Link-Resolver sind damit genauso live wie in Produktion, und die reale
 * KSF-Web-Security-Filterchain (JWT, Tenant-Binding) laeuft scharf - sonst wuerden die
 * TAG_ADMIN-gegateten Links faelschlich unterdrueckt.
 *
 * <p>Rel-Matrix (SPEC #141): Item {@code update}/{@code deactivate}/{@code delete}, Collection
 * {@code create}; alle TAG_ADMIN-gegatet (deactivate zusaetzlich nur bei aktivem Tag). KEIN
 * {@code self}-Item-Link (es gibt kein GET /tags/{id}).
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = TagAffordanceIT.ItConfig.class,
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
      // Reale KSF-Web-Security-Filterchain (FILTER-Modus, JWT) - identisch zu TaskDetailAffordanceIT.
      "ksf.web-security.enabled=true",
      "ksf.web-security.filter-chain.enabled=true",
      "ksf.web-security.principal.profile-claim.enabled=true",
      "ksf.web-security.tenant.context-binding=FILTER",
      "ksf.web-security.tenant.binding.source=JWT",
      "ksf.web-security.tenant.binding.required=false",
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
      // Dev-Modus: HATEOAS-Self-Check meldet Regelprobleme laut statt still.
      "ksf.web.dev.enabled=true",
      "logging.level.io.keel.framework.web.mvc=DEBUG",
      "logging.level.io.keel.framework.web.hateoas=DEBUG",
      "logging.level.io.keel.framework.web.enrichment=DEBUG",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(OrderAnnotation.class)
class TagAffordanceIT {

  private static final String TEST_KID = "tag-affordance-it-key";

  private static final long TENANT_A_ID = 580L;
  private static final UUID TENANT_A_UUID = UUID.randomUUID();

  /** TAG_ADMIN-Nutzer (legt Tags an, bekommt die admin-Rels). */
  private static final UUID USER_ADMIN_REF = UUID.randomUUID();

  private static final String USER_ADMIN_LOGIN = "tag-aff-admin@brasi.de";
  private static final long USER_ADMIN_DB_ID = 581L;

  /** Nutzer OHNE TAG_ADMIN (nur TASK_READ -> keine admin-Rels). */
  private static final UUID USER_READER_REF = UUID.randomUUID();

  private static final String USER_READER_LOGIN = "tag-aff-reader@brasi.de";
  private static final long USER_READER_DB_ID = 582L;

  private static final KeyPair RSA_KEY_PAIR;

  static {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (final NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static Path pemTempFile;

  @Autowired private WebApplicationContext webApplicationContext;

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
      pemTempFile = Files.createTempFile("tag-affordance-it-jwk-", ".pem");
      Files.writeString(pemTempFile, pemContent, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new RuntimeException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
  }

  @BeforeAll
  static void seedPrerequisites(@Autowired final JdbcTemplate jt) {
    seedFirmaIfAbsent(jt, TENANT_A_ID, TENANT_A_UUID);
    seedBenutzerIfAbsent(jt, USER_ADMIN_DB_ID, USER_ADMIN_REF, USER_ADMIN_LOGIN, TENANT_A_ID);
    seedBenutzerIfAbsent(jt, USER_READER_DB_ID, USER_READER_REF, USER_READER_LOGIN, TENANT_A_ID);

    // FINDING 2 (Slice 1): der KSF-Laufzeit-Regelresolver liest beim Link-Aufbau
    // system_controller_regel; fehlt die Tabelle, verschluckt der fail-safe-catch die GESAMTE
    // Link-Anreicherung (GET bleibt 200, aber ohne _links). RefSchemaItDb legt die leere
    // Struktur-Kopie an (= reales Deploy-Provisioning); hier nur noch beweisen, dass das gegriffen hat.
    final Integer tableExists =
        jt.queryForObject(
            "SELECT COUNT(*) FROM sys.objects"
                + " WHERE object_id = OBJECT_ID('dbo.system_controller_regel') AND type = 'U'",
            Integer.class);
    assertThat(tableExists)
        .as(
            "system_controller_regel muss als leere Struktur-Kopie existieren (RefSchemaItDb spiegelt"
                + " das Deploy-Provisioning); nur dann beweisen die _links den realen Deploy-Pfad"
                + " statt einer IT-Vortaeuschung.")
        .isEqualTo(1);
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
        } catch (final IOException ignored) {
          // best-effort cleanup
        }
      }
    }
  }

  /** Erwartetes fachliches Wire-Feldset eines Tag-Items (Vertrag VOR Slice 2). */
  private static final List<String> PRE_EXISTING_TAG_FIELDS =
      List.of("id", "name", "severityCode", "color");

  // ---- AC: TAG_ADMIN GET /tags -> _embedded.tags-Items tragen update/deactivate/delete + create --

  @Test
  @Order(2)
  @DisplayName(
      "admin_populated_item_und_collection_rels:"
          + " TAG_ADMIN legt Tag an; GET /tags -> 200; Item traegt update/deactivate/delete,"
          + " Collection traegt create; fachlicher Wire unveraendert + additiv modelState, kein version")
  void admin_populated_item_und_collection_rels() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String admin =
        buildJwt(USER_ADMIN_REF, USER_ADMIN_LOGIN, "task-management:TAG_ADMIN", "task-management:TASK_READ");

    final UUID tagId = createTag(mvc, admin, "{\"name\":\"AFF-Admin-Tag\",\"severityCode\":\"DANGER\"}");

    final String body =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[TAG-AFF-admin] GET /tags -> " + body);

    // Collection-Level _links.create (TAG_ADMIN) vorhanden.
    assertThat(root.path("_links").path("create").path("href").isTextual())
        .as("BLOCKER: Collection-_links.create fehlt (TagController ist Rule-Owner). Body=" + body)
        .isTrue();
    // Auflage 2 (IMPL-Gate): create zielt auf die Collection POST /api/v1/tags (ohne Tag-id) - fangt
    // eine @HateoasLinkTarget-Fehlzuordnung des create-rels auf einen Item-Handler.
    assertThat(root.path("_links").path("create").path("href").asText())
        .as("create-href zielt auf POST /api/v1/tags (ohne id)")
        .endsWith("/api/v1/tags");
    assertThat(root.path("_links").path("create").path("type").asText())
        .as("create ist ein POST")
        .isEqualTo("POST");

    // Items liegen unter _embedded.tags (@Relation collectionRelation="tags").
    final JsonNode items = root.path("_embedded").path("tags");
    assertThat(items.isArray()).as("GET /tags liefert _embedded.tags als Array. Body=" + body).isTrue();

    final JsonNode item = findById(items, tagId);
    assertThat(item).as("angelegter Tag muss im _embedded.tags-Item erscheinen").isNotNull();

    // Item-Rels: update + deactivate + delete (TAG_ADMIN + aktiv).
    final JsonNode itemLinks = item.path("_links");
    assertThat(itemLinks.path("update").path("href").isTextual())
        .as("Item-_links.update vorhanden. Item=" + item)
        .isTrue();
    assertThat(itemLinks.path("deactivate").path("href").isTextual())
        .as("Item-_links.deactivate vorhanden (TAG_ADMIN + aktiv)")
        .isTrue();
    assertThat(itemLinks.path("delete").path("href").isTextual())
        .as("Item-_links.delete vorhanden")
        .isTrue();
    // update/delete/deactivate-href tragen die Tag-id (Path-Variable aus #dto.id aufgeloest).
    assertThat(itemLinks.path("update").path("href").asText()).contains(tagId.toString());
    // Auflage 2 (IMPL-Gate): href-Ziel + HTTP-type je rel pinnen, damit eine @HateoasLinkTarget-
    // Fehlzuordnung auffaellt. update (PUT /tags/{id}) und delete (DELETE /tags/{id}) teilen die URI
    // und sind NUR ueber den type unterscheidbar; deactivate traegt den /deactivate-Suffix.
    assertThat(itemLinks.path("update").path("type").asText())
        .as("update ist ein PUT /tags/{id}")
        .isEqualTo("PUT");
    assertThat(itemLinks.path("delete").path("href").asText()).contains(tagId.toString());
    assertThat(itemLinks.path("delete").path("type").asText())
        .as("delete ist ein DELETE /tags/{id} (gleiche URI wie update -> nur type unterscheidet)")
        .isEqualTo("DELETE");
    assertThat(itemLinks.path("deactivate").path("href").asText())
        .as("deactivate zielt auf POST /tags/{id}/deactivate")
        .contains(tagId.toString())
        .endsWith("/deactivate");
    assertThat(itemLinks.path("deactivate").path("type").asText())
        .as("deactivate ist ein POST")
        .isEqualTo("POST");
    // KEIN self-Item-Link (es gibt kein GET /tags/{id}).
    assertThat(itemLinks.has("self")).as("kein self-Item-Link (kein GET /tags/{id})").isFalse();

    // Fachlicher Wire je Item unveraendert; modelState additiv; KEIN version.
    for (final String field : PRE_EXISTING_TAG_FIELDS) {
      assertThat(item.has(field))
          .as("Alt-Feld '" + field + "' muss im Tag-Item erhalten bleiben")
          .isTrue();
    }
    assertThat(item.path("id").asText()).isEqualTo(tagId.toString());
    assertThat(item.path("name").asText()).isEqualTo("AFF-Admin-Tag");
    assertThat(item.path("severityCode").asText()).isEqualTo("DANGER");
    assertThat(item.has("modelState")).as("modelState (BaseModel) additiv vorhanden").isTrue();
    assertThat(item.path("modelState").path("key").asText()).isEqualTo("ACTIVE");
    assertThat(item.has("version"))
        .as("geerbtes version darf NICHT im Tag-Wire erscheinen (JsonIgnore)")
        .isFalse();

    // Auflage 1 (IMPL-Gate): EXAKTES Item-Feldset pinnen - kein geerbtes BaseModel-Feld
    // (tenantId/refId/enriched/entityClass/createdAt/version) leakt in den Tag-Wire. Schliesst die
    // tenant-Leak-Regressionsklasse maschinell: ein kuenftiger KSF-Minor, der ein @JsonIgnore
    // entfernt, faellt hier sofort rot (statt still zu leaken).
    final java.util.Set<String> allowedItemFields =
        new java.util.LinkedHashSet<>(PRE_EXISTING_TAG_FIELDS);
    allowedItemFields.add("modelState");
    allowedItemFields.add("_links");
    final java.util.List<String> actualItemFields = new java.util.ArrayList<>();
    item.fieldNames().forEachRemaining(actualItemFields::add);
    assertThat(actualItemFields)
        .as(
            "Tag-Item-Wire muss GENAU id/name/severityCode/color + modelState + _links tragen"
                + " (kein tenantId/refId/enriched/entityClass/createdAt/version-Leak). Ist=%s",
            actualItemFields)
        .containsExactlyInAnyOrderElementsOf(allowedItemFields);
    for (final String forbidden :
        List.of("tenantId", "refId", "enriched", "entityClass", "createdAt")) {
      assertThat(item.has(forbidden))
          .as("geerbtes BaseModel-Feld '" + forbidden + "' darf NICHT im Tag-Wire leaken")
          .isFalse();
    }
  }

  // ---- AC: Nutzer OHNE TAG_ADMIN -> keine admin-Rels (Cross-User-Sicherheitsnetz) --------------

  @Test
  @Order(3)
  @DisplayName(
      "nonadmin_keine_admin_rels:"
          + " authenticated OHNE TAG_ADMIN (nur TASK_READ); GET /tags -> 200; Items OHNE"
          + " update/deactivate/delete, Collection OHNE create (conditions greifen in beide Richtungen)")
  void nonadmin_keine_admin_rels() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();

    // Ein Tag muss existieren (vom Admin angelegt), damit _embedded.tags ein Item traegt.
    final String admin =
        buildJwt(USER_ADMIN_REF, USER_ADMIN_LOGIN, "task-management:TAG_ADMIN", "task-management:TASK_READ");
    final UUID tagId = createTag(mvc, admin, "{\"name\":\"AFF-NonAdmin-Tag\"}");

    final String reader = buildJwt(USER_READER_REF, USER_READER_LOGIN, "task-management:TASK_READ");
    final String body =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + reader))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[TAG-AFF-nonadmin] GET /tags -> " + body);

    // Collection: KEIN create.
    assertThat(root.path("_links").has("create"))
        .as("ohne TAG_ADMIN darf die Collection KEIN create tragen. Body=" + body)
        .isFalse();

    // Item: KEIN update/deactivate/delete.
    final JsonNode item = findById(root.path("_embedded").path("tags"), tagId);
    assertThat(item).as("Tag muss auch fuer den Reader im _embedded.tags-Item erscheinen").isNotNull();
    final JsonNode itemLinks = item.path("_links");
    assertThat(itemLinks.has("update")).as("ohne TAG_ADMIN kein update-Rel").isFalse();
    assertThat(itemLinks.has("deactivate")).as("ohne TAG_ADMIN kein deactivate-Rel").isFalse();
    assertThat(itemLinks.has("delete")).as("ohne TAG_ADMIN kein delete-Rel").isFalse();

    // Fachlicher Wire bleibt vollstaendig (der Reader sieht den Tag inhaltlich).
    assertThat(item.path("name").asText()).isEqualTo("AFF-NonAdmin-Tag");
  }

  // ---- AC: TAG_ADMIN auf LEERER Collection -> create am Collection-Level (kritische Hypothese) ---

  @Test
  @Order(1)
  @DisplayName(
      "admin_empty_collection_traegt_create:"
          + " frische DB OHNE Tags; GET /tags als TAG_ADMIN -> 200; _links.create am Collection-Level"
          + " MUSS vorhanden sein (Rule-Owner-entityClass aus @EnableNovaHateoas, nicht aus Items)")
  void admin_empty_collection_traegt_create() throws Exception {
    final MockMvc mvc = buildMvc();
    final ObjectMapper om = new ObjectMapper();
    final String admin =
        buildJwt(USER_ADMIN_REF, USER_ADMIN_LOGIN, "task-management:TAG_ADMIN", "task-management:TASK_READ");

    final String body =
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode root = om.readTree(body);
    System.out.println("[TAG-AFF-empty] GET /tags (leer) -> " + body);

    // Die Collection ist leer (keine _embedded.tags ODER leeres Array), aber create MUSS da sein.
    final JsonNode items = root.path("_embedded").path("tags");
    assertThat(items.isMissingNode() || items.size() == 0)
        .as("frische DB -> keine Tag-Items. Body=" + body)
        .isTrue();
    assertThat(root.path("_links").path("create").path("href").isTextual())
        .as(
            "KRITISCH: create am Collection-Level MUSS auch auf leerer Rule-Owner-Collection"
                + " erscheinen (entityClass kommt aus @EnableNovaHateoas, nicht aus Items). Body="
                + body)
        .isTrue();
  }

  // ---- Hilfen --------------------------------------------------------------------------------

  private static JsonNode findById(final JsonNode items, final UUID id) {
    for (final JsonNode node : items) {
      if (id.toString().equals(node.path("id").asText())) {
        return node;
      }
    }
    return null;
  }

  private MockMvc buildMvc() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  private UUID createTag(final MockMvc mvc, final String token, final String jsonBody)
      throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final String response =
        mvc.perform(
                post("/api/v1/tags")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(om.readTree(response).get("id").asText());
  }

  private static String buildJwt(
      final UUID userUuid, final String loginName, final String... authorities) {
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
        .claim("lastActiveTenantId", TENANT_A_UUID.toString())
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

  // ---- IT-Context: identisch zu TaskDetailAffordanceIT.ItConfig (voller Enrichment-Graph) -----

  @SpringBootConfiguration
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
      exclude = {
        org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class
      })
  @org.springframework.boot.autoconfigure.AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ComponentScan(
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
        "de.brasi.nova.taskmanagement.security",
        "de.brasi.nova.taskmanagement.time",
      },
      excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "de\\.brasi\\.nova\\.taskmanagement\\.security\\.it\\..*")
      })
  @EnableTransactionManagement
  @io.keel.framework.persistence.factory.EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @org.springframework.context.annotation.Import({
    de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig.class,
  })
  static class ItConfig {

    @Bean
    io.keel.framework.api.security.AuditorProvider tagAffordanceAuditorProvider() {
      return () -> "tag-affordance-it";
    }
  }
}
