package de.brasi.nova.taskmanagement.attachment;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

/**
 * Dateisystem-Umsetzung von {@link AttachmentContentProvider} (Intake #152).
 *
 * <p>Der Basispfad kommt aus {@code task-management.attachments.storage.base-dir} (Config Server je
 * Umgebung, nie im Code). Er wird erst bei der Benutzung geprueft, damit die Anwendung, der
 * OpenAPI-Export und Integrationstests auch ohne Basispfad starten; beim Start meldet {@link
 * #verifyStorage()} einen fehlenden oder nicht beschreibbaren Pfad als Fehler im Log, ohne den
 * Start abzubrechen.
 *
 * <p>Ein Schluessel wird als {@code baseDir.resolve(key).normalize()} aufgeloest und muss unter dem
 * normalisierten Basispfad liegen; alles andere (z. B. {@code ../x.pdf}) ist ein Fehler.
 *
 * <p>Schreiben ist atomar und ueberschreibt nie: der Inhalt geht in eine temporaere Datei {@code
 * .{name}.{uuid}.part} im Zielordner, danach folgt {@link Files#move(Path, Path,
 * java.nio.file.CopyOption...)} ohne Optionen (Umbenennen im selben Ordner; ein vorhandenes Ziel
 * ergibt {@link FileAlreadyExistsException}). Die temporaere Datei wird bei jedem Fehler geloescht.
 */
@Component
public class FilesystemAttachmentContentProvider implements AttachmentContentProvider {

  private static final Logger log =
      LoggerFactory.getLogger(FilesystemAttachmentContentProvider.class);

  /** Namensanhang der temporaeren Datei beim atomaren Schreiben. */
  private static final String PART_SUFFIX = ".part";

  private static final String MISSING_BASE_DIR_MESSAGE =
      "Speicherpfad für Anhänge ist nicht konfiguriert"
          + " (task-management.attachments.storage.base-dir).";

  private final AttachmentProperties properties;

  /**
   * Konstruktor-Injektion. Der Basispfad wird hier nicht geprueft.
   *
   * @param properties Konfiguration der Anhaenge; {@code storage()} darf {@code null} sein
   */
  public FilesystemAttachmentContentProvider(final AttachmentProperties properties) {
    this.properties = properties;
  }

