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
   * <p>Wenn kein Eintrag existiert, wird ein neuer angelegt. Wenn bereits einer vorhanden ist,
   * bleibt er unveraendert. Laueft in der aktiven Transaktion des Aufrufers.
   *
   * @param taskId   interne Aufgaben-ID
   * @param userRef  UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
   * @param tenantId interner Mandanten-Schluessel
   */
  public void ensure(final Long taskId, final UUID userRef, final Long tenantId) {
    final List<TaskUserState> existing = repository.findByTaskIdAndUserRef(taskId, userRef);
    if (!existing.isEmpty()) {
      log.debug(
          "UserStateService.ensure: Eintrag bereits vorhanden fuer Task {} und User {}.",
          taskId,
          userRef);
      return;
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
