package de.brasi.nova.taskmanagement.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des Thread-Pools fuer die asynchrone Auslieferung von Anhaengen (Intake #152),
 * gebunden an {@code task-management.attachments.streaming}.
 *
 * <p>Die Auslieferung nutzt {@code StreamingResponseBody}; Spring MVC fuehrt den Body auf einem
 * Executor aus. Damit grosse Downloads bei langsamen Clients weder den geteilten {@code
 * applicationTaskExecutor} belegen noch unbegrenzt Threads oder Warteschlange verbrauchen, gilt ein
 * eigener, begrenzter Pool (siehe {@link AttachmentStreamingConfiguration}). Alle Werte stehen als
 * Vorgabe in {@code application.properties} und lassen sich je Umgebung im Config Server
 * ueberschreiben. Die Obergrenze der Laufzeit einer Anfrage steht in der Spring-Eigenschaft {@code
 * spring.mvc.async.request-timeout}.
 *
 * @param coreSize Zahl der Kern-Threads (gleichzeitige Auslieferungen ohne Warten), mindestens 1
 * @param maxSize hoechste Zahl der Threads, mindestens {@code coreSize}; ueber die Kernzahl hinaus
 *     wachsen Threads erst, wenn die Warteschlange voll ist
 * @param queueCapacity Groesse der Warteschlange fuer wartende Auslieferungen, mindestens 0; ist
 *     sie voll und sind alle Threads belegt, scheitert die Anfrage
 * @param threadNamePrefix Namensanfang der Threads (fuer Logs und Thread-Dumps)
 */
@ConfigurationProperties("task-management.attachments.streaming")
public record AttachmentStreamingProperties(
    int coreSize, int maxSize, int queueCapacity, String threadNamePrefix) {

  /** Prueft die Werte beim Binden, damit eine falsche Konfiguration den Start verhindert. */
  public AttachmentStreamingProperties {
    if (coreSize < 1) {
      throw new IllegalArgumentException("Die Zahl der Kern-Threads muss mindestens 1 sein.");
    }
    if (maxSize < coreSize) {
      throw new IllegalArgumentException(
          "Die hoechste Thread-Zahl darf nicht kleiner als die Zahl der Kern-Threads sein.");
    }
    if (queueCapacity < 0) {
      throw new IllegalArgumentException("Die Warteschlange darf nicht negativ sein.");
    }
    if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
      throw new IllegalArgumentException("Der Namensanfang der Threads darf nicht leer sein.");
    }
  }
}
