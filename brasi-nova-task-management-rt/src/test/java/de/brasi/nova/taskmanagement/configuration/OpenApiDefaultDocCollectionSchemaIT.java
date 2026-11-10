package de.brasi.nova.taskmanagement.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Beweist, dass das DEFAULT-OpenAPI-Dokument {@code /v3/api-docs} (Regen-Quelle des api-clients)
 * fuer die vier Listen-Endpunkte das Collection-Schema ({@code _embedded}, Cursor-Felder) traegt
 * und nicht auf das Einzel-Item-Model kollabiert ist.
 *
 * <p>Hintergrund: {@code @ApiFindResponse} laesst den KSF-{@code KsfApiResponseCustomizer} die 200
 * durch das Item-Model ersetzen; {@code @ApiFindCollectionResponse} laesst das springdoc-native
 * Schema des Rueckgabetyps stehen.
 */
@SpringBootTest(
    classes = OpenApiDefaultDocCollectionSchemaIT.ItConfig.class,
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
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      "ksf.web-security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
      // /v3/api-docs ohne JWT abrufbar (identisch zur Produktion: application.properties
      // permit-paths)
      "ksf.web-security.permit-paths=/actuator/health,/actuator/info,/v3/api-docs/**,/swagger-ui/**,/ws",
      "ksf.security.jwt.enabled=true",
      "ksf.module-registration.enabled=false",
      "springdoc.api-docs.path=/v3/api-docs",
      "springdoc.api-docs.enabled=true",
      "springdoc.api-docs.version=openapi_3_0",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class OpenApiDefaultDocCollectionSchemaIT {

  private static final String TEST_KID = "springdoc-it-key";
  private static final java.security.KeyPair RSA_KEY_PAIR;

  static {
    try {
      final java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      RSA_KEY_PAIR = gen.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HikariDataSource master;
  private static String dbName;
  private static java.nio.file.Path pemTempFile;

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
      pemTempFile = java.nio.file.Files.createTempFile("springdoc-it-jwk-", ".pem");
      java.nio.file.Files.writeString(
          pemTempFile, pemContent, java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
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
      try {
        if (master != null) {
          master.close();
        }
      } finally {
        if (pemTempFile != null) {
          try {
            java.nio.file.Files.deleteIfExists(pemTempFile);
          } catch (java.io.IOException ignored) {
            // Cleanup best-effort
          }
        }
      }
    }
  }

  private static String toPem(final byte[] der) {
    final String base64 =
        java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
  }

  @Autowired private WebApplicationContext webApplicationContext;

  private JsonNode defaultDocument() throws Exception {
    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    final String body =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new ObjectMapper().readTree(body);
  }

  private static JsonNode resolve(final JsonNode node, final JsonNode doc) {
    final String ref = node.path("$ref").asText("");
    if (ref.startsWith("#/components/schemas/")) {
      return doc.path("components").path("schemas").path(ref.substring(21));
    }
    return node;
  }

  /** Sucht Property rekursiv ueber $ref und allOf. */
  private static JsonNode property(final JsonNode schema, final JsonNode doc, final String name) {
    final JsonNode resolved = resolve(schema, doc);
    final JsonNode direct = resolved.path("properties").path(name);
    if (!direct.isMissingNode()) {
      return direct;
    }
    for (final JsonNode part : resolved.path("allOf")) {
      final JsonNode nested = property(part, doc, name);
      if (!nested.isMissingNode()) {
        return nested;
      }
    }
    return MissingNode.getInstance();
  }

  private static JsonNode okSchema(final JsonNode doc, final String path) {
    final JsonNode content =
        doc.path("paths").path(path).path("get").path("responses").path("200").path("content");
    final var types = content.fieldNames();
    return types.hasNext() ? content.path(types.next()).path("schema") : MissingNode.getInstance();
  }

  private static void collectRefs(final JsonNode node, final Set<String> refs) {
    if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              e -> {
                if ("$ref".equals(e.getKey()) && e.getValue().isTextual()) {
                  refs.add(e.getValue().asText());
                } else {
                  collectRefs(e.getValue(), refs);
                }
              });
    } else if (node.isArray()) {
      node.forEach(n -> collectRefs(n, refs));
    }
  }

  private void assertCollection(
      final JsonNode doc,
      final String path,
      final String rel,
      final String itemModel,
      final String... extra) {
    final JsonNode schema = okSchema(doc, path);
    final JsonNode embedded = property(schema, doc, "_embedded");
    assertThat(embedded.isMissingNode()).as(path + " hat _embedded: " + schema).isFalse();
    // EXAKTER Rel-Schluessel: _embedded hat genau eine Property <rel>, ein Array von $ref
    // Item-Model.
    final JsonNode embeddedProps = resolve(embedded, doc).path("properties");
    assertThat(embeddedProps.fieldNames())
        .toIterable()
        .as(path + " _embedded-Rel")
        .containsExactly(rel);
    final JsonNode relSchema = embeddedProps.path(rel);
    assertThat(relSchema.path("type").asText())
        .as(path + " _embedded." + rel + " ist Array")
        .isEqualTo("array");
    assertThat(relSchema.path("items").path("$ref").asText())
        .as(path + " _embedded." + rel + " Item-Model")
        .isEqualTo("#/components/schemas/" + itemModel);
    assertThat(property(schema, doc, "_links").isMissingNode()).as(path + " hat _links").isFalse();
    for (final String name : extra) {
      assertThat(property(schema, doc, name).isMissingNode())
          .as(path + " hat " + name + ": " + schema)
          .isFalse();
    }
  }

  @Test
  @DisplayName(
      "default_dokument_listen_endpunkte_tragen_collection_schema: _embedded + Cursor-Felder, keine"
          + " dangling refs")
  void default_dokument_listen_endpunkte_tragen_collection_schema() throws Exception {
    final JsonNode doc = defaultDocument();

    assertCollection(
        doc, "/api/v1/tasks", "tasks", "TaskSummaryModel", "nextCursor", "hasMore", "asOf");
    assertCollection(
        doc, "/api/v1/tasks/archive", "tasks", "TaskSummaryModel", "nextCursor", "hasMore", "asOf");
    assertCollection(
        doc,
        "/api/v1/notifications",
        "notifications",
        "NotificationModel",
        "nextCursor",
        "hasMore");
    assertCollection(doc, "/api/v1/tags", "tags", "TagModel");

    final Set<String> refs = new HashSet<>();
    collectRefs(doc, refs);
    final Set<String> dangling = new HashSet<>();
    for (final String ref : refs) {
      if (!ref.startsWith("#/components/schemas/")
          || doc.path("components").path("schemas").path(ref.substring(21)).isMissingNode()) {
        dangling.add(ref);
      }
    }
    assertThat(dangling).as("dangling $refs im Default-Dokument").isEmpty();
  }

  @Test
  @DisplayName(
      "snapshot_behaelt_springdoc_tag: GET /api/v1/tasks/snapshot traegt nach dem Handler-Move in den"
          + " TaskFacadeController weiterhin genau den Tag inbox-snapshot-controller (api-client-Stabilitaet)")
  void snapshot_behaelt_springdoc_tag() throws Exception {
    final JsonNode tags =
        defaultDocument().path("paths").path("/api/v1/tasks/snapshot").path("get").path("tags");
    assertThat(tags.isArray()).as("Snapshot-Operation hat tags: " + tags).isTrue();
    assertThat(tags.size()).as("genau ein Tag: " + tags).isEqualTo(1);
    assertThat(tags.get(0).asText()).isEqualTo("inbox-snapshot-controller");
  }

  // ---- GET /api/v1/tasks/participants: Doku-Vertrag (reine OpenAPI-Beschreibung) -----

  private static final String PARTICIPANTS = "/api/v1/tasks/participants";

  private JsonNode participantsGet() throws Exception {
    return defaultDocument().path("paths").path(PARTICIPANTS).path("get");
  }

  private static JsonNode parameter(final JsonNode op, final String name) {
    for (final JsonNode p : op.path("parameters")) {
      if (name.equals(p.path("name").asText())) {
        return p;
      }
    }
    return MissingNode.getInstance();
  }

  private static JsonNode example(final JsonNode op, final String code, final String name)
      throws Exception {
    final JsonNode ex =
        op.path("responses")
            .path(code)
            .path("content")
            .path("application/json")
            .path("examples")
            .path(name);
    assertThat(ex.isMissingNode()).as("Example " + code + "/" + name).isFalse();
    final JsonNode value = ex.path("value");
    return value.isTextual() ? new ObjectMapper().readTree(value.asText()) : value;
  }

  @Test
  @DisplayName("participants_operation_description_traegt_kernaussagen")
  void participants_operation_description_traegt_kernaussagen() throws Exception {
    final String d = participantsGet().path("description").asText();
    assertThat(d).contains("AKTIVEN BENUTZER").contains("Mandanten").contains("userRef");
    assertThat(d).contains("assignees").contains("participants").contains("mentions");
    assertThat(d).contains("400").contains("NICHT alphabetisch");
    assertThat(d).contains("Ohne q").contains("ALLE");
  }

  @Test
  @DisplayName("participants_parameter_beschreibungen_tragen_kerninfos")
  void participants_parameter_beschreibungen_tragen_kerninfos() throws Exception {
    final JsonNode op = participantsGet();
    final String q = parameter(op, "q").path("description").asText();
    assertThat(q).contains("Teiltreffer").contains("loginName").contains("fullName");
    assertThat(q).contains("Gross-/Kleinschreibung").contains("kein exakter Lookup");
    final String limit = parameter(op, "limit").path("description").asText();
    assertThat(limit).contains("[1,100]").contains("50");
    final String cursor = parameter(op, "cursor").path("description").asText();
    assertThat(cursor).contains("nextCursor").contains("identischem q").contains("400");
  }

  @Test
  @DisplayName("participants_200_hat_benannte_examples_und_schema_bleibt_ParticipantPage")
  void participants_200_hat_benannte_examples_und_schema_bleibt_ParticipantPage() throws Exception {
    final JsonNode doc = defaultDocument();
    final JsonNode op = doc.path("paths").path(PARTICIPANTS).path("get");
    assertThat(okSchema(doc, PARTICIPANTS).path("$ref").asText())
        .isEqualTo("#/components/schemas/ParticipantPage");

    final JsonNode all = example(op, "200", "alle-kandidaten");
    assertThat(all.path("items").size()).isEqualTo(3);
    assertThat(all.path("hasMore").asBoolean()).isFalse();
    assertThat(all.path("nextCursor").isNull()).isTrue();

    assertThat(example(op, "200", "suche").path("items").size()).isEqualTo(1);

    final JsonNode first = example(op, "200", "erste-seite-mit-weiteren");
    assertThat(first.path("items").size()).isEqualTo(2);
    assertThat(first.path("hasMore").asBoolean()).isTrue();
    assertThat(first.path("nextCursor").asText()).isNotBlank();
  }

  @Test
  @DisplayName("participants_400_und_403_haben_nova_fehler_examples")
  void participants_400_und_403_haben_nova_fehler_examples() throws Exception {
    final JsonNode op = participantsGet();
    final JsonNode bad = example(op, "400", "ungueltiger-cursor");
    assertThat(bad.path("errorKey").asText()).isEqualTo("NOVA_VALIDATION_FAILED");
    assertThat(bad.path("status").asInt()).isEqualTo(400);
    final JsonNode denied = example(op, "403", "fehlende-berechtigung");
    assertThat(denied.path("errorKey").asText()).isEqualTo("NOVA_ACCESS_DENIED");
    assertThat(denied.path("status").asInt()).isEqualTo(403);
    // Fehlerantworten duerfen nicht mehr auf das Erfolgs-Schema ParticipantPage zeigen.
    for (final String code : new String[] {"400", "403"}) {
      assertThat(
              op.path("responses")
                  .path(code)
                  .path("content")
                  .path("application/json")
                  .path("schema")
                  .path("$ref")
                  .asText())
          .as("Schema-$ref der " + code + "-Antwort")
          .doesNotContain("ParticipantPage");
    }
  }

  @Test
  @DisplayName("participants_schema_felder_beschreiben_userRef_verwendung")
  void participants_schema_felder_beschreiben_userRef_verwendung() throws Exception {
    final JsonNode schemas = defaultDocument().path("components").path("schemas");
    final String userRef =
        schemas
            .path("ParticipantSummary")
            .path("properties")
            .path("userRef")
            .path("description")
            .asText();
    assertThat(userRef).contains("assignees").contains("participants").contains("mentions");
    final String next =
        schemas
            .path("ParticipantPage")
            .path("properties")
            .path("nextCursor")
            .path("description")
            .asText();
    assertThat(next).contains("hasMore=true").contains("gleichem q");
  }

  // ---- IT-Context: identisch zu InboxListAffordanceIT.ItConfig (voller Autoconfig + HATEOAS)
  // -----

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
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.configuration",
      },
      excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ANNOTATION,
            classes = SpringBootConfiguration.class),
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
    io.keel.framework.api.security.AuditorProvider openApiDocAuditorProvider() {
      return () -> "openapi-default-doc-it";
    }
  }
}
