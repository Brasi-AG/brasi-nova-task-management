package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
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
import java.util.LinkedHashSet;
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
  private final UserReadRepository userReadRepository;
  private final NotificationService notificationService;
  private final InboxGenerationService inboxGenerationService;

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
      final TaskTagAssignmentQueryRepository tagAssignmentQueryRepository,
      final UserReadRepository userReadRepository,
      final NotificationService notificationService,
      final InboxGenerationService inboxGenerationService) {
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
    this.userReadRepository = userReadRepository;
    this.notificationService = notificationService;
    this.inboxGenerationService = inboxGenerationService;
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

    if (input.title() == null || input.title().isBlank()) {
      throw new EntityValidationException("Titel darf nicht leer sein.");
    }

    // BE-4 / D1 + D8 Schritt 2: ALLE Zusatzangaben VOR jeder Mutation validieren (All-or-Nothing).
    // Creator-Dedupe (D2): der Ersteller ist bereits Creator+Assignee -> aus der Initial-Assignee-
    // Menge entfernen (kein Doppel-Participant, kein Self-ASSIGN-Notify).
    final Set<UUID> initialAssignees =
        input.assignees().stream()
            .filter(ref -> !ref.equals(creatorRef))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    // Assignees validieren: aktive Benutzer DES Mandanten (findActiveByUuids ist tenant-scoped).
    // Nur aufrufen wenn nicht leer (MSSQL IN () ist ein Syntaxfehler). Unbekannt/fremd/inaktiv -> 400.
    if (!initialAssignees.isEmpty()) {
      final Set<UUID> activeRefs =
          userReadRepository.findActiveByUuids(initialAssignees).stream()
              .map(User::getUuid)
              .collect(Collectors.toSet());
      final Set<UUID> unknown =
          initialAssignees.stream()
              .filter(ref -> !activeRefs.contains(ref))
              .collect(Collectors.toCollection(LinkedHashSet::new));
      if (!unknown.isEmpty()) {
        throw new EntityValidationException(
            "Unbekannte oder nicht zugelassene Assignee-Benutzer: " + unknown);
      }
    }
    // Tags validieren + aufloesen (globaler Katalog, Tenant 0): unbekannt/inaktiv -> 400. Laeuft VOR
    // dem Task-Insert -> ein ungueltiger Tag (auch zusammen mit gueltigen Assignees) verhindert jede
    // Anlage (fail-fast; die Transaktion wuerde sonst ohnehin zurueckrollen).
    final Map<UUID, Long> tagUuidToId = resolveInitialTags(input.tagIds());

    // Aufgabe anlegen
    final Task task = new Task();
    task.setTenantId(tenantId);
    task.setTitle(input.title().strip());
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

    // D5: Initial-Marker (isPinned/isFlagged) auf die gerade angelegte Creator-UserState setzen.
    // Nur anwenden wenn im Input gesetzt (null -> Default false bleibt). isMuted nicht im Vertrag.
    if (input.isPinned() != null || input.isFlagged() != null) {
      final List<TaskUserState> creatorStates =
          userStateQueryRepository.findByTaskIdAndUserRef(taskId, creatorRef);
      if (!creatorStates.isEmpty()) {
        final TaskUserState creatorState = creatorStates.get(0);
        if (input.isPinned() != null) {
          creatorState.setPinned(input.isPinned());
        }
        if (input.isFlagged() != null) {
          creatorState.setFlagged(input.isFlagged());
        }
        userStateQueryRepository.save(creatorState);
      }
    }

    // D3: Nicht-Creator-Assignees initial zuweisen (volle Semantik wie AssigneeService):
    // neuer Participant (ASSIGNEE+PARTICIPANT_FLAG) + UserState + ASSIGN-Notification + Generation-Bump.
    for (final UUID assigneeRef : initialAssignees) {
      final TaskParticipant assigneeParticipant = new TaskParticipant();
      assigneeParticipant.setTenantId(tenantId);
      assigneeParticipant.setTaskId(taskId);
      assigneeParticipant.setParticipantRef(assigneeRef);
      assigneeParticipant.setParticipantKind(ParticipantKind.PERSON);
      assigneeParticipant.setParticipantTenantId(tenantId);
      assigneeParticipant.setCreatorFlag(false);
      assigneeParticipant.setAssigneeFlag(true);
      assigneeParticipant.setParticipantFlag(true);
      participantRepository.save(assigneeParticipant);

      userStateService.ensure(taskId, assigneeRef, tenantId);

      notificationService.emit(
          assigneeRef,
          taskId,
          NotificationTypeCodes.ASSIGN,
          NotificationSeverityCodes.INFO,
          "Du wurdest der Aufgabe zugewiesen.",
          task.getTitle(),
          creatorRef);

      // Finding 2 / Option B: die Zuweisung legt eine neue Inbox-Membership dieses Benutzers an ->
      // Generation atomar in dieser Tx bumpen (alte Inbox-Cursor -> 410 beim naechsten Read).
      inboxGenerationService.bump(tenantId, assigneeRef);
    }

    // D4: Initial-Tags zuordnen (frischer Task -> immer Neuanlage, keine Reaktivierung noetig).
    for (final Long tagId : new LinkedHashSet<>(tagUuidToId.values())) {
      final TaskTagAssignment assignment = new TaskTagAssignment();
      assignment.setTenantId(tenantId);
      assignment.setTaskId(taskId);
      assignment.setTagId(tagId);
      tagAssignmentQueryRepository.save(assignment);
    }

    // D3b (Codex-Gate-1 MAJOR): Auch der Ersteller erhaelt beim Create eine NEUE Inbox-Membership
    // (Participant+UserState) -> seine Generation EINMAL in derselben Tx bumpen, sonst koennte ein
    // paralleler Read mit altem Cursor den neuen Task auf einer Folgeseite sehen
    // (LAST_ACTIVITY_AT < asOf < commit).
    inboxGenerationService.bump(tenantId, creatorRef);

    // D8 Schritt 8 / After-Commit: snapshot-invalidated Signal fuer Ersteller + alle initialen
    // Assignees registrieren.
    final Set<UUID> invalidateRefs = new LinkedHashSet<>();
    invalidateRefs.add(creatorRef);
    invalidateRefs.addAll(initialAssignees);
    inboxSignalService.invalidate(tenantId, invalidateRefs);

    return taskExternalId;
  }

  /**
   * Loest die initialen Tag-UUIDs gegen den globalen Katalog (Tenant 0) auf (D4).
   *
   * <p>Laeuft im globalen Tenant-Scope ({@link TenantContextHolder#runWithTenant}); unbekannte oder
   * inaktive/abgelaufene Tags fuehren zu {@link EntityValidationException} (400), BEVOR irgendetwas
   * angelegt wird. Leere Eingabe -&gt; leere Map (kein DB-Zugriff; {@code IN ()} vermeiden).
   *
   * @param tagIds die bereits laengen-/null-validierten, angeforderten Tag-UUIDs (ggf. mit Duplikaten)
   * @return Zuordnung UUID -&gt; interne Tag-ID
   */
  private Map<UUID, Long> resolveInitialTags(final List<UUID> tagIds) {
    if (tagIds.isEmpty()) {
      return Map.of();
    }
    // Duplikate vor der Aufloesung entfernen (keine redundanten findActiveByUuid-Aufrufe).
    final Set<UUID> distinctTagIds = new LinkedHashSet<>(tagIds);
    return TenantContextHolder.runWithTenant(
        TenantInfo.global(),
        () -> {
          final Map<UUID, Long> result = new java.util.HashMap<>();
          for (final UUID tagUuid : distinctTagIds) {
            final Optional<TaskTag> found = tagRepository.findActiveByUuid(tagUuid.toString());
            if (found.isEmpty()) {
              throw new EntityValidationException(
                  "Unbekannte oder inaktive Tag-UUID: '" + tagUuid + "'.");
            }
            result.put(tagUuid, found.get().getId());
          }
          return result;
        });
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
      if (newTitle == null || newTitle.isBlank()) {
        throw new EntityValidationException("Titel darf nicht leer sein.");
      }
      historyService.record(
          taskId, tenantId, actorRef, "Titel geaendert",
          truncate(task.getTitle() + " -> " + newTitle, 500));
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
   * Kuerzt {@code s} auf hoechstens {@code max} UTF-16-Code-Units inkl. abschliessender Ellipse
   * ("...") und schneidet dabei NICHT mitten in ein Surrogatpaar (z.B. Emoji) - sonst entstuende
   * ein ungepaartes Surrogat (korrupter Unicode / Insert-Fehler). Paketsichtbar fuer Unit-Tests.
   *
   * @param s   der zu kuerzende Wert (null-sicher)
   * @param max maximale Gesamtlaenge inkl. Ellipse
   * @return der ggf. gekuerzte Wert
   */
  static String truncate(final String s, final int max) {
    if (s == null || s.length() <= max) {
      return s;
    }
    int end = max - 3;
    if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
      // Letztes eingeschlossenes Zeichen ist das High-Surrogate eines Paares, dessen
      // Low-Surrogate gerade abgeschnitten wuerde -> das High-Surrogate ebenfalls weglassen
      // (sonst ungepaartes Surrogat vor der Ellipse).
      end--;
    }
    return s.substring(0, end) + "...";
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
