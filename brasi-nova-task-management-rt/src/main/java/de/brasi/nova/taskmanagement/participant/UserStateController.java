package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.participant.model.MarkerInput;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer persoenliche Marker (pin/flag/mute) (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Marker sind persoenlich (pro Benutzer) und aendern NICHT den UNSUBSCRIBED_AT-Status
 * (Inbox-Invariante: {@link UserStateService#ensure} statt reactivate).
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class UserStateController {

  private static final Logger log = LoggerFactory.getLogger(UserStateController.class);

  private final BaseTaskRepository taskRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final UserStateService userStateService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public UserStateController(
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
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = angepinnt, {@code false} = nicht angepinnt)
   * @return 204 No Content
   */
  @PutMapping("/{id}/pin")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  @Transactional
  public ResponseEntity<Void> pin(
      @PathVariable final UUID id, @RequestBody final MarkerInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    setMarker(id, actor, state -> state.setPinned(input.value()));
    log.debug(
        "UserStateController.pin: IS_PINNED={} fuer Task {} und User {}.",
        input.value(), id, actor.user().id());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Flagged-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure).
   * {@code UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = markiert, {@code false} = nicht markiert)
   * @return 204 No Content
   */
  @PutMapping("/{id}/flag")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  @Transactional
  public ResponseEntity<Void> flag(
      @PathVariable final UUID id, @RequestBody final MarkerInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    setMarker(id, actor, state -> state.setFlagged(input.value()));
    log.debug(
        "UserStateController.flag: IS_FLAGGED={} fuer Task {} und User {}.",
        input.value(), id, actor.user().id());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Muted-Marker des aktuellen Benutzers fuer eine Aufgabe.
   *
   * <p>Legt den {@link TaskUserState} bei Bedarf an (idempotent via ensure).
   * {@code UNSUBSCRIBED_AT} bleibt unveraendert (Inbox-Invariante).
   *
   * @param id    externe UUID der Aufgabe
   * @param input gewuenschter Zielwert ({@code true} = stummgeschaltet, {@code false} = nicht stumm)
   * @return 204 No Content
   */
  @PutMapping("/{id}/mute")
  @RequiresPermission(TaskManagementPermissions.TASK_READ)
  @Transactional
  public ResponseEntity<Void> mute(
      @PathVariable final UUID id, @RequestBody final MarkerInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    setMarker(id, actor, state -> state.setMuted(input.value()));
    log.debug(
        "UserStateController.mute: IS_MUTED={} fuer Task {} und User {}.",
        input.value(), id, actor.user().id());
    return ResponseEntity.noContent().build();
  }

  /**
   * Gemeinsame Marker-Set-Logik:
   * <ol>
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>{@link TaskUserState} sicherstellen via {@link UserStateService#ensure}
   *       (legt an falls nicht vorhanden; aendert UNSUBSCRIBED_AT NICHT).
   *   <li>Marker-Setter ausfuehren und speichern.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param actor          aktueller Benutzer + Mandant
   * @param markerSetter   setzt den gewuenschten Marker am {@link TaskUserState}
   */
  private void setMarker(
      final UUID taskExternalId,
      final CurrentActor actor,
      final java.util.function.Consumer<TaskUserState> markerSetter) {
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
    return task;
  }
}
