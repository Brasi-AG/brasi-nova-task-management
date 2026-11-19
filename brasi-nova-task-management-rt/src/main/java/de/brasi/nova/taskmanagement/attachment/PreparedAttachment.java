package de.brasi.nova.taskmanagement.attachment;

import org.springframework.core.io.InputStreamSource;

/**
 * Gepruefter und vorbereiteter Anhang eines Uploads (Intake #152), bereit zum Speichern.
 *
 * <p>Alle Angaben stehen nach der Pruefung durch {@link AttachmentUploadPreparer} fest: Name und
 * Endung sind bereinigt, der Typ stammt aus der Erlaubtliste, Groesse und Inhalt sind geprueft. Der
 * Inhalt selbst liegt nicht im Speicher, sondern wird ueber {@link #source()} gelesen.
 *
 * @param fileName bereinigter Dateiname (nur letzter Pfadanteil, ohne Steuerzeichen)
 * @param type gepruefter Dateityp
 * @param extension Endung in Kleinbuchstaben ohne Punkt (wie im Dateinamen, z. B. {@code jpeg})
 * @param sizeBytes Groesse des Inhalts in Bytes (groesser 0, hoechstens die Grenze des Tarifs)
 * @param sha256Hex SHA-256 des Inhalts als Hexadezimalzeichenkette in Kleinbuchstaben
 * @param source Quelle des Inhalts (die hochgeladene Datei selbst); kann mehrfach gelesen werden
 * @param thumbnailJpeg JPEG-Bytes des Vorschaubilds; {@code null}, wenn der Typ keine Vorschau hat
 *     oder die Erzeugung gescheitert ist (der Anhang bleibt trotzdem gueltig)
 */
public record PreparedAttachment(
    String fileName,
    AttachmentFileType type,
    String extension,
    long sizeBytes,
    String sha256Hex,
    InputStreamSource source,
    byte[] thumbnailJpeg) {

  /**
   * Ob ein Vorschaubild vorliegt.
   *
   * @return {@code true}, wenn {@link #thumbnailJpeg()} gesetzt ist
   */
  public boolean hasThumbnail() {
    return thumbnailJpeg != null;
  }
}
