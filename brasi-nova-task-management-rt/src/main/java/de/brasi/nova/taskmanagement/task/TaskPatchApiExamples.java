package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;

/**
 * OpenAPI-Beispieltexte (JSON) fuer die Operation {@code patch} ({@code PATCH /api/v1/tasks/{id}}).
 *
 * <p>Compile-Zeit-Konstanten, weil Annotationswerte konstant sein muessen. Die Fehlerbeispiele
 * bilden die echte Antwort der KSF-Fehlerbehandlung ab (Schema {@code ApiError}, Karte {@code
 * ksf/error-format}): Fehler des Eingabe-DTOs entstehen beim Lesen des Bodys und kommen als {@code
 * NOVA_MESSAGE_NOT_READABLE} mit dem Regeltext in {@code details}, Fehler des Dienstes als {@code
 * NOVA_VALIDATION_FAILED}. {@code TaskFacadeControllerIT#i190_patch_openapi_examples_match_live}
 * haelt die beiden Erwaehnungs-Beispiele gegen die Live-Antworten fest.
 */
final class TaskPatchApiExamples {

  private TaskPatchApiExamples() {}

  /** Beispiel-UUID der Aufgabe im Pfad. */
  static final String TASK_REF = "3f2b7c1e-8a4d-4e59-9c3a-0b1d2e3f4a5b";

  /** Beispiel-UUID einer erwaehnten Person. */
  static final String USER_REF = "550e8400-e29b-41d4-a716-446655440000";

  /** Beispiel-UUID einer erwaehnten Abteilung (divisionId aus GET /api/v1/tasks/divisions). */
  static final String DIVISION_REF = "5d1c0a3e-2b4f-4c6d-8e9f-0a1b2c3d4e5f";

  /** Pfad der Fehlerbeispiele. */
  private static final String PATH = "/api/v1/tasks/" + TASK_REF;

  /** Kopf der 400-Beispiele im {@code ApiError}-Format (Zeitstempel ohne Zeitzone). */
  private static final String BAD_REQUEST_HEAD =
      "{\"timestamp\":\"2026-11-23T10:00:00.123456\",\"status\":400,\"error\":\"Bad Request\",";

  /** Trace-Id und Ende der Fehlerbeispiele. */
  private static final String TRACE = "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",";

  /** Anfrage: Titel und Prioritaet aendern. */
  static final String REQUEST_TITLE_PRIORITY =
      "{\"title\":\"Jahresabschluss vorbereiten\",\"priorityCode\":\"HIGH\"}";

  /** Anfrage: Beschreibung aendern und dabei eine Abteilung und eine Person erwaehnen (#190). */
  static final String REQUEST_DESCRIPTION_WITH_MENTIONS =
      "{\"description\":\"@Vertrieb @Anna Bitte die Belege bis Freitag abstimmen.\","
          + "\"mentions\":[\""
          + USER_REF
          + "\"],\"divisionMentions\":[\""
          + DIVISION_REF
          + "\"]}";

  // Text wortgleich mit DivisionMentionResolver (Intake #190).
  private static final String TEXT_UNKNOWN_DIVISION = "Unbekannte oder nicht aktive Abteilung.";

  /** 400: Erwaehnungen ohne description (Regel des Eingabe-DTOs, beim Lesen des Bodys). */
  static final String BAD_REQUEST_MENTIONS_WITHOUT_DESCRIPTION =
      BAD_REQUEST_HEAD
          + "\"message\":\"Der Request-Body ist fehlerhaft formatiert oder konnte nicht gelesen"
          + " werden.\",\"path\":\""
          + PATH
          + "\",\"errorKey\":\"NOVA_MESSAGE_NOT_READABLE\","
          + TRACE
          + "\"details\":[\""
          + TaskPatchInput.MENTIONS_WITHOUT_DESCRIPTION_MESSAGE
          + "\"]}";

  /** 400: eine erwaehnte Abteilung ist unbekannt, nicht aktiv oder mandantenfremd (#190). */
  static final String BAD_REQUEST_DIVISION =
      BAD_REQUEST_HEAD
          + "\"message\":\""
          + TEXT_UNKNOWN_DIVISION
          + "\",\"path\":\""
          + PATH
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException: "
          + TEXT_UNKNOWN_DIVISION
          + "\"]}";

  /** 400: unbekannter priorityCode (Intake #183). */
  static final String BAD_REQUEST_PRIORITY =
      BAD_REQUEST_HEAD
          + "\"message\":\"Ungueltiger priorityCode: 'GIBTS_NICHT'.\",\"path\":\""
          + PATH
          + "\",\"errorKey\":\"NOVA_VALIDATION_FAILED\","
          + TRACE
          + "\"details\":[\"io.keel.framework.api.validation.EntityValidationException:"
          + " Ungueltiger priorityCode: 'GIBTS_NICHT'.\"]}";
}
