package de.brasi.nova.taskmanagement.attachment;

import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Ergebnis eines Abrufs von Inhalt oder Vorschaubild eines Anhangs (Intake #152).
 *
 * <p>Der Strom ist beim Erzeugen bereits geoeffnet (im Service, synchron im Request), damit eine
 * fehlende Datei als 404 entsteht, bevor die Antwort beginnt; der Controller kopiert ihn nur und
 * schliesst ihn danach. Bei {@code notModified} gibt es keinen Strom (Antwort 304).
 *
 * @param notModified ob der {@code If-None-Match} des Clients passt (Antwort 304 ohne Inhalt)
 * @param etag schwacher ETag der Ressource (siehe {@link AttachmentEtags})
 * @param mimeType Inhaltsart der Antwort; {@code null} bei {@code notModified}
 * @param contentLength Laenge in Bytes; {@code -1}, wenn unbekannt oder bei {@code notModified}
 * @param fileName Dateiname fuer {@code Content-Disposition}; {@code null} ohne Dateinamen
 * @param stream geoeffneter Strom des Inhalts (vom Empfaenger zu schliessen); {@code null} bei
 *     {@code notModified}
 * @param maxAge Cache-Dauer ({@code Cache-Control: private, max-age})
 */
public record AttachmentDelivery(
    boolean notModified,
    String etag,
    String mimeType,
    long contentLength,
    String fileName,
    InputStream stream,
    Duration maxAge) {

  /** Unbekannte Laenge. */
  static final long UNKNOWN_LENGTH = -1L;

  /** Prueft die Pflichtangaben: ETag und Cache-Dauer immer, Strom und Art ausser bei 304. */
  public AttachmentDelivery {
    Objects.requireNonNull(etag, "etag");
    Objects.requireNonNull(maxAge, "maxAge");
    if (!notModified) {
      Objects.requireNonNull(mimeType, "mimeType");
      Objects.requireNonNull(stream, "stream");
    }
  }

  /**
   * Antwort 304: der Client hat den aktuellen Stand bereits.
   *
   * @param etag aktueller ETag
   * @param maxAge Cache-Dauer
   * @return Ergebnis ohne Strom
   */
  static AttachmentDelivery unchanged(final String etag, final Duration maxAge) {
    return new AttachmentDelivery(true, etag, null, UNKNOWN_LENGTH, null, null, maxAge);
  }
}
