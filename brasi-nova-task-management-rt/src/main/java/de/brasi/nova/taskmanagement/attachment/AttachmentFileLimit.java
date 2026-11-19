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
   * Menschenlesbare Groesse fuer Meldungstexte: ganzzahlig bei vollen Megabytes ({@code "5 MB"}),
   * sonst mit einer Nachkommastelle und deutschem Dezimalkomma ({@code "12,5 MB"}). 1 MB sind 1 048
   * 576 Bytes (wie Springs {@link DataSize}).
   *
   * @return Grenze mit Einheit {@code MB}
   */
  public String displaySize() {
    final long bytesPerMegabyte = DataSize.ofMegabytes(1).toBytes();
    if (maxFileBytes % bytesPerMegabyte == 0) {
      return (maxFileBytes / bytesPerMegabyte) + " MB";
    }
    return String.format(Locale.GERMANY, "%.1f MB", (double) maxFileBytes / bytesPerMegabyte);
  }
}
