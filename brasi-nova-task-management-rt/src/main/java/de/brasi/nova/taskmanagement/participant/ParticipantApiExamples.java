package de.brasi.nova.taskmanagement.participant;

/**
 * OpenAPI-Beispieltexte (JSON) fuer die Operationen {@code mute}, {@code updateAssignees} und
 * {@code exit}.
 *
 * <p>Die Beispiele sind Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Die
 * Fehlerbeispiele bilden die echte Antwort der KSF-Fehlerbehandlung ab (Schema {@code ApiError} mit
 * {@code errorKey}); das 401-Beispiel die problem+json-Antwort der Security-Filterkette. Die
 * Schemas liegen in den Komponenten und werden hier nicht veraendert: Eine Aenderung gemeinsamer
 * Komponenten wuerde die Fingerprints der Nachbar-Operationen in der OpenAPI-Basisliste
 * verschieben.
 */
final class ParticipantApiExamples {

  private ParticipantApiExamples() {}

  /** Beispiel-UUID eines Benutzers (Zuweisung, Uebergabeziel). */
  static final String USER_REF_A = "550e8400-e29b-41d4-a716-446655440000";

  /** Zweite Beispiel-UUID eines Benutzers. */
  static final String USER_REF_B = "6a1e2b3c-4d5e-4f60-8172-93a4b5c6d7e8";

  /** Pfad von {@code PUT /api/v1/tasks/{id}/mute} in den Fehlerbeispielen. */
  private static final String PATH_MUTE = "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/mute";

  /** Pfad von {@code PUT /api/v1/tasks/{id}/assignees} in den Fehlerbeispielen. */
  private static final String PATH_ASSIGNEES =
      "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/assignees";

  /** Pfad von {@code POST /api/v1/tasks/{id}/exit} in den Fehlerbeispielen. */
  private static final String PATH_EXIT = "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/exit";

  /** Kopf der Fehlerbeispiele im {@code ApiError}-Format (Zeitstempel ohne Zeitzone). */
  private static final String ERROR_HEAD = "{\"timestamp\":\"2026-11-17T10:00:00.123456\",";

  /** Meldung der KSF-Fehlerbehandlung bei fehlender Berechtigung. */
  private static final String FORBIDDEN_MESSAGE =
      "Zugriff verweigert. Sie haben nicht die erforderlichen Berechtigungen,"
          + " um diese Aktion auszuführen.";

  /** 401: problem+json der Security-Filterkette (kein oder ungueltiges JWT). */
  static final String UNAUTHORIZED =
      "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
          + "\"detail\":\"Authentifizierung erforderlich\"}";

  /** 404 von {@code mute}: Aufgabe unbekannt, fremder Mandant oder kein Mitglied (maskiert). */
  static final String NOT_FOUND_MUTE =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_MUTE
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\"}";

  /** 400 von {@code updateAssignees}: unbekannter Benutzer in der Ziel-Menge. */
  static final String BAD_REQUEST_ASSIGNEES =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Unbekannte oder nicht zugelassene Assignee-Benutzer: ["
          + USER_REF_B
          + "]\",\"path\":\""
          + PATH_ASSIGNEES
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\"}";

  /** 403 von {@code updateAssignees}: Berechtigung TASK_WRITE fehlt. */
  static final String FORBIDDEN_ASSIGNEES =
      ERROR_HEAD
          + "\"status\":403,\"error\":\"Forbidden\",\"message\":\""
          + FORBIDDEN_MESSAGE
          + "\",\"path\":\""
          + PATH_ASSIGNEES
          + "\",\"errorKey\":\"NOVA_ACCESS_DENIED\"}";

  /** 404 von {@code updateAssignees}: Aufgabe unbekannt oder fremder Mandant (maskiert). */
  static final String NOT_FOUND_ASSIGNEES =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_ASSIGNEES
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\"}";

  /** 400 von {@code exit}: unbekanntes oder fremdes Uebergabeziel. */
  static final String BAD_REQUEST_EXIT =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Unbekannte oder nicht zugelassene Uebergabeziele: ["
          + USER_REF_B
          + "]\",\"path\":\""
          + PATH_EXIT
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\"}";

  /** 403 von {@code exit}: Uebergabe an ein Nicht-WRITE-Mitglied ohne TASK_WRITE. */
  static final String FORBIDDEN_EXIT =
      ERROR_HEAD
          + "\"status\":403,\"error\":\"Forbidden\",\"message\":\""
          + FORBIDDEN_MESSAGE
          + "\",\"path\":\""
          + PATH_EXIT
          + "\",\"errorKey\":\"NOVA_ACCESS_DENIED\"}";

  /** 404 von {@code exit}: Aufgabe unbekannt, fremder Mandant oder kein aktives Mitglied. */
  static final String NOT_FOUND_EXIT =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_EXIT
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\"}";

  /** 409 von {@code exit}: der Ersteller kann die Aufgabe nicht ohne Verbleib verlassen. */
  static final String CONFLICT_EXIT =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Der Ersteller kann die Aufgabe nicht verlassen.\",\"path\":\""
          + PATH_EXIT
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\"}";
}
