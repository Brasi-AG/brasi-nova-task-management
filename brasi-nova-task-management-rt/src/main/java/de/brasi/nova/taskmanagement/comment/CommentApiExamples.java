package de.brasi.nova.taskmanagement.comment;

/**
 * OpenAPI-Beispieltexte (JSON) fuer die Operationen {@code createComment} ({@code POST
 * /api/v1/tasks/{id}/comments}) und {@code getComments} ({@code GET} auf demselben Pfad).
 *
 * <p>Die Beispiele sind Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Die
 * Fehlerbeispiele bilden die echte Antwort der KSF-Fehlerbehandlung ab (Schema {@code ApiError} mit
 * {@code errorKey}, Karte {@code ksf/error-format}); das 401-Beispiel die problem+json-Antwort der
 * Security-Filterkette. {@code CommentControllerIT} haelt sie gegen die Live-Antworten fest. Die
 * Schemas liegen in den Komponenten und werden hier nicht veraendert: Eine Aenderung gemeinsamer
 * Komponenten wuerde die Fingerprints der Nachbar-Operationen in der OpenAPI-Basisliste
 * verschieben.
 */
final class CommentApiExamples {

  private CommentApiExamples() {}

  /** Beispiel-UUID einer erwaehnten Person. */
  static final String USER_REF_A = "550e8400-e29b-41d4-a716-446655440000";

  /** Beispiel-UUID der Aufgabe in den Antworten. */
  static final String TASK_REF = "3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b";

  /** Beispiel-UUID des Eltern-Kommentars. */
  static final String PARENT_COMMENT_REF = "660e8400-e29b-41d4-a716-446655440000";

  /**
   * Pfad von {@code POST} und {@code GET /api/v1/tasks/{id}/comments} in den Fehlerbeispielen (401
   * und 404 sind fuer beide gleich).
   */
  private static final String PATH_COMMENTS = "/api/v1/tasks/" + TASK_REF + "/comments";

  /** Kopf der Fehlerbeispiele im {@code ApiError}-Format (Zeitstempel ohne Zeitzone). */
  private static final String ERROR_HEAD = "{\"timestamp\":\"2026-11-17T10:00:00.123456\",";

  /** Anfrage: Kommentar ohne Eltern-Kommentar und ohne Erwaehnung. */
  static final String REQUEST_TOP_LEVEL = "{\"text\":\"Bitte bis Ende der Woche erledigen.\"}";

  /** Anfrage: Kommentar mit Erwaehnung einer Person. */
  static final String REQUEST_WITH_MENTION =
      "{\"text\":\"@Anna Bitte pruefen.\",\"mentions\":[\"" + USER_REF_A + "\"]}";

  /** Anfrage: Antwort auf einen Kommentar. */
  static final String REQUEST_REPLY =
      "{\"text\":\"Erledigt, danke.\",\"parentCommentId\":\"" + PARENT_COMMENT_REF + "\"}";

  /**
   * 201: der neue Kommentar als Antwort auf einen Kommentar. Der JSON-Weg legt keine Anhaenge an:
   * {@code attachments} ist leer (Intake #152).
   */
  static final String CREATED_REPLY =
      "{\"id\":\"770e8400-e29b-41d4-a716-446655440002\",\"taskId\":\""
          + TASK_REF
          + "\",\"authorRef\":\"880e8400-e29b-41d4-a716-446655440003\",\"parentCommentId\":\""
          + PARENT_COMMENT_REF
          + "\",\"text\":\"Erledigt, danke.\",\"edited\":false,"
          + "\"createdAt\":\"2026-11-17T10:00:00Z\",\"isNew\":false,\"attachments\":[]}";

  /**
   * 201: der neue Kommentar ohne Eltern-Kommentar ({@code parentCommentId} ist {@code null}), ohne
   * Anhaenge.
   */
  static final String CREATED_TOP_LEVEL =
      "{\"id\":\""
          + PARENT_COMMENT_REF
          + "\",\"taskId\":\""
          + TASK_REF
          + "\",\"authorRef\":\"880e8400-e29b-41d4-a716-446655440003\",\"parentCommentId\":null,"
          + "\"text\":\"Bitte bis Ende der Woche erledigen.\",\"edited\":false,"
          + "\"createdAt\":\"2026-11-17T09:00:00Z\",\"isNew\":false,\"attachments\":[]}";

  /** Beispiel-UUID eines anderen Kommentar-Autors als des Anfragers (Liste). */
  private static final String OTHER_AUTHOR_REF = "990e8400-e29b-41d4-a716-446655440004";

  /** Beispiel-UUID des Kommentar-Anhangs (Liste). */
  private static final String COMMENT_ATTACHMENT_REF = "a1520000-0000-4000-8000-000000000003";

  /**
   * Gateway-relative Basis der Abruf-Links des Kommentar-Anhangs (Vorgabe von {@code
   * brasi.openapi.public-base}); die Links zeigen auf den Aufgaben-Pfad, wie in {@code
   * AttachmentApiExamples}.
   */
  private static final String ATTACHMENT_LINK_BASE =
      "/task-management/api/v1/tasks/" + TASK_REF + "/attachments/" + COMMENT_ATTACHMENT_REF;

