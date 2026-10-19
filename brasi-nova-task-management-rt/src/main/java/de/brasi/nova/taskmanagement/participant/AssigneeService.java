package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.Task;
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
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
      final InboxGenerationService inboxGenerationService) {
    this.taskRepository = taskRepository;
    this.participantQueryRepository = participantQueryRepository;
    this.userStateQueryRepository = userStateQueryRepository;
    this.userStateService = userStateService;
    this.notificationService = notificationService;
    this.inboxSignalService = inboxSignalService;
    this.userReadRepository = userReadRepository;
    this.userAccessor = userAccessor;
    this.inboxGenerationService = inboxGenerationService;
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
    // Creator-Schutz: Participants mit CREATOR_FLAG=1 werden aus toRemove ausgeschlossen
    // (Creator ist unveraenderlich Teilnehmer seiner eigenen Aufgabe - Task-8-Invariante).
    final Set<UUID> toRemove =
        currentRefs.stream()
            .filter(ref -> !desiredRefs.contains(ref))
            .filter(ref -> !creatorRefs.contains(ref))
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
      // (UNSUBSCRIBED_AT/EXIT_KIND -> null) -> Generation atomar in dieser Tx bumpen, damit alte
      // Inbox-Cursor dieses Benutzers beim naechsten Read abgelehnt werden (410).
      inboxGenerationService.bump(tenantId, assigneeRef);
      log.info(
          "AssigneeService.updateAssignees: Assignee {} der Aufgabe {} (id={}) zugewiesen"
              + " von Akteur {} in Mandant {}.",
          assigneeRef,
          taskExternalId,
          taskId,
          actorRef,
          tenantId);
    }

    // Abgewaehlte Assignees: EXIT_KIND=UNSUBSCRIBED + EXIT_AT setzen
    for (final UUID removedRef : toRemove) {
      final List<TaskParticipant> existing =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, removedRef);
      if (!existing.isEmpty()) {
        final TaskParticipant p = existing.get(0);
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

        affectedRefs.add(removedRef);
        // Finding 2 / Option B: Abwahl ueberschreibt destruktiv EXIT_KIND/EXIT_AT/UNSUBSCRIBED_AT
        // dieses Refs -> Generation atomar in dieser Tx bumpen (alte Inbox-Cursor -> 410).
        inboxGenerationService.bump(tenantId, removedRef);
        log.info(
            "AssigneeService.updateAssignees: Assignee {} aus Aufgabe {} (id={}) abgewaehlt"
                + " (EXIT_KIND=UNSUBSCRIBED) von Akteur {} in Mandant {}.",
            removedRef,
            taskExternalId,
            taskId,
            actorRef,
            tenantId);
      }
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

      final Set<UUID> toInvalidate = new HashSet<>(affectedRefs);
      userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
          .map(TaskUserState::getUserRef)
          .forEach(toInvalidate::add);
      inboxSignalService.invalidate(tenantId, toInvalidate);
    }
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe (EXIT_KIND aus Input).
   *
   * <p>Ablauf:
   * <ol>
   *   <li>Aufgabe tenant-scoped laden (404-Masking).
   *   <li>Participant des aktuellen Akteurs laden; falls kein Eintrag -> 400
   *       ({@link EntityValidationException}).
   *   <li>EXIT_KIND + EXIT_AT auf dem Participant setzen.
   *   <li>Bei JEDEM ExitKind: UNSUBSCRIBED_AT im {@link de.brasi.nova.taskmanagement.entity.TaskUserState}
   *       setzen (Inbox-Konsistenz: Verlassen = raus aus aktiver Inbox, unabhaengig von der Exit-Art).
   *   <li>Wenn HANDOVER: HANDOVER-Notification an den Akteur selbst emittieren (Selbstbestaetigung).
   *       <b>1a-Einschraenkung:</b> Ein echtes Handover-Ziel ({@code handoverTo}) ist in Phase 1a
   *       noch nicht modelliert; die Benachrichtigung des Uebernehmers ist ein spaeterer Schritt.
   *   <li>Nach Commit: {@link InboxSignalService#invalidate} fuer den Akteur.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input          Austritts-Eingabe (exitKind)
   * @throws EntityNotFoundException   wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn der Akteur kein aktiver Teilnehmer der Aufgabe ist
   */
  @Transactional
  public void exit(final UUID taskExternalId, final ExitInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Participant des aktuellen Akteurs suchen
    final List<TaskParticipant> existing =
        participantQueryRepository.findByTaskIdAndParticipantRef(taskId, actorRef);

    if (existing.isEmpty()) {
      throw new EntityValidationException(
          "Der aktuelle Benutzer ist kein aktiver Teilnehmer dieser Aufgabe.");
    }

    final TaskParticipant participant = existing.get(0);

    // Final-Review P2-4: Der Ersteller kann seine eigene Aufgabe nicht verlassen
    // (Creator-Immobilitaet, konsistent zu updateAssignees, das den Creator aus toRemove
    // ausschliesst). Ein Creator-Exit wuerde den Ersteller aus der aktiven Inbox entfernen und
    // die in Task 8 erzwungene Invariante verletzen -> Ablehnung als Geschaeftsregel (HTTP 409).
    if (participant.isCreatorFlag()) {
      throw new BusinessRuleException("Der Ersteller kann die Aufgabe nicht verlassen.");
    }

    // Final-Review P2-5: Ein bereits ausgetretener Teilnehmer (EXIT_KIND gesetzt) darf nicht
    // erneut austreten - sonst gaebe es eine zweite Erfolgsantwort und (bei HANDOVER) eine zweite
    // Notification. Neutral wie "kein aktiver Teilnehmer" ablehnen (HTTP 400).
    if (participant.getExitKind() != null) {
      throw new EntityValidationException(
          "Der aktuelle Benutzer ist kein aktiver Teilnehmer dieser Aufgabe.");
    }

    // EXIT_KIND + EXIT_AT setzen. Der save() laeuft ueber KSFs Optimistic-Lock (VERSION-Spalte):
    // Bei zwei nebenlaeufigen Austritts-Anfragen koennen beide den EXIT_KIND-Guard oben passieren,
    // solange noch keine committet hat. Der Versionsvergleich beim Commit laesst genau eine
    // gewinnen; die verlierende Transaktion meldet KSF als BusinessRuleException (HTTP 409). Diesen
    // Wettlauf-Verlierer uebersetzen wir - sofern der Teilnehmer inzwischen tatsaechlich ausgetreten
    // ist - in dieselbe neutrale 400-Antwort wie den sequentiellen Doppel-Austritt (P2-5), statt
    // einen generischen 409 nach aussen zu geben. So bleibt die Doppel-Austritts-Invariante auch
    // unter Nebenlaeufigkeit konsistent (keine zweite Erfolgsantwort / keine zweite Notification).
    participant.setExitKind(input.exitKind());
    participant.setExitAt(LocalDateTime.now());
    try {
      participantQueryRepository.save(participant);
    } catch (final BusinessRuleException concurrentConflict) {
      final List<TaskParticipant> recheck =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, actorRef);
      if (recheck.isEmpty() || recheck.get(0).getExitKind() != null) {
        throw new EntityValidationException(
            "Der aktuelle Benutzer ist kein aktiver Teilnehmer dieser Aufgabe.");
      }
      // Unverwandter Konflikt (Teilnehmer weiterhin aktiv) -> unveraendert durchreichen.
      throw concurrentConflict;
    }

    // Bei JEDEM Exit: UNSUBSCRIBED_AT im UserState setzen (Inbox-Konsistenz - MAJOR 2).
    // findActivePage filtert auf UNSUBSCRIBED_AT IS NULL -> nach HANDOVER UND UNSUBSCRIBED
    // muss der Task aus der aktiven Inbox verschwinden.
    final List<TaskUserState> states =
        userStateQueryRepository.findByTaskIdAndUserRef(taskId, actorRef);
    if (!states.isEmpty()) {
      final TaskUserState state = states.get(0);
      state.setUnsubscribedAt(LocalDateTime.now());
      userStateQueryRepository.save(state);
    }

    // Final-Re-Gate P2: Austritt am gemeinsamen TASK-Version-Guard teilnehmen lassen.
    // updateAssignees bumpt bei Membership-Aenderung die TASK-VERSION (optimistischer Lock); exit tat
    // das bisher NICHT (nur die Participant-VERSION ueber save(participant)). Dadurch konnten ein PUT,
    // der einen beibehaltenen Assignee als aktiv liest ohne dessen Participant-Zeile zu aendern, und
    // ein nebenlaeufiger exit DIESES Assignees beide committen -> der finale Assignee-Satz verletzt das
    // Ziel-Set des mit 204 quittierten PUT. Der TASK-save (VERSION-Bump) macht die Aufgabe zum
    // gemeinsamen Serialisierungspunkt beider Membership-Mutationen: genau eine Transaktion gewinnt,
    // die verlierende bricht mit BusinessRuleException (409) ab (0 Zeilen beim versionierten UPDATE)
    // und wird gegen frischen Stand wiederholt. Bewusst NICHT in den Participant-Doppel-Austritt-400-
    // Zweig uebersetzt: ein TASK-Versionskonflikt ist eine echte nebenlaeufige Membership-Mutation
    // (Retry), kein Doppel-Austritt.
    taskRepository.save(task);

    // Bei HANDOVER: HANDOVER-Notification emittieren (an den Akteur selbst als Bestaetigung)
    if (ExitKind.HANDOVER == input.exitKind()) {
      notificationService.emit(
          actorRef,
          taskId,
          NotificationTypeCodes.HANDOVER,
          NotificationSeverityCodes.INFO,
          "Du hast die Aufgabe uebergeben.",
          task.getTitle(),
          actorRef);
    }

    // Finding 2 / Option B: Austritt ueberschreibt destruktiv EXIT_KIND/EXIT_AT/UNSUBSCRIBED_AT des
    // Akteurs -> Generation atomar in dieser Tx bumpen, damit alte Inbox-Cursor des Akteurs beim
    // naechsten Read abgelehnt werden (410). Liegt NACH allen Abweiszweigen (Creator-/Doppel-
    // Austritt-/Wettlauf-Verlierer werfen vorher) -> Bump nur bei tatsaechlichem Austritt.
    inboxGenerationService.bump(tenantId, actorRef);

    // After-Commit: Invalidate fuer den Akteur UND alle verbleibenden aktiven Teilnehmer.
    // Der Austritt entfernt den Akteur aus participantSummaries -> der Snapshot jedes verbleibenden
    // aktiven Teilnehmers aendert sich ebenfalls (analog updateAssignees). Der Akteur selbst ist
    // nach dem UNSUBSCRIBED_AT-Set nicht mehr in findActiveUserRefsByTaskId -> explizit erganzen.
    final Set<UUID> toInvalidate = new HashSet<>();
    toInvalidate.add(actorRef);
    userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
        .map(TaskUserState::getUserRef)
        .forEach(toInvalidate::add);
    inboxSignalService.invalidate(tenantId, toInvalidate);

    log.info(
        "AssigneeService.exit: Akteur {} hat Aufgabe {} (id={}) verlassen"
            + " (EXIT_KIND={}) in Mandant {}.",
        actorRef,
        taskExternalId,
        taskId,
        input.exitKind(),
        tenantId);
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
