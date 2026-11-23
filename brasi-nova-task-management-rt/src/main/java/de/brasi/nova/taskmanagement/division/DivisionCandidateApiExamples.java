package de.brasi.nova.taskmanagement.division;

/**
 * OpenAPI-Beispieltexte (JSON) der Operation {@code searchDivisionCandidates} ({@code GET
 * /api/v1/tasks/divisions}, Intake #190).
 *
 * <p>Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Die Fehlerbeispiele
 * bilden die echte Antwort der KSF-Fehlerbehandlung ab (Schema {@code ApiError} mit {@code
 * errorKey}, Karte {@code ksf/error-format}), das 401-Beispiel die problem+json-Antwort der
 * Security-Filterkette. Die Fehlertexte sind wortgleich mit {@link DivisionCandidateService}; wer
 * dort einen Text aendert, zieht das Beispiel nach.
 */
final class DivisionCandidateApiExamples {

  private DivisionCandidateApiExamples() {}

  /** Pfad der Operation in den Fehlerbeispielen. */
  private static final String PATH = "/api/v1/tasks/divisions";

  /** Kopf der Fehlerbeispiele im {@code ApiError}-Format (Zeitstempel ohne Zeitzone). */
  private static final String ERROR_HEAD = "{\"timestamp\":\"2026-11-23T10:00:00.123456\",";

  /** Trace-ID der Fehlerbeispiele. */
  private static final String TRACE = "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",";

  /** Beispiel-UUID der Abteilung Vertrieb. */
  private static final String SALES_REF = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f";

  /** Beispiel-UUID der Abteilung Vertrieb Sued. */
  private static final String SALES_SOUTH_REF = "6e2d1b4f-3c5a-4d7e-9fa0-1b2c3d4e5f60";

  /** Beispiel-UUID einer Abteilung ohne Namen. */
  private static final String UNNAMED_REF = "7f3e2c5a-4d6b-4e8f-a0b1-2c3d4e5f6071";

  /** 200 ohne Parameter: alle aktiven Abteilungen, eine davon noch ohne Namen (zuletzt). */
  static final String ALL =
      "{\"items\":[{\"divisionId\":\""
          + SALES_REF
          + "\",\"name\":\"Vertrieb\",\"memberCount\":12},"
          + "{\"divisionId\":\""
          + SALES_SOUTH_REF
          + "\",\"name\":\"Vertrieb Süd\",\"memberCount\":4},"
          + "{\"divisionId\":\""
          + UNNAMED_REF
          + "\",\"name\":null,\"memberCount\":3}]}";

  /** 200 mit {@code q=süd}: Teiltreffer ueber den Namen, unbenannte fehlen. */
  static final String SEARCH =
      "{\"items\":[{\"divisionId\":\""
          + SALES_SOUTH_REF
          + "\",\"name\":\"Vertrieb Süd\",\"memberCount\":4}]}";

  /** 200 mit {@code ids}: Vorschau bekannter Abteilungen, die unbekannte zweite UUID fehlt. */
  static final String PREVIEW =
      "{\"items\":[{\"divisionId\":\""
          + SALES_REF
          + "\",\"name\":\"Vertrieb\",\"memberCount\":12}]}";

  /** Text der 400 bei {@code limit} ausserhalb des Bereichs (wie {@link DivisionCandidateService}). */
  private static final String TEXT_LIMIT =
      "Der Parameter 'limit' muss zwischen 1 und 100 liegen, ist aber 0.";

  /** Text der 400 bei zu vielen {@code ids} (wie {@link DivisionCandidateService}). */
  private static final String TEXT_IDS = "Der Parameter 'ids' darf hoechstens 50 Eintraege enthalten.";

  /** 400: {@code limit} ausserhalb von 1 bis 100. */
  static final String BAD_REQUEST_LIMIT =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\",\"message\":\""
          + TEXT_LIMIT
          + "\",\"path\":\""
          + PATH
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException: "
          + TEXT_LIMIT
          + "\"]}";

  /** 400: mehr als 50 {@code ids}. */
  static final String BAD_REQUEST_IDS =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\",\"message\":\""
          + TEXT_IDS
          + "\",\"path\":\""
          + PATH
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException: "
          + TEXT_IDS
          + "\"]}";

  /** 401: problem+json der Security-Filterkette (kein oder ungueltiges JWT). */
  static final String UNAUTHORIZED =
      "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
          + "\"detail\":\"Authentifizierung erforderlich\"}";

  /** 403: Berechtigung TASK_READ fehlt. */
  static final String FORBIDDEN =
      ERROR_HEAD
          + "\"status\":403,\"error\":\"Forbidden\",\"message\":\"Zugriff verweigert. Sie haben"
          + " nicht die erforderlichen Berechtigungen, um diese Aktion auszuführen.\",\"path\":\""
          + PATH
          + "\",\"errorKey\":\"NOVA_ACCESS_DENIED\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.security.permission.PermissionDeniedException:"
          + " Fehlende Permission 'task-management:TASK_READ' für"
          + " DivisionCandidateController#searchDivisionCandidates\"]}";
}
