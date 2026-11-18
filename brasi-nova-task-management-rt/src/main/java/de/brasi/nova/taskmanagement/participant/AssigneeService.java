package de.brasi.nova.taskmanagement.participant;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskHandover;
import de.brasi.nova.taskmanagement.entity.TaskHandoverTarget;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
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
import java.util.Collection;
import java.util.HashMap;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando-Operationen fuer Assignee-Verwaltung und Austritt.
 *
 * <p>Alle Mutationen laufen in einer eigenen {@code @Transactional}-Transaktion. Nach erfolgtem
 * Commit wird via {@link InboxSignalService} ein {@code snapshot-invalidated}-Signal an die
 * betroffenen Benutzer gesendet.
 *
 * <p>Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}), NIEMALS aus
 * Client-Eingabedaten.
 */
@Service
public class AssigneeService {

  private static final Logger log = LoggerFactory.getLogger(AssigneeService.class);

  /**
   * Meldung der 400 beim Versuch, sich selbst zu entfernen (Intake #145, auch OpenAPI-Beispiel).
   */
  static final String SELF_REMOVE_MESSAGE =
      "Sich selbst aus der Aufgabe zu entfernen ist nicht moeglich. Zum Verlassen der"
          + " Aufgabe POST /api/v1/tasks/{id}/exit verwenden.";

  /** Meldung der 403, wenn der Akteur weder Ersteller noch aktiver Assignee ist (Intake #145). */
  static final String REMOVE_ROLE_MESSAGE =
      "Beteiligte entfernen duerfen nur der Ersteller und aktive Assignees der Aufgabe.";

