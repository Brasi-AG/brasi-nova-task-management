package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen fuer persoenliche Marker (pin/flag/mute) eines Benutzers.
 *
 * <p>Jede Methode laedt den Actor aus dem Security-Context, prueft die Mandantenzugehoerigkeit
 * der Aufgabe (404-Masking) und stellt den {@link TaskUserState} via
 * {@link UserStateService#ensure} sicher, ohne {@code UNSUBSCRIBED_AT} zu veraendern
 * (Inbox-Invariante).
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 */
@Service
public class MarkerService {

  private static final Logger log = LoggerFactory.getLogger(MarkerService.class);

  private final BaseTaskRepository taskRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final UserStateService userStateService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public MarkerService(
      final BaseTaskRepository taskRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final UserStateService userStateService,
      final UserAccessor userAccessor) {
    this.taskRepository = taskRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.userStateService = userStateService;
    this.userAccessor = userAccessor;
  }

  /**
   * Setzt den Pinned-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure).
   * {@code UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param taskUuid externe UUID der Aufgabe
   * @param value    gewuenschter Zielwert ({@code true} = angepinnt)
   */
  @Transactional
  public void setPinned(final UUID taskUuid, final boolean value) {
    applyMarker(taskUuid, state -> state.setPinned(value));
    log.debug("MarkerService.setPinned: IS_PINNED={} fuer Task {}.", value, taskUuid);
  }

  /**
   * Setzt den Flagged-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure).
   * {@code UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param taskUuid externe UUID der Aufgabe
   * @param value    gewuenschter Zielwert ({@code true} = markiert)
   */
  @Transactional
  public void setFlagged(final UUID taskUuid, final boolean value) {
    applyMarker(taskUuid, state -> state.setFlagged(value));
    log.debug("MarkerService.setFlagged: IS_FLAGGED={} fuer Task {}.", value, taskUuid);
  }

  /**
   * Setzt den Muted-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure).
   * {@code UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param taskUuid externe UUID der Aufgabe
   * @param value    gewuenschter Zielwert ({@code true} = stummgeschaltet)
   */
  @Transactional
  public void setMuted(final UUID taskUuid, final boolean value) {
    applyMarker(taskUuid, state -> state.setMuted(value));
    log.debug("MarkerService.setMuted: IS_MUTED={} fuer Task {}.", value, taskUuid);
  }

  /**
   * Gemeinsame Marker-Set-Logik:
   * <ol>
   *   <li>Actor aus dem Security-Context laden.
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>{@link TaskUserState} sicherstellen via {@link UserStateService#ensure}
   *       (legt an falls nicht vorhanden; aendert UNSUBSCRIBED_AT NICHT - Inbox-Invariante).
   *   <li>Marker-Setter ausfuehren und speichern.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param markerSetter   setzt den gewuenschten Marker am {@link TaskUserState}
   */
  private void applyMarker(
      final UUID taskExternalId,
      final java.util.function.Consumer<TaskUserState> markerSetter) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID userRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // UserState sicherstellen: ensure legt an (falls nicht vorhanden),
    // aendert UNSUBSCRIBED_AT NICHT (Inbox-Invariante)
    userStateService.ensure(taskId, userRef, tenantId);

    final List<TaskUserState> states =
        userStateQueryRepository.findByTaskIdAndUserRef(taskId, userRef);
    final TaskUserState state = states.get(0);

    markerSetter.accept(state);
    userStateQueryRepository.save(state);
  }

  /**
   * Laedt eine Aufgabe anhand ihrer externen UUID und prueft die Mandantenzugehoerigkeit.
   *
   * <p>Beide Fehlerfaelle (nicht gefunden / fremder Mandant) liefern eine identische, neutrale
   * {@link EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben
   * (arch-v2 §8 / Cross-Tenant-Disclosure-Schutz).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId       erwarteter Mandanten-Schluessel
   * @return die gefundene Aufgabe
   * @throws EntityNotFoundException wenn nicht gefunden oder fremdem Tenant zugehoerig (HTTP 404)
   */
  private Task requireTask(final UUID taskExternalId, final long tenantId) {
    final Optional<Task> opt = taskRepository.findByUuid(taskExternalId.toString());
    if (opt.isEmpty()) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    final Task task = opt.get();
    if (!Objects.equals(tenantId, task.getTenantId())) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    // Final-Review P1-1: logisch deaktivierte Aufgaben neutral wie fremder Tenant maskieren (404).
    if (task.getEntityState() != io.keel.framework.api.model.EntityState.ACTIVE) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    return task;
  }
}
