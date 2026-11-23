package de.brasi.nova.taskmanagement.comment;

/**
 * OpenAPI-Beispieltexte (JSON) fuer die Operationen {@code createComment} ({@code POST
 * /api/v1/tasks/{id}/comments}), {@code getComments} ({@code GET} auf demselben Pfad) und {@code
 * createCommentWithAttachments} ({@code POST /api/v1/tasks/{id}/comments/with-attachments}).
 *
 * <p>Die Beispiele sind Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Die
 * Fehlerbeispiele bilden die echte Antwort der KSF-Fehlerbehandlung ab (Schema {@code ApiError} mit
 * {@code errorKey}, Karte {@code ksf/error-format}); das 401-Beispiel die problem+json-Antwort der
 * Security-Filterkette. {@code CommentControllerIT} haelt die Beispiele von {@code createComment}
 * und {@code getComments} gegen die Live-Antworten fest. Fuer die Beispiele von {@code
 * createCommentWithAttachments} (Konstanten mit {@code _WITH_ATTACHMENTS}) gibt es keinen solchen
 * Test: {@code CommentAttachmentIT} prueft das Verhalten, nicht die Beispieltexte. Ihre Fehlertexte
 * sind von Hand wortgleich mit den Quellen gehalten (Hinweis bei den Texten unten); wer einen Text
 * der Quellen aendert, muss das Beispiel mitziehen. Die Schemas liegen in den Komponenten und
 * werden hier nicht veraendert: Eine Aenderung gemeinsamer Komponenten wuerde die Fingerprints der
 * Nachbar-Operationen in der OpenAPI-Basisliste verschieben.
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

  /** Beispiel-UUID einer erwaehnten Abteilung (Intake #190). */
  static final String DIVISION_REF = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f";

  /** Anfrage: Kommentar mit Erwaehnung einer Abteilung und einer Person (Intake #190). */
  static final String REQUEST_WITH_DIVISION_MENTION =
      "{\"text\":\"@Vertrieb @Anna Bitte bis Freitag abstimmen.\",\"mentions\":[\""
          + USER_REF_A
          + "\"],\"divisionMentions\":[\""
          + DIVISION_REF
          + "\"]}";

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

  // Texte wortgleich mit DivisionMentionResolver (Intake #190); die Grenze ist die Vorgabe
  // task-management.division-mentions.max-persons aus application.properties.
  private static final String TEXT_UNKNOWN_DIVISION = "Unbekannte oder nicht aktive Abteilung.";
  private static final String TEXT_DIVISION_PERSON_LIMIT =
      "Die erwaehnten Abteilungen umfassen mehr als 200 Personen.";

  /**
   * 400: eine erwaehnte Abteilung ist unbekannt, nicht aktiv oder gehoert zu einem anderen
   * Mandanten (ein Text fuer alle Faelle, Intake #190).
   */
  static final String BAD_REQUEST_DIVISION =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\""
          + TEXT_UNKNOWN_DIVISION
          + "\",\"path\":\""
          + PATH_COMMENTS
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException: "
          + TEXT_UNKNOWN_DIVISION
          + "\"]}";

  /**
   * 400: die erwaehnten Abteilungen umfassen zusammen mehr verschiedene Personen als erlaubt
   * (Vorgabe 200, Intake #190).
   */
  static final String BAD_REQUEST_DIVISION_PERSON_LIMIT =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\""
          + TEXT_DIVISION_PERSON_LIMIT
          + "\",\"path\":\""
          + PATH_COMMENTS
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException: "
          + TEXT_DIVISION_PERSON_LIMIT
          + "\"]}";

  /**
   * 400: der Text fehlt oder ist leer (ebenso null-Element oder mehr als 100 Eintraege in mentions
   * bzw. mehr als 10 in divisionMentions). Die Validierung greift beim Lesen des Bodys; die
   * KSF-Fehlerbehandlung meldet daher "nicht lesbar" und legt den Validierungstext nach details.
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

  // ---- Kommentar mit Anhaengen (Intake #152 Teil B) ----------------------------------------

  /** Pfad von {@code POST /api/v1/tasks/{id}/comments/with-attachments} in den Fehlerbeispielen. */
  private static final String PATH_WITH_ATTACHMENTS = PATH_COMMENTS + "/with-attachments";

  /** Trace-ID der Fehlerbeispiele. */
  private static final String TRACE = "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",";

  /** Beispiel-UUID der kommentierenden Person (Autorin und hochladende Person). */
  private static final String AUTHOR_REF = "880e8400-e29b-41d4-a716-446655440003";

  /** Beispiel-UUID des neuen Kommentars mit Anhaengen. */
  private static final String COMMENT_WITH_ATTACHMENTS_REF = "770e8400-e29b-41d4-a716-446655440005";

  /** Beispiel-UUID des PDF-Anhangs am neuen Kommentar. */
  private static final String PDF_REF = "a1520000-0000-4000-8000-000000000004";

  /** Beispiel-UUID des Text-Anhangs am neuen Kommentar. */
  private static final String TXT_REF = "a1520000-0000-4000-8000-000000000005";

  /** Gateway-relative Basis der Abruf-Links der Anhaenge (Aufgaben-Pfad). */
  private static final String TASK_ATTACHMENT_LINK_BASE =
      "/task-management/api/v1/tasks/" + TASK_REF + "/attachments/";

  /**
   * Anfrage: Struktur des Multipart-Formulars. Der Teil comment ist JSON (Content-Type
   * application/json), jeder Teil files traegt den Binaerinhalt einer Datei; das Beispiel zeigt nur
   * die Struktur.
   */
  static final String REQUEST_WITH_ATTACHMENTS =
      "{\"comment\":{\"text\":\"Siehe Anhang.\",\"mentions\":[\""
          + USER_REF_A
          + "\"]},\"files\":[\"(Binärinhalt)\",\"(Binärinhalt)\"]}";

  /**
   * 201: der neue Kommentar ohne Eltern-Kommentar mit zwei Anhaengen in Upload-Reihenfolge, ein PDF
   * mit Vorschaubild und eine Textdatei ohne ({@code links.thumbnail} ist {@code null}). Die
   * Anhaenge sind nicht entfernbar, ihre Links zeigen auf den Aufgaben-Pfad.
   */
  static final String CREATED_WITH_ATTACHMENTS =
      "{\"id\":\""
          + COMMENT_WITH_ATTACHMENTS_REF
          + "\",\"taskId\":\""
          + TASK_REF
          + "\",\"authorRef\":\""
          + AUTHOR_REF
          + "\",\"parentCommentId\":null,\"text\":\"Siehe Anhang.\",\"edited\":false,"
          + "\"createdAt\":\"2026-11-19T09:15:00Z\",\"isNew\":false,\"attachments\":["
          + "{\"id\":\""
          + PDF_REF
          + "\",\"fileName\":\"Bericht.pdf\",\"mimeType\":\"application/pdf\","
          + "\"sizeBytes\":184320,\"hasThumbnail\":true,\"uploadedById\":\""
          + AUTHOR_REF
          + "\",\"createdAt\":\"2026-11-19T09:15:00Z\",\"removable\":false,"
          + "\"links\":{\"content\":\""
          + TASK_ATTACHMENT_LINK_BASE
          + PDF_REF
          + "/content\",\"download\":\""
          + TASK_ATTACHMENT_LINK_BASE
          + PDF_REF
          + "/download\",\"thumbnail\":\""
          + TASK_ATTACHMENT_LINK_BASE
          + PDF_REF
          + "/thumbnail\"}},"
          + "{\"id\":\""
          + TXT_REF
          + "\",\"fileName\":\"Notiz.txt\",\"mimeType\":\"text/plain\","
          + "\"sizeBytes\":2048,\"hasThumbnail\":false,\"uploadedById\":\""
          + AUTHOR_REF
          + "\",\"createdAt\":\"2026-11-19T09:15:00Z\",\"removable\":false,"
          + "\"links\":{\"content\":\""
          + TASK_ATTACHMENT_LINK_BASE
          + TXT_REF
          + "/content\",\"download\":\""
          + TASK_ATTACHMENT_LINK_BASE
          + TXT_REF
          + "/download\",\"thumbnail\":null}}]}";

  /** Anfang eines Fehlerbeispiels einer {@code EntityValidationException} (400) bis zum Text. */
  private static final String VALIDATION_HEAD =
      ERROR_HEAD + "\"status\":400,\"error\":\"Bad Request\",\"message\":\"";

  /** Mitte des Validierungsbeispiels: Pfad, Schluessel, Trace-ID und Beginn von details. */
  private static final String VALIDATION_MIDDLE =
      "\",\"path\":\""
          + PATH_WITH_ATTACHMENTS
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException: ";

  /** Schluss des Validierungsbeispiels. */
  private static final String VALIDATION_TAIL = "\"]}";

  // Texte wortgleich mit CommentService (Eltern-Kommentar), AttachmentUploadPreparer (Dateityp,
  // Tarifgrenze) und MultipartErrorAdvice (fehlender Teil). Der Anzahl-Text kommt aus
  // AttachmentProperties#tooManyAttachmentsMessage (genutzt von CommentService und
  // AttachmentUploadPreparer); die Zahlen sind die Vorgaben aus application.properties.
  private static final String TEXT_PARENT = "Der angegebene Eltern-Kommentar ist ungueltig.";
  private static final String TEXT_TYPE =
      "Der Dateityp „.exe“ ist nicht erlaubt. Erlaubt sind: pdf, xls, xlsx, doc, docx, ppt, pptx,"
          + " txt, csv, png, jpg, jpeg, mp4.";
  private static final String TEXT_TARIFF_LIMIT =
      "Die Datei „Bericht.pdf“ ist größer als die Grenze von 5 MB des Tarifs STANDARD.";
  private static final String TEXT_COUNT =
      "Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar.";
  private static final String TEXT_MISSING_COMMENT = "Der Teil „comment“ fehlt in der Anfrage.";
  private static final String TEXT_MISSING_FILES = "Der Teil „files“ fehlt in der Anfrage.";

  /**
   * 400: der Text im Teil comment fehlt oder ist leer (ebenso kein gueltiges JSON, ein null-Element
   * oder mehr als 100 Eintraege in mentions bzw. mehr als 10 in divisionMentions). Die Pruefung
   * greift beim Lesen des Teils, vor jeder Dateipruefung; die KSF-Fehlerbehandlung meldet daher
   * "nicht lesbar" und legt den Validierungstext nach details.
   */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_TEXT =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der Request-Body ist fehlerhaft formatiert oder konnte nicht gelesen"
          + " werden.\",\"path\":\""
          + PATH_WITH_ATTACHMENTS
          + "\",\"errorKey\":\"NOVA_MESSAGE_NOT_READABLE\","
          + TRACE
          + "\"details\":[\"Das Feld 'text' darf nicht null oder leer sein.\"]}";

  /** 400: der Eltern-Kommentar ist unbekannt, gehoert zu einer anderen Aufgabe oder ist inaktiv. */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_PARENT =
      VALIDATION_HEAD + TEXT_PARENT + VALIDATION_MIDDLE + TEXT_PARENT + VALIDATION_TAIL;

  /** 400: eine erwaehnte Abteilung ist unbekannt, nicht aktiv oder mandantenfremd (Intake #190). */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_DIVISION =
      VALIDATION_HEAD
          + TEXT_UNKNOWN_DIVISION
          + VALIDATION_MIDDLE
          + TEXT_UNKNOWN_DIVISION
          + VALIDATION_TAIL;

  /** 400: Endung einer Datei nicht in der Erlaubtliste. */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_TYPE =
      VALIDATION_HEAD + TEXT_TYPE + VALIDATION_MIDDLE + TEXT_TYPE + VALIDATION_TAIL;

  /** 400: Datei groesser als die Grenze des Tarifs (hier STANDARD mit der Vorgabe 5 MB). */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_TARIFF_LIMIT =
      VALIDATION_HEAD + TEXT_TARIFF_LIMIT + VALIDATION_MIDDLE + TEXT_TARIFF_LIMIT + VALIDATION_TAIL;

  /** 400: mehr als 20 Dateien fuer den Kommentar. */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_COUNT =
      VALIDATION_HEAD + TEXT_COUNT + VALIDATION_MIDDLE + TEXT_COUNT + VALIDATION_TAIL;

  /** 400: kein Teil comment im Formular. */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_MISSING_COMMENT =
      VALIDATION_HEAD
          + TEXT_MISSING_COMMENT
          + VALIDATION_MIDDLE
          + TEXT_MISSING_COMMENT
          + VALIDATION_TAIL;

  /** 400: kein Teil files im Formular (ein Kommentar ohne Datei geht ueber die JSON-Variante). */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_MISSING_FILES =
      VALIDATION_HEAD
          + TEXT_MISSING_FILES
          + VALIDATION_MIDDLE
          + TEXT_MISSING_FILES
          + VALIDATION_TAIL;

  /** 400: keine gueltige UUID im Pfad. */
  static final String BAD_REQUEST_WITH_ATTACHMENTS_PATH =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der URL-Parameter 'id' hat einen ungültigen Wert. Ein Wert vom Typ"
          + " 'UUID' wird erwartet.\","
          + "\"path\":\"/api/v1/tasks/keine-uuid/comments/with-attachments\","
          + "\"errorKey\":\"NOVA_TYPE_MISMATCH\","
          + TRACE
          + "\"details\":[\"Wert: 'keine-uuid'\"]}";

  /**
   * 404: Aufgabe unbekannt, fremder Mandant, inaktiv oder Aufrufer kein aktives Mitglied (auch bei
   * ungueltigen Dateien).
   */
  static final String NOT_FOUND_WITH_ATTACHMENTS =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_WITH_ATTACHMENTS
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.exception.EntityNotFoundException:"
          + " Aufgabe nicht gefunden.\"]}";

  /** 409: nebenlaeufiger Versionskonflikt beim Speichern der Aufgabe, wie bei der JSON-Variante. */
  static final String CONFLICT_WITH_ATTACHMENTS =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_WITH_ATTACHMENTS
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\","
          + TRACE
          + "\"details\":[\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\"]}";
}
