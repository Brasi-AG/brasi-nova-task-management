package de.brasi.nova.taskmanagement.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.brasi.nova.taskmanagement.BrasiNovaApplication;
import io.keel.framework.persistence.sequence.SequenceInitializer;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Exportiert das Default-OpenAPI-Dokument {@code /v3/api-docs} fuer das OpenAPI-Gate des Brains.
 *
 * <p>Bootet den vollen Anwendungskontext der {@link BrasiNovaApplication} (gleicher
 * Komponenten-Scan, gleiche Auto-Konfiguration, {@code application.properties}) und damit dasselbe
 * HAL-, Security- und springdoc-Rendering wie produktiv. Abweichungen vom Betrieb:
 *
 * <ul>
 *   <li>kein Config-Server (die Werte aus kpl-config fehlen; JWT mit Wegwerf-Schluessel);
 *   <li>keine Datenbank: Flyway bleibt aus (wie produktiv), die KSF-Sequenz-Initialisierung ist
 *       durch einen Stub ersetzt, und die DataSource zeigt auf eine nicht erreichbare Adresse, die
 *       beim Start niemand benutzt;
 *   <li>Klassen aus {@code target/test-classes} sind aus dem Komponenten-Scan ausgeschlossen
 *       ({@link ProductionClassesOnly}), damit Test-Controller und Test-Konfigurationen nicht im
 *       Dokument landen.
 * </ul>
 *
 * <p>Damit laeuft der Export im normalen {@code mvn test} ohne MSSQL-Zugang. Er schreibt nach
 * {@code target/openapi/}:
 *
 * <ul>
 *   <li>{@code openapi.json} &ndash; das Dokument, wie {@code /v3/api-docs} es liefert;
 *   <li>{@code operations.json} &ndash; je {@code operationId} die Art {@code handwritten}
 *       (Handler-Klasse liegt im {@code -rt}-Modul) oder {@code generated} (Handler-Klasse aus
 *       einem anderen Modul bzw. Framework), ermittelt ueber die {@link
 *       RequestMappingHandlerMapping}s.
 * </ul>
 */
@SpringBootTest(
    classes = BrasiNovaApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.banner-mode=off",
      // Kein Config-Server und kein Netz beim Export.
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      // Kein DB-Zugang beim Export: Flyway aus, DataSource ohne Verbindung beim Start.
      "spring.flyway.enabled=false",
      "spring.datasource.url=jdbc:sqlserver://127.0.0.1:1;databaseName=openapi_export;encrypt=false",
      "spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver",
      "spring.datasource.hikari.connection-timeout=250",
      // Produktiv aus der Verbindung erkannt; fest gesetzt, damit beim Start keine Abfrage laeuft.
      "ksf.persistence.dialect=MSSQL",
      // Produktiv aus kpl-config: JWT-Filterkette und Mandanten-Entitaet.
      "ksf.security.jwt.enabled=true",
      "ksf.security.tenant.entity-class=de.brasi.nova.taskmanagement.refentity.Firm",
    })
@ContextConfiguration(initializers = OpenApiExportTest.ProductionClassesOnly.Registration.class)
@Execution(ExecutionMode.SAME_THREAD)
class OpenApiExportTest {

  private static final Path EXPORT_DIR = Path.of("target", "openapi");
  private static final String HANDWRITTEN = "handwritten";
  private static final String GENERATED = "generated";
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private static final String TEST_KID = "openapi-export-key";
  private static final KeyPair RSA_KEY_PAIR;
  private static Path pemTempFile;

