package de.brasi.nova.taskmanagement.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.openapi.TaskManagementOpenApiGroups;
import de.brasi.nova.taskmanagement.security.SecurityContextUserAccessor;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.security.it.WhoAmIProbeController;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import io.keel.framework.security.KsfSecurityAutoConfiguration;
import io.keel.framework.security.web.KsfWebSecurityAutoConfiguration;
import io.keel.framework.web.openapi.KsfOpenApiConfig;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration-Test fuer die springdoc-OpenAPI-Generierung des Task-Management-Facade.
 *
 * <p>Bootet den vollen Facade-Context (alle 10 handgeschriebenen Controller + {@link OpenApiConfig}
 * + springdoc-AutoConfiguration) gegen eine echte MSSQL-Wegwerf-DB und ruft das gruppierte
 * OpenAPI-Dokument {@code /v3/api-docs/task-management} ueber MockMvc ab. Beweist:
 *
 * <ul>
 *   <li>Die Scaffold-Gruppen-Id {@link TaskManagementOpenApiGroups#TASK_MANAGEMENT} ist als {@code
 *       GroupedOpenApi} registriert und liefert ein nicht-leeres Dokument (Pre-Release-Audit:
 *       Gruppe war disjunkt/tot).
 *   <li>Die Endpunkte tragen {@code @Operation}-Zusammenfassungen und dokumentierte Fehlerantworten
 *       (401/403).
 *   <li>Die Hand-DTOs tragen {@code @Schema}-Beschreibungen.
 *   <li>Der {@code PatchValue<T>}-Wrapper in {@code TaskPatchInput} ist zum fachlichen Wert
 *       kollabiert (kein Leak der Wrapper-Interna), und die Generierung schlaegt nicht mit 500
 *       fehl.
 * </ul>
 *
 * <p>Uebersprungen, wenn kein MSSQL-Zugang konfiguriert ist ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = SpringDocApiDocsIT.ItConfig.class,
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
class SpringDocApiDocsIT {

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

  @Test
  @DisplayName(
      "task_management_gruppe_generiert_dokumentiertes_openapi: /v3/api-docs/task-management ->"
          + " 200, Operationen mit Summary + 401/403, DTO-@Schema, PatchValue kollabiert")
  void task_management_gruppe_generiert_dokumentiertes_openapi() throws Exception {
    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    final ObjectMapper om = new ObjectMapper();

    final String body =
        mvc.perform(get("/v3/api-docs/" + TaskManagementOpenApiGroups.TASK_MANAGEMENT))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode doc = om.readTree(body);

    // 1. Pfade nicht leer (die Gruppe matcht /api/v1/** und ist nicht mehr tot/disjunkt)
    final JsonNode paths = doc.get("paths");
    assertThat(paths).as("paths-Knoten vorhanden").isNotNull();
    assertThat(paths.size()).as("mindestens 10 dokumentierte Pfade").isGreaterThanOrEqualTo(10);

    // 2. POST /api/v1/tasks hat eine nicht-leere Summary + dokumentierte 401/403
    final JsonNode createOp = doc.path("paths").path("/api/v1/tasks").path("post");
    assertThat(createOp.isMissingNode()).as("POST /api/v1/tasks dokumentiert").isFalse();
    assertThat(createOp.path("summary").asText("")).as("Summary gesetzt").isNotBlank();
    assertThat(createOp.path("responses").has("401")).as("401 dokumentiert").isTrue();
    assertThat(createOp.path("responses").has("403")).as("403 dokumentiert").isTrue();

    final JsonNode contextCreateOp =
        doc.path("paths").path("/api/v1/tasks/from-source").path("post");
    assertThat(contextCreateOp.isMissingNode()).as("Kontext-Create dokumentiert").isFalse();
    assertThat(contextCreateOp.path("responses").has("201")).isTrue();
    assertThat(contextCreateOp.path("responses").has("200")).isTrue();
    assertThat(contextCreateOp.path("responses").has("403")).isTrue();
    assertThat(contextCreateOp.path("responses").has("409")).isTrue();
    assertThat(contextCreateOp.path("parameters").toString())
        .contains("Idempotency-Key", "\"required\":true");

    // 3. Mindestens ein Hand-DTO-Schema traegt eine Property mit description
    final JsonNode schemas = doc.path("components").path("schemas");
    assertThat(schemas.isMissingNode()).as("components.schemas vorhanden").isFalse();
    assertThat(anySchemaPropertyHasDescription(schemas))
        .as("mindestens ein DTO-Feld traegt eine @Schema-description")
        .isTrue();

    // 4. PatchValue-Wrapper in TaskPatchInput ist kollabiert: kein Leak der Wrapper-Interna
    final JsonNode patchSchema = schemas.path("TaskPatchInput");
    assertThat(patchSchema.isMissingNode()).as("TaskPatchInput-Schema vorhanden").isFalse();
    assertThat(patchSchema.toString())
        .as("TaskPatchInput darf die PatchValue-Interna nicht exponieren")
        .doesNotContain("PatchValue");

    // 5. Nullable-PATCH-Feld: dueDate darf explizit null gesetzt werden (Codex-P2).
    //    OpenAPI 3.1 drueckt Nullability als "null" im type-Set aus; 3.0 als nullable=true.
    final JsonNode dueDate = patchSchema.path("properties").path("dueDate");
    assertThat(isNullable(dueDate))
        .as("TaskPatchInput.dueDate muss nullable sein (null = Datum loeschen): " + dueDate)
        .isTrue();

    // 6. Keyset-Items typisiert: GET /api/v1/tasks (aktive Aufgaben) -> 200-Schema referenziert den
    //    Typparameter TaskSummaryModel, nicht einen untypisierten KeysetPage (Codex-P2)
    final JsonNode activeSchema =
        doc.path("paths")
            .path("/api/v1/tasks")
            .path("get")
            .path("responses")
            .path("200")
            .path("content")
            .path("application/json")
            .path("schema");
    assertThat(activeSchema.isMissingNode()).as("GET /api/v1/tasks 200-Schema vorhanden").isFalse();
    assertThat(activeSchema.toString())
        .as("Aktive-Aufgaben-Seite muss den Item-Typ TaskSummaryModel referenzieren")
        .contains("TaskSummaryModel");

    // 7. Create-Response typisiert: POST /api/v1/tasks 201 referenziert CreatedResourceResponse,
    //    und dessen id-Feld ist eine UUID (Codex-P2)
    final JsonNode createSchema =
        doc.path("paths")
            .path("/api/v1/tasks")
            .path("post")
            .path("responses")
            .path("201")
            .path("content")
            .path("application/json")
            .path("schema");
    assertThat(createSchema.toString())
        .as("POST /api/v1/tasks 201 muss CreatedResourceResponse referenzieren")
        .contains("CreatedResourceResponse");
    final JsonNode createdId =
        schemas.path("CreatedResourceResponse").path("properties").path("id");
    assertThat(createdId.path("format").asText(""))
        .as("CreatedResourceResponse.id Format uuid")
        .isEqualTo("uuid");
  }

