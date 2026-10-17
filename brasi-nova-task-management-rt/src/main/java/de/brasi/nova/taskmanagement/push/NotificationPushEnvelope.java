package de.brasi.nova.taskmanagement.push;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope fuer WebSocket-Benachrichtigungen (arch-v2 §6).
 *
 * <p>Felder gemaess Vertrag:
 * {@code {type, version="1", notificationId, timestamp(UTC Z), severity, payload{text, preview}}}.
 *
 * @param type           Benachrichtigungstyp-CODE (z.B. "ASSIGN", "DUE")
 * @param version        Envelope-Version (immer {@code "1"})
 * @param notificationId externe UUID der {@code TASK_NOTIFICATION} (EXTERNALID)
 * @param timestamp      Zeitstempel der Persistierung (UTC)
 * @param severity       Schweregrad-CODE (z.B. "INFO", "WARN") oder null
 * @param payload        Nutzlast mit Text und optionaler Vorschau
 */
public record NotificationPushEnvelope(
    String type,
    String version,
    UUID notificationId,
    Instant timestamp,
    String severity,
    Payload payload) {

  /**
   * Nutzlast-Anteil des Envelopes.
   *
   * @param text    Anzeigetext der Benachrichtigung (Pflicht)
   * @param preview optionale Vorschau (z.B. Aufgabentitel)
   */
  public record Payload(String text, String preview) {}
}
