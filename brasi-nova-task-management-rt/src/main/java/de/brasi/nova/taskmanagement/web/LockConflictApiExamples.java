package de.brasi.nova.taskmanagement.web;

/**
 * OpenAPI-Texte und -Beispiele (JSON) fuer die 409 eines Sperrkonflikts der Datenbank an den
 * schreibenden Endpunkten ({@link LockConflictTranslationAspect}, Intake #167).
 *
 * <p>Die Beispiele sind Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Sie
 * bilden die echte Antwort der KSF-Fehlerbehandlung fuer eine {@code BusinessRuleException} ab
 * (Schema {@code ApiError}, {@code errorKey} {@code NOVA_BUSINESS_RULE_VIOLATION}, Karte {@code
 * ksf/error-format}), in derselben Feldauswahl wie die uebrigen 409-Beispiele des Moduls.
 */
public final class LockConflictApiExamples {

  private LockConflictApiExamples() {}

  /** Beschreibung der 409 an Endpunkten, die bisher keine 409 dokumentierten. */
  public static final String DESCRIPTION =
      "Gleichzeitige Aenderung: ein paralleler Schreibzugriff hat die Datenbank-Transaktion als"
          + " Sperrkonflikt (Deadlock) abgebrochen; nichts wurde geaendert. Die Anfrage gegen den"
          + " frischen Stand wiederholen.";

  /** Ergaenzung der Beschreibung an Endpunkten, die schon eine 409 dokumentieren. */
  public static final String DESCRIPTION_SUFFIX =
      " Ebenso bei einem Sperrkonflikt der Datenbank (Deadlock) durch einen parallelen"
          + " Schreibzugriff: Meldung „"
          + LockConflictTranslationAspect.MESSAGE
          + "“, nichts wurde geaendert, die Anfrage wiederholen.";

  /** Beispiel-UUID einer Aufgabe in den Pfaden. */
  private static final String TASK_ID = "3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b";

  /** Beispiel-UUID eines Tags in den Pfaden. */
  private static final String TAG_ID = "aa0e8400-e29b-41d4-a716-446655440010";

  /** Beispiel-UUID einer Benachrichtigung in den Pfaden. */
  private static final String NOTIFICATION_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  /** Kopf der Fehlerbeispiele im {@code ApiError}-Format (Zeitstempel ohne Zeitzone). */
  private static final String HEAD =
      "{\"timestamp\":\"2026-11-18T10:00:00.123456\",\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\""
          + LockConflictTranslationAspect.MESSAGE
          + "\",\"path\":\"";

  /** Schluss der Fehlerbeispiele. */
  private static final String TAIL = "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\"}";

  /** 409 von {@code POST /api/v1/tasks}. */
  public static final String TASK_CREATE = HEAD + "/api/v1/tasks" + TAIL;

  /** 409 von {@code PATCH /api/v1/tasks/{id}}. */
  public static final String TASK_PATCH = HEAD + "/api/v1/tasks/" + TASK_ID + TAIL;

  /** 409 von {@code PUT /api/v1/tasks/{id}/pin}. */
  public static final String PIN = HEAD + "/api/v1/tasks/" + TASK_ID + "/pin" + TAIL;

  /** 409 von {@code PUT /api/v1/tasks/{id}/flag}. */
  public static final String FLAG = HEAD + "/api/v1/tasks/" + TASK_ID + "/flag" + TAIL;

  /** 409 von {@code PUT /api/v1/tasks/{id}/seen}. */
  public static final String SEEN = HEAD + "/api/v1/tasks/" + TASK_ID + "/seen" + TAIL;

  /** 409 von {@code PUT /api/v1/notifications/read-all}. */
  public static final String NOTIFICATIONS_READ_ALL =
      HEAD + "/api/v1/notifications/read-all" + TAIL;

  /** 409 von {@code PUT /api/v1/notifications/{id}/read}. */
  public static final String NOTIFICATION_READ =
      HEAD + "/api/v1/notifications/" + NOTIFICATION_ID + "/read" + TAIL;

  /** 409 von {@code POST /api/v1/tags}. */
  public static final String TAG_CREATE = HEAD + "/api/v1/tags" + TAIL;

  /** 409 von {@code PUT /api/v1/tags/{id}}. */
  public static final String TAG_UPDATE = HEAD + "/api/v1/tags/" + TAG_ID + TAIL;

  /** 409 von {@code POST /api/v1/tags/{id}/deactivate}. */
  public static final String TAG_DEACTIVATE =
      HEAD + "/api/v1/tags/" + TAG_ID + "/deactivate" + TAIL;
}