  @Test
  void sourceCreateRequestRequiresSourceTupleOnlyOnSourceRoute() throws Exception {
    final JsonNode doc = taskManagementDocument();
    final JsonNode schemas = doc.path("components").path("schemas");
    final JsonNode sourceRequest =
        doc.path("paths")
            .path("/api/v1/tasks/from-source")
            .path("post")
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("schema");
    final JsonNode manualRequest =
        doc.path("paths")
            .path("/api/v1/tasks")
            .path("post")
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("schema");

    assertThat(sourceRequest.path("$ref").asText())
        .as("Generator braucht ein benanntes, routespezifisches Request-Schema: " + sourceRequest)
        .isEqualTo("#/components/schemas/TaskCreateFromSourceInput");
    final JsonNode sourceProperties = schemas.path("TaskCreateFromSourceInput").path("properties");
    final JsonNode manualProperties = schemas.path("TaskCreateInput").path("properties");
    final Set<String> sourceNames = new HashSet<>();
    sourceProperties.fieldNames().forEachRemaining(sourceNames::add);
    final Set<String> manualNames = new HashSet<>();
    manualProperties.fieldNames().forEachRemaining(manualNames::add);
    assertThat(sourceNames).as("Request-Feldparitaet").isEqualTo(manualNames);
    for (final String name : manualNames) {
      for (final String wireFacet :
          new String[] {"type", "format", "$ref", "nullable", "additionalProperties"}) {
        assertThat(sourceProperties.path(name).path(wireFacet))
            .as("Request-Wire-Shape " + name + "." + wireFacet)
            .isEqualTo(manualProperties.path(name).path(wireFacet));
      }
      for (final String itemFacet : new String[] {"type", "format", "$ref", "nullable"}) {
        assertThat(sourceProperties.path(name).path("items").path(itemFacet))
            .as("Request-Array-Item-Wire-Shape " + name + ".items." + itemFacet)
            .isEqualTo(manualProperties.path(name).path("items").path(itemFacet));
      }
      for (final String documentationFacet : new String[] {"description", "example"}) {
        assertThat(sourceProperties.path(name).path(documentationFacet))
            .as("Request-Felddokumentation " + name + "." + documentationFacet)
            .isEqualTo(manualProperties.path(name).path(documentationFacet));
      }
      assertThat(sourceProperties.path(name).path("items").path("description"))
          .as("Request-Array-Item-Dokumentation " + name + ".items.description")
          .isEqualTo(manualProperties.path(name).path("items").path("description"));
    }
    final Set<String> expectedSourceRequired =
        requiredProperties(schemas.path("TaskCreateInput"), schemas);
    expectedSourceRequired.add("source");
    assertThat(requiredProperties(schemas.path("TaskCreateFromSourceInput"), schemas))
        .as("Required-Paritaet mit source als einziger zusaetzlicher Pflicht")
        .isEqualTo(expectedSourceRequired);

    assertThat(requiredProperties(sourceRequest, schemas))
        .as("from-source braucht source: " + sourceRequest)
        .contains("source");
    assertThat(requiredProperties(manualRequest, schemas))
        .as("manuelle Create-Route darf source nicht verlangen: " + manualRequest)
        .doesNotContain("source");
    assertThat(requiredProperties(schemas.path("TaskSourceInput"), schemas))
        .contains("module", "entityType", "entityRef");
    assertThat(schemas.path("TaskSourceInput").path("description").asText())
        .as("Quellkontext-Beschreibung bleibt im benannten Schema erhalten")
        .isEqualTo(
            "Registrierter Quellkontext; nur auf POST /api/v1/tasks/from-source erlaubt und dort erforderlich.");
  }

