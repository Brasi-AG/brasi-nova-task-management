package de.brasi.nova.taskmanagement.attachment;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Zugriff auf die Aufgabe eines Anhang-Endpunkts mit Mitgliedschaftspruefung (Intake #152).
 *
 * <p>Laedt die Aufgabe per externer UUID und maskiert eine unbekannte, mandantenfremde oder
 * deaktivierte Aufgabe neutral als 404 (wie {@code CommentService#requireTask}); erst danach prueft
 * der {@link TaskMembershipGuard} die Mitgliedschaft (Nicht- oder Ex-Mitglied ebenfalls neutrale
 * 404). Alle Anhang-Endpunkte gehen ueber diese Klasse, damit Lesen (READ-Mitglied, auch
 * Ausgetretene) und Schreiben (WRITE-Mitglied) ueberall gleich geprueft werden.
 */
@Component
public class AttachmentTaskAccess {

  private static final String TASK_NOT_FOUND = "Aufgabe nicht gefunden.";

  private final BaseTaskRepository taskRepository;
  private final TaskMembershipGuard membershipGuard;

  /**
   * Konstruktor-Injektion.
   *
   * @param taskRepository Repository der Aufgaben
   * @param membershipGuard Pruefung der Mitgliedschaft
   */
  public AttachmentTaskAccess(
      final BaseTaskRepository taskRepository, final TaskMembershipGuard membershipGuard) {
    this.taskRepository = taskRepository;
    this.membershipGuard = membershipGuard;
  }

  /**
   * Laedt die Aufgabe und verlangt, dass der Akteur READ-Mitglied ist (auch Ausgetretene).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param actor aktueller Akteur
   * @return die sichtbare Aufgabe
   * @throws EntityNotFoundException wenn die Aufgabe unbekannt, mandantenfremd oder deaktiviert ist
   *     oder der Akteur kein Mitglied ist (HTTP 404)
   */
  public Task requireReadMember(final UUID taskExternalId, final CurrentActor actor) {
    final Task task = requireVisibleTask(taskExternalId, actor.tenant().id());
    membershipGuard.requireReadMember(task.getId(), actor.user().id(), actor.tenant().id());
    return task;
  }

  /**
   * Laedt die Aufgabe und verlangt, dass der Akteur WRITE-Mitglied ist (aktiv, nicht ausgetreten).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param actor aktueller Akteur
   * @return die sichtbare Aufgabe
   * @throws EntityNotFoundException wenn die Aufgabe unbekannt, mandantenfremd oder deaktiviert ist
   *     oder der Akteur kein aktives Mitglied ist (HTTP 404)
   */
  public Task requireWriteMember(final UUID taskExternalId, final CurrentActor actor) {
    final Task task = requireVisibleTask(taskExternalId, actor.tenant().id());
    membershipGuard.requireWriteMember(task.getId(), actor.user().id(), actor.tenant().id());
    return task;
  }

  /**
   * Laedt die Aufgabe des Kontext-Mandanten; jede andere Lage ergibt dieselbe neutrale 404, ohne
   * dass der Guard gefragt wird.
   */
  private Task requireVisibleTask(final UUID taskExternalId, final long tenantId) {
    final Optional<Task> found = taskRepository.findByUuid(taskExternalId.toString());
    if (found.isEmpty()) {
      throw new EntityNotFoundException(TASK_NOT_FOUND);
    }
    final Task task = found.get();
    if (!Objects.equals(tenantId, task.getTenantId())
        || task.getEntityState() != EntityState.ACTIVE) {
      throw new EntityNotFoundException(TASK_NOT_FOUND);
    }
    return task;
  }
}
