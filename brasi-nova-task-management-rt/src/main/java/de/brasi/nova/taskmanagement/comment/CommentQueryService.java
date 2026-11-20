package de.brasi.nova.taskmanagement.comment;

import de.brasi.nova.taskmanagement.attachment.AttachmentQueryService;
import de.brasi.nova.taskmanagement.attachment.model.AttachmentModel;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskComment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskCommentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.user.UserAccessor;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lese-Service fuer die Kommentare einer Aufgabe (BE-2): liefert die flache, chronologische
 * Kommentarliste, aus der das Frontend den Antwort-Baum ueber {@code parentCommentId}
 * rekonstruiert.
 *
 * <p>Autorisierung task-scoped wie {@code TaskDetailService.loadDetail}: Standard-Selfservice
 * (Slice 2/Auflage 3) ersetzt das grobe {@code TASK_READ}-Gate durch den objekt-level {@link
 * TaskMembershipGuard}. Nach der Mandanten-/ACTIVE-Pruefung (404-Maskierung bei fremder/
 * fehlender/deaktivierter Aufgabe) wird {@code requireReadMember} erzwungen - konsistent mit dem
 * begleitenden {@code GET /tasks/{id}}: nur READ-Member (aktive PERSON-Zeile im Task-Mandanten,
 * ausgetretene eingeschlossen) sehen die Kommentare; Non-Member -> neutrale 404.
 *
 * <p>Neu-Kennzeichen (Intake #150): je Kommentar {@code isNew} fuer den Anfrager, abgeleitet aus
 * seinem Gesehen-Zeitpunkt {@code TASK_USER_STATE.SEEN_AT}. Der Lesepfad liest diesen Wert nur und
 * veraendert ihn nie; gesetzt wird er ausschliesslich ueber {@code PUT /api/v1/tasks/{id}/seen}.
 *
 * <p>Anhaenge (Intake #152 B1): je Kommentar seine aktiven Anhaenge, gelesen nach dem READ-Guard in
 * genau einer Abfrage fuer die ganze Liste ({@link
 * AttachmentQueryService#commentAttachmentsByCommentId}) und hier den aktiven Kommentaren
 * zugeordnet; ohne Anhang eine leere Liste.
 */
@Service
public class CommentQueryService {

  private final BaseTaskRepository taskRepository;
  private final TaskCommentQueryRepository commentQueryRepository;
  private final UserAccessor userAccessor;
  private final AuditTimeConverter auditTimeConverter;
  private final TaskMembershipGuard membershipGuard;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final AttachmentQueryService attachmentQueryService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public CommentQueryService(
      final BaseTaskRepository taskRepository,
      final TaskCommentQueryRepository commentQueryRepository,
      final UserAccessor userAccessor,
      final AuditTimeConverter auditTimeConverter,
      final TaskMembershipGuard membershipGuard,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final AttachmentQueryService attachmentQueryService) {
    this.taskRepository = taskRepository;
    this.commentQueryRepository = commentQueryRepository;
    this.userAccessor = userAccessor;
    this.auditTimeConverter = auditTimeConverter;
    this.membershipGuard = membershipGuard;
    this.userStateQueryRepository = userStateQueryRepository;
    this.attachmentQueryService = attachmentQueryService;
  }

  /**
   * Liefert alle aktiven Kommentare einer Aufgabe als flache, chronologische Liste.
   *
   * @param taskExternalId externe UUID der Aufgabe
   *     <p>{@code isNew} je Kommentar: {@code true}, wenn der Kommentar nicht vom Anfrager stammt
   *     und nach dessen Gesehen-Zeitpunkt erstellt wurde. Ohne Gesehen-Zeitpunkt (erster Besuch,
   *     kein {@code SEEN_AT} oder kein persoenlicher State) gelten alle Kommentare anderer als neu;
   *     eigene Kommentare sind nie neu. Verglichen wird die Audit-Zeit {@code ANGELEGT_AM} des
   *     Kommentars mit {@code SEEN_AT}; beide sind lokale Wanduhr derselben Persistenz-Zone. Dieser
   *     Lesepfad schreibt nichts, auch nicht den Gesehen-Zeitpunkt.
   *     <p>{@code attachments} je Kommentar: seine aktiven Anhaenge in Hochlade-Reihenfolge, nicht
   *     entfernbar; gelesen in einer Abfrage nach dem READ-Guard (ohne Kommentare keine).
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

    // Gesehen-Zeitpunkt des Anfragers (Intake #150): nur lesen, nie schreiben. Hoechstens eine
    // aktive State-Zeile je (Aufgabe, Benutzer) (UQ_TASK_USER_STATE_TASK_USER).
    final LocalDateTime seenAt =
        userStateQueryRepository.findByTaskIdAndUserRef(task.getId(), actorRef).stream()
            .map(TaskUserState::getSeenAt)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

    // Intake #152 B1: Anhaenge aller Kommentare in EINER Abfrage (kein N+1), erst nach dem Guard.
    // Anhaenge nicht (mehr) aktiver Kommentare finden hier keinen Kommentar und fallen weg.
    final Map<Long, List<AttachmentModel>> attachmentsByComment =
        comments.isEmpty() ? Map.of() : attachmentQueryService.commentAttachmentsByCommentId(task);

    return comments.stream()
        .map(
            c ->
                toModel(
                    c,
                    taskExternalId,
                    idToUuid,
                    isNewFor(c, actorRef, seenAt),
                    attachmentsByComment.getOrDefault(c.getId(), List.of())))
        .toList();
  }

  /**
   * Entscheidet, ob ein Kommentar fuer den Anfrager neu ist: nicht von ihm selbst und nach seinem
   * Gesehen-Zeitpunkt erstellt; ohne Gesehen-Zeitpunkt ist jeder fremde Kommentar neu.
   *
   * @param comment Kommentar
   * @param actorRef UUID des Anfragers
   * @param seenAt Gesehen-Zeitpunkt des Anfragers (lokale Wanduhr) oder {@code null}
   * @return {@code true}, wenn der Kommentar fuer den Anfrager neu ist
   */
  static boolean isNewFor(
      final TaskComment comment, final UUID actorRef, final LocalDateTime seenAt) {
    if (actorRef.equals(comment.getAuthorRef())) {
      return false;
    }
    if (seenAt == null) {
      return true;
    }
    return comment.getCreatedAt() != null && comment.getCreatedAt().isAfter(seenAt);
  }

  /**
   * Mappt eine {@link TaskComment}-Entity auf das {@link CommentModel} (parentId intern -> UUID)
   * mit den bereits gelesenen Anhaengen des Kommentars.
   */
  private CommentModel toModel(
      final TaskComment comment,
      final UUID taskExternalId,
      final Map<Long, UUID> idToUuid,
      final boolean isNew,
      final List<AttachmentModel> attachments) {
    final UUID parentUuid =
        comment.getParentCommentId() == null ? null : idToUuid.get(comment.getParentCommentId());
    return new CommentModel(
        comment.getUuid(),
        taskExternalId,
        comment.getAuthorRef(),
        parentUuid,
        comment.getText(),
        comment.isEdited(),
        auditTimeConverter.toInstant(comment.getCreatedAt()),
        isNew,
        attachments);
  }

  /**
   * Laedt eine Aufgabe anhand ihrer externen UUID und prueft die Mandantenzugehoerigkeit.
   *
   * <p>Nicht gefunden / fremder Mandant / DEACTIVE liefern eine identische, neutrale {@link
   * EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben
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