  /**
   * Ein Anhang des ersten Kommentars der Liste: PDF mit Vorschaubild, hochgeladen von dessen Autor
   * mit dem Kommentar, nicht entfernbar (Kommentar-Anhaenge sind unveraenderlich).
   */
  private static final String COMMENT_ATTACHMENT =
      "{\"id\":\""
          + COMMENT_ATTACHMENT_REF
          + "\",\"fileName\":\"Angebot.pdf\",\"mimeType\":\"application/pdf\","
          + "\"sizeBytes\":184320,\"hasThumbnail\":true,\"uploadedById\":\""
          + OTHER_AUTHOR_REF
          + "\",\"createdAt\":\"2026-11-17T09:00:00Z\",\"removable\":false,"
          + "\"links\":{\"content\":\""
          + ATTACHMENT_LINK_BASE
          + "/content\",\"download\":\""
          + ATTACHMENT_LINK_BASE
          + "/download\",\"thumbnail\":\""
          + ATTACHMENT_LINK_BASE
          + "/thumbnail\"}}";

  /**
   * 200 von {@code getComments}: chronologisch aufsteigend, ein Kommentar ohne und eine Antwort
   * darauf, beide von einer anderen Person als dem Anfrager. Der Anfrager hat die Aufgabe zwischen
   * beiden Kommentaren als gesehen markiert: der erste ist nicht mehr neu, die Antwort danach ist
   * neu ({@code isNew}, Intake #150). Der erste Kommentar traegt einen Anhang, die Antwort keinen
   * ({@code attachments}, Intake #152).
   */
  static final String LIST_COMMENTS =
      "[{\"id\":\""
          + PARENT_COMMENT_REF
          + "\",\"taskId\":\""
          + TASK_REF
          + "\",\"authorRef\":\""
          + OTHER_AUTHOR_REF
          + "\",\"parentCommentId\":null,"
          + "\"text\":\"Bitte bis Ende der Woche erledigen.\",\"edited\":false,"
          + "\"createdAt\":\"2026-11-17T09:00:00Z\",\"isNew\":false,\"attachments\":["
          + COMMENT_ATTACHMENT
          + "]},"
          + "{\"id\":\"770e8400-e29b-41d4-a716-446655440002\",\"taskId\":\""
          + TASK_REF
          + "\",\"authorRef\":\""
          + OTHER_AUTHOR_REF
          + "\",\"parentCommentId\":\""
          + PARENT_COMMENT_REF
          + "\",\"text\":\"Erledigt, danke.\",\"edited\":false,"
          + "\"createdAt\":\"2026-11-17T10:00:00Z\",\"isNew\":true,\"attachments\":[]}]";

  /** Pfad von {@code GET /api/v1/tasks/{id}/comments} bei einer ungueltigen UUID im Pfad. */
  private static final String PATH_INVALID_ID = "/api/v1/tasks/keine-uuid/comments";

  /** 400 von {@code getComments}: keine gueltige UUID im Pfad. */
  static final String BAD_REQUEST_PATH =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der URL-Parameter 'id' hat einen ungültigen Wert. Ein Wert vom Typ"
          + " 'UUID' wird erwartet.\",\"path\":\""
          + PATH_INVALID_ID
          + "\",\"errorKey\":\"NOVA_TYPE_MISMATCH\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Wert: 'keine-uuid'\"]}";

  /** 400: der Eltern-Kommentar ist unbekannt, gehoert zu einer anderen Aufgabe oder ist inaktiv. */
  static final String BAD_REQUEST_PARENT =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der angegebene Eltern-Kommentar ist ungueltig.\",\"path\":\""
          + PATH_COMMENTS
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException:"
          + " Der angegebene Eltern-Kommentar ist ungueltig.\"]}";

  /**
   * 400: der Text fehlt oder ist leer (ebenso null-Element oder mehr als 100 Eintraege in
   * mentions). Die Validierung greift beim Lesen des Bodys; die KSF-Fehlerbehandlung meldet daher
   * "nicht lesbar" und legt den Validierungstext nach details.
   */
  static final String BAD_REQUEST_BODY =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der Request-Body ist fehlerhaft formatiert oder konnte nicht gelesen"
          + " werden.\",\"path\":\""
          + PATH_COMMENTS
          + "\",\"errorKey\":\"NOVA_MESSAGE_NOT_READABLE\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Das Feld 'text' darf nicht null oder leer sein.\"]}";

  /** 401: problem+json der Security-Filterkette (kein oder ungueltiges JWT). */
  static final String UNAUTHORIZED =
      "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
          + "\"detail\":\"Authentifizierung erforderlich\"}";

  /** 404: Aufgabe unbekannt, fremder Mandant, inaktiv oder Aufrufer kein aktives Mitglied. */
  static final String NOT_FOUND =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_COMMENTS
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.exception.EntityNotFoundException:"
          + " Aufgabe nicht gefunden.\"]}";

  /**
   * 409: nebenlaeufiger Versionskonflikt beim Speichern der Aufgabe (Zaehler und Aktivitaet) oder
   * der Teilnehmerzeile bzw. des persoenlichen Zustands einer erwaehnten Person.
   */
  static final String CONFLICT =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_COMMENTS
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\"]}";
}
