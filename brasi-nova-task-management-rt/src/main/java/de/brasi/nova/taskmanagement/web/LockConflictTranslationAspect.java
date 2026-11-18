package de.brasi.nova.taskmanagement.web;

import io.keel.framework.api.exception.BusinessRuleException;
import java.sql.SQLException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Uebersetzt ein Deadlock-Opfer (SQL-Server-Fehler 1205) oder einen anderen Sperrfehler der
 * Datenbank an den schreibenden Endpunkten in eine 409 (Intake #167).
 *
 * <p><b>Warum hier:</b> Der Aspekt liegt um die schreibenden Methoden der mit {@link
 * TranslatesLockConflicts} markierten Controller, also AUSSERHALB der Service-Transaktion. Wenn er
 * greift, hat die Transaktion schon vollstaendig zurueckgerollt, und nichts ist geaendert; genau
 * das sagt eine 409 zu. Innerhalb des Services liesse sich die Ausnahme nicht sauber fangen: SQL
 * Server hat die Transaktion beim Deadlock bereits abgebrochen, und der Commit-Pfad des
 * Transaktionsmanagers laeuft erst nach dem Service-Aufruf. Der Aspekt wirft {@link
 * BusinessRuleException} mit Text und Ursache; daraus macht der KSF-Handler das Standard-409
 * ({@code NOVA_BUSINESS_RULE_VIOLATION}, Karte {@code ksf/error-format}). Kein eigener {@code
 * ControllerAdvice}, kein nachgebautes {@code ApiError}.
 *
 * <p><b>Was uebersetzt wird:</b> nur Sperrfehler, also eine {@link
 * PessimisticLockingFailureException} (Spring meldet 1205 als {@code CannotAcquireLockException})
 * oder ein {@link SQLException} mit Fehlercode 1205 irgendwo in der Ursachenkette. Andere {@code
 * DataAccessException}s bleiben unveraendert, ebenso eine schon vorhandene {@link
 * BusinessRuleException}.
 *
 * <p>Die Lock-Reihenfolge der Mitgliedschaftspfade (Karte {@code participants-actions}) verhindert
 * die bekannten Deadlocks; die Uebersetzung ist die Absicherung fuer unbekannte Faelle, damit ein
 * Client nie 500 fuer einen wiederholbaren Konflikt bekommt. Der Ordnungswert stellt den Aspekt vor
 * alle anderen Advices der Controller-Methode.
 *
 * <p><b>Sonderfall {@code TaskFacadeController#createFromSource}:</b> Bei einem Unique-Konflikt der
 * Quittung laeuft danach eine zweite, rein lesende Transaktion ({@code
 * TaskSourceCreateTransaction#replayAfterConflict}, REQUIRES_NEW, readOnly). Ein Sperrkonflikt dort
 * meldet ebenfalls 409; "nichts geaendert" gilt fuer diesen Request. Die parallele Gewinnerin hat
 * die Aufgabe angelegt, ein Retry mit demselben Schluessel liefert das Replay.
 *
 * <p><b>Sequenzvergabe:</b> Die Vergabe der KSF ({@code SequenceGenerator}, REQUIRES_NEW) laeuft in
 * eigener Transaktion; bei einem Rollback hinterlaesst sie nur eine Luecke in den IDs.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LockConflictTranslationAspect {

  /** Meldung der 409 nach einem Sperrfehler (Text von Stefan, Intake #167). */
  public static final String MESSAGE =
      "Die Aufgabe wurde gleichzeitig geändert. Bitte neu laden und erneut versuchen.";

  /** Fehlercode des SQL-Server-Deadlock-Opfers. */
  static final int SQL_SERVER_DEADLOCK = 1205;

  private static final Logger log = LoggerFactory.getLogger(LockConflictTranslationAspect.class);

  /**
   * Fuehrt die schreibende Controller-Methode aus und uebersetzt einen Sperrfehler in 409.
   *
   * @param joinPoint die Controller-Methode
   * @return das Ergebnis der Methode
   * @throws Throwable jede Ausnahme der Methode; ein Sperrfehler als {@link BusinessRuleException}
   */
  @Around(
      "@within(de.brasi.nova.taskmanagement.web.TranslatesLockConflicts)"
          + " && (@annotation(org.springframework.web.bind.annotation.PostMapping)"
          + " || @annotation(org.springframework.web.bind.annotation.PutMapping)"
          + " || @annotation(org.springframework.web.bind.annotation.PatchMapping)"
          + " || @annotation(org.springframework.web.bind.annotation.DeleteMapping))")
  public Object translateLockConflict(final ProceedingJoinPoint joinPoint) throws Throwable {
    try {
      return joinPoint.proceed();
    } catch (final BusinessRuleException alreadyConflict) {
      throw alreadyConflict;
    } catch (final RuntimeException error) {
      if (!isLockConflict(error)) {
        throw error;
      }
      log.warn(
          "Sperrfehler der Datenbank in {} als 409 gemeldet (Transaktion zurueckgerollt): {}",
          joinPoint.getSignature().toShortString(),
          error.toString());
      throw new BusinessRuleException(MESSAGE, error);
    }
  }

  /**
   * Ist die Ausnahme ein Sperrfehler der Datenbank?
   *
   * @param error die gefangene Ausnahme
   * @return {@code true}, wenn die Ursachenkette eine {@link PessimisticLockingFailureException}
   *     oder ein {@link SQLException} mit Fehlercode 1205 enthaelt
   */
  static boolean isLockConflict(final Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 32) {
      if (current instanceof PessimisticLockingFailureException) {
        return true;
      }
      if (current instanceof SQLException sql && sql.getErrorCode() == SQL_SERVER_DEADLOCK) {
        return true;
      }
      current = current.getCause() == current ? null : current.getCause();
      depth++;
    }
    return false;
  }
}
