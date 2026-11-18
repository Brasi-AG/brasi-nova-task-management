package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import io.keel.framework.api.exception.BusinessRuleException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verwaltet den benutzerspezifischen Zustand ({@link TaskUserState}) fuer Aufgaben.
 *
 * <p>Alle Schreiboperationen laufen in der aktiven Transaktion des Aufrufers (kein eigenes
 * {@code @Transactional} - KSF-{@code NovaRepository.save()} hat selbst Tx-Propagation).
 */
@Service
public class UserStateService {

  private static final Logger log = LoggerFactory.getLogger(UserStateService.class);

  private final TaskUserStateQueryRepository repository;

  /** Konstruktor-Injektion des {@link TaskUserStateQueryRepository}. */
  public UserStateService(final TaskUserStateQueryRepository repository) {
    this.repository = repository;
  }

  /**
   * Stellt sicher, dass ein {@link TaskUserState}-Eintrag fuer den Benutzer existiert (idempotent).
   *
   * <p>Wenn kein Eintrag existiert, wird ein neuer (aktiver) angelegt. Wenn bereits einer vorhanden
   * ist, bleibt er unveraendert. Laueft in der aktiven Transaktion des Aufrufers.
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   * @return {@code true}, wenn ein neuer aktiver Eintrag angelegt wurde (der Benutzer also durch
   *     diesen Aufruf inbox-faehig wird); {@code false}, wenn bereits ein Eintrag existierte
   */
  public boolean ensure(final Long taskId, final UUID userRef, final Long tenantId) {
    final List<TaskUserState> existing = repository.findByTaskIdAndUserRef(taskId, userRef);
    if (!existing.isEmpty()) {
      log.debug(
          "UserStateService.ensure: Eintrag bereits vorhanden fuer Task {} und User {}.",
          taskId,
          userRef);
      return false;
    }
    final TaskUserState state = new TaskUserState();
    state.setTenantId(tenantId);
    state.setTaskId(taskId);
    state.setUserRef(userRef);
    state.setUserTenantId(tenantId);
    state.setPinned(false);
    state.setFlagged(false);
    state.setMuted(false);
    // Re-Read-Translate: bei UNIQUE-Verletzung (nebenlaeufiger Erst-Insert hat bereits committet)
    // den Konflikt idempotent abfangen statt als HTTP 409 nach aussen zu geben.
    try {
      repository.save(state);
    } catch (final BusinessRuleException concurrentInsert) {
      final List<TaskUserState> recheck = repository.findByTaskIdAndUserRef(taskId, userRef);
      if (!recheck.isEmpty()) {
        log.debug(
            "UserStateService.ensure: Nebenlaeufige Anlage erkannt fuer Task {} User {} -"
                + " idempotent false.",
            taskId,
            userRef);
        return false;
      }
      throw concurrentInsert;
    }
    log.debug(
        "UserStateService.ensure: Neuer Eintrag angelegt fuer Task {} und User {}.",
        taskId,
        userRef);
    return true;
  }

  /**
   * Reaktiviert den {@link TaskUserState} eines wieder aufgenommenen Teilnehmers (KEINE Zuweisung).
   *
   * <p>Stellt sicher, dass ein Eintrag existiert (wie {@link #ensure}), und setzt {@code
   * UNSUBSCRIBED_AT} auf {@code null} zurueck, sodass der Benutzer wieder in der aktiven Inbox
   * erscheint ({@code findActivePage} filtert auf {@code UNSUBSCRIBED_AT IS NULL}). Der
   * persoenliche Mute-Marker {@code IS_MUTED} eines vorhandenen States bleibt unveraendert (Intake
   * #158): ein stummer Teilnehmer, der per {@code addParticipants} wieder aufgenommen wird, bleibt
   * stumm. Fuer eine neue Zuweisung (Assignee) gilt {@link #reactivateAsAssignee}. Laueft in der
   * aktiven Transaktion des Aufrufers.
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   */
  public void reactivate(final Long taskId, final UUID userRef, final Long tenantId) {
    reactivate(taskId, userRef, tenantId, false);
  }

  /**
   * Reaktiviert den {@link TaskUserState} einer NEU zugewiesenen Person und hebt ihren Mute auf.
   *
   * <p>Wie {@link #reactivate}, setzt aber zusaetzlich {@code IS_MUTED} auf {@code false}: eine
   * neue Zuweisung (PUT /assignees fuer neu hinzukommende oder wieder zugewiesene Personen, Ziele
   * einer Uebergabe per HANDOVER, die vorher kein aktiver Assignee waren) soll die Person die
   * Aufgabe wieder aktiv wahrnehmen lassen (Folge von Intake #158). Pin und Flag bleiben
   * unberuehrt. Unveraendert bleibende Assignees eines PUT, reine Teilnehmer und die Marker-Pfade
   * rufen diese Methode NICHT auf. Ein neu angelegter State startet ohnehin nicht stumm. Laueft in
   * der aktiven Transaktion des Aufrufers.
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   */
  public void reactivateAsAssignee(final Long taskId, final UUID userRef, final Long tenantId) {
    reactivate(taskId, userRef, tenantId, true);
  }

