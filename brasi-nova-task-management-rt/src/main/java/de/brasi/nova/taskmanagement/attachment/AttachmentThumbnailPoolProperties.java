package de.brasi.nova.taskmanagement.attachment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des begrenzten Thread-Pools fuer die Vorschaubild-Erzeugung (Intake #152), gebunden
 * an {@code task-management.attachments.thumbnail} (neben Breite, Qualitaet und Pixel-Obergrenze in
 * {@link AttachmentProperties.Thumbnail}).
 *
 * <p>PDF-Rendering und Bild-Dekodierung laufen nicht im Request-Thread, sondern auf einem eigenen
 * Pool mit Zeitlimit (siehe {@link AttachmentThumbnailGenerator}): Die Poolgroesse begrenzt die
 * Zahl gleichzeitig laufender Erzeugungen und damit Rechenzeit und Speicher, die Warteschlange die
 * Zahl wartender. Alle Werte stehen als Vorgabe in {@code application.properties} und lassen sich
 * je Umgebung im Config Server ueberschreiben.
 *
 * @param poolSize Zahl der Threads (gleichzeitige Erzeugungen), mindestens 1
 * @param queueCapacity Groesse der Warteschlange fuer wartende Erzeugungen, mindestens 0; ist sie
 *     voll und sind alle Threads belegt, entsteht kein Vorschaubild
 * @param timeout hoechste Wartezeit des Aufrufers auf ein Vorschaubild (einschliesslich der Zeit in
 *     der Warteschlange); positiv
 * @param threadNamePrefix Namensanfang der Threads (fuer Logs und Thread-Dumps)
 */
@ConfigurationProperties("task-management.attachments.thumbnail")
public record AttachmentThumbnailPoolProperties(
    int poolSize, int queueCapacity, Duration timeout, String threadNamePrefix) {

  /** Prueft die Werte beim Binden, damit eine falsche Konfiguration den Start verhindert. */
  public AttachmentThumbnailPoolProperties {
    if (poolSize < 1) {
      throw new IllegalArgumentException(
          "Die Zahl der Threads fuer Vorschaubilder muss mindestens 1 sein.");
    }
    if (queueCapacity < 0) {
      throw new IllegalArgumentException(
          "Die Warteschlange fuer Vorschaubilder darf nicht negativ sein.");
    }
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Das Zeitlimit fuer Vorschaubilder muss positiv sein.");
    }
    if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
      throw new IllegalArgumentException(
          "Der Namensanfang der Threads fuer Vorschaubilder darf nicht leer sein.");
    }
  }
}
