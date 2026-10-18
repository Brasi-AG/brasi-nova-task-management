package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verwaltet den benutzerspezifischen Zustand ({@link TaskUserState}) fuer Aufgaben.
 *
 * <p>Alle Schreiboperationen laufen in der aktiven Transaktion des Aufrufers
 * (kein eigenes {@code @Transactional} - KSF-{@code NovaRepository.save()} hat selbst Tx-Propagation).
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
   * <p>Wenn kein Eintrag existiert, wird ein neuer (aktiver) angelegt. Wenn bereits einer
   * vorhanden ist, bleibt er unveraendert. Laueft in der aktiven Transaktion des Aufrufers.
   *
   * @param taskId   interne Aufgaben-ID
   * @param userRef  UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
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
    repository.save(state);
    log.debug(
        "UserStateService.ensure: Neuer Eintrag angelegt fuer Task {} und User {}.",
        taskId,
        userRef);
    return true;
  }

  /**
   * Reaktiviert den {@link TaskUserState} eines erneut zugewiesenen Benutzers.
   *
   * <p>Stellt sicher, dass ein Eintrag existiert (wie {@link #ensure}), und setzt
   * {@code UNSUBSCRIBED_AT} auf {@code null} zurueck, sodass der Benutzer wieder in der
   * aktiven Inbox erscheint ({@code findActivePage} filtert auf {@code UNSUBSCRIBED_AT IS NULL}).
   * Laueft in der aktiven Transaktion des Aufrufers.
   *
   * @param taskId   interne Aufgaben-ID
   * @param userRef  UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   */
  public void reactivate(final Long taskId, final UUID userRef, final Long tenantId) {
    final List<TaskUserState> existing = repository.findByTaskIdAndUserRef(taskId, userRef);
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
      // Bestehenden Eintrag reaktivieren: UNSUBSCRIBED_AT zuruecksetzen
      final TaskUserState state = existing.get(0);
      state.setUnsubscribedAt(null);
      repository.save(state);
      log.debug(
          "UserStateService.reactivate: UNSUBSCRIBED_AT zurueckgesetzt fuer Task {} und User {}.",
          taskId,
          userRef);
    }
  }

  /**
   * Setzt READ_AT auf den aktuellen Zeitpunkt fuer den Benutzer.
   *
   * <p>Wenn kein {@link TaskUserState}-Eintrag existiert, wird der Aufruf ignoriert.
   *
   * @param taskId  interne Aufgaben-ID
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
        "UserStateService.markRead: READ_AT gesetzt fuer Task {} und User {}.",
        taskId,
        userRef);
  }
}
