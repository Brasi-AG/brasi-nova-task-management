package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen fuer Aufgaben.
 *
 * <p>Alle Mutationen (create/patch) laufen in einer eigenen {@code @Transactional}-Transaktion.
 * Nach erfolgtem Commit wird via {@link InboxSignalService} ein {@code snapshot-invalidated}-Signal
 * an die betroffenen Benutzer gesendet.
 *
 * <p>Creator-Invariante: Bei {@link #create} wird in DERSELBEN Transaktion verbindlich ein
 * {@link TaskParticipant} (CREATOR_FLAG=1, ASSIGNEE_FLAG=1) und ein {@link
 * de.brasi.nova.taskmanagement.entity.TaskUserState} fuer den Ersteller angelegt. Ohne
 * diesen Eintrag verschwaaende der neue Task aus der eigenen user-scoped Inbox.
 */
@Service
public class TaskCommandService {

  private static final Logger log = LoggerFactory.getLogger(TaskCommandService.class);

  private final BaseTaskRepository taskRepository;
  private final BaseTaskParticipantRepository participantRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final DictionaryCodeResolver resolver;
  private final UserAccessor userAccessor;
  private final UserStateService userStateService;
  private final TaskHistoryService historyService;
  private final InboxSignalService inboxSignalService;
  private final TaskTagRepository tagRepository;
  private final TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TaskCommandService(
      final BaseTaskRepository taskRepository,
      final BaseTaskParticipantRepository participantRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final DictionaryCodeResolver resolver,
      final UserAccessor userAccessor,
      final UserStateService userStateService,
      final TaskHistoryService historyService,
      final InboxSignalService inboxSignalService,
      final TaskTagRepository tagRepository,
      final TaskTagAssignmentQueryRepository tagAssignmentQueryRepository) {
    this.taskRepository = taskRepository;
    this.participantRepository = participantRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.resolver = resolver;
    this.userAccessor = userAccessor;
    this.userStateService = userStateService;
    this.historyService = historyService;
    this.inboxSignalService = inboxSignalService;
    this.tagRepository = tagRepository;
    this.tagAssignmentQueryRepository = tagAssignmentQueryRepository;
  }

  /**
   * Legt eine neue Aufgabe an.
   *
   * <p>Erzwingt:
   * <ul>
   *   <li>Direction = {@code CUSTOMER_INTERNAL} (serverseitig, unabhaengig vom Input)
   *   <li>Status = {@code OPEN}
   *   <li>Prioritaet = {@code priorityCode ?? NORMAL}
   *   <li>ORIGIN_TENANT_ID = TENANT_ID (CUSTOMER_INTERNAL-Semantik)
   *   <li>LAST_ACTIVITY_AT = jetzt
   * </ul>
   *
   * <p>In DERSELBEN Transaktion: Creator-{@link TaskParticipant} + {@link
   * de.brasi.nova.taskmanagement.entity.TaskUserState} fuer den Ersteller.
   *
   * <p>Nach Commit: {@link InboxSignalService#invalidate} fuer den Ersteller.
   *
   * @param input Eingabe-DTO
   * @return externe UUID der angelegten Aufgabe
   */
  @Transactional
  public UUID create(final TaskCreateInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID creatorRef = actor.user().id();

    // Direction serverseitig erzwingen
    final Long directionId = resolver.resolveTaskDirection(TaskDirectionCodes.CUSTOMER_INTERNAL);
    final Long statusId = resolver.resolveTaskStatus(TaskStatusCodes.OPEN);

    // Prioritaet: aus Input oder Default NORMAL; unbekannter Client-Code -> 400
    final Long priorityId;
    if (input.priorityCode() != null && !input.priorityCode().isBlank()) {
      try {
        priorityId = resolver.resolveTaskPriority(input.priorityCode());
      } catch (DictionaryResolverException e) {
        throw new EntityValidationException(
            "Ungueltiger priorityCode: '" + input.priorityCode() + "'.");
      }
    } else {
      priorityId = resolver.resolveTaskPriority(TaskPriorityCodes.NORMAL);
    }

    // Aufgabe anlegen
    final Task task = new Task();
    task.setTenantId(tenantId);
    task.setTitle(input.title());
    task.setDescription(input.description());
    task.setTaskStatusId(statusId);
    task.setPriorityId(priorityId);
    task.setDirectionId(directionId);
    task.setOriginTenantId(tenantId);
    task.setStartDate(input.startDate());
    task.setDueDate(input.dueDate());
    task.setLastActivityAt(LocalDateTime.now());
    task.setCommentCount(0);

    final Task saved = taskRepository.save(task);
    final Long taskId = saved.getId();
    final UUID taskExternalId = saved.getUuid();

    log.info(
        "TaskCommandService.create: Aufgabe {} (id={}) angelegt von Benutzer {} in Mandant {}.",
        taskExternalId,
        taskId,
        creatorRef,
        tenantId);

    // Creator-Participant in DERSELBEN Transaktion anlegen (Invariante: Inbox-Sichtbarkeit)
    final TaskParticipant participant = new TaskParticipant();
    participant.setTenantId(tenantId);
    participant.setTaskId(taskId);
    participant.setParticipantRef(creatorRef);
    participant.setParticipantKind(ParticipantKind.PERSON);
    participant.setParticipantTenantId(tenantId);
    participant.setCreatorFlag(true);
    participant.setAssigneeFlag(true);
    participant.setParticipantFlag(true);
    participantRepository.save(participant);

    // UserState fuer Creator in DERSELBEN Transaktion sicherstellen
    userStateService.ensure(taskId, creatorRef, tenantId);

    // After-Commit: snapshot-invalidated Signal registrieren
    inboxSignalService.invalidate(tenantId, List.of(creatorRef));

    return taskExternalId;
  }

  /**
   * Patcht eine bestehende Aufgabe (title, dueDate, statusCode).
   *
   * <p>Legt einen Historieneintrag und aktualisiert LAST_ACTIVITY_AT in DERSELBEN Transaktion.
   * Nach Commit: {@link InboxSignalService#invalidate} fuer alle aktiven Teilnehmer.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          Patch-Eingabe
   * @throws EntityNotFoundException wenn die Aufgabe nicht gefunden wird oder fremdem Tenant gehoert
   * @throws EntityValidationException bei unbekanntem statusCode
   */
  @Transactional
  public void patch(final UUID taskExternalId, final TaskPatchInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();
    boolean changed = false;

    // title
    if (input.title().isPresent()) {
      final String newTitle = input.title().get();
      historyService.record(
          taskId, tenantId, actorRef, "Titel geaendert", task.getTitle() + " -> " + newTitle);
      task.setTitle(newTitle);
      changed = true;
    }

    // dueDate
    if (input.dueDate().isPresent()) {
      final java.time.LocalDate newDueDate = input.dueDate().get();
      historyService.record(
          taskId,
          tenantId,
          actorRef,
          "Faelligkeitsdatum geaendert",
          task.getDueDate() + " -> " + newDueDate);
      task.setDueDate(newDueDate);
      changed = true;
    }

    // statusCode: Client-Eingabe validieren -> unbekannt/leer -> 400
    if (input.statusCode().isPresent()) {
      final String newStatusCode = input.statusCode().get();
      if (newStatusCode == null || newStatusCode.isBlank()) {
        throw new EntityValidationException("statusCode darf nicht leer sein.");
      }
      final Long newStatusId;
      try {
        newStatusId = resolver.resolveTaskStatus(newStatusCode);
      } catch (DictionaryResolverException e) {
        throw new EntityValidationException(
            "Ungueltiger statusCode: '" + newStatusCode + "'.");
      }
      historyService.record(
          taskId, tenantId, actorRef, "Status geaendert", "-> " + newStatusCode);
      task.setTaskStatusId(newStatusId);
      changed = true;
    }

    // tagIds: PUT-Set-Semantik (R-PATCH)
    if (input.tagIds().isPresent()) {
      final Set<UUID> desiredTagUuids =
          input.tagIds().get() != null ? input.tagIds().get() : Set.of();

      // null-Element-Pruefung: {"tagIds":[null]} -> 400 statt NPE (F-P2b).
      // Iteration statt contains(null): ImmutableSet.contains(null) wuerde NPE ausloesen.
      for (final UUID tagUuid : desiredTagUuids) {
        if (tagUuid == null) {
          throw new EntityValidationException(
              "tagIds darf keine null-Elemente enthalten.");
        }
      }

      // Alle Tag-UUIDs gegen den globalen Katalog aufloesen (unbekannt/inaktiv/abgelaufen -> 400)
      // Verwendet findActiveByUuid (F-P1): filtert ENTITY_STATUS + Gueltigkeit;
      // findByUuid wuerde inaktive/abgelaufene Tags akzeptieren (R-PATCH-Verletzung).
      // Abbruch-sicher: alle validieren BEVOR irgendwas geaendert wird
      final Map<UUID, Long> tagUuidToId =
          TenantContextHolder.runWithTenant(TenantInfo.global(), () -> {
            final Map<UUID, Long> result = new java.util.HashMap<>();
            for (final UUID tagUuid : desiredTagUuids) {
              final Optional<de.brasi.nova.taskmanagement.entity.TaskTag> found =
                  tagRepository.findActiveByUuid(tagUuid.toString());
              if (found.isEmpty()) {
                throw new EntityValidationException(
                    "Unbekannte oder inaktive Tag-UUID: '" + tagUuid + "'.");
              }
              result.put(tagUuid, found.get().getId());
            }
            return result;
          });

      // Aktuelle aktive Assignments laden
      final List<TaskTagAssignment> currentAssignments =
          tagAssignmentQueryRepository.findActiveByTaskId(taskId);
      final Map<Long, TaskTagAssignment> currentByTagId = currentAssignments.stream()
          .collect(Collectors.toMap(TaskTagAssignment::getTagId, a -> a));

      // Neue Tag-IDs: gewuenschaft aber noch nicht vorhanden
      final Set<Long> desiredTagIds = new HashSet<>(tagUuidToId.values());
      final Set<Long> toAdd = desiredTagIds.stream()
          .filter(id -> !currentByTagId.containsKey(id))
          .collect(Collectors.toSet());

      // Abgewaehlte Tag-IDs: vorhanden aber nicht gewuenscht
      final List<Long> toRemoveIds = currentByTagId.entrySet().stream()
          .filter(e -> !desiredTagIds.contains(e.getKey()))
          .map(e -> e.getValue().getId())
          .collect(Collectors.toList());

      if (!toAdd.isEmpty() || !toRemoveIds.isEmpty()) {
        // Neue Assignments anlegen oder inaktive reaktivieren (UNIQUE-Guard: [TASK_ID, TAG_ID])
        for (final Long tagId : toAdd) {
          final java.util.Optional<TaskTagAssignment> existing =
              tagAssignmentQueryRepository.findByTaskIdAndTagId(taskId, tagId);
          if (existing.isPresent()) {
            // Bereits vorhandener (inaktiver) Eintrag: reaktivieren statt neuen anlegen
            final TaskTagAssignment exAssignment = existing.get();
            exAssignment.setEntityState(io.keel.framework.api.model.EntityState.ACTIVE);
            tagAssignmentQueryRepository.save(exAssignment);
          } else {
            // Kein Eintrag: neu anlegen
            final TaskTagAssignment assignment = new TaskTagAssignment();
            assignment.setTenantId(tenantId);
            assignment.setTaskId(taskId);
            assignment.setTagId(tagId);
            tagAssignmentQueryRepository.save(assignment);
          }
        }
        // Abgewaehlte deaktivieren (KSF-idiomatisch)
        if (!toRemoveIds.isEmpty()) {
          tagAssignmentQueryRepository.deactivateAllByIds(toRemoveIds);
        }
        changed = true;
        log.info(
            "TaskCommandService.patch: Tag-Menge fuer Aufgabe {} aktualisiert"
                + " (hinzugefuegt={}, entfernt={}).",
            taskExternalId, toAdd.size(), toRemoveIds.size());
      }
    }

    if (changed) {
      task.setLastActivityAt(LocalDateTime.now());
      taskRepository.save(task);
      log.info(
          "TaskCommandService.patch: Aufgabe {} von Benutzer {} in Mandant {} aktualisiert.",
          taskExternalId,
          actorRef,
          tenantId);
    }

    // After-Commit: snapshot-invalidated Signal fuer ALLE aktiven Teilnehmer registrieren.
    // Aktive Teilnehmer = TASK_USER_STATE mit UNSUBSCRIBED_AT IS NULL (innerhalb dieser Tx ermittelt).
    if (changed) {
      final List<UUID> activeUserRefs = userStateQueryRepository
          .findActiveUserRefsByTaskId(taskId)
          .stream()
          .map(TaskUserState::getUserRef)
          .collect(Collectors.toList());
      if (!activeUserRefs.isEmpty()) {
        inboxSignalService.invalidate(tenantId, activeUserRefs);
      }
    }
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
