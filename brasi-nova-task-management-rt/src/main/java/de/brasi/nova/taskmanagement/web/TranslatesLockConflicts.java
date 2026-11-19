package de.brasi.nova.taskmanagement.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert einen handgeschriebenen Controller, dessen schreibende Endpunkte ({@code POST}, {@code
 * PUT}, {@code PATCH}, {@code DELETE}) ein Deadlock-Opfer oder einen anderen Sperrfehler der
 * Datenbank als 409 melden statt als 500 (Intake #167, {@link LockConflictTranslationAspect}).
 *
 * <p>Der Text der 409 gehoert zur Sache des Controllers ({@link #message}, Stefan 2026-11-18: Text
 * je Sache); die Texte stehen als Konstanten hier. Jeder schreibende Endpunkt eines so markierten
 * Controllers dokumentiert die 409 in OpenAPI, und ein Beispiel mit Sperrkonflikt-Text traegt den
 * Text seiner Markierung; das prueft {@code LockConflictTranslationAspectTest}. Ein neuer
 * handgeschriebener Controller mit Datenbank-Schreibzugriff bekommt die Markierung ebenfalls.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TranslatesLockConflicts {

  /** Text fuer Aufgaben-Endpunkte (Aufgabe, Mitgliedschaft, Kommentar, persoenliche Marker). */
  String TASK = "Die Aufgabe wurde gleichzeitig geändert. Bitte neu laden und erneut versuchen.";

  /** Text fuer Tag-Endpunkte. */
  String TAG = "Der Tag wurde gleichzeitig geändert. Bitte neu laden und erneut versuchen.";

  /** Text fuer Benachrichtigungs-Endpunkte. */
  String NOTIFICATION =
      "Die Benachrichtigung wurde gleichzeitig geändert. Bitte neu laden und erneut"
          + " versuchen.";

  /**
   * Meldung der 409 nach einem Sperrfehler an einem schreibenden Endpunkt dieses Controllers.
   *
   * @return einer der Texte {@link #TASK}, {@link #TAG} oder {@link #NOTIFICATION}
   */
  String message();
}
