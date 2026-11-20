package de.brasi.nova.taskmanagement.attachment;

import io.keel.framework.api.validation.EntityValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Prueft hochgeladene Dateien und bereitet sie zum Speichern vor (Intake #152).
 *
 * <p>Alles oder nichts: Zuerst werden <em>alle</em> Dateien geprueft; erst wenn jede gueltig ist,
 * werden Pruefsumme und Vorschaubild berechnet. Eine ungueltige Datei beendet den Upload mit einer
 * {@link EntityValidationException} (HTTP 400), ohne dass der Vorschaubild-Generator je aufgerufen
 * wurde. Die Pruefung liest nichts in die Datenbank und speichert nichts; sie laeuft vor der
 * Transaktion.
 *
 * <p>Reihenfolge der Pruefungen:
 *
 * <ol>
 *   <li>mindestens eine Datei
 *   <li>Zahl der aktiven Anhaenge plus neue Dateien hoechstens {@link
 *       AttachmentProperties#maxPerTarget()}
 *   <li>je Datei: Name bereinigen, Endung in der Erlaubtliste
 *   <li>je Datei: nicht leer
 *   <li>je Datei: nicht groesser als die Grenze des Tarifs ({@link
 *       AttachmentLimitPolicy#limitFor(long)})
 *   <li>je Datei: Inhalt passt zum Dateityp ({@link AttachmentFileInspector})
 * </ol>
 *
 * <p>Die Groessenpruefung steht bewusst vor der Inhaltspruefung: Bei Office-Open-XML-Dateien
 * kopiert der Inspector den Inhalt in eine temporaere Datei und verlaesst sich darauf, dass die
 * Groesse vorher begrenzt wurde.
 */
@Component
public class AttachmentUploadPreparer {

  private static final String SHA_256 = "SHA-256";

  private final AttachmentLimitPolicy limitPolicy;
  private final AttachmentFileInspector inspector;
  private final AttachmentThumbnailGenerator thumbnailGenerator;
  private final AttachmentProperties properties;

  /**
   * Konstruktor-Injektion aller Abhaengigkeiten.
   *
   * @param limitPolicy liefert die Dateigroessen-Grenze des Mandanten
   * @param inspector prueft, ob der Inhalt zum Dateityp passt
   * @param thumbnailGenerator erzeugt Vorschaubilder fuer PDF und Bilder
   * @param properties Anhang-Konfiguration (hoechste Zahl aktiver Anhaenge je Ziel)
   */
  public AttachmentUploadPreparer(
      final AttachmentLimitPolicy limitPolicy,
      final AttachmentFileInspector inspector,
      final AttachmentThumbnailGenerator thumbnailGenerator,
      final AttachmentProperties properties) {
    this.limitPolicy = limitPolicy;
    this.inspector = inspector;
    this.thumbnailGenerator = thumbnailGenerator;
    this.properties = properties;
  }

  /**
   * Meldungstext, wenn ein Ziel mehr aktive Anhaenge als erlaubt bekaeme. Gemeinsamer Text aller
   * Pruefungen der Hoechstzahl (Upload, Speichern an der Aufgabe, Speichern am Kommentar).
   *
   * @param maxPerTarget hoechste Zahl aktiver Anhaenge je Aufgabe bzw. je Kommentar
   * @return Meldungstext fuer die {@link EntityValidationException} (HTTP 400)
   */
  public static String tooManyAttachmentsMessage(final int maxPerTarget) {
    return "Höchstens " + maxPerTarget + " aktive Anhänge je Aufgabe bzw. je Kommentar.";
  }

  /**
   * Prueft alle Dateien eines Uploads und bereitet sie zum Speichern vor.
   *
   * @param tenantId Mandanten-ID des Kontexts (fuer die Tarif-Grenze)
   * @param activeCount Zahl der bereits aktiven Anhaenge des Ziels (Aufgabe bzw. Kommentar)
   * @param files hochgeladene Dateien in der Reihenfolge des Uploads
   * @return je Datei ein vorbereiteter Anhang in derselben Reihenfolge
   * @throws EntityValidationException wenn keine Datei vorliegt, das Ziel zu viele Anhaenge
   *     bekaeme, oder eine Datei ungueltig ist (Name, Endung, leer, zu gross, Inhalt); es wird
   *     nichts vorbereitet (HTTP 400)
   * @throws UncheckedIOException wenn eine gueltige Datei nicht gelesen werden kann (HTTP 500)
   */
  public List<PreparedAttachment> prepare(
      final long tenantId, final int activeCount, final List<MultipartFile> files) {
    if (files == null || files.isEmpty()) {
      throw new EntityValidationException("Es wurde keine Datei übergeben.");
    }
    final int maxPerTarget = properties.maxPerTarget();
    if ((long) activeCount + files.size() > maxPerTarget) {
      throw new EntityValidationException(tooManyAttachmentsMessage(maxPerTarget));
    }
    final AttachmentFileLimit limit = limitPolicy.limitFor(tenantId);

    final List<CheckedFile> checked = new ArrayList<>(files.size());
    for (final MultipartFile file : files) {
      checked.add(check(file, limit));
    }

    final List<PreparedAttachment> prepared = new ArrayList<>(checked.size());
    for (final CheckedFile file : checked) {
      prepared.add(complete(file));
    }
    return List.copyOf(prepared);
  }

  /** Prueft eine Datei in der festgelegten Reihenfolge; die erste Verletzung bricht ab. */
  private CheckedFile check(final MultipartFile file, final AttachmentFileLimit limit) {
    final String fileName = AttachmentFileNames.sanitize(file.getOriginalFilename());
    final String extension = AttachmentFileNames.extensionOf(fileName);
    final AttachmentFileType type =
        AttachmentFileType.fromExtension(extension).orElseThrow(() -> unsupportedType(extension));
    final long sizeBytes = file.getSize();
    if (sizeBytes == 0) {
      throw new EntityValidationException("Die Datei „" + fileName + "“ ist leer.");
    }
    if (sizeBytes > limit.maxFileBytes()) {
      throw new EntityValidationException(
          "Die Datei „"
              + fileName
              + "“ ist größer als die Grenze von "
              + limit.displaySize()
              + " des Tarifs "
              + limit.tariff().name()
              + ".");
    }
    if (!inspector.contentMatches(type, file)) {
      throw new EntityValidationException(
          "Der Inhalt der Datei „" + fileName + "“ passt nicht zum Dateityp " + type.name() + ".");
    }
    return new CheckedFile(file, fileName, type, extension, sizeBytes);
  }

  /** Meldung fuer eine Endung ausserhalb der Erlaubtliste. */
  private static EntityValidationException unsupportedType(final String extension) {
    return new EntityValidationException(
        "Der Dateityp „."
            + extension
            + "“ ist nicht erlaubt. Erlaubt sind: "
            + AttachmentFileType.allowedExtensionsText()
            + ".");
  }

  /** Berechnet Pruefsumme und Vorschaubild einer bereits geprueften Datei. */
  private PreparedAttachment complete(final CheckedFile checked) {
    final byte[] thumbnail =
        checked.type().previewable()
            ? thumbnailGenerator.generate(checked.type(), checked.file()).orElse(null)
            : null;
    return new PreparedAttachment(
        checked.fileName(),
        checked.type(),
        checked.extension(),
        checked.sizeBytes(),
        sha256Hex(checked.file()),
        checked.file(),
        thumbnail);
  }

  /** SHA-256 des Inhalts, streamend berechnet (der Inhalt wird nie ganz im Speicher gehalten). */
  private static String sha256Hex(final MultipartFile file) {
    final MessageDigest digest = newSha256();
    try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Die Pruefsumme der hochgeladenen Datei konnte nicht berechnet werden.", e);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance(SHA_256);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 gehoert zu jeder Java-Laufzeit; kein behebbarer Fehler
      throw new IllegalStateException("Hash-Verfahren " + SHA_256 + " nicht verfuegbar.", e);
    }
  }

  /** Gepruefte Datei vor der Berechnung von Pruefsumme und Vorschaubild. */
  private record CheckedFile(
      MultipartFile file,
      String fileName,
      AttachmentFileType type,
      String extension,
      long sizeBytes) {}
}
