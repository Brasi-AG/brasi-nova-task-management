package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.user.UserAccessor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
  private final DictionaryCodeResolver resolver;
  private final UserAccessor userAccessor;
  private final UserStateService userStateService;
  private final TaskHistoryService historyService;
  private final InboxSignalService inboxSignalService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TaskCommandService(
      final BaseTaskRepository taskRepository,
      final BaseTaskParticipantRepository participantRepository,
      final DictionaryCodeResolver resolver,
      final UserAccessor userAccessor,
      final UserStateService userStateService,
      final TaskHistoryService historyService,
      final InboxSignalService inboxSignalService) {
    this.taskRepository = taskRepository;
    this.participantRepository = participantRepository;
    this.resolver = resolver;
    this.userAccessor = userAccessor;
    this.userStateService = userStateService;
    this.historyService = historyService;
    this.inboxSignalService = inboxSignalService;
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

    // Prioritaet: aus Input oder Default NORMAL
    final Long priorityId;
    if (input.priorityCode() != null && !input.priorityCode().isBlank()) {
      priorityId = resolver.resolveTaskPriority(input.priorityCode());
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
   * Nach Commit: {@link InboxSignalService#invalidate} fuer alle Teilnehmer.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          Patch-Eingabe
   * @throws BusinessRuleException wenn die Aufgabe nicht gefunden wird oder fremdem Tenant gehoert
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

    // statusCode
    if (input.statusCode().isPresent()) {
      final String newStatusCode = input.statusCode().get();
      final Long newStatusId = resolver.resolveTaskStatus(newStatusCode);
      historyService.record(
          taskId, tenantId, actorRef, "Status geaendert", "-> " + newStatusCode);
      task.setTaskStatusId(newStatusId);
      changed = true;
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

    // After-Commit: snapshot-invalidated Signal registrieren (auch wenn changed=false,
    // damit idempotente Aufrufe keine stillen Skips verursachen - sicherheitshalber nur
    // wenn tatsaechlich etwas geaendert wurde)
    if (changed) {
      inboxSignalService.invalidate(tenantId, List.of(actorRef));
    }
  }

  /**
   * Laedt eine Aufgabe anhand ihrer externen UUID und prueft die Mandantenzugehoerigkeit.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId       erwarteter Mandanten-Schluessel
   * @return die gefundene Aufgabe
   * @throws BusinessRuleException wenn nicht gefunden oder falschem Tenant zugehoerig
   */
  private Task requireTask(final UUID taskExternalId, final long tenantId) {
    final Optional<Task> opt = taskRepository.findByUuid(taskExternalId.toString());
    if (opt.isEmpty()) {
      throw new BusinessRuleException(
          "Aufgabe mit UUID " + taskExternalId + " nicht gefunden.");
    }
    final Task task = opt.get();
    if (!Objects.equals(tenantId, task.getTenantId())) {
      throw new BusinessRuleException(
          "Aufgabe " + taskExternalId + " gehoert nicht dem aktuellen Mandanten.");
    }
    return task;
  }
}
