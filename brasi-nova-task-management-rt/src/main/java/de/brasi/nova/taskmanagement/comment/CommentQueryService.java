package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskCommentQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lese-Service fuer die Kommentare einer Aufgabe (BE-2): liefert die flache, chronologische
 * Kommentarliste, aus der das Frontend den Antwort-Baum ueber {@code parentCommentId} rekonstruiert.
 *
 * <p>Autorisierung task-scoped wie {@code TaskDetailService.loadDetail}: Standard-Selfservice
 * (Slice 2/Auflage 3) ersetzt das grobe {@code TASK_READ}-Gate durch den objekt-level
 * {@link TaskMembershipGuard}. Nach der Mandanten-/ACTIVE-Pruefung (404-Maskierung bei fremder/
 * fehlender/deaktivierter Aufgabe) wird {@code requireReadMember} erzwungen - konsistent mit dem
 * begleitenden {@code GET /tasks/{id}}: nur READ-Member (aktive PERSON-Zeile im Task-Mandanten,
 * ausgetretene eingeschlossen) sehen die Kommentare; Non-Member -> neutrale 404.
 */
@Service
public class CommentQueryService {

  private final BaseTaskRepository taskRepository;
  private final TaskCommentQueryRepository commentQueryRepository;
  private final UserAccessor userAccessor;
  private final AuditTimeConverter auditTimeConverter;
  private final TaskMembershipGuard membershipGuard;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public CommentQueryService(
      final BaseTaskRepository taskRepository,
      final TaskCommentQueryRepository commentQueryRepository,
      final UserAccessor userAccessor,
      final AuditTimeConverter auditTimeConverter,
      final TaskMembershipGuard membershipGuard) {
    this.taskRepository = taskRepository;
    this.commentQueryRepository = commentQueryRepository;
    this.userAccessor = userAccessor;
    this.auditTimeConverter = auditTimeConverter;
    this.membershipGuard = membershipGuard;
  }

  /**
   * Liefert alle aktiven Kommentare einer Aufgabe als flache, chronologische Liste.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @return Kommentare (chronologisch aufsteigend); {@code parentCommentId} als externe UUID des
   *     Eltern-Kommentars oder {@code null} (Top-Level ODER Eltern nicht mehr aktiv)
   * @throws EntityNotFoundException wenn die Aufgabe nicht gefunden, fremdem Mandanten zugehoerig
   *     oder deaktiviert ist (HTTP 404, neutral maskiert)
   */
  public List<CommentModel> getComments(final UUID taskExternalId) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);

    // Standard-Selfservice (Slice 2/Auflage 3): READ-Member-Guard NACH Tenant-/ACTIVE-Check und VOR
    // dem Kommentar-Read. Non-Member -> neutrale 404 (konsistent zu GET /tasks/{id}).
    membershipGuard.requireReadMember(task.getId(), actorRef, tenantId);

    final List<TaskComment> comments =
        commentQueryRepository.findActiveByTaskIdOrdered(task.getId());

    // interne Kommentar-ID -> externe UUID (fuer die parentCommentId-Aufloesung). Der Eltern-
    // Kommentar liegt per POST-Invariante in derselben Aufgabe; ist er nicht (mehr) im aktiven Set,
    // bleibt parentCommentId null (FE zeigt den Kommentar als Top-Level; keine Dangling-Referenz).
    final Map<Long, UUID> idToUuid = new LinkedHashMap<>();
    for (final TaskComment comment : comments) {
      idToUuid.put(comment.getId(), comment.getUuid());
    }

    return comments.stream().map(c -> toModel(c, taskExternalId, idToUuid)).toList();
  }

  /** Mappt eine {@link TaskComment}-Entity auf das {@link CommentModel} (parentId intern -> UUID). */
  private CommentModel toModel(
      final TaskComment comment, final UUID taskExternalId, final Map<Long, UUID> idToUuid) {
    final UUID parentUuid =
        comment.getParentCommentId() == null ? null : idToUuid.get(comment.getParentCommentId());
    return new CommentModel(
        comment.getUuid(),
        taskExternalId,
        comment.getAuthorRef(),
        parentUuid,
        comment.getText(),
        comment.isEdited(),
        auditTimeConverter.toInstant(comment.getCreatedAt()));
  }

  /**
   * Laedt eine Aufgabe anhand ihrer externen UUID und prueft die Mandantenzugehoerigkeit.
   *
   * <p>Nicht gefunden / fremder Mandant / DEACTIVE liefern eine identische, neutrale
   * {@link EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben
   * (Cross-Tenant-Disclosure-Schutz; Muster wie {@code CommentService.requireTask}).
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
    if (task.getEntityState() != EntityState.ACTIVE) {
      throw new EntityNotFoundException("Aufgabe nicht gefunden.");
    }
    return task;
  }
}