  @Test
  void sourceCreateHeaderDocumentsVisibleAsciiLimit() throws Exception {
    final JsonNode parameters =
        taskManagementDocument()
            .path("paths")
            .path("/api/v1/tasks/from-source")
            .path("post")
            .path("parameters");
    JsonNode header = MissingNode.getInstance();
    for (final JsonNode parameter : parameters) {
      if ("Idempotency-Key".equals(parameter.path("name").asText())) {
        header = parameter;
        break;
      }
    }

    assertThat(header.isMissingNode()).as("Idempotency-Key dokumentiert").isFalse();
    assertThat(header.path("required").asBoolean()).isTrue();
    assertThat(header.path("schema").path("type").asText()).isEqualTo("string");
    assertThat(header.path("schema").path("minLength").asInt()).isEqualTo(1);
    assertThat(header.path("schema").path("maxLength").asInt()).isEqualTo(128);
    assertThat(header.path("schema").path("pattern").asText()).isEqualTo("^[!-~]{1,128}$");
  }

  @Test
  void sourceCreateResponsesRequireUuidIdWithoutSuccessSchemaOnErrors() throws Exception {
    final JsonNode doc = taskManagementDocument();
    final JsonNode schemas = doc.path("components").path("schemas");
    final JsonNode responses =
        doc.path("paths").path("/api/v1/tasks/from-source").path("post").path("responses");
    for (final String statusCode : new String[] {"200", "201"}) {
      final JsonNode schema =
          responses.path(statusCode).path("content").path("application/json").path("schema");
      assertThat(schema.isMissingNode()).as(statusCode + " Success-Schema vorhanden").isFalse();
      assertThat(requiredProperties(schema, schemas))
          .as(statusCode + " braucht id: " + schema)
          .contains("id");
      assertThat(propertySchema(schema, schemas, "id").path("format").asText())
          .as(statusCode + " id ist UUID: " + schema)
          .isEqualTo("uuid");
    }
    for (final String statusCode : new String[] {"400", "401", "403", "404", "409"}) {
      final JsonNode schema =
          responses.path(statusCode).path("content").path("application/json").path("schema");
      assertThat(propertySchema(schema, schemas, "id").isMissingNode())
          .as(statusCode + " darf kein Success-id-Schema tragen: " + schema)
          .isTrue();
    }
  }

  @Test
  void taskSourceHrefIsNullable() throws Exception {
    final JsonNode href =
        taskManagementDocument()
            .path("components")
            .path("schemas")
            .path("TaskSourceModel")
            .path("properties")
            .path("href");
    assertThat(href.isMissingNode()).as("TaskSourceModel.href vorhanden").isFalse();
    assertThat(isNullable(href)).as("TaskSourceModel.href nullable: " + href).isTrue();
  }