  static {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      RSA_KEY_PAIR = generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;

  /** Ohne Datenbank: die KSF-Sequenz-Initialisierung beim Start bleibt aus (Stub). */
  @MockitoBean private SequenceInitializer sequenceInitializer;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    // Wegwerf-Schluessel nur fuer den Kontextstart (JWT-Filterkette wie in Produktion aktiv).
    final String pem =
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(RSA_KEY_PAIR.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----";
    try {
      pemTempFile = Files.createTempFile("openapi-export-jwk-", ".pem");
      Files.writeString(pemTempFile, pem, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Konnte temporaere PEM-Datei nicht erstellen.", e);
    }
    registry.add("ksf.security.jwt.jwk.default-kid", () -> TEST_KID);
    registry.add("ksf.security.jwt.jwk.keys[0].kid", () -> TEST_KID);
    registry.add(
        "ksf.security.jwt.jwk.keys[0].public-pem", () -> "file:" + pemTempFile.toAbsolutePath());
  }

  @AfterAll
  static void teardown() throws IOException {
    if (pemTempFile != null) {
      Files.deleteIfExists(pemTempFile);
    }
  }

  @Test
  @DisplayName(
      "exportiert_openapi_und_operationsarten: /v3/api-docs -> target/openapi/openapi.json +"
          + " operations.json (handwritten/generated je operationId)")
  void exportiert_openapi_und_operationsarten() throws Exception {
    final MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    final String body =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final JsonNode document = mapper.readTree(body);
    final JsonNode paths = document.path("paths");
    assertThat(paths.size()).as("dokumentierte Pfade").isGreaterThanOrEqualTo(10);
    assertThat(paths.has("/it/whoami")).as("Test-Controller ausgeschlossen").isFalse();

    final Map<String, String> kinds = handlerKinds();
    final Map<String, String> operations = new TreeMap<>();
    final List<String> unmapped = new ArrayList<>();
    paths
        .fields()
        .forEachRemaining(
            path ->
                path.getValue()
                    .fields()
                    .forEachRemaining(
                        method -> {
                          if (!HTTP_METHODS.contains(method.getKey())) {
                            return;
                          }
                          final String key = key(method.getKey(), path.getKey());
                          final String operationId = method.getValue().path("operationId").asText();
                          final String kind = kinds.get(key);
                          if (operationId.isEmpty() || kind == null) {
                            unmapped.add(key + " (" + operationId + ")");
                          } else {
                            operations.put(operationId, kind);
                          }
                        }));
    assertThat(unmapped).as("jede Operation hat operationId und Handler-Methode").isEmpty();

    final ObjectNode operationsNode = mapper.createObjectNode();
    operations.forEach(operationsNode::put);
    Files.createDirectories(EXPORT_DIR);
    Files.writeString(
        EXPORT_DIR.resolve("openapi.json"),
        mapper.writeValueAsString(document) + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        EXPORT_DIR.resolve("operations.json"),
        mapper.writeValueAsString(operationsNode) + "\n",
        StandardCharsets.UTF_8);
    assertThat(operations).as("Operationen exportiert").isNotEmpty();
  }

  /** Art je {@code METHODE pfad} aus allen Handler-Mappings des Kontexts. */
  private Map<String, String> handlerKinds() {
    final URL rtModule = codeSource(BrasiNovaApplication.class);
    final Map<String, String> kinds = new HashMap<>();
    for (final RequestMappingHandlerMapping mapping :
        webApplicationContext.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      for (final Map.Entry<RequestMappingInfo, HandlerMethod> entry :
          mapping.getHandlerMethods().entrySet()) {
        final Class<?> handler = ClassUtils.getUserClass(entry.getValue().getBeanType());
        final String kind = Objects.equals(rtModule, codeSource(handler)) ? HANDWRITTEN : GENERATED;
        Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
          methods = Set.of(RequestMethod.values());
        }
        for (final String pattern : entry.getKey().getPatternValues()) {
          for (final RequestMethod method : methods) {
            kinds.put(key(method.name(), pattern), kind);
          }
        }
      }
    }
    return kinds;
  }

  /** Schluessel ohne Regex-Teil der Pfadvariablen ({@code {id:\d+}} wird zu {@code {id}}). */
  private static String key(final String method, final String path) {
    return method.toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\{([^}:]+):[^}]*}", "{$1}");
  }

  private static URL codeSource(final Class<?> type) {
    final var source = type.getProtectionDomain().getCodeSource();
    return source == null ? null : source.getLocation();
  }

  /**
   * Schliesst Klassen aus {@code target/test-classes} vom Komponenten-Scan der Anwendung aus.
   *
   * <p>{@code @SpringBootApplication} scannt {@code de.brasi.nova}; im Test liegen dort auch
   * Test-Controller und -Konfigurationen. Der Spring-Boot-{@code TypeExcludeFilter} fragt alle
   * Beans dieses Typs; die Registrierung erfolgt vor dem Scan ueber {@link Registration}.
   */
  static final class ProductionClassesOnly extends TypeExcludeFilter {

    private static final URL TEST_CLASSES = codeSource(OpenApiExportTest.class);

    @Override
    public boolean match(
        final MetadataReader metadataReader, final MetadataReaderFactory metadataReaderFactory)
        throws IOException {
      final String className = metadataReader.getClassMetadata().getClassName();
      try {
        final Class<?> type =
            ClassUtils.forName(className, OpenApiExportTest.class.getClassLoader());
        return Objects.equals(TEST_CLASSES, codeSource(type));
      } catch (ClassNotFoundException | LinkageError e) {
        return false;
      }
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof ProductionClassesOnly;
    }

    @Override
    public int hashCode() {
      return ProductionClassesOnly.class.hashCode();
    }

    /** Registriert den Filter als Singleton, bevor der Komponenten-Scan laeuft. */
    static final class Registration
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

      @Override
      public void initialize(final ConfigurableApplicationContext context) {
        context
            .getBeanFactory()
            .registerSingleton(ProductionClassesOnly.class.getName(), new ProductionClassesOnly());
      }
    }
  }
}
