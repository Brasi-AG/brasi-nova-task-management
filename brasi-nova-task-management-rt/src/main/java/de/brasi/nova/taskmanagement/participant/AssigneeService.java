package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskHandover;
import de.brasi.nova.taskmanagement.entity.TaskHandoverTarget;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.security.permission.PermissionDeniedException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen fuer Assignee-Verwaltung und Austritt.
 *
 * <p>Alle Mutationen laufen in einer eigenen {@code @Transactional}-Transaktion.
 * Nach erfolgtem Commit wird via {@link InboxSignalService} ein {@code snapshot-invalidated}-Signal
 * an die betroffenen Benutzer gesendet.
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 */
@Service
public class AssigneeService {

  private static final Logger log = LoggerFactory.getLogger(AssigneeService.class);

  private final BaseTaskRepository taskRepository;
  private final TaskParticipantQueryRepository participantQueryRepository;
  private final TaskUserStateQueryRepository userStateQueryRepository;
  private final UserStateService userStateService;
  private final NotificationService notificationService;
  private final InboxSignalService inboxSignalService;
  private final UserReadRepository userReadRepository;
  private final UserAccessor userAccessor;
  private final InboxGenerationService inboxGenerationService;
  private final TaskHandoverQueryRepository handoverRepository;
  private final BaseTaskHandoverTargetRepository handoverTargetRepository;
  private final TaskHistoryService taskHistoryService;
  private final TaskMembershipGuard membershipGuard;
  private final PermissionEvaluator permissionEvaluator;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public AssigneeService(
      final BaseTaskRepository taskRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
      final TaskUserStateQueryRepository userStateQueryRepository,
      final UserStateService userStateService,
      final NotificationService notificationService,
      final InboxSignalService inboxSignalService,
      final UserReadRepository userReadRepository,
      final UserAccessor userAccessor,
      final InboxGenerationService inboxGenerationService,
      final TaskHandoverQueryRepository handoverRepository,
      final BaseTaskHandoverTargetRepository handoverTargetRepository,
      final TaskHistoryService taskHistoryService,
      final TaskMembershipGuard membershipGuard,
      final PermissionEvaluator permissionEvaluator) {
    this.taskRepository = taskRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.userStateService = userStateService;
    this.notificationService = notificationService;
    this.inboxSignalService = inboxSignalService;
    this.userReadRepository = userReadRepository;
    this.userAccessor = userAccessor;
    this.inboxGenerationService = inboxGenerationService;
    this.handoverRepository = handoverRepository;
    this.handoverTargetRepository = handoverTargetRepository;
    this.taskHistoryService = taskHistoryService;
    this.membershipGuard = membershipGuard;
    this.permissionEvaluator = permissionEvaluator;
  }

  /**
   * Aktualisiert die Assignee-Menge einer Aufgabe (PUT-Semantik: Ziel-Menge ersetzt aktuellen Stand).
   *
   * <p>Ablauf:
   * <ol>
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>Aktuelle Assignees laden und mit Ziel-Menge vergleichen.
   *   <li>Neu zugewiesene Assignees: {@link TaskParticipant} anlegen/reaktivieren
   *       (ASSIGNEE_FLAG=1, PARTICIPANT_KIND=PERSON), {@link UserStateService#reactivate}
   *       (UserState sichern + UNSUBSCRIBED_AT zuruecksetzen), ASSIGN-Notification emittieren.
   *   <li>Abgewaehlte Assignees (vorher drin, jetzt nicht mehr):
   *       EXIT_KIND=UNSUBSCRIBED + EXIT_AT setzen.
   *   <li>Nach Commit: {@link InboxSignalService#invalidate} fuer alle betroffenen userRefs.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          Ziel-Assignee-Menge
   * @throws EntityNotFoundException   wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn Input ungueltig
   */
  @Transactional
  public void updateAssignees(final UUID taskExternalId, final AssigneeUpdateInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Aktuelle Assignees laden (Snapshot vor Mutationen)
    final List<TaskParticipant> currentAssignees =
        participantQueryRepository.findActiveAssigneesByTaskId(taskId);
    final Set<UUID> currentRefs =
        currentAssignees.stream()
            .map(TaskParticipant::getParticipantRef)
            .collect(Collectors.toSet());

    final Set<UUID> desiredRefs = input.assignees();

    // Creator-UUIDs ermitteln (duerfen nie abgewaehlt werden)
    final Set<UUID> creatorRefs =
        currentAssignees.stream()
            .filter(TaskParticipant::isCreatorFlag)
            .map(TaskParticipant::getParticipantRef)
            .collect(Collectors.toSet());

    // Neue Assignees: in desired, aber nicht in current
    final Set<UUID> toAdd =
        desiredRefs.stream()
            .filter(ref -> !currentRefs.contains(ref))
            .collect(Collectors.toSet());

    // Abgewaehlte Assignees: in current, aber nicht in desired.
    // BE-7 (A3): Creator-Schutz ist nun MITGLIEDSCHAFTS-Schutz (nicht mehr "Creator nie aus
    // toRemove"): ein ausgelassener Creator wird DE-ASSIGNT (ASSIGNEE_FLAG=false), behaelt aber
    // CREATOR_FLAG + PARTICIPANT_FLAG + aktiven UserState + EXIT_KIND=null (KEIN Austritt). Ein
    // ausgelassener Nicht-Creator wird wie bisher mit EXIT_KIND=UNSUBSCRIBED ausgetreten.
    final Set<UUID> toRemove =
        currentRefs.stream()
            .filter(ref -> !desiredRefs.contains(ref))
            .collect(Collectors.toSet());

    // Final-Review P1-2: neu zuzuweisende UUIDs muessen aktive Benutzer DES Mandanten sein.
    // Sonst entstuenden orphan-/cross-tenant-Participant/UserState/Notification. Unbekannt/fremd/
    // inaktiv -> 400 (EntityValidationException), bevor irgendetwas angelegt wird.
    if (!toAdd.isEmpty()) {
      final Set<UUID> activeUserRefs =
          userReadRepository.findActiveByUuids(toAdd).stream()
              .map(User::getUuid)
              .collect(Collectors.toSet());
      final Set<UUID> unknown =
          toAdd.stream().filter(ref -> !activeUserRefs.contains(ref)).collect(Collectors.toSet());
      if (!unknown.isEmpty()) {
        throw new EntityValidationException(
            "Unbekannte oder nicht zugelassene Assignee-Benutzer: " + unknown);
      }
    }

    final List<UUID> affectedRefs = new ArrayList<>();

    // Neue Assignees anlegen
    for (final UUID assigneeRef : toAdd) {
      // Pruefen ob bereits ein Participant-Eintrag existiert (z.B. als Beobachter oder CREATOR)
      final List<TaskParticipant> existing =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, assigneeRef);

      if (!existing.isEmpty()) {
        // Bestehenden Eintrag reaktivieren: ASSIGNEE_FLAG setzen, EXIT_KIND loeschen
        final TaskParticipant p = existing.get(0);
        p.setAssigneeFlag(true);
        p.setExitKind(null);
        p.setExitAt(null);
        participantQueryRepository.save(p);
        log.debug(
            "AssigneeService.updateAssignees: Participant {} fuer Task {} reaktiviert als Assignee.",
            assigneeRef,
            taskExternalId);
      } else {
        // Neuen Participant-Eintrag anlegen
        final TaskParticipant p = new TaskParticipant();
        p.setTenantId(tenantId);
        p.setTaskId(taskId);
        p.setParticipantRef(assigneeRef);
        p.setParticipantKind(ParticipantKind.PERSON);
        p.setParticipantTenantId(tenantId);
        p.setCreatorFlag(false);
        p.setAssigneeFlag(true);
        p.setParticipantFlag(true);
        participantQueryRepository.save(p);
        log.debug(
            "AssigneeService.updateAssignees: Neuer Assignee-Participant {} fuer Task {} angelegt.",
            assigneeRef,
            taskExternalId);
      }

      // UserState reaktivieren: UNSUBSCRIBED_AT=null setzen (Reassign-Reaktivierung - MAJOR 3).
      // reactivate stellt sicher, dass ein Eintrag existiert UND UNSUBSCRIBED_AT zurueckgesetzt
      // wird. Damit erscheint der erneut zugewiesene Benutzer wieder in findActivePage.
      userStateService.reactivate(taskId, assigneeRef, tenantId);

      // ASSIGN-Notification emittieren
      notificationService.emit(
          assigneeRef,
          taskId,
          NotificationTypeCodes.ASSIGN,
          NotificationSeverityCodes.INFO,
          "Du wurdest der Aufgabe zugewiesen.",
          task.getTitle(),
          actorRef);

      affectedRefs.add(assigneeRef);
      // Finding 2 / Option B: Zuweisung reaktiviert destruktiv die Membership-Felder dieses Refs
      // (UNSUBSCRIBED_AT/EXIT_KIND -> null) -> Generation muss atomar in dieser Tx gebumpt werden,
      // damit alte Inbox-Cursor dieses Benutzers beim naechsten Read abgelehnt werden (410). Ref in
      // affectedRefs gesammelt; der eigentliche (sortierte) Bump erfolgt gebuendelt NACH dem TASK-save
      // (einheitliche Save-dann-Bump- + Lock-Ordnung, Codex #6).
      log.info(
          "AssigneeService.updateAssignees: Assignee {} der Aufgabe {} (id={}) zugewiesen"
              + " von Akteur {} in Mandant {}.",
          assigneeRef,
          taskExternalId,
          taskId,
          actorRef,
          tenantId);
    }