  /**
   * Prueft nach dem Start den Basispfad und meldet einen fehlenden, nicht vorhandenen oder nicht
   * beschreibbaren Pfad als Fehler im Log. Der Start wird nie abgebrochen, damit der OpenAPI-Export
   * und Integrationstests ohne Basispfad starten.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void verifyStorage() {
    try {
      final Path baseDir = configuredBaseDir();
      if (baseDir == null) {
        log.error(
            "Speicherpfad fuer Anhaenge ist nicht konfiguriert"
                + " (task-management.attachments.storage.base-dir);"
                + " Hochladen und Abrufen von Anhaengen schlaegt fehl.");
      } else if (!Files.isDirectory(baseDir) || !Files.isWritable(baseDir)) {
        log.error(
            "Speicherpfad fuer Anhaenge {} ist kein vorhandenes, beschreibbares Verzeichnis;"
                + " Hochladen von Anhaengen schlaegt fehl.",
            baseDir);
      } else {
        log.info("Speicherpfad fuer Anhaenge: {}", baseDir);
      }
    } catch (RuntimeException e) {
      log.error("Pruefung des Speicherpfads fuer Anhaenge fehlgeschlagen: {}", e.toString());
    }
  }

  @Override
  public void writeContent(final String storageKey, final InputStreamSource source) {
    final Path target = resolve(storageKey);
    final boolean written;
    try {
      written =
          writeAtomically(
              target,
              out -> {
                try (InputStream in = source.getInputStream()) {
                  in.transferTo(out);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Die Anhang-Datei konnte nicht geschrieben werden: " + storageKey, e);
    }
    if (!written) {
      throw new IllegalStateException(
          "Die Anhang-Datei existiert bereits und wird nicht überschrieben: " + storageKey);
    }
    log.debug("Anhang-Datei {} geschrieben.", storageKey);
  }

  @Override
  public void writeThumbnail(final String contentKey, final byte[] jpeg) {
    Objects.requireNonNull(jpeg, "jpeg");
    final String thumbnailKey = AttachmentStorageKeys.thumbnailKey(contentKey);
    final Path target = resolve(thumbnailKey);
    final boolean written;
    try {
      written = writeAtomically(target, out -> out.write(jpeg));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Das Vorschaubild konnte nicht geschrieben werden: " + thumbnailKey, e);
    }
    if (written) {
      log.debug("Vorschaubild {} geschrieben.", thumbnailKey);
    } else {
      log.debug("Vorschaubild {} existiert bereits und bleibt unveraendert.", thumbnailKey);
    }
  }

  @Override
  public Optional<InputStream> openContent(final String storageKey) {
    return open(storageKey);
  }

  @Override
  public Optional<InputStream> openThumbnail(final String contentKey) {
    return open(AttachmentStorageKeys.thumbnailKey(contentKey));
  }

  @Override
  public void deleteQuietly(final String contentKey) {
    if (contentKey == null || contentKey.isBlank()) {
      return;
    }
    deleteFileQuietly(contentKey);
    deleteFileQuietly(AttachmentStorageKeys.thumbnailKey(contentKey));
  }

  private Optional<InputStream> open(final String key) {
    final Path file = resolve(key);
    try {
      return Optional.of(Files.newInputStream(file));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("Die Anhang-Datei konnte nicht geöffnet werden: " + key, e);
    }
  }

  private void deleteFileQuietly(final String key) {
    try {
      if (Files.deleteIfExists(resolve(key))) {
        log.debug("Anhang-Datei {} geloescht.", key);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Anhang-Datei {} konnte nicht geloescht werden: {}", key, e.toString());
    }
  }

  /**
   * Schreibt atomar: temporaere Datei im Zielordner, dann Umbenennen ohne Optionen.
   *
   * @return {@code true}, wenn die Datei geschrieben wurde; {@code false}, wenn das Ziel bereits
   *     existiert (dann bleibt es unveraendert)
   */
  private static boolean writeAtomically(final Path target, final ContentWriter writer)
      throws IOException {
    final Path folder = target.getParent();
    Files.createDirectories(folder);
    final Path partFile =
        folder.resolve("." + target.getFileName() + "." + UUID.randomUUID() + PART_SUFFIX);
    try {
      try (OutputStream out = Files.newOutputStream(partFile, CREATE_NEW, WRITE)) {
        writer.writeTo(out);
      }
      try {
        Files.move(partFile, target);
        return true;
      } catch (FileAlreadyExistsException e) {
        return false;
      }
    } finally {
      deletePartFile(partFile);
    }
  }

  /** Loescht die temporaere Datei, falls sie noch existiert; ein Fehler wird nur protokolliert. */
  private static void deletePartFile(final Path partFile) {
    try {
      Files.deleteIfExists(partFile);
    } catch (IOException | RuntimeException e) {
      log.warn("Temporaere Datei {} konnte nicht geloescht werden: {}", partFile, e.toString());
    }
  }

  /** Loest einen Schluessel unter dem Basispfad auf und lehnt Pfade ausserhalb davon ab. */
  private Path resolve(final String key) {
    final Path baseDir = configuredBaseDir();
    if (baseDir == null) {
      throw new IllegalStateException(MISSING_BASE_DIR_MESSAGE);
    }
    final Path resolved;
    try {
      resolved = baseDir.resolve(key).normalize();
    } catch (InvalidPathException e) {
      throw new IllegalStateException("Der Speicherschlüssel ist kein gültiger Pfad: " + key, e);
    }
    if (resolved.equals(baseDir) || !resolved.startsWith(baseDir)) {
      throw new IllegalStateException(
          "Der Speicherschlüssel liegt außerhalb des Speicherpfads: " + key);
    }
    return resolved;
  }

  /** Normalisierter, absoluter Basispfad; {@code null}, wenn keiner konfiguriert ist. */
  private Path configuredBaseDir() {
    final AttachmentProperties.Storage storage = properties.storage();
    if (storage == null || storage.baseDir() == null) {
      return null;
    }
    return storage.baseDir().toAbsolutePath().normalize();
  }

  /** Schreibt den Inhalt einer Datei in den Strom der temporaeren Datei. */
  @FunctionalInterface
  private interface ContentWriter {

    void writeTo(OutputStream out) throws IOException;
  }
}
