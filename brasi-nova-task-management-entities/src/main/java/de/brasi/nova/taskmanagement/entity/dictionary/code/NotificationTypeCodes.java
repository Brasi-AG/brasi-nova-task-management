package de.brasi.nova.taskmanagement.entity.dictionary.code;

/** Technische CODE-Konstanten fuer {@code DIC_NOTIFICATION_TYPE}. Nicht instanziierbar. */
public final class NotificationTypeCodes {

  private NotificationTypeCodes() {}

  public static final String ASSIGN = "ASSIGN";
  public static final String REPLY = "REPLY";
  public static final String DUE = "DUE";
  public static final String MENTION = "MENTION";
  public static final String DONE = "DONE";
  public static final String CHANGE = "CHANGE";
  public static final String HANDOVER = "HANDOVER";
  public static final String SYSTEM = "SYSTEM";

  /**
   * Neuer Top-Level-Kommentar an einer Aufgabe (Intake #136). Benachrichtigt die zugewiesene Person
   * auch ohne @-Erwaehnung.
   */
  public static final String COMMENT = "COMMENT";

  /**
   * Aufgabe wurde abgebrochen, also in den Status {@code CANCELLED} ueberfuehrt (Intake #157).
   * Spiegelt {@link #DONE}: der Typ-CODE entspricht dem Status-CODE. Seed per Flyway V10.
   */
  public static final String CANCELLED = "CANCELLED";

  /**
   * Person wurde einer Aufgabe als Beteiligte hinzugefuegt (Intake #147, {@code POST
   * /api/v1/tasks/{id}/participants}). Direkt persoenlich wie {@link #ASSIGN} und {@link
   * #HANDOVER} und daher von der Stummschaltung ausgenommen. Seed per Flyway V11.
   */
  public static final String PARTICIPANT = "PARTICIPANT";
}