  @Test
  @DisplayName(
      "overview_gruppe_landet_nicht_auf_404: /v3/api-docs/overview -> 200 + nicht-leeres Dokument"
          + " (KSF-UI brasi.openapi.ui.groups[0]=OVERVIEW)")
  void overview_gruppe_landet_nicht_auf_404() throws Exception {
    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    final ObjectMapper om = new ObjectMapper();

    final String body =
        mvc.perform(get("/v3/api-docs/overview"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final JsonNode paths = om.readTree(body).get("paths");
    assertThat(paths).as("overview-Gruppe liefert ein paths-Dokument").isNotNull();
    assertThat(paths.size())
        .as("die von der KSF-UI referenzierte overview-Gruppe ist nicht leer/tot")
        .isGreaterThanOrEqualTo(10);
  }

  /**
   * Nullability tolerant gegen OpenAPI 3.0 (nullable=true) und 3.1 ("null" im type-Set/-String).
   */
  private static boolean isNullable(final JsonNode schema) {
    if (schema.path("nullable").asBoolean(false)) {
      return true;
    }
    final JsonNode type = schema.path("type");
    if (type.isArray()) {
      for (final JsonNode t : type) {
        if ("null".equals(t.asText())) {
          return true;
        }
      }
    }
    return "null".equals(type.asText(""));
  }

  private JsonNode taskManagementDocument() throws Exception {
    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    final String body =
        mvc.perform(get("/v3/api-docs/" + TaskManagementOpenApiGroups.TASK_MANAGEMENT))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String snapshotPath = System.getProperty("task.openapi.snapshot.path");
    if (snapshotPath != null && !snapshotPath.isBlank()) {
      java.nio.file.Files.writeString(java.nio.file.Path.of(snapshotPath), body);
    }
    return new ObjectMapper().readTree(body);
  }

  private static Set<String> requiredProperties(final JsonNode schema, final JsonNode schemas) {
    final Set<String> required = new HashSet<>();
    for (final JsonNode name : schema.path("required")) {
      required.add(name.asText());
    }
    final String ref = schema.path("$ref").asText("");
    if (ref.startsWith("#/components/schemas/")) {
      required.addAll(requiredProperties(schemas.path(ref.substring(21)), schemas));
    }
    for (final JsonNode part : schema.path("allOf")) {
      required.addAll(requiredProperties(part, schemas));
    }
    return required;
  }

  private static JsonNode propertySchema(
      final JsonNode schema, final JsonNode schemas, final String name) {
    final JsonNode direct = schema.path("properties").path(name);
    if (!direct.isMissingNode()) {
      return direct;
    }
    final String ref = schema.path("$ref").asText("");
    if (ref.startsWith("#/components/schemas/")) {
      return propertySchema(schemas.path(ref.substring(21)), schemas, name);
    }
    for (final JsonNode part : schema.path("allOf")) {
      final JsonNode nested = propertySchema(part, schemas, name);
      if (!nested.isMissingNode()) {
        return nested;
      }
    }
    return MissingNode.getInstance();
  }

  private static boolean anySchemaPropertyHasDescription(final JsonNode schemas) {
    final var schemaNames = schemas.fieldNames();
    while (schemaNames.hasNext()) {
      final JsonNode props = schemas.path(schemaNames.next()).path("properties");
      final var propNames = props.fieldNames();
      while (propNames.hasNext()) {
        if (props.path(propNames.next()).path("description").asText("").isBlank() == false) {
          return true;
        }
      }
    }
    return false;
  }

  // ---- IT-Konfigurations-Context (voller Facade + springdoc) ---------------------------------

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
        "de.brasi.nova.taskmanagement.time",
        "de.brasi.nova.taskmanagement.participant",
        "de.brasi.nova.taskmanagement.inbox",
        "de.brasi.nova.taskmanagement.tag",
        "de.brasi.nova.taskmanagement.notification",
        "de.brasi.nova.taskmanagement.comment",
        "de.brasi.nova.taskmanagement.dictionary",
        "de.brasi.nova.taskmanagement.push",
        "de.brasi.nova.taskmanagement.configuration",
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
    KsfOpenApiConfig.class,
    // springdoc (2.8.12): Kern + WebMvc + Gruppen-Support fuer /v3/api-docs/{group}
    org.springdoc.core.properties.SpringDocConfigProperties.class,
    org.springdoc.core.configuration.SpringDocConfiguration.class,
    org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
    org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class,
  })
  @Import({
    KsfWebSecurityAutoConfiguration.class,
    SecurityContextUserAccessor.class,
    WhoAmIProbeController.class,
    // Slice 4 (Standard-Selfservice): die member-scoped Services haengen jetzt am objekt-level
    // TaskMembershipGuard (@Component im nicht-gescannten Paket ...security).
    de.brasi.nova.taskmanagement.security.TaskMembershipGuard.class,
    de.brasi.nova.taskmanagement.push.NoOpInboxSignalConfig.class,
    de.brasi.nova.taskmanagement.push.NoOpNotificationPushConfig.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "springdoc-it";
    }
  }
}