    // Abgewaehlte Assignees abhandeln (A3: Creator = reiner Mitgliedschafts-Schutz).
    for (final UUID removedRef : toRemove) {
      final List<TaskParticipant> existing =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, removedRef);
      if (existing.isEmpty()) {
        continue;
      }
      final TaskParticipant p = existing.get(0);
      final boolean isCreator = creatorRefs.contains(removedRef);

      if (isCreator) {
        // Creator: NUR de-assignen (ASSIGNEE_FLAG=false). CREATOR_FLAG + PARTICIPANT_FLAG +
        // aktiver UserState + EXIT_KIND=null bleiben -> permanentes, immobiles Mitglied.
        p.setAssigneeFlag(false);
        participantQueryRepository.save(p);
        log.info(
            "AssigneeService.updateAssignees: Creator {} aus Aufgabe {} (id={}) de-assignt"
                + " (ASSIGNEE_FLAG=false, kein Austritt) von Akteur {} in Mandant {}.",
            removedRef,
            taskExternalId,
            taskId,
            actorRef,
            tenantId);
      } else {
        // Nicht-Creator: EXIT_KIND=UNSUBSCRIBED + EXIT_AT (echter Austritt).
        p.setExitKind(ExitKind.UNSUBSCRIBED);
        p.setExitAt(LocalDateTime.now());
        participantQueryRepository.save(p);

        // UNSUBSCRIBED_AT auch im UserState setzen
        final List<TaskUserState> states =
            userStateQueryRepository.findByTaskIdAndUserRef(taskId, removedRef);
        if (!states.isEmpty()) {
          final TaskUserState state = states.get(0);
          state.setUnsubscribedAt(LocalDateTime.now());
          userStateQueryRepository.save(state);
        }
        log.info(
            "AssigneeService.updateAssignees: Assignee {} aus Aufgabe {} (id={}) abgewaehlt"
                + " (EXIT_KIND=UNSUBSCRIBED) von Akteur {} in Mandant {}.",
            removedRef,
            taskExternalId,
            taskId,
            actorRef,
            tenantId);
      }

