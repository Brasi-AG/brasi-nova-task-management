package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.web.TranslatesLockConflicts;

/**
 * OpenAPI-Beispieltexte (JSON) fuer die Anhang-Operationen (Intake #152), hier fuer {@code POST
 * /api/v1/tasks/{id}/attachments}.
 *
 * <p>Die Beispiele sind Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Die
 * Fehlerbeispiele bilden die echte Antwort der KSF-Fehlerbehandlung ab (Schema {@code ApiError} mit
 * {@code errorKey}, {@code traceId} und {@code details} = {@code <Klassenname>: <Text>} bei einer
 * {@code EntityValidationException}, Karte {@code ksf/error-format}); das 401-Beispiel die
 * problem+json-Antwort der Security-Filterkette. Die Texte der 400 sind wortgleich mit {@link
 * AttachmentUploadPreparer}, {@link AttachmentCommandService} und {@code MultipartErrorAdvice}; die
 * Zahlen darin sind die Vorgaben aus {@code application.properties}. {@code AttachmentControllerIT}
 * haelt die Feldmenge von {@link #UPLOAD_CREATED} gegen die Live-Antwort fest.
 */
public final class AttachmentApiExamples {

  private AttachmentApiExamples() {}

  /** Beispiel-UUID der Aufgabe. */
  private static final String TASK_REF = "3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b";

  /** Beispiel-UUID des PDF-Anhangs. */
  private static final String PDF_REF = "a1520000-0000-4000-8000-000000000001";

  /** Beispiel-UUID des Text-Anhangs. */
  private static final String TXT_REF = "a1520000-0000-4000-8000-000000000002";

  /** Beispiel-UUID der hochladenden Person. */
  private static final String UPLOADER_REF = "880e8400-e29b-41d4-a716-446655440003";

  /** Gateway-relative Basis der Abruf-Links (Vorgabe von {@code brasi.openapi.public-base}). */
  private static final String LINK_BASE =
      "/task-management/api/v1/tasks/" + TASK_REF + "/attachments/";

  /** Pfad von {@code POST /api/v1/tasks/{id}/attachments} in den Fehlerbeispielen. */
  private static final String PATH_UPLOAD = "/api/v1/tasks/" + TASK_REF + "/attachments";

  /** Kopf der Fehlerbeispiele im {@code ApiError}-Format (Zeitstempel ohne Zeitzone). */
  private static final String ERROR_HEAD = "{\"timestamp\":\"2026-11-19T10:00:00.123456\",";

  /** Trace-ID der Fehlerbeispiele. */
  private static final String TRACE = "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",";

  /** Klassenname der Validierungs-Ausnahme in {@code details}. */
  private static final String VALIDATION_CLASS =
      "io.keel.framework.api.validation.EntityValidationException";

  /** Anfrage: Multipart-Formular mit einer Datei im Teil {@code files}. */
  public static final String UPLOAD_REQUEST = "{\"files\":[\"(Binärinhalt)\"]}";

  /**
   * 201: zwei neue Anhaenge in Upload-Reihenfolge, ein PDF mit Vorschaubild und eine Textdatei
   * ohne ({@code links.thumbnail} ist {@code null}). Das erste Element hat genau die Feldmenge der
   * Live-Antwort.
   */
  public static final String UPLOAD_CREATED =
      "[{\"id\":\""
          + PDF_REF
          + "\",\"fileName\":\"Bericht.pdf\",\"mimeType\":\"application/pdf\","
          + "\"sizeBytes\":184320,\"hasThumbnail\":true,\"uploadedById\":\""
          + UPLOADER_REF
          + "\",\"createdAt\":\"2026-11-19T09:15:00Z\",\"removable\":true,"
          + "\"links\":{\"content\":\""
          + LINK_BASE
          + PDF_REF
          + "/content\",\"download\":\""
          + LINK_BASE
          + PDF_REF
          + "/download\",\"thumbnail\":\""
          + LINK_BASE
          + PDF_REF
          + "/thumbnail\"}},"
          + "{\"id\":\""
          + TXT_REF
          + "\",\"fileName\":\"Notiz.txt\",\"mimeType\":\"text/plain\","
          + "\"sizeBytes\":2048,\"hasThumbnail\":false,\"uploadedById\":\""
          + UPLOADER_REF
          + "\",\"createdAt\":\"2026-11-19T09:15:00Z\",\"removable\":true,"
          + "\"links\":{\"content\":\""
          + LINK_BASE
          + TXT_REF
          + "/content\",\"download\":\""
          + LINK_BASE
          + TXT_REF
          + "/download\",\"thumbnail\":null}}]";

  /** Anfang eines Fehlerbeispiels einer {@code EntityValidationException} (400) bis zum Text. */
  private static final String VALIDATION_HEAD =
      ERROR_HEAD + "\"status\":400,\"error\":\"Bad Request\",\"message\":\"";

  /** Mitte des Validierungsbeispiels: Pfad, Schluessel, Trace-ID und Beginn von details. */
  private static final String VALIDATION_MIDDLE =
      "\",\"path\":\""
          + PATH_UPLOAD
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + TRACE
          + "\"details\":[\""
          + VALIDATION_CLASS
          + ": ";

  /** Schluss des Validierungsbeispiels. */
  private static final String VALIDATION_TAIL = "\"]}";

