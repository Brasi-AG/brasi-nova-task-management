package de.brasi.nova.taskmanagement.attachment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Konfiguration der Anhaenge (Intake #152), gebunden an {@code task-management.attachments}.
 *
 * <p>Alle Werte kommen aus der Konfiguration, keine Grenze und kein Pfad steht im Java-Code. Die
 * Vorgaben fuer Grenzen, Vorschaubild und Cache stehen in {@code application.properties}. Der
 * Speicher-Basispfad ({@code storage.base-dir}) und die ENTERPRISE-Mandantenliste ({@code
 * enterprise-tenant-ids}) stehen bewusst nur im Config Server je Umgebung.
 *
 * @param storage Speicherort der Dateien; {@code null}, solange kein Basispfad konfiguriert ist
 *     (die Pruefung erfolgt erst bei der Benutzung des Dateispeichers)
 * @param enterpriseTenantIds Mandanten-IDs, die unabhaengig von der Tabelle den Tarif ENTERPRISE
 *     erhalten; nie {@code null}, ohne Eigenschaft leer
 * @param maxPerTarget hoechste Zahl aktiver Anhaenge je Aufgabe bzw. je Kommentar
 * @param limits Dateigroessen-Grenzen je Tarif und harte Obergrenze
 * @param thumbnail Parameter der Vorschaubild-Erzeugung
 * @param cache Cache-Dauer der Auslieferung
 */
@ConfigurationProperties("task-management.attachments")
public record AttachmentProperties(
    Storage storage,
    List<Long> enterpriseTenantIds,
    int maxPerTarget,
    Limits limits,
    Thumbnail thumbnail,
    Cache cache) {

  /** Normalisiert die Mandantenliste: ohne Eigenschaft eine leere, unveraenderliche Liste. */
  public AttachmentProperties {
    enterpriseTenantIds =
        enterpriseTenantIds == null ? List.of() : List.copyOf(enterpriseTenantIds);
  }

  /**
   * Speicherort der Anhang-Dateien.
   *
   * @param baseDir Basispfad im Dateisystem; darf fehlen, die Pruefung erfolgt erst bei der
   *     Benutzung des Dateispeichers
   */
  public record Storage(Path baseDir) {}

  /**
   * Dateigroessen-Grenzen je Tarif. Je Tarif gilt eine Spanne (Untergrenze bis Obergrenze); eine
   * eigene Grenze aus der Tabelle wird in die Spanne geklemmt, die harte Obergrenze gilt danach
   * fuer jeden Tarif.
   *
   * @param standardMax Grenze je Datei im Tarif STANDARD (Spanne mit gleicher Unter- und
   *     Obergrenze)
   * @param expertMin Untergrenze der Spanne im Tarif EXPERT
   * @param expertMax Obergrenze der Spanne im Tarif EXPERT
   * @param enterpriseMin Untergrenze der Spanne im Tarif ENTERPRISE
   * @param enterpriseMax Obergrenze der Spanne im Tarif ENTERPRISE
   * @param hardMax harte Obergrenze je Datei fuer alle Tarife
   */
  public record Limits(
      DataSize standardMax,
      DataSize expertMin,
      DataSize expertMax,
      DataSize enterpriseMin,
      DataSize enterpriseMax,
      DataSize hardMax) {}

  /**
   * Parameter der Vorschaubild-Erzeugung.
   *
   * @param width Breite des Vorschaubilds in Pixeln
   * @param jpegQuality JPEG-Qualitaet von 0 bis 1
   * @param maxSourcePixels hoechste Pixelzahl (Breite mal Hoehe) eines Quellbilds, das dekodiert
   *     wird
   */
  public record Thumbnail(int width, float jpegQuality, long maxSourcePixels) {}

  /**
   * Cache-Dauer der Auslieferung ({@code Cache-Control: private, max-age}).
   *
   * @param contentMaxAge Dauer fuer den Dateiinhalt
   * @param thumbnailMaxAge Dauer fuer das Vorschaubild
   */
  public record Cache(Duration contentMaxAge, Duration thumbnailMaxAge) {}
}