      affectedRefs.add(removedRef);
      // Finding 2 / Option B: die Abwahl ueberschreibt destruktiv Membership-Felder dieses Refs
      // (ASSIGNEE_FLAG bzw. EXIT_KIND/EXIT_AT/UNSUBSCRIBED_AT) -> Generation muss atomar gebumpt
      // werden. Ref in affectedRefs gesammelt; der (sortierte) Bump erfolgt gebuendelt NACH dem
      // TASK-save (Codex #6).
    }

    // After-Commit: Invalidate NUR bei tatsaechlicher Aenderung (affectedRefs nicht leer).
    // Idempotente No-Op-PUTs (Ziel-Menge == aktueller Stand, inkl. Creator-Schutz-No-Ops) duerfen
    // weder aktive UserStates laden noch Invalidierungen senden - sonst vermeidbare DB-Last,
    // Push-Traffic und Client-Snapshot-Reloads (analog TaskCommandService.patch 'changed'-Guard).
    // Bei echter Aenderung: participantSummaries aendern sich fuer JEDEN verbleibenden aktiven
    // Teilnehmer - deshalb die Vereinigung aus geaenderten Refs (affectedRefs, inkl. der gerade
    // abgewaehlten) UND allen aktuell aktiven Teilnehmer-Refs der Aufgabe invalidieren.
    if (!affectedRefs.isEmpty()) {
      // Whole-Branch-Re-Gate P2 #3: Nebenlaeufige PUT-Set-Ersetzungen serialisieren. Zwei parallele
      // Ersetzungen ab demselben Ist-Stand ({creator} -> {creator,A} bzw. {creator,B}) koennten sonst
      // beide committen und die Ziel-Menge still zu {creator,A,B} mergen (PUT-Set-Semantik verletzt).
      // Ein optimistischer Lock auf die Aufgabe (VERSION-Bump via save) laesst genau eine Transaktion
      // gewinnen; die verlierende bricht mit BusinessRuleException (409) ab (0 betroffene Zeilen beim
      // versionierten UPDATE) und der Client wiederholt den PUT gegen frischen Stand. Nur bei
      // tatsaechlicher Aenderung (affectedRefs nicht leer) - ein No-Op-PUT bumpt die Version nicht.
      taskRepository.save(task);

      // Codex #6: Generation-Bump der geaenderten Refs (toAdd + toRemove) ERST NACH dem TASK-save -
      // einheitliche Save-dann-Bump-Ordnung ueber alle Pfade (vorher lag der Bump in den Loops VOR
      // dem save -> invertierte TASK-vs-Generation-Lock-Ordnung). Ueber den zentralen Helfer in
      // SORTIERTER Reihenfolge (keine ueberkreuzte Generation-Lock-Order). Der Bump-Scope bleibt
      // UNVERAENDERT genau affectedRefs (NICHT die weitere Invalidate-Menge toInvalidate).
      inboxGenerationService.bumpSorted(tenantId, affectedRefs);

      final Set<UUID> toInvalidate = new HashSet<>(affectedRefs);
      userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
          .map(TaskUserState::getUserRef)
          .forEach(toInvalidate::add);
      inboxSignalService.invalidate(tenantId, toInvalidate);
    }
  }

  /**
   * Fuegt Benutzer additiv und idempotent als <b>Teilnehmer</b> (PARTICIPANT_FLAG) zu einer Aufgabe
   * hinzu. Verleiht NIE die Assignee-Rolle (BE-6).
   *
   * <p>Ablauf:
   * <ol>
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>Eingabe deduplizieren; leere Menge -> No-Op (keine DB-Mutation, kein Signal).
   *   <li>All-or-Nothing: alle verbleibenden Refs muessen aktive Benutzer DES Mandanten sein
   *       ({@code findActiveByUuids}) - sonst 400 VOR jeder Mutation.
   *   <li>Je Ref: aktive Zeile -> SKIP (idempotent); ausgetretene Zeile -> als REINER Teilnehmer
   *       reaktivieren (ASSIGNEE_FLAG=false); keine Zeile -> neu anlegen (reiner Teilnehmer).
   *   <li>Nebenlaeufigkeit (D8, Pfad A): gleich-Ref-Race wird in
   *       {@link #saveParticipantHandlingRace} idempotent aufgeloest.
   *   <li>Generation-Bump + UserState NUR fuer tatsaechlich neu angelegte/reaktivierte Refs.
   *   <li><b>KEIN</b> TASK-Optimistic-Lock: die additive Union ist kommutativ (anders als die
   *       PUT-Set-Semantik von {@link #updateAssignees}) - die TASK-VERSION wird NICHT gebumpt.
   *   <li>Nach Commit: {@link InboxSignalService#invalidate} NUR bei tatsaechlicher Aenderung.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          hinzuzufuegende Teilnehmer-UUIDs
   * @throws EntityNotFoundException   wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn ein Ref kein aktiver Benutzer des Mandanten ist
   */
  @Transactional
  public void addParticipants(final UUID taskExternalId, final ParticipantAddInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Deduplizieren (stabile Reihenfolge). Leere Menge -> No-Op: kein findActiveByUuids (MSSQL
    // IN () waere ein Syntaxfehler), keine Mutation, kein invalidate.
    final Set<UUID> refs = new LinkedHashSet<>(input.participantIds());
    if (refs.isEmpty()) {
      return;
    }

    // D3 All-or-Nothing: alle Refs muessen aktive Benutzer DES Mandanten sein - sonst 400, bevor
    // irgendetwas angelegt wird (keine orphan-/cross-tenant-Teilnehmer/-UserStates).
    final Set<UUID> activeUserRefs =
        userReadRepository.findActiveByUuids(refs).stream()
            .map(User::getUuid)
            .collect(Collectors.toSet());
    final Set<UUID> unknown =
        refs.stream().filter(ref -> !activeUserRefs.contains(ref)).collect(Collectors.toSet());
    if (!unknown.isEmpty()) {
      throw new EntityValidationException(
          "Unbekannte oder nicht zugelassene Teilnehmer-Benutzer: " + unknown);
    }

    final List<UUID> affectedRefs = new ArrayList<>();
    for (final UUID ref : refs) {
      if (addSingleParticipant(taskId, ref, tenantId)) {
        affectedRefs.add(ref);
      }
    }

    // Codex #6: Generation-Bump der tatsaechlich hinzugefuegten/reaktivierten Refs gebuendelt ueber
    // den zentralen Helfer in SORTIERTER Reihenfolge (konsistente Generation-Lock-Ordnung mit allen
    // anderen Pfaden). addParticipants mutiert die TASK-Zeile NICHT (additive Union, kommutativ, kein
    // LAST_ACTIVITY_AT/VERSION-Bump) -> eine TASK-vs-Generation-Inversion ist hier strukturell
    // ausgeschlossen; der Helfer sichert nur die Generation-untereinander-Ordnung. Scope unveraendert
    // (nur tatsaechlich geaenderte Refs, wie bisher je-Ref im Loop).
    inboxGenerationService.bumpSorted(tenantId, affectedRefs);

    // D7 changed-Guard: invalidate NUR bei tatsaechlicher Aenderung (>=1 add/reactivate). Ein reiner
    // No-Op (alle schon aktiv) laedt die aktiven Teilnehmer nicht und sendet kein Signal (analog
    // updateAssignees / TaskCommandService.patch).
    if (!affectedRefs.isEmpty()) {
      final Set<UUID> toInvalidate = new HashSet<>(affectedRefs);
      userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
          .map(TaskUserState::getUserRef)
          .forEach(toInvalidate::add);
      inboxSignalService.invalidate(tenantId, toInvalidate);
    }
  }

  /**
   * Fuegt EINEN Teilnehmer-Ref hinzu bzw. reaktiviert ihn (reine Teilnehmer-Rolle).
   *
   * @return {@code true}, wenn tatsaechlich angelegt/reaktiviert wurde (Generation-Bump faellig);
   *     {@code false} bei idempotentem SKIP (bereits aktiv - sequentiell ODER als Race-Verlierer)
   */
  private boolean addSingleParticipant(final Long taskId, final UUID ref, final long tenantId) {
    final List<TaskParticipant> existing =
        participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref);
    if (!existing.isEmpty()) {
      final TaskParticipant p = existing.get(0);
      if (p.getExitKind() == null) {
        // (a) aktive Zeile (Creator / Assignee / Teilnehmer) -> idempotenter SKIP, keine Mutation.
        return false;
      }
      // (b) ausgetretene Zeile -> als REINEN Teilnehmer reaktivieren. ASSIGNEE_FLAG MUSS false
      // werden (D4-BLOCKER: ein abgewaehlter Ex-Assignee behaelt sonst ASSIGNEE_FLAG=1 und wuerde
      // durch die Reaktivierung faelschlich wieder aktiver Assignee - findActiveAssigneesByTaskId
      // filtert ASSIGNEE_FLAG=1 AND EXIT_KIND IS NULL). Add-Participants verleiht NIE Assignee.
      p.setParticipantFlag(true);
      p.setAssigneeFlag(false);
      p.setExitKind(null);
      p.setExitAt(null);
      if (!saveParticipantHandlingRace(p, taskId, ref)) {
        return false; // Race-Verlierer: Gewinner-Zustand bereits aktiv -> idempotent.
      }
      userStateService.reactivate(taskId, ref, tenantId);
      // Codex #6: Generation-Bump NICHT hier (sofort/ungeordnet), sondern gebuendelt + SORTIERT im
      // Aufrufer (addParticipants) ueber affectedRefs - konsistente Generation-Lock-Ordnung. Der
      // true-Return meldet "Bump faellig"; der Ref wird dort gesammelt. Scope unveraendert.
      log.debug(
          "AssigneeService.addParticipants: Teilnehmer {} fuer Task {} reaktiviert.", ref, taskId);
      return true;
    }
    // (c) keine Zeile -> neuen reinen Teilnehmer anlegen (PERSON, PARTICIPANT_FLAG=1,
    // ASSIGNEE_FLAG=0, CREATOR_FLAG=0).
    final TaskParticipant p = new TaskParticipant();
    p.setTenantId(tenantId);
    p.setTaskId(taskId);
    p.setParticipantRef(ref);
    p.setParticipantKind(ParticipantKind.PERSON);
    p.setParticipantTenantId(tenantId);
    p.setCreatorFlag(false);
    p.setAssigneeFlag(false);
    p.setParticipantFlag(true);
    if (!saveParticipantHandlingRace(p, taskId, ref)) {
      return false;
    }
    userStateService.ensure(taskId, ref, tenantId);
    // Codex #6: Generation-Bump gebuendelt + SORTIERT im Aufrufer (addParticipants), nicht hier.
    log.debug(
        "AssigneeService.addParticipants: Neuer Teilnehmer {} fuer Task {} angelegt.", ref, taskId);
    return true;
  }

  /**
   * Speichert einen Teilnehmer und behandelt den gleich-Ref-Race idempotent (D8, Pfad A -
   * Re-Read-Translate IN der aeusseren Transaktion).
   *
   * <p>Bei {@link BusinessRuleException} aus {@code save} wird NUR bei passender Konfliktursache
   * erneut gelesen: KSF verpackt den UNIQUE-Insert als {@link DuplicateKeyException} und den
   * VERSION-Konflikt als {@link OptimisticLockingFailureException}. Eine unverwandte
   * {@link BusinessRuleException} (andere/fehlende Ursache) wird NIE als Erfolg geschluckt, auch
   * nicht bei zufaellig passendem Re-Read. Bestaetigt der Re-Read den vollen aktiven
   * Gewinner-Zustand (aktive Zeile, PARTICIPANT_FLAG=1, EXIT_KIND null), wird der Konflikt
   * idempotent als SKIP behandelt; sonst wird die urspruengliche Exception weitergeworfen.
   *
   * <p>Die aeussere Transaktion bleibt dabei committable: das KSF-{@code save()} hat fuer
   * Einzel-Entities KEINE wirksame eigene Transaktions-Klammer und markiert die aeussere Tx daher
   * NICHT rollback-only; eine MSSQL-UNIQUE-Verletzung ist statement-level. Dieses Verhalten wird im
   * IT real gegen MSSQL bewiesen (Committability-Sonde).
   *
   * @return {@code true} bei erfolgreichem Save; {@code false}, wenn der Ref bereits als aktiver
   *     Gewinner existiert (idempotenter SKIP)
   */
  private boolean saveParticipantHandlingRace(
      final TaskParticipant participant, final Long taskId, final UUID ref) {
    // Test-Seam: deterministische Synchronisation der Nebenlaeufigkeits-ITs ZWISCHEN Vorab-Read und
    // save (D8-Race-Beweis). Produktiv No-Op.
    onBeforeParticipantSaveForTest(taskId, ref);
    try {
      participantQueryRepository.save(participant);
      return true;
    } catch (final BusinessRuleException conflict) {
      final Throwable cause = conflict.getCause();
      final boolean recoverableCause =
          cause instanceof DuplicateKeyException
              || cause instanceof OptimisticLockingFailureException;
      if (!recoverableCause) {
        throw conflict;
      }
      // Test-Seam: Catch-Zweig-Zaehlung + Committability-Sonde (XACT_STATE / @@OPTIONS) auf DERSELBEN
      // Tx-Verbindung VOR dem Re-Read (D8-Beweis-Auflagen 1+2). Produktiv No-Op.
      onParticipantConflictCaughtForTest(taskId, ref);
      final List<TaskParticipant> recheck =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref);
      if (recheck.isEmpty()) {
        throw conflict;
      }
      final TaskParticipant winner = recheck.get(0);
      if (winner.isParticipantFlag() && winner.getExitKind() == null) {
        log.debug(
            "AssigneeService.addParticipants: Nebenlaeufiger gleich-Ref-Konflikt fuer Task {}"
                + " Ref {} - idempotenter SKIP (Gewinner-Zeile aktiv).",
            taskId,
            ref);
        return false;
      }
      throw conflict;
    }
  }

  /**
   * Test-Seam (Default No-Op): Haken ZWISCHEN dem Vorab-Read und dem Participant-save in
   * {@link #addSingleParticipant}/{@link #saveParticipantHandlingRace}. Ermoeglicht deterministische
   * Nebenlaeufigkeits-ITs (D8): beide Threads werden nachweislich VOR dem ersten Write synchronisiert,
   * sodass der gleich-Ref-Race den Konfliktzweig garantiert erreicht. Im Produktivbetrieb ein No-Op;
   * nur Test-Subklassen ueberschreiben diesen Haken.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref    Teilnehmer-UUID
   */
  protected void onBeforeParticipantSaveForTest(final Long taskId, final UUID ref) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): wird im Konfliktzweig von {@link #saveParticipantHandlingRace} NACH der
   * gefangenen (recoverable) {@link BusinessRuleException} und VOR dem Re-Read aufgerufen, auf DERSELBEN
   * Transaktionsverbindung. Ermoeglicht den Nebenlaeufigkeits-ITs die direkte Catch-Zweig-Zaehlung (D8-
   * Auflage 1) und die Committability-Sonde (XACT_STATE()/@@OPTIONS, D8-Auflage 2). Produktiv ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref    Teilnehmer-UUID
   */
  protected void onParticipantConflictCaughtForTest(final Long taskId, final UUID ref) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe bzw. uebergibt sie (BE-7, SPEC v3 Abschnitt 4).
   *
   * <p>Zwei Austrittsarten (Input {@link ExitInput#exitKind()}):
   * <ul>
   *   <li><b>UNSUBSCRIBED:</b> der Akteur meldet sich ab. {@code handedOverToIds} muss leer und
   *       {@code shouldStay} false sein (sonst 400). Nicht-Creator -> EXIT_KIND=UNSUBSCRIBED;
   *       Creator -> 409.
   *   <li><b>HANDOVER:</b> der Akteur uebergibt die Aufgabe an {@code handedOverToIds} (werden
   *       Assignees). Optionaler Verbleib ({@code shouldStay}) laesst den Akteur reiner Teilnehmer.
   *       Der Akteur wird IMMER de-assignt (ASSIGNEE_FLAG=false). Nicht-Creator + !shouldStay ->
   *       EXIT_KIND=HANDOVER + Austritt; Creator -> nur shouldStay=true erlaubt (sonst 409), bleibt
   *       dann immobiler Teilnehmer (CREATOR+PARTICIPANT + aktiver UserState, ASSIGNEE_FLAG=false).
   * </ul>
   *
   * <p>Alles in EINER Transaktion: TASK_HANDOVER-Ereignis + Ziele + Rollenwechsel + History +
   * LAST_ACTIVITY_AT + {@code taskRepository.save} (VERSION-Guard) + Notification-Persistenz; Signale
   * (Push/Invalidate) strikt after-commit. Der VERSION-Guard macht die Aufgabe zum gemeinsamen
   * Serialisierungspunkt gegen nebenlaeufige Membership-Mutationen (PUT/exit); der Verlierer bricht
   * mit {@link BusinessRuleException} (409) ab und committet NICHTS.
   *
   * <p><b>Inbox-Generation-Bump (Finding 2 / Option B, Codex #6):</b> Die Generation wird fuer die
   * VOLLSTAENDIGE betroffene Menge ATOMAR in dieser Tx gebumpt: Akteur (nur bei tatsaechlichem
   * Austritt), alle Handover-Ziele UND ALLE nach dem Exit/Handover verbleibenden aktiven Teilnehmer
   * der Aufgabe - nicht nur Akteur + Ziele. Der LAST_ACTIVITY_AT-Bump verschiebt die aktive
   * Keyset-Position ({@code (asOf, LAST_ACTIVITY_AT, externalId)}) jedes verbleibenden Teilnehmers;
   * ohne Bump lieferte dessen alter aktiver Cursor stale 200 (Skip/Dupe) statt 410.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          Austritts-Eingabe (exitKind, handedOverToIds, shouldStay)
   * @throws EntityNotFoundException   wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException (400) bei ungueltiger Eingabe oder wenn der Akteur kein aktiver
   *     Teilnehmer der Aufgabe ist
   * @throws BusinessRuleException     (409) Creator-Austritt ohne Verbleib / nebenlaeufiger
   *     Versionskonflikt
   */
  @Transactional
  public void exit(final UUID taskExternalId, final ExitInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Standard-Selfservice (Slice 4, Auflage 2): WRITE-Member-Guard GANZ VORNE - direkt nach dem
    // Task-Laden + Tenant/ENTITY_STATUS=ACTIVE-Check (requireTask), VOR jeder Eingabe-/Zielvalidierung.
    // Fehlende/ausgetretene Membership -> einheitlich neutrale 404 (nicht 400, kein Ziel-Leak ueber die
    // nachgelagerte findActiveByUuids-Zielvalidierung). Der bestehende Service-interne Akteur-Check
    // (unten, Z. ~670) bleibt als Defense-in-Depth erhalten, wird aber vom Guard vorweggenommen. Das
    // Gate am Controller ist entfernt (authenticated-only); anonym bleibt 401 ueber die KSF-Filter-Chain.
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    // Test-Seam: deterministische Synchronisation der Versions-Verlierer-ITs (BE-7 IT7) GENAU NACH
    // dem Lesen der Aufgabe (VERSION erfasst) und VOR jeder Mutation - zu diesem Zeitpunkt haelt der
    // Verlierer noch KEINE Schreibsperren, sodass ein nebenlaeufiger Gewinner (Exit/PUT/Handover)
    // blockierungsfrei committen kann; der Verlierer trifft dann beim versionierten TASK-save die
    // stale VERSION -> 409 + voller Rollback. Produktiv ein No-Op.
    onBeforeExitMutationForTest(taskId);

    final boolean handover = ExitKind.HANDOVER == input.exitKind();
    final boolean shouldStay = Boolean.TRUE.equals(input.shouldStay());
    final List<UUID> rawTargets = input.handedOverToIds();

    // (2) Eingabe-Konsistenz VOR jeder Mutation (SPEC F4/A5) - insbesondere VOR dem Creator-409.
    if (!handover) {
      // UNSUBSCRIBED: keine Ziele, kein Verbleib.
      if (!rawTargets.isEmpty() || shouldStay) {
        throw new EntityValidationException(
            "Bei exitKind=UNSUBSCRIBED sind handedOverToIds und shouldStay nicht zulaessig.");
      }
    } else {
      // HANDOVER: Selbstziel verboten; shouldStay=true verlangt >=1 Ziel (leer-stay -> 400, auch
      // fuer Creator VOR dem Creator-409).
      if (rawTargets.contains(actorRef)) {
        throw new EntityValidationException(
            "Der Akteur kann die Aufgabe nicht an sich selbst uebergeben.");
      }
      if (shouldStay && rawTargets.isEmpty()) {
        throw new EntityValidationException(
            "Bei shouldStay=true muss mindestens ein Uebergabeziel angegeben sein.");
      }
    }

    // Deduplizierte Zielmenge (stabile Reihenfolge). Alle Ziele muessen aktive Benutzer DES
    // Mandanten sein (All-or-Nothing 400, keine Mutation bei Verstoss).
    final Set<UUID> dedupedTargets = new LinkedHashSet<>(rawTargets);
    if (!dedupedTargets.isEmpty()) {
      final Set<UUID> activeUserRefs =
          userReadRepository.findActiveByUuids(dedupedTargets).stream()
              .map(User::getUuid)
              .collect(Collectors.toSet());
      final Set<UUID> unknown =
          dedupedTargets.stream()
              .filter(ref -> !activeUserRefs.contains(ref))
              .collect(Collectors.toSet());
      if (!unknown.isEmpty()) {
        throw new EntityValidationException(
            "Unbekannte oder nicht zugelassene Uebergabeziele: " + unknown);
      }
    }

    // (2b) Handover-Autz-Grenze (Standard-Selfservice, Slice 4, Auflage 2 - Eskalationsschutz):
    // HANDOVER fuehrt via ensureAssignee neue Assignees ein bzw. reaktiviert sie (= effektiv
    // "add participant"). Da addParticipants/updateAssignees TASK_WRITE-gated BLEIBEN, ist ein
    // grant-freier HANDOVER NUR dann zulaessig, wenn JEDES deduplizierte Ziel bereits VOLLER
    // WRITE-Member der Aufgabe ist (reiner Rollenwechsel innerhalb bestehender aktiver Mitglieder).
    // Fuehrt ODER reaktiviert der HANDOVER mindestens EIN Ziel, das KEIN WRITE-Member ist (neu,
    // ausgetreten, inaktiv, TEAM, fremder PARTICIPANT_TENANT_ID), erfordert er exakte TASK_WRITE-
    // Authority; fehlt sie dem (bestaetigten) Akteur -> 403, KEINE Mutation (All-or-Nothing ueber die
    // GESAMTE deduplizierte Zielmenge). Dieser bedingte Grant-Check laeuft NACH dem Akteur-WRITE-Guard
    // (oben, requireWriteMember) und VOR jeder Mutation (403 statt 404 ist korrekt: der Akteur ist
    // bestaetigtes Mitglied, kein Existenz-Leak). UNSUBSCRIBED hat keine Ziele -> immer grant-frei.
    if (handover && !dedupedTargets.isEmpty()) {
      final boolean allTargetsAreWriteMembers =
          dedupedTargets.stream()
              .allMatch(targetRef -> membershipGuard.isWriteMember(taskId, targetRef, tenantId));
      if (!allTargetsAreWriteMembers
          && !permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)) {
        throw new PermissionDeniedException(
            TaskManagementPermissions.TASK_WRITE,
            "Die Uebergabe an ein noch nicht als WRITE-Mitglied gefuehrtes Ziel erfordert"
                + " TASK_WRITE.");
      }
    }

    // (3) Akteur-Participant laden; kein Eintrag / bereits ausgetreten -> 400.
    final List<TaskParticipant> existing =
        participantQueryRepository.findByTaskIdAndParticipantRef(taskId, actorRef);
    if (existing.isEmpty()) {
      throw new EntityValidationException(
          "Der aktuelle Benutzer ist kein aktiver Teilnehmer dieser Aufgabe.");
    }
    final TaskParticipant participant = existing.get(0);
    if (participant.getExitKind() != null) {
      throw new EntityValidationException(
          "Der aktuelle Benutzer ist kein aktiver Teilnehmer dieser Aufgabe.");
    }

    // (4) Creator-Zweig (A2): HANDOVER+shouldStay=true erlaubt; sonst 409 (NACH Eingabepruefung).
    final boolean isCreator = participant.isCreatorFlag();
    if (isCreator && !(handover && shouldStay)) {
      throw new BusinessRuleException("Der Ersteller kann die Aufgabe nicht verlassen.");
    }

    final LocalDateTime now = LocalDateTime.now();
    final List<UUID> notifyTargets = new ArrayList<>();

    // (5) HANDOVER: Ereignis + Ziele (-> Assignees) + Reaktivierung + Ziel-Notify + Ziel-Bump.
    if (handover) {
      final TaskHandover event = new TaskHandover();
      event.setTenantId(tenantId);
      event.setTaskId(taskId);
      event.setFromRef(actorRef);
      event.setShouldStay(shouldStay);
      final TaskHandover savedEvent = handoverRepository.save(event);
      final Long handoverId = savedEvent.getId();

      for (final UUID targetRef : dedupedTargets) {
        final TaskHandoverTarget targetRow = new TaskHandoverTarget();
        targetRow.setTenantId(tenantId);
        targetRow.setHandoverId(handoverId);
        targetRow.setToRef(targetRef);
        handoverTargetRepository.save(targetRow);

        ensureAssignee(taskId, targetRef, tenantId);
        userStateService.reactivate(taskId, targetRef, tenantId);
        notificationService.emit(
            targetRef,
            taskId,
            NotificationTypeCodes.HANDOVER,
            NotificationSeverityCodes.INFO,
            "Dir wurde die Aufgabe uebergeben.",
            task.getTitle(),
            actorRef);
        notifyTargets.add(targetRef);
      }
    }

    // (6) Akteur-Rollenwechsel. ASSIGNEE_FLAG=false IMMER (auch Creator). Nicht-Creator + !shouldStay
    // (bei HANDOVER) ODER UNSUBSCRIBED -> echter Austritt (EXIT_KIND + EXIT_AT + UNSUBSCRIBED_AT).
    final boolean actorExits = !isCreator && !shouldStay;
    participant.setAssigneeFlag(false);
    if (actorExits) {
      participant.setExitKind(handover ? ExitKind.HANDOVER : ExitKind.UNSUBSCRIBED);
      participant.setExitAt(now);
    }
    // (9) VERSION-/Optimistic-Lock-Guard auf dem Participant. Wettlauf-Verlierer -> 409 bzw. bei
    // tatsaechlichem Doppel-Austritt neutral 400 (Invariante: keine zweite Erfolgsantwort).
    try {
      participantQueryRepository.save(participant);
    } catch (final BusinessRuleException concurrentConflict) {
      final List<TaskParticipant> recheck =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, actorRef);
      if (recheck.isEmpty() || recheck.get(0).getExitKind() != null) {
        throw new EntityValidationException(
            "Der aktuelle Benutzer ist kein aktiver Teilnehmer dieser Aufgabe.");
      }
      throw concurrentConflict;
    }

    if (actorExits) {
      final List<TaskUserState> states =
          userStateQueryRepository.findByTaskIdAndUserRef(taskId, actorRef);
      if (!states.isEmpty()) {
        final TaskUserState state = states.get(0);
        state.setUnsubscribedAt(now);
        userStateQueryRepository.save(state);
      }
    }

    // (7) History (Namen-Snapshot): "<AkteurName> -> <Ziel-Namen>".
    recordHandoverHistory(taskId, tenantId, actorRef, dedupedTargets, handover, shouldStay);

    // (8)+(9) LAST_ACTIVITY_AT-Bump VOR dem versionierten TASK-save. Der TASK-VERSION-Bump
    // serialisiert bei JEDEM erfolgreichen Austritt/Handover gegen nebenlaeufiges PUT/exit; der
    // Verlierer bricht mit BusinessRuleException (409) ab und committet NICHTS.
    task.setLastActivityAt(now);
    taskRepository.save(task);

    // (10) Selbstbestaetigungs-Notify an den Akteur.
    if (handover) {
      notificationService.emit(
          actorRef,
          taskId,
          NotificationTypeCodes.HANDOVER,
          NotificationSeverityCodes.INFO,
          shouldStay ? "Du hast die Aufgabe uebergeben." : "Du hast die Aufgabe uebergeben und verlassen.",
          task.getTitle(),
          actorRef);
    }

    // (11) Betroffene Menge bestimmen: Akteur (bei Austritt) + alle Handover-Ziele + ALLE nach dem
    // Exit/Handover verbleibenden aktiven Teilnehmer der Aufgabe. Diese Menge dient GEMEINSAM dem
    // Generation-Bump (atomar in dieser Tx) UND dem after-commit Invalidate - dieselbe Menge, ein
    // einziger Bump je Benutzer (HashSet dedupliziert Ziele/Akteur, die sonst mehrfach gebumpt
    // wuerden).
    final Set<UUID> affected = new HashSet<>();
    if (actorExits) {
      affected.add(actorRef);
    }
    affected.addAll(notifyTargets);
    userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
        .map(TaskUserState::getUserRef)
        .forEach(affected::add);

    // Finding 2 / Option B / Codex #6: Generation JEDES betroffenen aktiven Teilnehmers ATOMAR in
    // dieser Tx bumpen - nicht nur Akteur + Ziele. Der TASK-LAST_ACTIVITY_AT-Bump (oben, now())
    // verschiebt die aktive Keyset-Position (asOf, LAST_ACTIVITY_AT, externalId) fuer JEDEN
    // verbleibenden aktiven Teilnehmer; zusaetzlich aendert der Rollenwechsel die
    // participantSummaries. Ein alter aktiver Cursor eines verbleibenden Teilnehmers lieferte sonst
    // stale 200 (Skip/Dupe) statt 410. Der Akteur wird nur bei tatsaechlichem Austritt (actorExits)
    // gebumpt - bei shouldStay/Creator-Verbleib bleibt er aktiver Teilnehmer und ist bereits ueber
    // die findActiveUserRefsByTaskId-Menge erfasst.
    // Deadlock-Robustheit (Codex #6): die (ungeordnete HashSet-)Menge ueber den zentralen Helfer in
    // DETERMINISTISCH SORTIERTER Ref-Reihenfolge bumpen - NACH dem TASK-save (oben, taskRepository.
    // save), sodass ueberall zuerst die TASK-Zeile und dann die TASK_INBOX_GENERATION-Zeilen gesperrt
    // werden und parallele Membership-Mutationen mit ueberlappenden Teilnehmern die Generation-Zeilen
    // in gleicher Reihenfolge sperren (keine ueberkreuzte Lock-Order).
    inboxGenerationService.bumpSorted(tenantId, affected);

    // After-Commit: snapshot-invalidated Signal fuer dieselbe Menge. Signale strikt after-commit
    // (ueber den InboxSignalService-Hook).
    inboxSignalService.invalidate(tenantId, affected);

    log.info(
        "AssigneeService.exit: Akteur {} hat Aufgabe {} (id={}) {} (EXIT_KIND={}, shouldStay={},"
            + " Ziele={}) in Mandant {}.",
        actorRef,
        taskExternalId,
        taskId,
        handover ? "uebergeben" : "verlassen",
        actorExits ? input.exitKind() : null,
        shouldStay,
        dedupedTargets.size(),
        tenantId);
  }

  /**
   * Stellt sicher, dass {@code targetRef} aktiver Assignee der Aufgabe ist (BE-7, SPEC F3 +
   * Standard-Selfservice Slice 4 ensureAssignee-Auswahlvertrag, Auflage 2/8).
   *
   * <p><b>Unique-Key-exakte Auswahl (NICHT lose {@code findByTaskIdAndParticipantRef}+{@code
   * get(0)}):</b> der Lookup laeuft ueber {@link TaskParticipantQueryRepository#findRowByUniqueKey}
   * auf den V3-Unique-Key {@code (TASK_ID, PARTICIPANT_TENANT_ID=task-tenant, PARTICIPANT_REF)} -
   * per Index genau {@code <=1} Zeile - OHNE {@code ENTITY_STATUS}-/{@code EXIT_KIND}-/{@code
   * PARTICIPANT_KIND}-Filter (sonst wuerde eine zu reaktivierende inaktive/ausgetretene Zeile
   * verfehlt). Die {@code <=1} Zeile wird dann klassifiziert:
   *
   * <ul>
   *   <li><b>vorhanden, {@code PARTICIPANT_KIND='PERSON'} UND Basis-{@code TENANT_ID}=task-tenant</b>
   *       (Status/Exit egal: aktiv/exited/inaktiv) -> VOLL reaktivieren: {@code ASSIGNEE_FLAG=
   *       PARTICIPANT_FLAG=1}, {@code EXIT_KIND/EXIT_AT=null}, {@code ENTITY_STATUS='ACTIVE'} (auch
   *       eine inaktive/exited PERSON wird voll auf aktiv gehoben - kein 204 ohne aktiven Assignee).
   *   <li><b>vorhanden, aber {@code PARTICIPANT_KIND='TEAM'} ODER Basis-{@code TENANT_ID} !=
   *       task-tenant</b> -> <b>409, KEINE Mutation</b> ({@link BusinessRuleException}, fail-closed;
   *       ein PERSON-Insert waere per Unique-Key unmoeglich, stilles Flippen wuerde ungueltige
   *       Membership hinterlassen).
   *   <li><b>keine Zeile</b> (Unique-Key frei) -> NEUE PERSON-Zeile mit {@code TENANT_ID =
   *       PARTICIPANT_TENANT_ID = task-tenant}, {@code ENTITY_STATUS='ACTIVE'}, Assignee INSERT.
   * </ul>
   *
   * <p><b>Konflikt-Recheck (volle Invariante):</b> bei gleich-Ref-Race ({@link
   * BusinessRuleException} aus {@code save}) ist ein idempotenter SKIP nur dann zulaessig, wenn der
   * Re-Read die VOLLE Membership-Invariante bestaetigt ({@code PARTICIPANT_KIND='PERSON' AND
   * TENANT_ID=task-tenant AND PARTICIPANT_TENANT_ID=task-tenant AND ASSIGNEE_FLAG=1 AND EXIT_KIND IS
   * NULL AND ENTITY_STATUS='ACTIVE'}); sonst Konflikt weiterwerfen -> voller Rollback (kein 204 ohne
   * gueltigen aktiven Assignee).
   *
   * @param taskId    interne Aufgaben-ID
   * @param targetRef UUID des Uebergabeziels
   * @param tenantId  Task-Mandant (= PARTICIPANT_TENANT_ID der zu haltenden/neu anzulegenden Zeile)
   * @throws BusinessRuleException 409 fail-closed, wenn die Unique-Key-Zeile TEAM ist oder ein
   *     fremdes Basis-{@code TENANT_ID} traegt (Laufzeit-Vertrag, keine Mutation); ODER bei einem
   *     nebenlaeufigen Konflikt, dessen Re-Read die volle Invariante NICHT bestaetigt
   */
  private void ensureAssignee(final Long taskId, final UUID targetRef, final long tenantId) {
    // Unique-Key-exakter Lookup (ohne Status/EXIT/KIND-Filter) -> <=1 Zeile unabhaengig vom Basis-
    // TENANT_ID; die Klassifikation (reaktivieren vs. 409 vs. INSERT) erfolgt hier.
    final List<TaskParticipant> rows = participantQueryRepository.findRowByUniqueKey(taskId, targetRef);
    final TaskParticipant p;
    if (!rows.isEmpty()) {
      final TaskParticipant row = rows.get(0);
      // fail-closed 409: TEAM-Zeile ODER fremdes Basis-TENANT_ID -> KEINE Mutation (ensureAssignee-
      // Auswahlvertrag). Ein PERSON-Insert waere per Unique-Key unmoeglich; stilles Flippen wuerde
      // ungueltige Membership hinterlassen.
      if (row.getParticipantKind() != ParticipantKind.PERSON
          || !Objects.equals(tenantId, row.getTenantId())) {
        throw new BusinessRuleException(
            "Das Uebergabeziel kann nicht als Assignee gefuehrt werden: die vorhandene"
                + " Teilnehmerzeile ist kein PERSON-Mitglied des Task-Mandanten.");
      }
      // PERSON + Basis-TENANT_ID=task-tenant (aktiv/exited/inaktiv egal): VOLL reaktivieren.
      p = row;
      p.setAssigneeFlag(true);
      p.setParticipantFlag(true);
      p.setExitKind(null);
      p.setExitAt(null);
      p.setEntityState(EntityState.ACTIVE);
    } else {
      // Unique-Key frei -> neue PERSON-Assignee-Zeile (TENANT_ID = PARTICIPANT_TENANT_ID = task-tenant).
      p = new TaskParticipant();
      p.setTenantId(tenantId);
      p.setTaskId(taskId);
      p.setParticipantRef(targetRef);
      p.setParticipantKind(ParticipantKind.PERSON);
      p.setParticipantTenantId(tenantId);
      p.setCreatorFlag(false);
      p.setAssigneeFlag(true);
      p.setParticipantFlag(true);
    }
    // Test-Seam: deterministische Synchronisation der Ziel-Race-ITs ZWISCHEN Vorab-Read und save.
    onBeforeHandoverAssigneeSaveForTest(taskId, targetRef);
    try {
      participantQueryRepository.save(p);
    } catch (final BusinessRuleException conflict) {
      final List<TaskParticipant> recheck =
          participantQueryRepository.findRowByUniqueKey(taskId, targetRef);
      if (recheck.isEmpty()) {
        throw conflict;
      }
      final TaskParticipant winner = recheck.get(0);
      // Volle Membership-Invariante als akzeptabler SKIP (nicht nur Flag/EXIT_KIND): PERSON,
      // task-tenant (Basis + PARTICIPANT_TENANT_ID), ASSIGNEE_FLAG=1, EXIT_KIND null, ENTITY_STATUS
      // ACTIVE. Ein nebenlaeufiger Gewinner, der nur Flag/EXIT_KIND erfuellt, aber falschen
      // PARTICIPANT_TENANT_ID/KIND/Basis-Tenant hat, ist KEIN akzeptabler SKIP.
      if (winner.getParticipantKind() == ParticipantKind.PERSON
          && Objects.equals(tenantId, winner.getTenantId())
          && Objects.equals(tenantId, winner.getParticipantTenantId())
          && winner.isAssigneeFlag()
          && winner.getExitKind() == null
          && winner.getEntityState() == EntityState.ACTIVE) {
        log.debug(
            "AssigneeService.ensureAssignee: nebenlaeufiger Ziel-Konflikt fuer Task {} Ref {} -"
                + " idempotenter SKIP (Gewinner erfuellt volle Assignee-Invariante).",
            taskId,
            targetRef);
        return;
      }
      // z.B. paralleles reines-Teilnehmer-Add (ASSIGNEE_FLAG=false) oder Anomalie-Zeile -> KEIN
      // akzeptabler Zustand fuer ein Handover-Ziel: Konflikt weiterwerfen -> voller Rollback.
      throw conflict;
    }
  }

  /**
   * Test-Seam (Default No-Op): Haken ZWISCHEN Vorab-Read und save in {@link #ensureAssignee}.
   * Ermoeglicht deterministische Ziel-Race-ITs (BE-7 IT6). Produktiv ein No-Op.
   *
   * @param taskId    interne Aufgaben-ID
   * @param targetRef UUID des Uebergabeziels
   */
  protected void onBeforeHandoverAssigneeSaveForTest(final Long taskId, final UUID targetRef) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): Haken am ANFANG von {@link #exit} - direkt nach dem Laden der Aufgabe
   * (VERSION erfasst) und VOR jeder Mutation. Ermoeglicht deterministische Versions-Verlierer-ITs
   * (BE-7 IT7): ein nebenlaeufiger Gewinner (Exit/PUT/Handover) committet, waehrend der Verlierer hier
   * pausiert und noch KEINE Schreibsperren haelt (der {@code requireTask}-SELECT gibt seine
   * Shared-Locks statement-weise frei) - so entsteht KEIN Wartezyklus/Deadlock. Nach der Fortsetzung
   * trifft der Verlierer beim versionierten {@code taskRepository.save} die stale VERSION -> 409 +
   * voller Rollback. Produktiv ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   */
  protected void onBeforeExitMutationForTest(final Long taskId) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Schreibt den Handover-/Austritts-Historieneintrag mit Namen-Snapshot (SPEC v3 Schritt 7). Refs
   * bleiben im Ereignis; die Namen werden zum Zeitpunkt aufgeloest ({@code findActiveByUuids} fuer
   * Ziele, {@code userReadRepository} fuer den Akteur). Faellt ein Name aus, wird die Ref als Fallback
   * verwendet.
   */
  private void recordHandoverHistory(
      final Long taskId,
      final long tenantId,
      final UUID actorRef,
      final Set<UUID> targets,
      final boolean handover,
      final boolean shouldStay) {
    final String text =
        shouldStay ? "hat die Aufgabe uebergeben" : "hat die Aufgabe uebergeben und verlassen";
    final String unsubscribeText = "hat die Aufgabe verlassen";

    final Map<UUID, String> names = resolveNames(targets, actorRef);
    final String actorName = names.getOrDefault(actorRef, actorRef.toString());

    final String detail;
    if (handover) {
      final String targetNames =
          targets.isEmpty()
              ? "(keine Ziele)"
              : targets.stream()
                  .map(ref -> names.getOrDefault(ref, ref.toString()))
                  .collect(Collectors.joining(", "));
      detail = actorName + " -> " + targetNames;
    } else {
      detail = actorName;
    }
    taskHistoryService.record(taskId, tenantId, actorRef, handover ? text : unsubscribeText, detail);
  }

  /** Loest die Anzeigenamen fuer Akteur + Ziele in EINER Abfrage auf (Namen-Snapshot). */
  private Map<UUID, String> resolveNames(final Set<UUID> targets, final UUID actorRef) {
    final Set<UUID> all = new LinkedHashSet<>(targets);
    all.add(actorRef);
    return userReadRepository.findActiveByUuids(all).stream()
        .filter(u -> u.getUuid() != null)
        .collect(
            Collectors.toMap(
                User::getUuid,
                u -> u.getFullName() != null ? u.getFullName() : u.getUuid().toString(),
                (a, b) -> a));
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