  private static final String TEXT_TYPE =
      "Der Dateityp „.exe“ ist nicht erlaubt. Erlaubt sind: pdf, xls, xlsx, doc, docx, ppt, pptx,"
          + " txt, png, jpg, jpeg.";
  private static final String TEXT_EMPTY = "Die Datei „Notiz.txt“ ist leer.";
  private static final String TEXT_TARIFF_LIMIT =
      "Die Datei „Bericht.pdf“ ist größer als die Grenze von 5 MB des Tarifs STANDARD.";
  private static final String TEXT_HARD_LIMIT =
      "Die Datei ist größer als die zulässige Obergrenze von 15 MB.";
  private static final String TEXT_COUNT =
      "Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar.";
  private static final String TEXT_CONTENT =
      "Der Inhalt der Datei „bild.pdf“ passt nicht zum Dateityp PDF.";
  private static final String TEXT_MISSING_PART = "Der Teil „files“ fehlt in der Anfrage.";
  private static final String TEXT_MULTIPART =
      "Die Anfrage konnte nicht als multipart/form-data gelesen werden.";

  /** 400: Endung nicht in der Erlaubtliste. */
  public static final String BAD_REQUEST_TYPE =
      VALIDATION_HEAD + TEXT_TYPE + VALIDATION_MIDDLE + TEXT_TYPE + VALIDATION_TAIL;

  /** 400: leere Datei. */
  public static final String BAD_REQUEST_EMPTY =
      VALIDATION_HEAD + TEXT_EMPTY + VALIDATION_MIDDLE + TEXT_EMPTY + VALIDATION_TAIL;

  /** 400: Datei groesser als die Grenze des Tarifs (hier STANDARD mit der Vorgabe 5 MB). */
  public static final String BAD_REQUEST_TARIFF_LIMIT =
      VALIDATION_HEAD
          + TEXT_TARIFF_LIMIT
          + VALIDATION_MIDDLE
          + TEXT_TARIFF_LIMIT
          + VALIDATION_TAIL;

  /** 400: Datei groesser als die harte Obergrenze (Multipart-Grenze des Servers). */
  public static final String BAD_REQUEST_HARD_LIMIT =
      VALIDATION_HEAD + TEXT_HARD_LIMIT + VALIDATION_MIDDLE + TEXT_HARD_LIMIT + VALIDATION_TAIL;

  /** 400: die Aufgabe haette danach mehr aktive Anhaenge als erlaubt. */
  public static final String BAD_REQUEST_COUNT =
      VALIDATION_HEAD + TEXT_COUNT + VALIDATION_MIDDLE + TEXT_COUNT + VALIDATION_TAIL;

  /** 400: der Inhalt passt nicht zur Endung (Magic Bytes). */
  public static final String BAD_REQUEST_CONTENT =
      VALIDATION_HEAD + TEXT_CONTENT + VALIDATION_MIDDLE + TEXT_CONTENT + VALIDATION_TAIL;

  /** 400: kein Teil {@code files} im Formular. */
  public static final String BAD_REQUEST_MISSING_PART =
      VALIDATION_HEAD
          + TEXT_MISSING_PART
          + VALIDATION_MIDDLE
          + TEXT_MISSING_PART
          + VALIDATION_TAIL;

  /** 400: der Request ist kein lesbarer Multipart-Request. */
  public static final String BAD_REQUEST_MULTIPART =
      VALIDATION_HEAD + TEXT_MULTIPART + VALIDATION_MIDDLE + TEXT_MULTIPART + VALIDATION_TAIL;

  /** 400: keine gueltige UUID im Pfad. */
  public static final String BAD_REQUEST_PATH =
      ERROR_HEAD
          + "\"status\":400,\"error\":\"Bad Request\","
          + "\"message\":\"Der URL-Parameter 'id' hat einen ungültigen Wert. Ein Wert vom Typ"
          + " 'UUID' wird erwartet.\",\"path\":\"/api/v1/tasks/keine-uuid/attachments\","
          + "\"errorKey\":\"NOVA_TYPE_MISMATCH\","
          + TRACE
          + "\"details\":[\"Wert: 'keine-uuid'\"]}";

  /** 401: problem+json der Security-Filterkette (kein oder ungueltiges JWT). */
  public static final String UNAUTHORIZED =
      "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
          + "\"detail\":\"Authentifizierung erforderlich\"}";

  /** 404: Aufgabe unbekannt, fremder Mandant, inaktiv oder Aufrufer kein aktives Mitglied. */
  public static final String NOT_FOUND =
      ERROR_HEAD
          + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Aufgabe nicht gefunden.\","
          + "\"path\":\""
          + PATH_UPLOAD
          + "\",\"errorKey\":\"NOVA_NOT_FOUND\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.exception.EntityNotFoundException:"
          + " Aufgabe nicht gefunden.\"]}";

  /** 409: nebenlaeufiger Versionskonflikt beim Fortschreiben der Aufgabe. */
  public static final String CONFLICT_VERSION =
      ERROR_HEAD
          + "\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\","
          + "\"path\":\""
          + PATH_UPLOAD
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\","
          + TRACE
          + "\"details\":[\"Datensatz wurde zwischenzeitlich geändert (Versionskonflikt).\"]}";

  /**
   * 409: Sperrkonflikt der Datenbank (z. B. Deadlock), Text der Markierung des Controllers; in
   * derselben Feldauswahl wie die uebrigen Sperrkonflikt-Beispiele ({@code
   * LockConflictApiExamples}).
   */
  public static final String CONFLICT_LOCK =
      "{\"timestamp\":\"2026-11-19T10:00:00.123456\",\"status\":409,\"error\":\"Conflict\","
          + "\"message\":\""
          + TranslatesLockConflicts.TASK
          + "\",\"path\":\""
          + PATH_UPLOAD
          + "\",\"errorKey\":\"NOVA_BUSINESS_RULE_VIOLATION\"}";
}
