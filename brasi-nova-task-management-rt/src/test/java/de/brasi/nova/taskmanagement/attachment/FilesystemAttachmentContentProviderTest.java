package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.util.unit.DataSize;

/**
 * Unit-Tests fuer {@link FilesystemAttachmentContentProvider} (Intake #152, Task A4) mit {@link
 * TempDir} als Basispfad.
 *
 * <p>Vertrag: atomares Schreiben (temporaere {@code .part}-Datei im Zielordner, dann umbenennen),
 * vorhandene Datei nie ueberschreiben, bei Fehlern bleibt nichts liegen; fehlende Datei ergibt ein
 * leeres {@link Optional}; Schluessel ausserhalb des Basispfads und fehlender Basispfad ergeben
 * {@link IllegalStateException} (Basispfad erst bei Benutzung geprueft).
 */
class FilesystemAttachmentContentProviderTest {

  private static final byte[] PDF_BYTES =
      "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] OTHER = "%PDF-1.4\nanderer Inhalt\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2};
  private static final byte[] OTHER_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 9};

  @TempDir Path base;

  private FilesystemAttachmentContentProvider provider;

  @BeforeEach
  void setUp() {
    provider =
        new FilesystemAttachmentContentProvider(properties(new AttachmentProperties.Storage(base)));
  }

  @Test
  void i152_write_creates_folders_and_leaves_no_part_file() throws IOException {
    provider.writeContent("005/0/42.pdf", source(PDF_BYTES));

    assertThat(Files.readAllBytes(base.resolve("005/0/42.pdf"))).isEqualTo(PDF_BYTES);
    assertThat(listFiles(base.resolve("005/0"))).containsExactly("42.pdf");
    assertThat(readAll(provider.openContent("005/0/42.pdf"))).isEqualTo(PDF_BYTES);
  }

  @Test
  void i152_write_never_overwrites() throws IOException {
    provider.writeContent("005/0/42.pdf", source(PDF_BYTES));

    assertThatThrownBy(() -> provider.writeContent("005/0/42.pdf", source(OTHER)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Files.readAllBytes(base.resolve("005/0/42.pdf"))).isEqualTo(PDF_BYTES);
    assertThat(listFiles(base.resolve("005/0"))).containsExactly("42.pdf");
  }

  @Test
  void i152_failing_source_leaves_nothing() throws IOException {
    assertThatThrownBy(() -> provider.writeContent("005/0/43.pdf", sourceFailingAfter(1024)))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(listFiles(base.resolve("005/0"))).isEmpty();
  }

  @Test
  void i152_open_missing_is_empty() {
    assertThat(provider.openContent("005/0/99.pdf")).isEmpty();
    assertThat(provider.openThumbnail("005/0/99.pdf")).isEmpty();
  }

  @Test
  void i152_key_outside_base_rejected() {
    assertThatThrownBy(() -> provider.openContent("../x.pdf"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.writeContent("../i152-outside.pdf", source(PDF_BYTES)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(base.resolve("../i152-outside.pdf").normalize()).doesNotExist();
  }

  @Test
  void i152_unconfigured_base_fails_on_use_only() {
    // Binder liefert ohne Eigenschaft unter storage.* ein null-Storage; beides gilt als fehlend
    for (final AttachmentProperties.Storage storage :
        new AttachmentProperties.Storage[] {null, new AttachmentProperties.Storage(null)}) {
      final var p = new FilesystemAttachmentContentProvider(properties(storage)); // Konstruktion ok

      assertThatThrownBy(() -> p.openContent("005/0/1.pdf"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Speicherpfad für Anhänge ist nicht konfiguriert"
                  + " (task-management.attachments.storage.base-dir).");
    }
  }

  @Test
  void i152_delete_quietly_removes_content_and_thumbnail() throws IOException {
    provider.writeContent("005/0/44.pdf", source(PDF_BYTES));
    provider.writeThumbnail("005/0/44.pdf", JPEG);
    assertThat(listFiles(base.resolve("005/0")))
        .containsExactlyInAnyOrder("44.pdf", "44_thumb.jpeg");

    provider.deleteQuietly("005/0/44.pdf");

    assertThat(listFiles(base.resolve("005/0"))).isEmpty();
    // fehlende Dateien: kein Fehler
    assertThatCode(() -> provider.deleteQuietly("005/0/44.pdf")).doesNotThrowAnyException();
    assertThatCode(() -> provider.deleteQuietly("005/0/77.pdf")).doesNotThrowAnyException();
  }

  @Test
  void i152_thumbnail_written_next_to_content_and_never_replaced() throws IOException {
    // Zusatz zum Brief: writeThumbnail/openThumbnail aus dem Interfaces-Block (Selbstheilung A8)
    provider.writeContent("005/0/45.pdf", source(PDF_BYTES));
    provider.writeThumbnail("005/0/45.pdf", JPEG);

    assertThatCode(() -> provider.writeThumbnail("005/0/45.pdf", OTHER_JPEG))
        .doesNotThrowAnyException();
    assertThat(Files.readAllBytes(base.resolve("005/0/45_thumb.jpeg"))).isEqualTo(JPEG);
    assertThat(readAll(provider.openThumbnail("005/0/45.pdf"))).isEqualTo(JPEG);
    assertThat(listFiles(base.resolve("005/0")))
        .containsExactlyInAnyOrder("45.pdf", "45_thumb.jpeg");
  }

  private static InputStreamSource source(final byte[] content) {
    return new ByteArrayResource(content);
  }

  /** Quelle, die nach {@code limit} Bytes mit einer IOException abbricht. */
  private static InputStreamSource sourceFailingAfter(final int limit) {
    return () ->
        new InputStream() {
          private int served;

          @Override
          public int read() throws IOException {
            if (served >= limit) {
              throw new IOException("Quelle bricht ab (Test)");
            }
            served++;
            return 'x';
          }
        };
  }

  /** Dateinamen eines Ordners (auch versteckte {@code .part}-Dateien); leer, wenn er fehlt. */
  private static List<String> listFiles(final Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> p.getFileName().toString()).toList();
    }
  }

  private static byte[] readAll(final Optional<InputStream> stream) throws IOException {
    assertThat(stream).isPresent();
    try (InputStream in = stream.get()) {
      return in.readAllBytes();
    }
  }

  private static AttachmentProperties properties(final AttachmentProperties.Storage storage) {
    return new AttachmentProperties(
        storage,
        List.of(),
        20,
        new AttachmentProperties.Limits(
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(15),
            DataSize.ofMegabytes(15)),
        new AttachmentProperties.Thumbnail(600, 0.85f, 50_000_000L),
        new AttachmentProperties.Cache(Duration.ofDays(1), Duration.ofDays(10)));
  }
}
