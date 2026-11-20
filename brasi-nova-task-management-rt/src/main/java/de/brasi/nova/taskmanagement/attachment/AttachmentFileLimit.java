package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff;
import java.util.Locale;
import org.springframework.util.unit.DataSize;

/**
 * Wirksame Dateigroessen-Grenze eines Mandanten fuer Anhaenge.
 *
 * @param tariff Tarif, aus dem die Grenze stammt
 * @param maxFileBytes hoechste erlaubte Groesse je Datei in Bytes (nach Klemmen auf die Tarifspanne
 *     und harter Obergrenze)
 */
public record AttachmentFileLimit(AttachmentTariff tariff, long maxFileBytes) {

  /**
   * Menschenlesbare Groesse der Grenze fuer Meldungstexte, siehe {@link #displaySize(long)}.
   *
   * @return Grenze mit Einheit {@code MB}
   */
  public String displaySize() {
    return displaySize(maxFileBytes);
  }

  /**
   * Menschenlesbare Groesse fuer Meldungstexte: ganzzahlig bei vollen Megabytes ({@code "5 MB"}),
   * sonst mit einer Nachkommastelle und deutschem Dezimalkomma ({@code "12,5 MB"}). 1 MB sind 1 048
   * 576 Bytes (wie Springs {@link DataSize}). Unabhaengig von einem Tarif, etwa fuer die harte
   * Obergrenze.
   *
   * @param bytes Groesse in Bytes
   * @return Groesse mit Einheit {@code MB}
   */
  public static String displaySize(final long bytes) {
    final long bytesPerMegabyte = DataSize.ofMegabytes(1).toBytes();
    if (bytes % bytesPerMegabyte == 0) {
      return (bytes / bytesPerMegabyte) + " MB";
    }
    return String.format(Locale.GERMANY, "%.1f MB", (double) bytes / bytesPerMegabyte);
  }
}
