package de.brasi.nova.taskmanagement.push;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope fuer WebSocket-Benachrichtigungen (arch-v2 §6, arch-v1 §WebSocket-Protokoll).
 *
 * <p>{@code type} ist der feste ENVELOPE-Diskriminator {@code "notification"} (unterscheidet diese
 * Nachricht von z.B. {@code snapshot-invalidated}); die fachliche Benachrichtigungs-KATEGORIE
 * (ASSIGN/REPLY/DUE/...) steht separat in {@code category}.
 *
 * @param type           fester Envelope-Diskriminator, IMMER {@code "notification"}
 * @param version        Envelope-Version (immer {@code "1"})
 * @param notificationId externe UUID der {@code TASK_NOTIFICATION} (EXTERNALID)
 * @param timestamp      Zeitstempel der Persistierung (UTC)
 * @param category       Benachrichtigungstyp-CODE (z.B. "ASSIGN", "DUE")
 * @param severity       Schweregrad-CODE (z.B. "INFO", "WARN") oder null
 * @param payload        Nutzlast mit Text und optionaler Vorschau
 */
public record NotificationPushEnvelope(
    String type,
    String version,
    UUID notificationId,
    Instant timestamp,
    String category,
    String severity,
    Payload payload) {

  /** Fester Envelope-Diskriminator fuer Benachrichtigungen. */
  public static final String TYPE_NOTIFICATION = "notification";

  /** Aktuelle Envelope-Version. */
  public static final String VERSION = "1";

  /**
   * Factory fuer einen Benachrichtigungs-Envelope mit festem {@code type="notification"} und
   * {@code version="1"}.
   *
   * @param notificationId externe UUID der Benachrichtigung
   * @param timestamp      Zeitstempel (UTC)
   * @param category       Benachrichtigungstyp-CODE
   * @param severity       Schweregrad-CODE oder null
   * @param text           Anzeigetext
   * @param preview        optionale Vorschau
   * @return der Envelope
   */
  public static NotificationPushEnvelope of(
      final UUID notificationId,
      final Instant timestamp,
      final String category,
      final String severity,
      final String text,
      final String preview) {
    return new NotificationPushEnvelope(
        TYPE_NOTIFICATION, VERSION, notificationId, timestamp, category, severity,
        new Payload(text, preview));
  }

  /**
   * Nutzlast-Anteil des Envelopes.
   *
   * @param text    Anzeigetext der Benachrichtigung (Pflicht)
   * @param preview optionale Vorschau (z.B. Aufgabentitel)
   */
  public record Payload(String text, String preview) {}
}
