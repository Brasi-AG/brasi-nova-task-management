package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import de.brasi.nova.taskmanagement.BrasiNovaApplication;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.unit.DataSize;

/**
 * Prueft die Anhang-Konfiguration in {@code application.properties} (Intake #152, Task A2) ohne
 * Spring-Kontext: Datei per {@link PropertiesLoaderUtils} lesen, {@link AttachmentProperties} per
 * {@link Binder} binden.
 *
 * <p>Review Focus 1 und 2: Multipart- und Tomcat-Grenzen muessen 20 Dateien zu je 15 MB (plus
 * Kommentar-Teil) tragen. Basispfad und ENTERPRISE-Liste stehen nur im Config Server; im Java-Code
 * gibt es weder Speicherpfad noch die Brasi-Mandanten-ID als Literal.
 */
class AttachmentPropertiesTest {

  private static final String PREFIX = "task-management.attachments";

  private Properties raw;
  private AttachmentProperties bound;

  @BeforeEach
  void setUp() throws IOException {
    raw = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    bound =
        new Binder(new MapConfigurationPropertySource(raw))
            .bind(PREFIX, AttachmentProperties.class)
            .get();
  }

  @Test
  void i152_defaults_bound_and_secrets_absent() {
    assertThat(raw)
        .doesNotContainKey(PREFIX + ".storage.base-dir")
        .doesNotContainKey(PREFIX + ".enterprise-tenant-ids");
    assertThat(bound.enterpriseTenantIds()).isNotNull().isEmpty();
    assertThat(bound.maxPerTarget()).isEqualTo(20);
    assertThat(bound.limits().standardMax()).isEqualTo(DataSize.ofMegabytes(5));
    assertThat(bound.limits().expertMin()).isEqualTo(DataSize.ofMegabytes(5));
    assertThat(bound.limits().expertMax()).isEqualTo(DataSize.ofMegabytes(10));
    assertThat(bound.limits().enterpriseMin()).isEqualTo(DataSize.ofMegabytes(10));
    assertThat(bound.limits().enterpriseMax()).isEqualTo(DataSize.ofMegabytes(15));
    assertThat(bound.limits().hardMax()).isEqualTo(DataSize.ofMegabytes(15));
    assertThat(bound.thumbnail().width()).isEqualTo(600);
    assertThat(bound.thumbnail().jpegQuality()).isEqualTo(0.85f);
    assertThat(bound.thumbnail().maxSourcePixels()).isEqualTo(50_000_000L);
    assertThat(bound.cache().contentMaxAge()).isEqualTo(Duration.ofDays(1));
    assertThat(bound.cache().thumbnailMaxAge()).isEqualTo(Duration.ofDays(10));
  }

  @Test
  void i152_too_many_attachments_message_names_the_configured_limit() {
    assertThat(bound.tooManyAttachmentsMessage())
        .isEqualTo("Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar.");
    assertThat(
            new AttachmentProperties(
                    bound.storage(),
                    bound.enterpriseTenantIds(),
                    3,
                    bound.limits(),
                    bound.thumbnail(),
                    bound.cache())
                .tooManyAttachmentsMessage())
        .isEqualTo("Höchstens 3 aktive Anhänge je Aufgabe bzw. je Kommentar.");
  }

  @Test
  void i152_multipart_and_tomcat_limits_fit_twenty_files() {
    // Review Focus 1 und 2
    assertThat(raw.getProperty("spring.servlet.multipart.max-file-size"))
        .isEqualTo("${task-management.attachments.limits.hard-max}");
    assertThat(DataSize.parse(raw.getProperty("spring.servlet.multipart.max-request-size")))
        .isGreaterThanOrEqualTo(DataSize.ofBytes(20 * DataSize.ofMegabytes(15).toBytes()));
    assertThat(Integer.parseInt(raw.getProperty("server.tomcat.max-part-count")))
        .isGreaterThanOrEqualTo(21);
    assertThat(raw.getProperty("server.tomcat.max-swallow-size"))
        .isNotNull()
        .isEqualTo(raw.getProperty("spring.servlet.multipart.max-request-size"));
  }

  @Test
  void i152_no_path_or_brasi_id_literals_in_main_sources() throws Exception {
    // alle .java unter brasi-nova-task-management-rt/src/main/java
    final List<String> sources = allMainSources();

    assertThat(sources).isNotEmpty();
    assertThat(sources)
        .noneMatch(
            s ->
                s.contains("1000000001")
                    || s.contains("Y:\\\\")
                    || s.contains("TaskManagement\\\\"));
  }

  /** Liest den Text aller Produktiv-Quellen des rt-Moduls (Modulordner aus target/classes). */
  private static List<String> allMainSources() throws Exception {
    final URL location =
        BrasiNovaApplication.class.getProtectionDomain().getCodeSource().getLocation();
    final Path classesDir = Path.of(location.toURI());
    final Path mainJava = classesDir.getParent().getParent().resolve("src/main/java");
    assertThat(mainJava).isDirectory();
    try (Stream<Path> walk = Files.walk(mainJava)) {
      return walk.filter(p -> p.toString().endsWith(".java"))
          .map(AttachmentPropertiesTest::read)
          .toList();
    }
  }

  private static String read(final Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Quelle nicht lesbar: " + file, e);
    }
  }
}
