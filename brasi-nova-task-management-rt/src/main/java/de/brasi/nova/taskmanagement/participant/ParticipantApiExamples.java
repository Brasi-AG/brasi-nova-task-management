package de.brasi.nova.taskmanagement.participant;

/**
 * OpenAPI-Beispieltexte (JSON) fuer die Operationen {@code mute}, {@code seen}, {@code
 * updateAssignees}, {@code exit} und {@code addParticipants}.
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

  /** Pfad von {@code PUT /api/v1/tasks/{id}/seen} in den Fehlerbeispielen. */
  private static final String PATH_SEEN = "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/seen";

  /** Pfad von {@code PUT /api/v1/tasks/{id}/seen} bei einer ungueltigen UUID im Pfad. */
  private static final String PATH_SEEN_INVALID_ID = "/api/v1/tasks/keine-uuid/seen";

  /** Pfad von {@code PUT /api/v1/tasks/{id}/assignees} in den Fehlerbeispielen. */
  private static final String PATH_ASSIGNEES =
      "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/assignees";

  /** Pfad von {@code POST /api/v1/tasks/{id}/exit} in den Fehlerbeispielen. */
  private static final String PATH_EXIT = "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/exit";

  /** Pfad von {@code POST /api/v1/tasks/{id}/participants} in den Fehlerbeispielen. */
  private static final String PATH_PARTICIPANTS =
      "/api/v1/tasks/3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b/participants";

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

  /**
   * 400 von {@code seen}: keine gueltige UUID im Pfad. Format gegen die Live-Antwort in {@code
   * TaskSeenMarkerIT} abgesichert.
   */
  static final String BAD_REQUEST_SEEN =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der URL-Parameter 'id' hat einen ung\u00fcltigen Wert. Ein Wert vom Typ"
          + " 'UUID' wird erwartet.\",\"path\":\""
          + PATH_SEEN_INVALID_ID
          + "\",\"errorKey\":\"NOVA_TYPE_MISMATCH\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Wert: 'keine-uuid'\"]}";

  /**
   * 404 von {@code seen}: Aufgabe unbekannt, fremder Mandant oder kein Mitglied (maskiert). Format
   * gegen die Live-Antwort in {@code TaskSeenMarkerIT} abgesichert (mit {@code traceId} und {@code
   * details}).
   */
  static final String NOT_FOUND_SEEN =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_SEEN
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.exception.EntityNotFoundException:"
          + " Aufgabe nicht gefunden.\"]}";

  /**
   * 409 von {@code seen}: nebenlaeufiger Versionskonflikt beim Speichern des versionierten {@code
   * TaskUserState}.
   */
  static final String CONFLICT_SEEN =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich ge\u00e4ndert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_SEEN
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\"]}";

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

  /**
   * 409 von {@code updateAssignees}: nebenlaeufiger Versionskonflikt beim Speichern der Aufgabe.
   */
  static final String CONFLICT_ASSIGNEES =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich ge\u00e4ndert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_ASSIGNEES
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\"}";

  /**
   * 400 von {@code mute}: das Feld value fehlt oder ist null. Die Validierung greift beim Lesen des
   * Bodys; die KSF-Fehlerbehandlung meldet daher "nicht lesbar" und legt den Validierungstext nach
   * details. Format gegen die Live-Antwort in {@code UserStateControllerIT} abgesichert.
   */
  static final String BAD_REQUEST_MUTE =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der Request-Body ist fehlerhaft formatiert oder konnte nicht gelesen"
          + " werden.\",\"path\":\""
          + PATH_MUTE
          + "\",\"errorKey\":\"NOVA_MESSAGE_NOT_READABLE\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"value ist Pflichtfeld.\"]}";

  /**
   * 409 von {@code mute}: nebenlaeufiger Versionskonflikt beim Speichern des versionierten {@code
   * TaskUserState}.
   */
  static final String CONFLICT_MUTE =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich ge\u00e4ndert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_MUTE
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\"}";

  /** 400 von {@code addParticipants}: ein Eintrag ist kein aktiver Benutzer des Mandanten. */
  static final String BAD_REQUEST_PARTICIPANTS =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Unbekannte oder nicht zugelassene Teilnehmer-Benutzer: ["
          + USER_REF_B
          + "]\",\"path\":\""
          + PATH_PARTICIPANTS
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException:"
          + " Unbekannte oder nicht zugelassene Teilnehmer-Benutzer: ["
          + USER_REF_B
          + "]\"]}";

  /**
   * 400 von {@code addParticipants}: das Feld participantIds fehlt oder ist null (ebenso null-
   * Element, mehr als 100 Eintraege). Die Validierung greift beim Lesen des Bodys; die
   * KSF-Fehlerbehandlung meldet daher "nicht lesbar" und legt den Validierungstext nach details.
   */
  static final String BAD_REQUEST_PARTICIPANTS_BODY =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der Request-Body ist fehlerhaft formatiert oder konnte nicht gelesen"
          + " werden.\",\"path\":\""
          + PATH_PARTICIPANTS
          + "\",\"errorKey\":\"NOVA_MESSAGE_NOT_READABLE\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Das Feld 'participantIds' darf nicht null sein. Verwende ein leeres"
          + " Array [] fuer einen No-Op.\"]}";

  /** 403 von {@code addParticipants}: Berechtigung TASK_WRITE fehlt. */
  static final String FORBIDDEN_PARTICIPANTS =
      ERROR_HEAD
          + "\"status\":403,\"error\":\"Forbidden\",\"message\":\""
          + FORBIDDEN_MESSAGE
          + "\",\"path\":\""
          + PATH_PARTICIPANTS
          + "\",\"errorKey\":\"NOVA_ACCESS_DENIED\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.security.permission.PermissionDeniedException:"
          + " Fehlende Permission 'task-management:TASK_WRITE' für"
          + " AssigneeController#addParticipants\"]}";

  /** 404 von {@code addParticipants}: Aufgabe unbekannt, fremder Mandant oder kein Mitglied. */
  static final String NOT_FOUND_PARTICIPANTS =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_PARTICIPANTS
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.exception.EntityNotFoundException:"
          + " Aufgabe nicht gefunden.\"]}";

  /**
   * 409 von {@code addParticipants}: nebenlaeufiger Versionskonflikt beim Speichern der
   * Teilnehmerzeile oder des persoenlichen Zustands.
   */
  static final String CONFLICT_PARTICIPANTS =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich ge\u00e4ndert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_PARTICIPANTS
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\"]}";

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