  /** Meldung der 409, wenn der Ersteller entfernt werden soll (Intake #145). */
  static final String REMOVE_CREATOR_MESSAGE =
      "Der Ersteller kann nicht aus der Aufgabe entfernt werden.";

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
   * Aktualisiert die Assignee-Menge einer Aufgabe (PUT-Semantik: Ziel-Menge ersetzt aktuellen
   * Stand).
   *
   * <p>Ablauf:
   *
   * <ol>
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>Aktuelle Assignees laden und mit Ziel-Menge vergleichen.
   *   <li>Neu zugewiesene Assignees (in der Ziel-Menge, aber noch kein aktiver Assignee), nach UUID
   *       aufsteigend: die Zeile Unique-Key-exakt suchen ({@link #findPersonRowByUniqueKey}, findet
   *       auch ausgetretene und DEACTIVE-Zeilen, Intake #163) und voll als Assignee reaktivieren
   *       (ASSIGNEE_FLAG=PARTICIPANT_FLAG=1, Austritt geloescht, ENTITY_STATUS=ACTIVE) bzw. ohne
   *       Zeile neu anlegen (PARTICIPANT_KIND=PERSON); TEAM-Zeile oder fremdes Basis-TENANT_ID ->
   *       409 ohne Mutation. Danach {@link UserStateService#reactivateAsAssignee} (UserState
   *       sichern, UNSUBSCRIBED_AT zuruecksetzen und einen Mute dieser Aufgabe aufheben),
   *       ASSIGN-Notification emittieren. Unveraendert bleibende Assignees werden nicht angefasst,
   *       ihr Mute bleibt bestehen.
   *   <li>Abgewaehlte Assignees (vorher drin, jetzt nicht mehr): EXIT_KIND=UNSUBSCRIBED + EXIT_AT
   *       setzen.
   *   <li>Lock-Reihenfolge (Intake #167): alle Reads (Zeilen und States der neuen und der
   *       abgewaehlten Personen, Leser fuer Bump und Signal) stehen VOR der ersten Schreibung,
   *       danach die Schreibungen, der versionierte TASK-save, der sortierte Bump.
   *   <li>Bei echter Aenderung: Generation-Bump und nach Commit {@link
   *       InboxSignalService#invalidate} fuer die geaenderten Personen, alle aktiven Leser und alle
   *       ausgetretenen Archivleser (Intake #166, {@link #collectTaskReaders}).
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input Ziel-Assignee-Menge
   * @throws EntityNotFoundException wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn Input ungueltig
   * @throws BusinessRuleException (409) nebenlaeufiger Versionskonflikt, Unique- oder
   *     Sequenzkonflikt beim Speichern einer neuen Teilnehmerzeile (gleichzeitiger Insert) oder
   *     vorhandene Teilnehmerzeile einer neu zuzuweisenden Person ist keine PERSON-Zeile des
   *     Task-Mandanten; in allen Faellen rollt die Transaktion alles bisher Geschriebene zurueck
   */
  @Transactional
  public void updateAssignees(final UUID taskExternalId, final AssigneeUpdateInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Privilege-Escalation-Schutz: WRITE-Member-Guard GANZ VORNE - direkt nach requireTask (Task-
    // Laden + Tenant/ENTITY_STATUS=ACTIVE), VOR jeder Eingabe-/Zielvalidierung und jeder Mutation
    // (analog exit(), Z. ~619). Ohne diesen Guard koennte ein Akteur mit breit gegrantetem
    // TASK_WRITE,
    // der KEIN Member der Aufgabe ist, sich selbst/andere als Assignee eintragen -> wuerde Member
    // ->
    // Vollzugriff. Fehlende/ausgetretene Membership -> einheitlich neutrale 404 (kein
    // Existenz-Leak).
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

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

    // Neue Assignees: in desired, aber nicht in current. Intake #156: nach UUID sortiert, weil
    // dieselben Teilnehmer-/State-Zeilen auch createComment (Aufnahme erwaehnter Personen),
    // addParticipants und die Uebergabe schreiben und alle Pfade mehrere Zeilen in GLEICHER
    // Reihenfolge sperren muessen; eine HashSet-Reihenfolge liesse zwei ausgetretene Personen, die
    // gleichzeitig erwaehnt und zugewiesen werden, ueberkreuz sperren (Deadlock).
    final List<UUID> toAdd =
        desiredRefs.stream().filter(ref -> !currentRefs.contains(ref)).sorted().toList();

    // Abgewaehlte Assignees: in current, aber nicht in desired.
    // BE-7 (A3): Creator-Schutz ist nun MITGLIEDSCHAFTS-Schutz (nicht mehr "Creator nie aus
    // toRemove"): ein ausgelassener Creator wird DE-ASSIGNT (ASSIGNEE_FLAG=false), behaelt aber
    // CREATOR_FLAG + PARTICIPANT_FLAG + aktiven UserState + EXIT_KIND=null (KEIN Austritt). Ein
    // ausgelassener Nicht-Creator wird wie bisher mit EXIT_KIND=UNSUBSCRIBED ausgetreten.
    // Ebenfalls nach UUID sortiert (siehe toAdd).
    final List<UUID> toRemove =
        currentRefs.stream().filter(ref -> !desiredRefs.contains(ref)).sorted().toList();

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

    // Intake #167: alle Reads VOR der ersten Mitgliedschafts-Schreibung (Lock-Reihenfolge wie
    // removeParticipant): Zeilen und States der neu zuzuweisenden und der abgewaehlten Personen
    // sowie die Leser fuer Bump und Signal. Eine Lesung nach eigenen Schreibungen wartet unter
    // sperrendem READ COMMITTED auf die X-gesperrten Zeilen eines parallelen Aufrufs fuer andere
    // Personen derselben Aufgabe und verklemmt mit ihm (Deadlock 1205), bevor der
    // TASK-Versionskonflikt (409) greift.
    //
    // Neu zuzuweisende Personen: Zeile Unique-Key-exakt suchen (Intake #163, wie addParticipants
    // seit #156 und die Uebergabe): auch DEACTIVE- und ausgetretene PERSON-Zeilen (z.B.
    // Beobachter, Ersteller, Ex-Assignee). Der fruehere ACTIVE-Lookup uebersah DEACTIVE-Zeilen,
    // der Insert brach dann den V3-Unique-Key und der ganze PUT endete mit 409. TEAM-Zeile oder
    // fremdes Basis-TENANT_ID -> 409 ohne Mutation.
    final Map<UUID, Optional<TaskParticipant>> addRows = new HashMap<>();
    final Map<UUID, List<TaskUserState>> statesByRef = new HashMap<>();
    for (final UUID assigneeRef : toAdd) {
      addRows.put(
          assigneeRef,
          findPersonRowByUniqueKey(
              taskId,
              assigneeRef,
              tenantId,
              "Die Person kann nicht als Assignee gefuehrt werden: die vorhandene"
                  + " Teilnehmerzeile ist kein PERSON-Mitglied des Task-Mandanten."));
      statesByRef.put(
          assigneeRef, userStateQueryRepository.findByTaskIdAndUserRef(taskId, assigneeRef));
    }
    // Abgewaehlte Personen: Teilnehmerzeile und (bei Nicht-Erstellern, die austreten) State.
    final Map<UUID, TaskParticipant> removeRows = new HashMap<>();
    for (final UUID removedRef : toRemove) {
      final List<TaskParticipant> existing =
          participantQueryRepository.findByTaskIdAndParticipantRef(taskId, removedRef);
      if (existing.isEmpty()) {
        continue;
      }
      removeRows.put(removedRef, existing.get(0));
      if (!creatorRefs.contains(removedRef)) {
        statesByRef.put(
            removedRef, userStateQueryRepository.findByTaskIdAndUserRef(taskId, removedRef));
      }
    }
    // Leser fuer Bump und Signal (Intake #166): alle aktiven Leser und alle ausgetretenen
    // Archivleser, weil die Archivsuche Namen aktiver Teilnehmer trifft und sich mit jeder
    // Zuweisung oder Abwahl die Treffer jedes Lesers aendern koennen. Ein No-Op-PUT (keine neue,
    // keine abzuwaehlende Zeile) liest sie nicht.
    final Set<UUID> readers =
        toAdd.isEmpty() && removeRows.isEmpty() ? Set.of() : collectTaskReaders(taskId);

    // Test-Seam (Intake #167): nach den Vorab-Reads, vor der ersten Mitgliedschafts-Schreibung.
    // Produktiv No-Op.
    onBeforeMembershipWritesForTest(taskId);

    final List<UUID> affectedRefs = new ArrayList<>();

    // Neue Assignees anlegen
    for (final UUID assigneeRef : toAdd) {
      // Test-Seam: Synchronisation der Nebenlaeufigkeits-ITs VOR der ersten Schreibung je Person
      // (Reihenfolge-Beweis gegen die Aufnahme per Erwaehnung). Produktiv No-Op.
      onBeforeAssigneeAddForTest(taskId, assigneeRef);

      final Optional<TaskParticipant> existing = addRows.get(assigneeRef);

      if (existing.isPresent()) {
        // Bestehende Zeile reaktivieren: Assignee und Teilnehmer, Austritt geloescht, ACTIVE.
        final TaskParticipant p = existing.get();
        activateAsAssignee(p);
        participantQueryRepository.save(p);
        log.debug(
            "AssigneeService.updateAssignees: Participant {} fuer Task {} reaktiviert als Assignee.",
            assigneeRef,
            taskExternalId);
      } else {
        // Neuen Participant-Eintrag anlegen
        participantQueryRepository.save(newPersonRow(taskId, assigneeRef, tenantId, true));
        log.debug(
            "AssigneeService.updateAssignees: Neuer Assignee-Participant {} fuer Task {} angelegt.",
            assigneeRef,
            taskExternalId);
      }

      // UserState reaktivieren: UNSUBSCRIBED_AT=null setzen (Reassign-Reaktivierung - MAJOR 3).
      // reactivateAsAssignee stellt sicher, dass ein Eintrag existiert UND UNSUBSCRIBED_AT
      // zurueckgesetzt wird. Damit erscheint der erneut zugewiesene Benutzer wieder in
      // findActivePage. Eine NEUE Zuweisung hebt zudem den Mute dieser Aufgabe auf (Folge von
      // #158); diese Schleife laeuft nur ueber toAdd, unveraenderte Assignees bleiben stumm. Der
      // State ist vorab gelesen (Intake #167).
      userStateService.reactivateAsAssignee(
          taskId, assigneeRef, tenantId, statesByRef.get(assigneeRef));

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
      // affectedRefs gesammelt; der eigentliche (sortierte) Bump erfolgt gebuendelt NACH dem
      // TASK-save
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
      final TaskParticipant p = removeRows.get(removedRef);
      if (p == null) {
        continue;
      }
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
        // Nicht-Creator: EXIT_KIND=UNSUBSCRIBED + EXIT_AT (echter Austritt) samt UNSUBSCRIBED_AT im
        // UserState; gemeinsamer Kern mit removeParticipant (Intake #145), State vorab gelesen.
        saveUnsubscribedParticipant(p);
        unsubscribeState(statesByRef.get(removedRef));
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

    // Test-Seam (Intake #167): nach den eigenen Teilnehmer- und State-Schreibungen, vor TASK-save
    // und Bump. Produktiv No-Op.
    onAfterMembershipWritesForTest(taskId);

    // After-Commit: Invalidate NUR bei tatsaechlicher Aenderung (affectedRefs nicht leer).
    // Idempotente No-Op-PUTs (Ziel-Menge == aktueller Stand, inkl. Creator-Schutz-No-Ops) duerfen
    // weder Leser laden noch bumpen noch Invalidierungen senden - sonst vermeidbare DB-Last,
    // Push-Traffic und Client-Snapshot-Reloads (analog TaskCommandService.patch 'changed'-Guard).
    // Bei echter Aenderung (Intake #166): participantSummaries und die Treffer der Archivsuche
    // (Namen aktiver Teilnehmer) aendern sich fuer JEDEN Leser - deshalb Bump und Signal fuer die
    // Vereinigung aus geaenderten Refs (affectedRefs) und allen vorab gelesenen Lesern (aktive
    // States und ausgetretene Archivleser), dieselbe Menge wie beim Entfernen.
    // Das ist eine bewusste fachliche Entscheidung zu Intake #166, einheitlich mit Austritt,
    // Aendern und Entfernen. Mehrlast: je Aenderung ein Inbox-Neuladen fuer jeden Leser der
    // Aufgabe, auch bei offenen Aufgaben.
    if (!affectedRefs.isEmpty()) {
      // Whole-Branch-Re-Gate P2 #3: Nebenlaeufige PUT-Set-Ersetzungen serialisieren. Zwei parallele
      // Ersetzungen ab demselben Ist-Stand ({creator} -> {creator,A} bzw. {creator,B}) koennten
      // sonst
      // beide committen und die Ziel-Menge still zu {creator,A,B} mergen (PUT-Set-Semantik
      // verletzt).
      // Ein optimistischer Lock auf die Aufgabe (VERSION-Bump via save) laesst genau eine
      // Transaktion
      // gewinnen; die verlierende bricht mit BusinessRuleException (409) ab (0 betroffene Zeilen
      // beim
      // versionierten UPDATE) und der Client wiederholt den PUT gegen frischen Stand. Nur bei
      // tatsaechlicher Aenderung (affectedRefs nicht leer) - ein No-Op-PUT bumpt die Version nicht.
      //
      // Intake #156/#167: die Leser fuer Bump und Signal sind VOR der ersten Schreibung gelesen
      // (oben). Eine Lesung unter dem TASK-Lock wartete auf eine uncommittete State-Zeile, die
      // createComment beim Aufnehmen einer erwaehnten Person schreibt (Deadlock), eine Lesung nach
      // den eigenen Schreibungen auf die eines parallelen PUT, Austritts oder Hinzufuegens.
      final Set<UUID> readersAndChanged = new HashSet<>(readers);
      readersAndChanged.addAll(affectedRefs);

      taskRepository.save(task);

      // Codex #6: Generation-Bump ERST NACH dem TASK-save - einheitliche Save-dann-Bump-Ordnung
      // ueber alle Pfade. Ueber den zentralen Helfer in SORTIERTER Reihenfolge (keine
      // ueberkreuzte Generation-Lock-Order). Bump und Signal fuer dieselbe Menge (Intake #166).
      inboxGenerationService.bumpSorted(tenantId, readersAndChanged);

      inboxSignalService.invalidate(tenantId, readersAndChanged);
    }
  }

  /**
   * Entfernt eine Person aus einer Aufgabe (Intake #145, {@code DELETE
   * /api/v1/tasks/{id}/participants/{userId}}).
   *
   * <p>Ablauf (Reihenfolge ist Vertrag):
   *
   * <ol>
   *   <li>Aufgabe tenant-scoped laden, dann WRITE-Member-Guard des Akteurs: fremd, unbekannt oder
   *       kein aktives Mitglied ergibt die neutrale 404, vor jeder weiteren Pruefung.
   *   <li>Sich selbst entfernen ist 400 mit Hinweis auf {@code POST /api/v1/tasks/{id}/exit}; der
   *       eigene Austritt hat dort seine eigenen Regeln (Ersteller, Uebergabe).
   *   <li>Rechtegrenze: entfernen duerfen nur der Ersteller und aktive Assignees der Aufgabe. Ein
   *       anderes WRITE-Mitglied bekommt 403 ohne Mutation; das ist kein Existenz-Leck, weil der
   *       Akteur bestaetigtes Mitglied ist (Muster der Handover-Rechtegrenze in {@link #exit}). Das
   *       grobe Recht TASK_WRITE prueft der Controller.
   *   <li>Ziel lesen (aktive PERSON-Zeile des Task-Mandanten, Austritt beliebig): keine Zeile
   *       (unbekannte Person, nie beteiligt, deaktivierte Zeile) oder schon ausgetreten ergibt
   *       idempotent 204 ohne Aenderung, ohne Benachrichtigung und ohne Signal. Der Ersteller ist
   *       dauerhaftes Mitglied und nicht entfernbar (409).
   *   <li>Restliche Reads, alle VOR der ersten Schreibung (Review #145 Runde 2): State-Zeile der
   *       entfernten Person, Namen-Snapshot fuer die Historie und die Leser fuer Bump und Signal
   *       ({@link #collectRemovalReaders}: die entfernte Person, alle aktiven Leser und alle
   *       ausgetretenen Archivleser der Aufgabe). Nach der ersten Schreibung liest der Pfad keine
   *       Teilnehmer-, State- oder Benutzerzeile mehr; so kann eine parallele Entfernung einer
   *       anderen Person nicht ueberkreuz warten (Deadlock 1205 unter sperrendem READ COMMITTED).
   *   <li>Wirkung wie das Weglassen eines Nicht-Erstellers in {@link #updateAssignees}: derselbe
   *       Austrittskern ({@link #saveUnsubscribedParticipant}, {@link #unsubscribeState}:
   *       EXIT_KIND=UNSUBSCRIBED, EXIT_AT, UNSUBSCRIBED_AT im UserState), nur mit vorab gelesenem
   *       State; zusaetzlich verliert ein entfernter Assignee seine Zuweisung
   *       (ASSIGNEE_FLAG=false). Die Zeile bleibt ACTIVE, damit der READ-Guard das Archiv-Detail
   *       weiter zulaesst. Keine neue Austrittsart.
   *   <li>Historieneintrag mit Namen-Snapshot der entfernten Person, dann REMOVED-Benachrichtigung
   *       an sie (nie an den Akteur; der Selbstfall ist oben schon 400). REMOVED ist von der
   *       Stummschaltung ausgenommen (Begruendung in {@code NotificationMuteFilter}), daher keine
   *       Filter-Lesung.
   *   <li>Versionierter TASK-save: der VERSION-Bump serialisiert gegen parallele PUT/exit/Entfernen
   *       (Verlierer 409, voller Rollback). Danach Generation-Bump (sortiert) und after-commit
   *       Signal fuer die vorab gelesene Menge. LAST_ACTIVITY_AT bleibt wie beim PUT unveraendert,
   *       die Keyset-Position der Aufgabe verschiebt sich nicht.
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param participantRef UUID der zu entfernenden Person
   * @throws EntityNotFoundException Aufgabe unbekannt, fremd, deaktiviert oder Akteur kein aktives
   *     WRITE-Mitglied (404, neutral)
   * @throws EntityValidationException der Akteur will sich selbst entfernen (400)
   * @throws PermissionDeniedException der Akteur ist weder Ersteller noch aktiver Assignee (403)
   * @throws BusinessRuleException Ziel ist der Ersteller, nebenlaeufiger Versionskonflikt oder
   *     Sequenzkonflikt beim Anlegen von Historieneintrag oder Benachrichtigung (409)
   */
  @Transactional
  public void removeParticipant(final UUID taskExternalId, final UUID participantRef) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Guard GANZ VORNE (vor Selbst-, Rechte- und Zielpruefung): kein Existenz-Leck ueber
    // 400/403/409.
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    if (participantRef.equals(actorRef)) {
      throw new EntityValidationException(SELF_REMOVE_MESSAGE);
    }

    // Rechtegrenze: nur Ersteller und aktive Assignees. Der Guard hat die aktive, nicht
    // ausgetretene PERSON-Zeile des Akteurs bereits bestaetigt.
    final List<TaskParticipant> actorRows =
        participantQueryRepository.findByTaskIdAndParticipantRef(taskId, actorRef);
    final boolean mayRemove =
        !actorRows.isEmpty()
            && actorRows.get(0).getExitKind() == null
            && (actorRows.get(0).isCreatorFlag() || actorRows.get(0).isAssigneeFlag());
    if (!mayRemove) {
      throw new PermissionDeniedException(
          "Ersteller oder aktiver Assignee der Aufgabe", REMOVE_ROLE_MESSAGE);
    }

    final List<TaskParticipant> rows =
        participantQueryRepository.findByTaskIdAndParticipantRef(taskId, participantRef);
    if (rows.isEmpty()) {
      log.debug(
          "AssigneeService.removeParticipant: Person {} ist kein Teilnehmer der Aufgabe {}"
              + " - idempotent ohne Aenderung.",
          participantRef,
          taskExternalId);
      return;
    }
    final TaskParticipant target = rows.get(0);
    if (target.isCreatorFlag()) {
      throw new BusinessRuleException(REMOVE_CREATOR_MESSAGE);
    }
    if (target.getExitKind() != null) {
      log.debug(
          "AssigneeService.removeParticipant: Person {} ist aus der Aufgabe {} bereits"
              + " ausgetreten - idempotent ohne Aenderung.",
          participantRef,
          taskExternalId);
      return;
    }

    // Review #145 Runde 2: ALLE Reads stehen vor der ersten Schreibung (Lock-Reihenfolge, Karte
    // participants-actions). Zwei gleichzeitige Entfernungen verschiedener Personen derselben
    // Aufgabe schreiben je ihre eigene Teilnehmer- und State-Zeile; laege eine Lesung ueber alle
    // States oder Teilnehmer der Aufgabe hinter der eigenen Schreibung, wartete unter sperrendem
    // READ COMMITTED jede auf die X-gesperrte Zeile der anderen (Deadlock 1205), bevor der
    // TASK-Versionskonflikt greift. So wartet hoechstens eine Lesung auf eine fremde Schreibung,
    // ohne selbst Schreibsperren zu halten.
    // (a) State-Zeile der entfernten Person (vorher erst nach dem Speichern der Teilnehmerzeile).
    final List<TaskUserState> targetStates =
        userStateQueryRepository.findByTaskIdAndUserRef(taskId, participantRef);
    // (b) Namen-Snapshot fuer die Historie (Benutzerstamm, keine Mitgliedschaftszeile).
    final Map<UUID, String> names = resolveNames(Set.of(participantRef), actorRef);
    // (c) Leser fuer Bump und Signal; die entfernte Person ist ausdruecklich in der Menge.
    final Set<UUID> affected = collectRemovalReaders(taskId, participantRef);

    // Test-Seam: Synchronisation der Nebenlaeufigkeits-ITs NACH allen Reads und VOR der ersten
    // Schreibung (zu diesem Zeitpunkt haelt die Transaktion keine Schreibsperren). Produktiv No-Op.
    onBeforeRemoveMutationForTest(taskId, participantRef);

    // Ab hier nur noch Schreibungen: Teilnehmer, State, Historie, Benachrichtigung, Aufgabe. Die
    // Benachrichtigung liest nur noch Katalog-Codes (Cache des DictionaryCodeResolver, sonst die
    // Katalogtabelle, die kein Pfad schreibt); die Sequenz-Vergabe laeuft in eigener Transaktion.
    final boolean wasAssignee = target.isAssigneeFlag();
    target.setAssigneeFlag(false);
    saveUnsubscribedParticipant(target);
    unsubscribeState(targetStates);

    taskHistoryService.record(
        taskId,
        tenantId,
        actorRef,
        "hat einen Beteiligten entfernt",
        names.getOrDefault(participantRef, participantRef.toString()));

    notificationService.emit(
        participantRef,
        taskId,
        NotificationTypeCodes.REMOVED,
        NotificationSeverityCodes.INFO,
        "Du wurdest aus der Aufgabe entfernt.",
        task.getTitle(),
        actorRef);

    // Versionierter TASK-save serialisiert gegen parallele PUT/exit/Entfernen (Verlierer 409,
    // voller
    // Rollback). Danach Bump und Signal fuer die oben gelesene Menge.
    taskRepository.save(task);

    inboxGenerationService.bumpSorted(tenantId, affected);
    inboxSignalService.invalidate(tenantId, affected);

    log.info(
        "AssigneeService.removeParticipant: Person {} aus Aufgabe {} (id={}) entfernt"
            + " (EXIT_KIND=UNSUBSCRIBED, vorher Assignee={}) von Akteur {} in Mandant {}.",
        participantRef,
        taskExternalId,
        taskId,
        wasAssignee,
        actorRef,
        tenantId);
  }

  /**
   * Ermittelt die Leser, deren Inbox-Sicht durch das Entfernen einer Person ({@link
   * #removeParticipant}) veraltet: genau sie bekommen den Generation-Bump und das Signal nach
   * Commit.
   *
   * <p>Die Menge ist die Vereinigung aus
   *
   * <ul>
   *   <li><b>der entfernten Person</b>: die Aufgabe verlaesst ihre aktive Inbox und erscheint im
   *       Archiv "verlassen". Gelesen wird vor dem Austritt; ob sie dabei als aktiver Leser
   *       erscheint, haengt an ihrem State (fehlt er, fehlt sie). Sie wird deshalb immer
   *       ausdruecklich aufgenommen;
   *   <li><b>allen aktiven Lesern</b> ({@link
   *       TaskUserStateQueryRepository#findActiveUserRefsByTaskId}, States ohne {@code
   *       UNSUBSCRIBED_AT}): die Archivsuche trifft auch den Anzeigenamen aktiver PERSON-Teilnehmer
   *       ({@code TaskKeysetQueryRepository#findArchiveSearchPage}); mit dem Austritt faellt der
   *       Name der entfernten Person aus dem Treffer-Praedikat, und die Treffer jedes Lesers, der
   *       die Aufgabe im Archiv sieht (erledigte oder abgebrochene Aufgabe mit aktivem State),
   *       aendern sich. Ein alter Suchcursor lieferte sonst weiter 200 statt 410. Die
   *       Teilnehmer-Zusammenfassungen aendern sich fuer jeden aktiven Leser ohnehin. Bei einer
   *       offenen Aufgabe sieht ein aktiver Leser sie nicht im Archiv; der Bump kostet dort nur ein
   *       Neuladen und haelt die Menge einheitlich mit {@link #exit} und dem Patch;
   *   <li><b>allen ausgetretenen Archivlesern</b> ({@link
   *       TaskParticipantQueryRepository#findArchiveReadersByTaskId}, aktive PERSON-Zeile mit
   *       {@code EXIT_KIND} oder abgemeldetem State): sie sehen die Aufgabe im Archiv "verlassen"
   *       und in dessen Suche, ihre Treffer aendern sich aus demselben Grund. Sie fehlen in der
   *       State-Lesung (abgemeldet) und bekamen bisher weder Bump noch Signal. Die entfernte Person
   *       ist beim Lesen noch nicht ausgetreten und steht nur ueber die ausdrueckliche Aufnahme in
   *       der Menge.
   * </ul>
   *
   * <p>Nicht betroffen sind Personen ohne aktive PERSON-Zeile (kein READ-Mitglied) und aktive
   * Mitglieder ohne State: sie sehen die Aufgabe weder in der aktiven Inbox noch im Archiv.
   *
   * <p>Die Lesung steht VOR jeder Schreibung dieser Transaktion (Review #145 Runde 2). Da die
   * Transaktion nur die Zeilen der entfernten Person aendert und diese ausdruecklich aufgenommen
   * wird, ergibt die Lesung vor dem Austritt dieselbe Menge wie danach. Eine Lesung nach der
   * eigenen Schreibung wartete bei einer parallelen Entfernung einer anderen Person auf deren
   * X-gesperrte Zeile, waehrend diese auf die eigene wartet (Deadlock 1205). Den sortierten Bump
   * uebernimmt {@link InboxGenerationService#bumpSorted}.
   *
   * @param taskId interne Aufgaben-ID
   * @param removedRef UUID der entfernten Person
   * @return die zu bumpenden und zu signalisierenden Benutzer (nie leer, enthaelt {@code
   *     removedRef})
   */
  private Set<UUID> collectRemovalReaders(final Long taskId, final UUID removedRef) {
    final Set<UUID> readers = collectTaskReaders(taskId);
    readers.add(removedRef);
    return readers;
  }

  /**
   * Alle Leser einer Aufgabe, deren Inbox- oder Archivsicht sich mit einer Mitgliedschafts-
   * aenderung aendert (Intake #145/#166): alle aktiven Leser ({@link
   * TaskUserStateQueryRepository#findActiveUserRefsByTaskId}) und alle ausgetretenen Archivleser
   * ({@link TaskParticipantQueryRepository#findArchiveReadersByTaskId}). Begruendung je Teilmenge
   * bei {@link #collectRemovalReaders}. Genutzt von {@link #updateAssignees}, {@link
   * #addParticipants}, {@link #exit} und {@link #removeParticipant}; die Aufrufer ergaenzen die
   * geaenderten Personen selbst.
   *
   * <p>Dass jede Mitgliedschaftsaenderung ALLE diese Leser bumpt und signalisiert, ist eine
   * bewusste fachliche Entscheidung zu Intake #166, einheitlich mit Austritt, Aendern und
   * Entfernen. Mehrlast: je Aenderung ein Inbox-Neuladen fuer jeden Leser der Aufgabe, auch bei
   * offenen Aufgaben, die kein Leser im Archiv sieht.
   *
   * <p>Muss VOR der ersten Teilnehmer-, State-, Historien- oder Benachrichtigungs-Schreibung der
   * Transaktion laufen (Intake #167): danach wartete die Lesung unter sperrendem READ COMMITTED auf
   * die X-gesperrten Zeilen eines parallelen Aufrufs fuer eine andere Person derselben Aufgabe,
   * waehrend dieser auf die eigenen wartet (Deadlock 1205).
   *
   * @param taskId interne Aufgaben-ID
   * @return veraenderliche Menge der Leser (kann leer sein)
   */
  private Set<UUID> collectTaskReaders(final Long taskId) {
    final Set<UUID> readers = new HashSet<>();
    userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
        .map(TaskUserState::getUserRef)
        .forEach(readers::add);
    participantQueryRepository.findArchiveReadersByTaskId(taskId).stream()
        .map(TaskParticipant::getParticipantRef)
        .forEach(readers::add);
    return readers;
  }

  /**
   * Austrittskern, Teil 1 (Intake #145): setzt {@code EXIT_KIND=UNSUBSCRIBED} und {@code EXIT_AT}
   * und speichert die Teilnehmerzeile. Rollenflags und {@code ENTITY_STATUS} bleiben unberuehrt;
   * die Zeile bleibt ACTIVE (Archivzugriff). Liest nichts.
   *
   * @param participant die aktive, nicht ausgetretene Teilnehmerzeile eines Nicht-Erstellers
   */
  private void saveUnsubscribedParticipant(final TaskParticipant participant) {
    participant.setExitKind(ExitKind.UNSUBSCRIBED);
    participant.setExitAt(LocalDateTime.now());
    participantQueryRepository.save(participant);
  }

  /**
   * Austrittskern, Teil 2 (Intake #145): setzt im aktiven UserState der Person {@code
   * UNSUBSCRIBED_AT} und speichert ihn; fehlt er, bleibt es bei der Teilnehmerzeile. Liest nichts,
   * die State-Zeile kommt vom Aufrufer.
   *
   * @param states Ergebnis von {@link TaskUserStateQueryRepository#findByTaskIdAndUserRef} fuer die
   *     austretende Person (leer, wenn sie keinen State hat)
   */
  private void unsubscribeState(final List<TaskUserState> states) {
    if (!states.isEmpty()) {
      final TaskUserState state = states.get(0);
      state.setUnsubscribedAt(LocalDateTime.now());
      userStateQueryRepository.save(state);
    }
  }

  /**
   * Fuegt Benutzer additiv und idempotent als <b>Teilnehmer</b> (PARTICIPANT_FLAG) zu einer Aufgabe
   * hinzu. Verleiht NIE die Assignee-Rolle (BE-6).
   *
   * <p>Ablauf:
   *
   * <ol>
   *   <li>Aufgabe tenant-scoped laden (404-Masking bei fremder/fehlender UUID).
   *   <li>Eingabe deduplizieren; leere Menge -> No-Op (keine DB-Mutation, kein Signal).
   *   <li>All-or-Nothing: alle verbleibenden Refs muessen aktive Benutzer DES Mandanten sein
   *       ({@code findActiveByUuids}) - sonst 400 VOR jeder Mutation.
   *   <li>Je Ref ({@link #addAsParticipants}, gemeinsamer Kern mit dem Kommentar, Intake #156):
   *       Unique-Key-exakter Lookup (findet auch DEACTIVE-Zeilen): aktive Zeile -> SKIP
   *       (idempotent); ausgetretene oder inaktive (DEACTIVE) PERSON-Zeile -> als REINER Teilnehmer
   *       reaktivieren (ASSIGNEE_FLAG=false, ENTITY_STATUS=ACTIVE); TEAM-Zeile oder fremdes
   *       Basis-TENANT_ID -> 409 ohne Mutation; keine Zeile -> neu anlegen (reiner Teilnehmer).
   *   <li>Vor der ersten Schreibung (Intake #167): die Leser fuer Bump und Signal lesen ({@link
   *       #collectTaskReaders}: aktive Leser und ausgetretene Archivleser).
   *   <li>Nebenlaeufigkeit (D8, Pfad A): gleich-Ref-Race wird in {@link
   *       #saveParticipantHandlingRace} idempotent aufgeloest.
   *   <li>Bei keiner tatsaechlichen Aenderung (alle schon aktiv, Race-Verliererin): Ende ohne
   *       Benachrichtigung, TASK-save, Bump und Signal (204).
   *   <li>PARTICIPANT-Benachrichtigung (Intake #147) an die neu angelegten/reaktivierten Refs, nie
   *       an den Akteur, nie an schon aktive Mitglieder und nie an den Verlierer eines
   *       gleich-Ref-Race (den meldet der Gewinner). Der Typ ist vom Mute ausgenommen ({@code
   *       NotificationMuteFilter}); das Hinzufuegen hebt einen Mute NICHT auf. Der Push folgt nach
   *       Commit ({@link NotificationService#emit}).
   *   <li>Versionierter TASK-save (Intake #167, nur VERSION, LAST_ACTIVITY_AT unveraendert): zwei
   *       gleichzeitige Aufnahmen verschiedener Personen serialisieren wie PUT, Austritt und
   *       Entfernen; die Verliererin bekommt 409 mit vollem Rollback.
   *   <li>Generation-Bump (sortiert) und nach Commit {@link InboxSignalService#invalidate} fuer die
   *       geaenderten Refs und alle vorab gelesenen Leser (Intake #166).
   * </ol>
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input hinzuzufuegende Teilnehmer-UUIDs
   * @throws EntityNotFoundException wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException wenn ein Ref kein aktiver Benutzer des Mandanten ist
   * @throws BusinessRuleException (409) nebenlaeufiger Versionskonflikt der Aufgabe, TEAM-Zeile
   *     oder fremdes Basis-TENANT_ID einer vorhandenen Zeile; die Transaktion rollt vollstaendig
   *     zurueck
   */
  @Transactional
  public void addParticipants(final UUID taskExternalId, final ParticipantAddInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Privilege-Escalation-Schutz: WRITE-Member-Guard GANZ VORNE - direkt nach requireTask (Task-
    // Laden + Tenant/ENTITY_STATUS=ACTIVE), VOR der Dedup-/Zielvalidierung und jeder Mutation
    // (analog
    // exit(), Z. ~619). Ohne diesen Guard koennte ein Akteur mit breit gegrantetem TASK_WRITE, der
    // KEIN Member der Aufgabe ist, sich selbst/andere als Teilnehmer eintragen -> wuerde Member ->
    // Vollzugriff. Fehlende/ausgetretene Membership -> einheitlich neutrale 404 (kein
    // Existenz-Leak).
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    // Deduplizieren und vor den Participant-/UserState-Writes sortieren, damit parallele
    // Mehrfach-Ref-Adds dieselben DB-Zeilen in gleicher Reihenfolge sperren.
    // Leere Menge -> No-Op: kein findActiveByUuids (MSSQL IN () waere ein Syntaxfehler).
    final List<UUID> refs = new ArrayList<>(new LinkedHashSet<>(input.participantIds()));
    refs.sort(UUID::compareTo);
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

    // Intake #166/#167: Leser fuer Bump und Signal (aktive Leser und ausgetretene Archivleser) VOR
    // der ersten Schreibung lesen. Hinter den eigenen Schreibungen wartete die Lesung auf die
    // X-gesperrten Zeilen eines parallelen Aufrufs fuer eine andere Person (Deadlock 1205). Ob das
    // Hinzufuegen etwas aendert, zeigt erst der Kern; die Lesung laeuft deshalb auch bei einem
    // spaeteren No-Op (eine Abfrage mehr, kein Bump, kein Signal).
    final Set<UUID> readers = collectTaskReaders(taskId);

    // Gemeinsamer Aufnahme-Kern (auch von CommentService#createComment genutzt, Intake #156): legt
    // die Teilnehmer- und State-Zeilen an bzw. reaktiviert sie und liefert die tatsaechlich
    // aufgenommenen Refs. Benachrichtigung, TASK-save, Bump und Signal bleiben hier beim Aufrufer.
    // Test-Seams (Intake #167) davor und danach: vor der ersten und nach der letzten
    // Mitgliedschafts-Schreibung (vor Benachrichtigung, TASK-save und Bump). Produktiv No-Op.
    onBeforeMembershipWritesForTest(taskId);
    final List<UUID> affectedRefs = addAsParticipants(taskId, tenantId, refs);
    onAfterMembershipWritesForTest(taskId);

    // D7 changed-Guard: ein reiner No-Op (alle schon aktiv, auch als Verliererin eines
    // gleich-Ref-Race) benachrichtigt nicht, speichert die Aufgabe nicht, bumpt nicht und sendet
    // kein Signal (analog updateAssignees / TaskCommandService.patch).
    if (affectedRefs.isEmpty()) {
      return;
    }

    // Intake #147: PARTICIPANT-Benachrichtigung nur an tatsaechlich neu hinzugefuegte oder aus dem
    // Austritt reaktivierte Personen (affectedRefs: aktive Zeilen und Race-Verlierer sind schon
    // herausgefallen), nie an den Akteur. PARTICIPANT ist vom Mute ausgenommen -> kein
    // NotificationMuteFilter und damit keine zusaetzliche Lesung. Reihenfolge wie
    // removeParticipant: Mitgliedschafts-Writes, Benachrichtigung, TASK-save, Bump, Signal. Der
    // Push folgt erst nach Commit (NotificationService#emit).
    for (final UUID ref : affectedRefs) {
      if (ref.equals(actorRef)) {
        continue;
      }
      notificationService.emit(
          ref,
          taskId,
          NotificationTypeCodes.PARTICIPANT,
          NotificationSeverityCodes.INFO,
          "Du wurdest als Beteiligter zur Aufgabe hinzugefuegt.",
          task.getTitle(),
          actorRef);
    }

    // Intake #167 (Stefan, 2026-11-18): bei echter Aenderung die Aufgabe versioniert speichern (nur
    // VERSION, LAST_ACTIVITY_AT bleibt). Zwei gleichzeitige Aufnahmen verschiedener Personen
    // serialisieren so wie PUT, Austritt und Entfernen: die Verliererin bekommt 409 und rollt
    // vollstaendig zurueck. Die Verliererin eines gleich-Ref-Race aendert nichts (oben return) und
    // bleibt 204.
    taskRepository.save(task);

    // Intake #166: Bump und Signal fuer die geaenderten Personen und alle vorab gelesenen Leser
    // (dieselbe Menge wie beim Entfernen), sortiert ueber den zentralen Helfer NACH dem TASK-save
    // (Codex #6: einheitliche Save-dann-Bump-Ordnung). Das ist eine bewusste fachliche
    // Entscheidung zu Intake #166, einheitlich mit Austritt, Aendern und Entfernen. Mehrlast: je
    // Aenderung ein Inbox-Neuladen fuer jeden Leser der Aufgabe, auch bei offenen Aufgaben.
    final Set<UUID> readersAndChanged = new HashSet<>(readers);
    readersAndChanged.addAll(affectedRefs);
    inboxGenerationService.bumpSorted(tenantId, readersAndChanged);
    inboxSignalService.invalidate(tenantId, readersAndChanged);
  }

  /**
   * Gemeinsamer Kern des Hinzufuegens als Beteiligter (Intake #156): legt je Ref einen reinen
   * Teilnehmer samt persoenlichem State an bzw. reaktiviert einen ausgetretenen als reinen
   * Teilnehmer (nie Assignee, ein Mute bleibt, {@link UserStateService#reactivate}) und behandelt
   * den gleich-Ref-Race idempotent ({@link #saveParticipantHandlingRace}).
   *
   * <p>Schreibt AUSSCHLIESSLICH die Zeilen {@code TASK_PARTICIPANT} und {@code TASK_USER_STATE}:
   * keine Benachrichtigung, kein Generation-Bump, kein Signal, kein TASK-Save. Das ist Sache des
   * Aufrufers, weil dessen Lock-Reihenfolge die Stelle vorgibt: zuerst alle Mitgliedschafts-Reads
   * und -Writes, dann das Speichern der Aufgabe, dann der sortierte Bump (Karten {@code
   * participants-actions} und {@code inbox-snapshot}). {@link #addParticipants} meldet danach
   * PARTICIPANT, {@code CommentService#createComment} die MENTION.
   *
   * <p>Die Refs muessen vom Aufrufer als aktive Benutzer DES Mandanten geprueft sein. Sie werden
   * dedupliziert und nach UUID sortiert verarbeitet, damit parallele Aufrufe mit mehreren Refs
   * dieselben Zeilen in gleicher Reihenfolge sperren. Muss in der Transaktion des Aufrufers laufen.
   *
   * @param taskId interne Aufgaben-ID
   * @param tenantId interner Mandanten-Schluessel
   * @param refs aufzunehmende Benutzer-UUIDs (beliebige Reihenfolge, Duplikate erlaubt)
   * @return die tatsaechlich neu angelegten oder reaktivierten Refs in aufsteigender UUID-
   *     Reihenfolge; aktive Mitglieder und Race-Verlierer (den meldet der Gewinner) fehlen
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> addAsParticipants(
      final Long taskId, final long tenantId, final Collection<UUID> refs) {
    final List<UUID> sortedRefs = new ArrayList<>(new LinkedHashSet<>(refs));
    sortedRefs.sort(UUID::compareTo);
    final List<UUID> affectedRefs = new ArrayList<>();
    for (final UUID ref : sortedRefs) {
      if (addSingleParticipant(taskId, ref, tenantId)) {
        affectedRefs.add(ref);
      }
      // Test-Seam (Codex-Review #167): nach der Aufnahme je Person, vor der naechsten. Produktiv
      // No-Op.
      onAfterParticipantAddForTest(taskId, ref);
    }
    return affectedRefs;
  }

  /**
   * Darf der aktuelle Akteur Personen als Beteiligte hinzufuegen? Dieselbe Pruefung wie am Endpunkt
   * {@code POST /api/v1/tasks/{id}/participants} ({@code @RequiresPermission(TASK_WRITE)} am
   * Controller): die exakte Authority {@code task-management:TASK_WRITE}. Die Mitgliedschaft des
   * Akteurs prueft der Aufrufer selbst ({@link TaskMembershipGuard#requireWriteMember}).
   *
   * <p>Genutzt vom Kommentar mit Erwaehnung (Intake #156) und von der Uebergabe an Nicht-Mitglieder
   * ({@link #exit}), die effektiv ebenfalls jemanden hinzufuegt.
   *
   * @return {@code true}, wenn der Akteur die Berechtigung hat
   */
  public boolean mayAddParticipants() {
    return permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE);
  }

  /**
   * Fuegt EINEN Teilnehmer-Ref hinzu bzw. reaktiviert ihn (reine Teilnehmer-Rolle).
   *
   * <p>Der Lookup ist Unique-Key-exakt wie in {@link #ensureAssignee} ({@link
   * TaskParticipantQueryRepository#findRowByUniqueKey}, ohne {@code ENTITY_STATUS}-/{@code
   * EXIT_KIND}-/{@code PARTICIPANT_KIND}-Filter). Der lose Lookup {@code
   * findByTaskIdAndParticipantRef} liest nur {@code ENTITY_STATUS='ACTIVE'}: eine vorhandene {@code
   * DEACTIVE}-PERSON-Zeile bliebe unsichtbar, der Insert braeche den V3-Unique-Key und der ganze
   * Aufruf (auch der Kommentar mit Erwaehnung) endete mit 409.
   *
   * @return {@code true}, wenn tatsaechlich angelegt/reaktiviert wurde (Generation-Bump faellig);
   *     {@code false} bei idempotentem SKIP (bereits aktiv - sequentiell ODER als Race-Verlierer)
   * @throws BusinessRuleException 409 fail-closed ohne Mutation, wenn die Unique-Key-Zeile eine
   *     TEAM-Zeile ist oder ein fremdes Basis-{@code TENANT_ID} traegt (wie {@link
   *     #ensureAssignee}); ein PERSON-Insert waere per Unique-Key unmoeglich
   */
  private boolean addSingleParticipant(final Long taskId, final UUID ref, final long tenantId) {
    final Optional<TaskParticipant> existing =
        findPersonRowByUniqueKey(
            taskId,
            ref,
            tenantId,
            "Die Person kann nicht als Beteiligter gefuehrt werden: die vorhandene"
                + " Teilnehmerzeile ist kein PERSON-Mitglied des Task-Mandanten.");
    if (existing.isPresent()) {
      final TaskParticipant p = existing.get();
      if (p.getExitKind() == null && p.getEntityState() == EntityState.ACTIVE) {
        // (a) aktive Zeile (Creator / Assignee / Teilnehmer) -> idempotenter SKIP, keine Mutation.
        return false;
      }
      // (b) ausgetretene ODER inaktive (DEACTIVE) PERSON-Zeile -> als REINEN Teilnehmer
      // reaktivieren (wie die Uebergabe: ENTITY_STATUS wieder ACTIVE). ASSIGNEE_FLAG MUSS false
      // werden (D4-BLOCKER: ein abgewaehlter Ex-Assignee behaelt sonst ASSIGNEE_FLAG=1 und wuerde
      // durch die Reaktivierung faelschlich wieder aktiver Assignee - findActiveAssigneesByTaskId
      // filtert ASSIGNEE_FLAG=1 AND EXIT_KIND IS NULL). Add-Participants verleiht NIE Assignee.
      p.setParticipantFlag(true);
      p.setAssigneeFlag(false);
      p.setExitKind(null);
      p.setExitAt(null);
      p.setEntityState(EntityState.ACTIVE);
      if (!saveParticipantHandlingRace(p, taskId, ref)) {
        return false; // Race-Verlierer: Gewinner-Zustand bereits aktiv -> idempotent.
      }
      // Reiner Teilnehmer, keine Zuweisung: bewusst reactivate (nicht reactivateAsAssignee), ein
      // vorhandener Mute dieser Aufgabe bleibt bestehen.
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
    final TaskParticipant p = newPersonRow(taskId, ref, tenantId, false);
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
   * VERSION-Konflikt als {@link OptimisticLockingFailureException}. Eine unverwandte {@link
   * BusinessRuleException} (andere/fehlende Ursache) wird NIE als Erfolg geschluckt, auch nicht bei
   * zufaellig passendem Re-Read. Bestaetigt der Re-Read den vollen aktiven Gewinner-Zustand (aktive
   * Zeile, PARTICIPANT_FLAG=1, EXIT_KIND null), wird der Konflikt idempotent als SKIP behandelt;
   * sonst wird die urspruengliche Exception weitergeworfen.
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
      // Test-Seam: Catch-Zweig-Zaehlung + Committability-Sonde (XACT_STATE / @@OPTIONS) auf
      // DERSELBEN
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
   * Test-Seam (Default No-Op): Haken in {@link #updateAssignees} VOR dem ersten Zeilen-Zugriff je
   * neu zugewiesener Person. Ermoeglicht dem Nebenlaeufigkeits-IT (Intake #156), updateAssignees
   * und die Aufnahme per Erwaehnung vor ihrem ersten Write zu synchronisieren und so die gleiche
   * Sperr-Reihenfolge (UUID-aufsteigend) zu beweisen. Im Produktivbetrieb ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref UUID der gleich zugewiesenen Person
   */
  protected void onBeforeAssigneeAddForTest(final Long taskId, final UUID ref) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): Haken ZWISCHEN dem Vorab-Read und dem Participant-save in {@link
   * #addSingleParticipant}/{@link #saveParticipantHandlingRace}. Ermoeglicht deterministische
   * Nebenlaeufigkeits-ITs (D8): beide Threads werden nachweislich VOR dem ersten Write
   * synchronisiert, sodass der gleich-Ref-Race den Konfliktzweig garantiert erreicht. Im
   * Produktivbetrieb ein No-Op; nur Test-Subklassen ueberschreiben diesen Haken.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref Teilnehmer-UUID
   */
  protected void onBeforeParticipantSaveForTest(final Long taskId, final UUID ref) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): wird im Konfliktzweig von {@link #saveParticipantHandlingRace} NACH
   * der gefangenen (recoverable) {@link BusinessRuleException} und VOR dem Re-Read aufgerufen, auf
   * DERSELBEN Transaktionsverbindung. Ermoeglicht den Nebenlaeufigkeits-ITs die direkte
   * Catch-Zweig-Zaehlung (D8- Auflage 1) und die Committability-Sonde (XACT_STATE()/@@OPTIONS,
   * D8-Auflage 2). Produktiv ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref Teilnehmer-UUID
   */
  protected void onParticipantConflictCaughtForTest(final Long taskId, final UUID ref) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Aktueller Akteur verlaesst die Aufgabe bzw. uebergibt sie (BE-7, SPEC v3 Abschnitt 4).
   *
   * <p>Zwei Austrittsarten (Input {@link ExitInput#exitKind()}):
   *
   * <ul>
   *   <li><b>UNSUBSCRIBED:</b> der Akteur meldet sich ab. {@code handedOverToIds} muss leer und
   *       {@code shouldStay} false sein (sonst 400). Nicht-Creator -> EXIT_KIND=UNSUBSCRIBED;
   *       Creator -> 409.
   *   <li><b>HANDOVER:</b> der Akteur uebergibt die Aufgabe an {@code handedOverToIds} (werden
   *       Assignees; der Mute dieser Aufgabe wird nur bei Zielen aufgehoben, die vorher kein
   *       aktiver Assignee waren, siehe {@link UserStateService#reactivateAsAssignee}). Optionaler
   *       Verbleib ({@code shouldStay}) laesst den Akteur reiner Teilnehmer. Der Akteur wird IMMER
   *       de-assignt (ASSIGNEE_FLAG=false). Nicht-Creator + !shouldStay -> EXIT_KIND=HANDOVER +
   *       Austritt; Creator -> nur shouldStay=true erlaubt (sonst 409), bleibt dann immobiler
   *       Teilnehmer (CREATOR+PARTICIPANT + aktiver UserState, ASSIGNEE_FLAG=false).
   * </ul>
   *
   * <p>Alles in EINER Transaktion: TASK_HANDOVER-Ereignis + Ziele + Rollenwechsel + History +
   * LAST_ACTIVITY_AT + {@code taskRepository.save} (VERSION-Guard) + Notification-Persistenz;
   * Signale (Push/Invalidate) strikt after-commit. Der VERSION-Guard macht die Aufgabe zum
   * gemeinsamen Serialisierungspunkt gegen nebenlaeufige Membership-Mutationen (PUT/exit); der
   * Verlierer bricht mit {@link BusinessRuleException} (409) ab und committet NICHTS.
   *
   * <p><b>Inbox-Generation-Bump (Finding 2 / Option B, Codex #6):</b> Die Generation wird fuer die
   * VOLLSTAENDIGE betroffene Menge ATOMAR in dieser Tx gebumpt: Akteur (nur bei tatsaechlichem
   * Austritt), alle Handover-Ziele UND ALLE nach dem Exit/Handover verbleibenden aktiven Teilnehmer
   * der Aufgabe - nicht nur Akteur + Ziele. Der LAST_ACTIVITY_AT-Bump verschiebt die aktive
   * Keyset-Position ({@code (asOf, LAST_ACTIVITY_AT, externalId)}) jedes verbleibenden Teilnehmers;
   * ohne Bump lieferte dessen alter aktiver Cursor stale 200 (Skip/Dupe) statt 410.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input Austritts-Eingabe (exitKind, handedOverToIds, shouldStay)
   * @throws EntityNotFoundException wenn Aufgabe nicht gefunden oder fremdem Mandant zugehoerig
   * @throws EntityValidationException (400) bei ungueltiger Eingabe oder wenn der Akteur kein
   *     aktiver Teilnehmer der Aufgabe ist
   * @throws BusinessRuleException (409) Creator-Austritt ohne Verbleib / nebenlaeufiger
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
    // Task-Laden + Tenant/ENTITY_STATUS=ACTIVE-Check (requireTask), VOR jeder
    // Eingabe-/Zielvalidierung.
    // Fehlende/ausgetretene Membership -> einheitlich neutrale 404 (nicht 400, kein Ziel-Leak ueber
    // die
    // nachgelagerte findActiveByUuids-Zielvalidierung). Der bestehende Service-interne Akteur-Check
    // (unten, Z. ~670) bleibt als Defense-in-Depth erhalten, wird aber vom Guard vorweggenommen.
    // Das
    // Gate am Controller ist entfernt (authenticated-only); anonym bleibt 401 ueber die
    // KSF-Filter-Chain.
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    // Test-Seam: deterministische Synchronisation der Versions-Verlierer-ITs (BE-7 IT7) GENAU NACH
    // dem Lesen der Aufgabe (VERSION erfasst) und VOR jeder Mutation - zu diesem Zeitpunkt haelt
    // der
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

    // Deduplizierte Zielmenge (stabile Reihenfolge).
    final Set<UUID> dedupedTargets = new LinkedHashSet<>(rawTargets);
    // (2b) Handover-Autz-Grenze (Standard-Selfservice, Slice 4, Auflage 2 - Eskalationsschutz):
    // HANDOVER fuehrt via ensureAssignee neue Assignees ein bzw. reaktiviert sie (= effektiv
    // "add participant"). Da addParticipants/updateAssignees TASK_WRITE-gated BLEIBEN, ist ein
    // grant-freier HANDOVER NUR dann zulaessig, wenn JEDES deduplizierte Ziel bereits VOLLER
    // WRITE-Member der Aufgabe ist (reiner Rollenwechsel innerhalb bestehender aktiver Mitglieder).
    // Fuehrt ODER reaktiviert der HANDOVER mindestens EIN Ziel, das KEIN WRITE-Member ist (neu,
    // ausgetreten, inaktiv, TEAM, fremder PARTICIPANT_TENANT_ID), erfordert er exakte TASK_WRITE-
    // Authority; fehlt sie dem (bestaetigten) Akteur -> 403, KEINE Mutation (All-or-Nothing ueber
    // die
    // GESAMTE deduplizierte Zielmenge). Dieser bedingte Grant-Check laeuft NACH dem
    // Akteur-WRITE-Guard
    // (oben, requireWriteMember) und VOR Zielnutzeraufloesung und jeder Mutation (403 statt 404 ist
    // korrekt: der Akteur ist bestaetigtes Mitglied, kein Existenz-Leak). UNSUBSCRIBED hat keine
    // Ziele
    // -> immer grant-frei.
    if (handover && !dedupedTargets.isEmpty()) {
      final boolean allTargetsAreWriteMembers =
          dedupedTargets.stream()
              .allMatch(targetRef -> membershipGuard.isWriteMember(taskId, targetRef, tenantId));
      if (!allTargetsAreWriteMembers && !mayAddParticipants()) {
        throw new PermissionDeniedException(
            TaskManagementPermissions.TASK_WRITE,
            "Die Uebergabe an ein noch nicht als WRITE-Mitglied gefuehrtes Ziel erfordert"
                + " TASK_WRITE.");
      }
    }

    // Alle Ziele muessen aktive Benutzer DES Mandanten sein (All-or-Nothing 400, keine Mutation bei
    // Verstoss). Erst nach der Autorisierung aufloesen, damit grant-freie Member keine Existenz von
    // Nicht-WRITE-Zielen ueber 400/403 unterscheiden koennen.
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

    // Intake #167: alle Reads VOR der ersten Schreibung (Lock-Reihenfolge wie removeParticipant).
    // Eine Lesung nach den eigenen Schreibungen wartet unter sperrendem READ COMMITTED auf die
    // X-gesperrten Zeilen eines parallelen Austritts, PUT oder Hinzufuegens fuer eine andere
    // Person derselben Aufgabe und verklemmt mit ihm (Deadlock 1205), bevor der
    // TASK-Versionskonflikt (409) greift.
    // (a) Uebergabeziele: Zeile Unique-Key-exakt (TEAM-Zeile oder fremdes Basis-TENANT_ID -> 409
    // ohne Mutation) und State, nach UUID sortiert wie die Schreibungen.
    final List<UUID> sortedTargets = dedupedTargets.stream().sorted().toList();
    final Map<UUID, Optional<TaskParticipant>> targetRows = new HashMap<>();
    final Map<UUID, List<TaskUserState>> targetStates = new HashMap<>();
    for (final UUID targetRef : sortedTargets) {
      targetRows.put(
          targetRef,
          findPersonRowByUniqueKey(
              taskId,
              targetRef,
              tenantId,
              "Das Uebergabeziel kann nicht als Assignee gefuehrt werden: die vorhandene"
                  + " Teilnehmerzeile ist kein PERSON-Mitglied des Task-Mandanten."));
      targetStates.put(
          targetRef, userStateQueryRepository.findByTaskIdAndUserRef(taskId, targetRef));
    }
    // (b) State des Akteurs, wenn er austritt (Nicht-Creator ohne Verbleib).
    final boolean actorExits = !isCreator && !shouldStay;
    final List<TaskUserState> actorStates =
        actorExits ? userStateQueryRepository.findByTaskIdAndUserRef(taskId, actorRef) : List.of();
    // (c) Leser fuer Bump und Signal: alle aktiven Leser und alle ausgetretenen Archivleser
    // (Intake #145/#166). Vor dem Austritt gelesen ist der Akteur noch aktiver Leser und wird
    // unten bei actorExits ohnehin aufgenommen; die Ziele ebenso. Die Menge ist dieselbe wie bei
    // einer Lesung nach den eigenen Schreibungen.
    final Set<UUID> readerRefs = collectTaskReaders(taskId);
    // (d) Namen-Snapshot fuer die Historie (Benutzerstamm), wie beim Entfernen vor der ersten
    // Schreibung.
    final Map<UUID, String> historyNames = resolveNames(dedupedTargets, actorRef);

    // Test-Seam (Intake #167): nach den Vorab-Reads, vor der ersten Schreibung (Handover-Ereignis
    // bzw. Teilnehmerzeile des Akteurs). Produktiv No-Op.
    onBeforeMembershipWritesForTest(taskId);

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

      // Intake #156: die Ziele schreiben Teilnehmer- und State-Zeilen wie updateAssignees und die
      // Aufnahme per Erwaehnung und werden daher nach UUID sortiert verarbeitet (gleiche
      // Sperr-Reihenfolge ueber alle Pfade). dedupedTargets bleibt in Eingabereihenfolge
      // (Historie).
      for (final UUID targetRef : sortedTargets) {
        final TaskHandoverTarget targetRow = new TaskHandoverTarget();
        targetRow.setTenantId(tenantId);
        targetRow.setHandoverId(handoverId);
        targetRow.setToRef(targetRef);
        handoverTargetRepository.save(targetRow);

        final boolean newlyAssigned =
            ensureAssignee(taskId, targetRef, tenantId, targetRows.get(targetRef));
        if (newlyAssigned) {
          // Das Ziel war kein aktiver Assignee und wird es jetzt: wie bei einer neuen Zuweisung per
          // PUT hebt das den Mute dieser Aufgabe fuer das Ziel auf (Folge von #158).
          userStateService.reactivateAsAssignee(
              taskId, targetRef, tenantId, targetStates.get(targetRef));
        } else {
          // Das Ziel war bereits aktiver Assignee: keine neue Zuweisung, der Mute bleibt bestehen.
          userStateService.reactivate(taskId, targetRef, tenantId, targetStates.get(targetRef));
        }
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

    // (6) Akteur-Rollenwechsel. ASSIGNEE_FLAG=false IMMER (auch Creator). Nicht-Creator +
    // !shouldStay
    // (bei HANDOVER) ODER UNSUBSCRIBED -> echter Austritt (EXIT_KIND + EXIT_AT + UNSUBSCRIBED_AT).
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

    if (actorExits && !actorStates.isEmpty()) {
      final TaskUserState state = actorStates.get(0);
      state.setUnsubscribedAt(now);
      userStateQueryRepository.save(state);
    }

    // Test-Seam (Intake #167): nach den eigenen Teilnehmer- und State-Schreibungen, vor Historie,
    // TASK-save und Bump. Produktiv No-Op.
    onAfterMembershipWritesForTest(taskId);

    // (7) History (Namen-Snapshot): "<AkteurName> -> <Ziel-Namen>".
    recordHandoverHistory(
        taskId, tenantId, actorRef, dedupedTargets, handover, shouldStay, historyNames);

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
          shouldStay
              ? "Du hast die Aufgabe uebergeben."
              : "Du hast die Aufgabe uebergeben und verlassen.",
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
    affected.addAll(readerRefs);

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
    // save), sodass ueberall zuerst die TASK-Zeile und dann die TASK_INBOX_GENERATION-Zeilen
    // gesperrt
    // werden und parallele Membership-Mutationen mit ueberlappenden Teilnehmern die
    // Generation-Zeilen
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
   * get(0)}):</b> der Aufrufer liest vorab ueber {@link
   * TaskParticipantQueryRepository#findRowByUniqueKey} auf den V3-Unique-Key {@code (TASK_ID,
   * PARTICIPANT_TENANT_ID=task-tenant, PARTICIPANT_REF)} - per Index genau {@code <=1} Zeile - OHNE
   * {@code ENTITY_STATUS}-/{@code EXIT_KIND}-/{@code PARTICIPANT_KIND}-Filter (sonst wuerde eine zu
   * reaktivierende inaktive/ausgetretene Zeile verfehlt). Die {@code <=1} Zeile wird dann
   * klassifiziert:
   *
   * <ul>
   *   <li><b>vorhanden, {@code PARTICIPANT_KIND='PERSON'} UND Basis-{@code
   *       TENANT_ID}=task-tenant</b> (Status/Exit egal: aktiv/exited/inaktiv) -> VOLL reaktivieren:
   *       {@code ASSIGNEE_FLAG= PARTICIPANT_FLAG=1}, {@code EXIT_KIND/EXIT_AT=null}, {@code
   *       ENTITY_STATUS='ACTIVE'} (auch eine inaktive/exited PERSON wird voll auf aktiv gehoben -
   *       kein 204 ohne aktiven Assignee).
   *   <li><b>vorhanden, aber {@code PARTICIPANT_KIND='TEAM'} ODER Basis-{@code TENANT_ID} !=
   *       task-tenant</b> -> <b>409, KEINE Mutation</b> ({@link BusinessRuleException},
   *       fail-closed; ein PERSON-Insert waere per Unique-Key unmoeglich, stilles Flippen wuerde
   *       ungueltige Membership hinterlassen).
   *   <li><b>keine Zeile</b> (Unique-Key frei) -> NEUE PERSON-Zeile mit {@code TENANT_ID =
   *       PARTICIPANT_TENANT_ID = task-tenant}, {@code ENTITY_STATUS='ACTIVE'}, Assignee INSERT.
   * </ul>
   *
   * <p><b>Konflikt-Recheck (volle Invariante):</b> bei gleich-Ref-Race ({@link
   * BusinessRuleException} aus {@code save}) ist ein idempotenter SKIP nur dann zulaessig, wenn der
   * Re-Read die VOLLE Membership-Invariante bestaetigt ({@code PARTICIPANT_KIND='PERSON' AND
   * TENANT_ID=task-tenant AND PARTICIPANT_TENANT_ID=task-tenant AND ASSIGNEE_FLAG=1 AND EXIT_KIND
   * IS NULL AND ENTITY_STATUS='ACTIVE'}); sonst Konflikt weiterwerfen -> voller Rollback (kein 204
   * ohne gueltigen aktiven Assignee).
   *
   * @param taskId interne Aufgaben-ID
   * @param targetRef UUID des Uebergabeziels
   * @param tenantId Task-Mandant (= PARTICIPANT_TENANT_ID der zu haltenden/neu anzulegenden Zeile)
   * @param existing vom Aufrufer vor der ersten Schreibung gelesene Zeile ({@link
   *     #findPersonRowByUniqueKey}, leer bei freiem Unique-Key; Intake #167)
   * @return {@code true}, wenn das Ziel vorher KEIN aktiver Assignee war und es durch diesen Aufruf
   *     (neu angelegt oder reaktiviert) wird, also eine neue Zuweisung vorliegt; {@code false},
   *     wenn es bereits aktiver Assignee war (aktiv = {@code ASSIGNEE_FLAG=1}, {@code EXIT_KIND}
   *     null, {@code ENTITY_STATUS='ACTIVE'}, wie {@code findActiveAssigneesByTaskId})
   * @throws BusinessRuleException 409 fail-closed, wenn die Unique-Key-Zeile TEAM ist oder ein
   *     fremdes Basis-{@code TENANT_ID} traegt (Laufzeit-Vertrag, keine Mutation); ODER bei einem
   *     nebenlaeufigen Konflikt, dessen Re-Read die volle Invariante NICHT bestaetigt
   */
  private boolean ensureAssignee(
      final Long taskId,
      final UUID targetRef,
      final long tenantId,
      final Optional<TaskParticipant> existing) {
    // Die Zeile hat der Aufrufer VORAB Unique-Key-exakt gelesen ({@link #findPersonRowByUniqueKey},
    // ohne Status/EXIT/KIND-Filter, Intake #167: vor der ersten Schreibung): <=1 Zeile unabhaengig
    // vom Basis-TENANT_ID; TEAM-Zeile ODER fremdes Basis-TENANT_ID ist dort schon 409 ohne
    // Mutation (ensureAssignee-Auswahlvertrag). Die Klassifikation (reaktivieren vs. INSERT)
    // erfolgt hier.
    final TaskParticipant p;
    // War das Ziel vor diesem Aufruf schon aktiver Assignee? (Zustand VOR der Mutation)
    boolean wasActiveAssignee = false;
    if (existing.isPresent()) {
      final TaskParticipant row = existing.get();
      // PERSON + Basis-TENANT_ID=task-tenant (aktiv/exited/inaktiv egal): VOLL reaktivieren.
      wasActiveAssignee =
          row.isAssigneeFlag()
              && row.getExitKind() == null
              && row.getEntityState() == EntityState.ACTIVE;
      p = row;
      activateAsAssignee(p);
    } else {
      // Unique-Key frei -> neue PERSON-Assignee-Zeile (TENANT_ID = PARTICIPANT_TENANT_ID =
      // task-tenant).
      p = newPersonRow(taskId, targetRef, tenantId, true);
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
        return !wasActiveAssignee;
      }
      // z.B. paralleles reines-Teilnehmer-Add (ASSIGNEE_FLAG=false) oder Anomalie-Zeile -> KEIN
      // akzeptabler Zustand fuer ein Handover-Ziel: Konflikt weiterwerfen -> voller Rollback.
      throw conflict;
    }
    return !wasActiveAssignee;
  }

  /**
   * Gemeinsamer Zeilen-Lookup aller Pfade, die eine Person zur Aufgabe aufnehmen oder ihr die
   * Assignee-Rolle geben ({@link #updateAssignees}, {@link #addSingleParticipant} und {@link
   * #ensureAssignee}; Intake #156 und #163).
   *
   * <p>Sucht Unique-Key-exakt ueber {@link TaskParticipantQueryRepository#findRowByUniqueKey}
   * ({@code TASK_ID, PARTICIPANT_TENANT_ID = Task-Mandant, PARTICIPANT_REF}, ohne {@code
   * ENTITY_STATUS}-, {@code EXIT_KIND}- und {@code PARTICIPANT_KIND}-Filter), damit auch eine
   * ausgetretene oder {@code DEACTIVE}-Zeile gefunden wird. Der lose Lookup {@code
   * findByTaskIdAndParticipantRef} liest nur {@code ENTITY_STATUS='ACTIVE'}; mit ihm bliebe eine
   * {@code DEACTIVE}-Zeile unsichtbar, der folgende Insert braeche den V3-Unique-Key und der ganze
   * Aufruf endete mit 409.
   *
   * <p>Eine gefundene Zeile, die keine PERSON-Zeile des Task-Mandanten ist (TEAM-Zeile oder fremdes
   * Basis-{@code TENANT_ID}), wird nicht angefasst: der Aufruf scheitert fail-closed mit 409, denn
   * ein PERSON-Insert waere per Unique-Key unmoeglich und ein stilles Umschreiben hinterliesse eine
   * ungueltige Mitgliedschaft. Die Klassifikation der gueltigen Zeile (aktiv, ausgetreten, inaktiv)
   * und die Folgen bleiben beim Aufrufer.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref UUID der Person
   * @param tenantId Task-Mandant
   * @param rejectionMessage Meldung der 409, wenn die vorhandene Zeile keine PERSON-Zeile des
   *     Task-Mandanten ist
   * @return die vorhandene PERSON-Zeile des Task-Mandanten (Status und Austritt beliebig) oder
   *     leer, wenn der Unique-Key frei ist
   * @throws BusinessRuleException 409 ohne Mutation bei TEAM-Zeile oder fremdem Basis-{@code
   *     TENANT_ID}
   */
  private Optional<TaskParticipant> findPersonRowByUniqueKey(
      final Long taskId, final UUID ref, final long tenantId, final String rejectionMessage) {
    final List<TaskParticipant> rows = participantQueryRepository.findRowByUniqueKey(taskId, ref);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    final TaskParticipant row = rows.get(0);
    if (row.getParticipantKind() != ParticipantKind.PERSON
        || !Objects.equals(tenantId, row.getTenantId())) {
      throw new BusinessRuleException(rejectionMessage);
    }
    return Optional.of(row);
  }

  /**
   * Hebt eine vorhandene PERSON-Zeile voll auf aktiven Assignee: {@code ASSIGNEE_FLAG} und {@code
   * PARTICIPANT_FLAG} gesetzt, Austritt geloescht, {@code ENTITY_STATUS='ACTIVE'}. Speichert nicht;
   * genutzt von {@link #updateAssignees} und {@link #ensureAssignee}.
   *
   * @param p die zu reaktivierende Teilnehmerzeile
   */
  private static void activateAsAssignee(final TaskParticipant p) {
    p.setAssigneeFlag(true);
    p.setParticipantFlag(true);
    p.setExitKind(null);
    p.setExitAt(null);
    p.setEntityState(EntityState.ACTIVE);
  }

  /**
   * Neue PERSON-Teilnehmerzeile im Task-Mandanten ({@code TENANT_ID = PARTICIPANT_TENANT_ID}), nie
   * Ersteller, immer Teilnehmer; Assignee nach {@code assignee}. Speichert nicht.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref UUID der Person
   * @param tenantId Task-Mandant
   * @param assignee {@code true} fuer eine Zuweisung, {@code false} fuer einen reinen Teilnehmer
   * @return die neue, ungespeicherte Zeile
   */
  private static TaskParticipant newPersonRow(
      final Long taskId, final UUID ref, final long tenantId, final boolean assignee) {
    final TaskParticipant p = new TaskParticipant();
    p.setTenantId(tenantId);
    p.setTaskId(taskId);
    p.setParticipantRef(ref);
    p.setParticipantKind(ParticipantKind.PERSON);
    p.setParticipantTenantId(tenantId);
    p.setCreatorFlag(false);
    p.setAssigneeFlag(assignee);
    p.setParticipantFlag(true);
    return p;
  }

  /**
   * Test-Seam (Default No-Op): Haken ZWISCHEN Vorab-Read und save in {@link #ensureAssignee}.
   * Ermoeglicht deterministische Ziel-Race-ITs (BE-7 IT6). Produktiv ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   * @param targetRef UUID des Uebergabeziels
   */
  protected void onBeforeHandoverAssigneeSaveForTest(final Long taskId, final UUID targetRef) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): Haken am ANFANG von {@link #exit} - direkt nach dem Laden der
   * Aufgabe (VERSION erfasst) und VOR jeder Mutation. Ermoeglicht deterministische
   * Versions-Verlierer-ITs (BE-7 IT7): ein nebenlaeufiger Gewinner (Exit/PUT/Handover) committet,
   * waehrend der Verlierer hier pausiert und noch KEINE Schreibsperren haelt (der {@code
   * requireTask}-SELECT gibt seine Shared-Locks statement-weise frei) - so entsteht KEIN
   * Wartezyklus/Deadlock. Nach der Fortsetzung trifft der Verlierer beim versionierten {@code
   * taskRepository.save} die stale VERSION -> 409 + voller Rollback. Produktiv ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   */
  protected void onBeforeExitMutationForTest(final Long taskId) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op): Haken in {@link #removeParticipant} NACH allen Reads (Akteur, Ziel,
   * State der entfernten Person, Namen, Leser fuer Bump und Signal) und VOR der ersten Schreibung.
   * Die Transaktion haelt hier keine Schreibsperren. Ermoeglicht dem Nebenlaeufigkeits-IT (Review
   * #145 Runde 2), zwei Entfernungen verschiedener Personen derselben Aufgabe an diesem Punkt zu
   * synchronisieren und so zu belegen, dass nach der ersten Schreibung keine Lesung mehr auf die
   * Zeilen der anderen wartet. Im Produktivbetrieb ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   * @param participantRef UUID der zu entfernenden Person
   */
  protected void onBeforeRemoveMutationForTest(final Long taskId, final UUID participantRef) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op, Intake #167): Haken in {@link #updateAssignees}, {@link
   * #addParticipants} und {@link #exit} NACH den Vorab-Reads und VOR der ersten Schreibung
   * (Teilnehmer, State, Handover-Ereignis). Nach der Lock-Reihenfolge der Karte {@code
   * participants-actions} stehen hier alle Reads hinter dem Akteur, auch die Leser fuer Bump und
   * Signal; die Transaktion haelt noch keine Schreibsperren. Der Nebenlaeufigkeits-IT ({@code
   * MembershipConcurrencyIT}) synchronisiert zwei Aufrufe fuer verschiedene Personen derselben
   * Aufgabe an diesem Punkt (Sync 1). Im Produktivbetrieb ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   */
  protected void onBeforeMembershipWritesForTest(final Long taskId) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op, Intake #167): Haken in {@link #updateAssignees}, {@link
   * #addParticipants} und {@link #exit} NACH den eigenen Teilnehmer- und State-Schreibungen und VOR
   * Historie, Benachrichtigung, TASK-save und Generation-Bump. Die Transaktion haelt hier ihre
   * X-Sperren auf den eigenen Mitgliedschafts-Zeilen. Der Nebenlaeufigkeits-IT ({@code
   * MembershipConcurrencyIT}) laesst an diesem Punkt (Sync 2) beide Transaktionen ihre Sperren
   * gleichzeitig halten; danach darf keine Lesung mehr auf die Zeilen der anderen warten, sonst
   * verklemmen beide (Deadlock 1205). Im Produktivbetrieb ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   */
  protected void onAfterMembershipWritesForTest(final Long taskId) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Test-Seam (Default No-Op, Codex-Review #167): Haken in {@link #addAsParticipants} NACH der
   * Aufnahme einer Person und VOR den Reads der naechsten (auch nach der letzten). Die Transaktion
   * haelt hier die X-Sperren der bisher aufgenommenen Personen. Der Nebenlaeufigkeits-IT ({@code
   * MembershipConcurrencyIT}) verzahnt daran zwei Aufnahmen mit je zwei Personen: beide haben ihre
   * erste Person geschrieben, bevor eine die Zeilen ihrer zweiten liest. Gilt auch fuer die
   * Aufnahme per Erwaehnung ({@code CommentService#createComment}). Im Produktivbetrieb ein No-Op.
   *
   * @param taskId interne Aufgaben-ID
   * @param ref UUID der gerade verarbeiteten Person
   */
  protected void onAfterParticipantAddForTest(final Long taskId, final UUID ref) {
    // No-Op im Produktivbetrieb.
  }

  /**
   * Schreibt den Handover-/Austritts-Historieneintrag mit Namen-Snapshot (SPEC v3 Schritt 7). Refs
   * bleiben im Ereignis; die Namen hat der Aufrufer vor der ersten Schreibung aufgeloest ({@link
   * #resolveNames}, Intake #167). Faellt ein Name aus, wird die Ref als Fallback verwendet.
   */
  private void recordHandoverHistory(
      final Long taskId,
      final long tenantId,
      final UUID actorRef,
      final Set<UUID> targets,
      final boolean handover,
      final boolean shouldStay,
      final Map<UUID, String> names) {
    final String text =
        shouldStay ? "hat die Aufgabe uebergeben" : "hat die Aufgabe uebergeben und verlassen";
    final String unsubscribeText = "hat die Aufgabe verlassen";

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
    taskHistoryService.record(
        taskId, tenantId, actorRef, handover ? text : unsubscribeText, detail);
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
   * {@link EntityNotFoundException} (HTTP 404), um keine Existenz-Information preiszugeben (arch-v2
   * §8 / Cross-Tenant-Disclosure-Schutz).
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param tenantId erwarteter Mandanten-Schluessel
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