  /**
   * Wie {@link #reactivate(Long, UUID, Long)}, aber mit dem vom Aufrufer VORAB gelesenen State
   * (Intake #167): liest selbst nichts. Fuer Pfade, die alle Reads vor der ersten Schreibung
   * erledigen muessen (Lock-Reihenfolge der Karte {@code participants-actions}); eine Lesung nach
   * eigenen Schreibungen kann bei parallelen Aufrufen fuer andere Personen derselben Aufgabe
   * verklemmen.
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   * @param existing Ergebnis von {@link TaskUserStateQueryRepository#findByTaskIdAndUserRef} fuer
   *     diese Person (leer, wenn sie keinen State hat)
   */
  public void reactivate(
      final Long taskId,
      final UUID userRef,
      final Long tenantId,
      final List<TaskUserState> existing) {
    apply(taskId, userRef, tenantId, false, existing);
  }

  /**
   * Wie {@link #reactivateAsAssignee(Long, UUID, Long)}, aber mit dem vom Aufrufer VORAB gelesenen
   * State (Intake #167, siehe {@link #reactivate(Long, UUID, Long, List)}).
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   * @param existing Ergebnis von {@link TaskUserStateQueryRepository#findByTaskIdAndUserRef} fuer
   *     diese Person (leer, wenn sie keinen State hat)
   */
  public void reactivateAsAssignee(
      final Long taskId,
      final UUID userRef,
      final Long tenantId,
      final List<TaskUserState> existing) {
    apply(taskId, userRef, tenantId, true, existing);
  }

  /**
   * Gemeinsame Umsetzung von {@link #reactivate} und {@link #reactivateAsAssignee} mit eigener
   * Lesung des States.
   *
   * @param clearMute {@code true}: {@code IS_MUTED} eines vorhandenen States zuruecksetzen (neue
   *     Zuweisung); {@code false}: Mute unveraendert lassen
   */
  private void reactivate(
      final Long taskId, final UUID userRef, final Long tenantId, final boolean clearMute) {
    apply(taskId, userRef, tenantId, clearMute, repository.findByTaskIdAndUserRef(taskId, userRef));
  }

  /**
   * Schreibt die Reaktivierung auf Basis eines gelesenen States: fehlt er, wird er neu angelegt,
   * sonst {@code UNSUBSCRIBED_AT} (und bei {@code clearMute} der Mute) zurueckgesetzt.
   */
  private void apply(
      final Long taskId,
      final UUID userRef,
      final Long tenantId,
      final boolean clearMute,
      final List<TaskUserState> existing) {
    if (existing.isEmpty()) {
      // Kein Eintrag vorhanden -> neu anlegen (wie ensure)
      final TaskUserState state = new TaskUserState();
      state.setTenantId(tenantId);
      state.setTaskId(taskId);
      state.setUserRef(userRef);
      state.setUserTenantId(tenantId);
      state.setPinned(false);
      state.setFlagged(false);
      state.setMuted(false);
      repository.save(state);
      log.debug(
          "UserStateService.reactivate: Neuer Eintrag angelegt fuer Task {} und User {}.",
          taskId,
          userRef);
    } else {
      // Bestehenden Eintrag reaktivieren: UNSUBSCRIBED_AT zuruecksetzen; bei neuer Zuweisung
      // zusaetzlich den Mute aufheben
      final TaskUserState state = existing.get(0);
      final boolean muteCleared = clearMute && state.isMuted();
      state.setUnsubscribedAt(null);
      if (clearMute) {
        state.setMuted(false);
      }
      repository.save(state);
      log.debug(
          "UserStateService.reactivate: UNSUBSCRIBED_AT zurueckgesetzt fuer Task {} und User {}"
              + " (Mute aufgehoben: {}).",
          taskId,
          userRef,
          muteCleared);
    }
  }

  /**
   * Setzt READ_AT auf den aktuellen Zeitpunkt fuer den Benutzer.
   *
   * <p>Wenn kein {@link TaskUserState}-Eintrag existiert, wird der Aufruf ignoriert.
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   */
  public void markRead(final Long taskId, final UUID userRef) {
    final List<TaskUserState> existing = repository.findByTaskIdAndUserRef(taskId, userRef);
    if (existing.isEmpty()) {
      log.debug(
          "UserStateService.markRead: Kein Eintrag gefunden fuer Task {} und User {} -"
              + " uebersprungen.",
          taskId,
          userRef);
      return;
    }
    final TaskUserState state = existing.get(0);
    state.setReadAt(LocalDateTime.now());
    repository.save(state);
    log.debug(
        "UserStateService.markRead: READ_AT gesetzt fuer Task {} und User {}.", taskId, userRef);
  }
}
