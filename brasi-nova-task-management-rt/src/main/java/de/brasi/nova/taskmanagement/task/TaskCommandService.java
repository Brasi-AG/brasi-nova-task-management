package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.comment.MentionRecipientService;
import de.brasi.nova.taskmanagement.comment.MentionRecipients;
import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationMuteFilter;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDateTime;
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
 * <p>Creator-Invariante: Bei {@link #create} wird in DERSELBEN Transaktion verbindlich ein {@link
 * TaskParticipant} (CREATOR_FLAG=1, ASSIGNEE_FLAG=1) und ein {@link
 * de.brasi.nova.taskmanagement.entity.TaskUserState} fuer den Ersteller angelegt. Ohne diesen
 * Eintrag verschwaaende der neue Task aus der eigenen user-scoped Inbox.
 */
@Service
public class TaskCommandService {

  private static final Logger log = LoggerFactory.getLogger(TaskCommandService.class);

  /** Text der MENTION aus der Aufgabenbeschreibung (Intake #190). */
  static final String DESCRIPTION_MENTION_TEXT = "Du wurdest in der Aufgabenbeschreibung erwaehnt.";

  private final BaseTaskRepository taskRepository;
  private final BaseTaskParticipantRepository participantRepository;
  private final TaskParticipantQueryRepository participantQueryRepository;
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
  private final TaskMembershipGuard membershipGuard;
  private final TaskSourceRegistry sourceRegistry;
  private final NotificationMuteFilter muteFilter;
  private final MentionRecipientService mentionRecipientService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TaskCommandService(
      final BaseTaskRepository taskRepository,
      final BaseTaskParticipantRepository participantRepository,
      final TaskParticipantQueryRepository participantQueryRepository,
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
      final InboxGenerationService inboxGenerationService,
      final TaskMembershipGuard membershipGuard,
      final TaskSourceRegistry sourceRegistry,
      final NotificationMuteFilter muteFilter,
      final MentionRecipientService mentionRecipientService) {
    this.taskRepository = taskRepository;
    this.participantRepository = participantRepository;
    this.participantQueryRepository = participantQueryRepository;
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
    this.membershipGuard = membershipGuard;
    this.sourceRegistry = sourceRegistry;
    this.muteFilter = muteFilter;
    this.mentionRecipientService = mentionRecipientService;
  }

  /**
   * Legt eine neue Aufgabe an.
   *
   * <p>Erzwingt:
   *
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

    // A context task is never persisted from a client assertion alone. The adapter checks the
    // current actor, current tenant and source existence before any Task-side mutation.
    if (input.source() != null) {
      sourceRegistry.requireReadable(input.source(), actor);
    }

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
    // Nur aufrufen wenn nicht leer (MSSQL IN () ist ein Syntaxfehler). Unbekannt/fremd/inaktiv ->
    // 400.
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
    // Tags validieren + aufloesen (globaler Katalog, Tenant 0): unbekannt/inaktiv -> 400. Laeuft
    // VOR
    // dem Task-Insert -> ein ungueltiger Tag (auch zusammen mit gueltigen Assignees) verhindert
    // jede
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
    if (input.source() != null) {
      task.setSourceModule(input.source().module());
      task.setSourceEntityType(input.source().entityType());
      task.setSourceEntityRef(input.source().entityRef());
    }

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

    // Codex #6: Alle in dieser Tx zu bumpenden Refs sammeln und EINMAL NACH dem TASK-save (oben, Z.
    // taskRepository.save) ueber den zentralen Helfer in SORTIERTER Reihenfolge bumpen. Der
    // TASK-save
    // liegt am Anfang der Mutation -> TASK-Zeile wird vor den Generation-Zeilen gesperrt
    // (einheitliche
    // Save-dann-Bump-Ordnung). Menge = Nicht-Creator-Assignees + Creator (disjunkt per Dedupe
    // oben);
    // Reihenfolge/Platzierung geaendert, NICHT welche Refs gebumpt werden.
    final Set<UUID> refsToBump = new LinkedHashSet<>();

    // D3: Nicht-Creator-Assignees initial zuweisen (volle Semantik wie AssigneeService):
    // neuer Participant (ASSIGNEE+PARTICIPANT_FLAG) + UserState + ASSIGN-Notification +
    // Generation-Bump.
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
      // Generation atomar in dieser Tx bumpen (alte Inbox-Cursor -> 410 beim naechsten Read). Ref
      // sammeln; der eigentliche (sortierte) Bump erfolgt gebuendelt nach dem Loop.
      refsToBump.add(assigneeRef);
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
    // (LAST_ACTIVITY_AT < asOf < commit). Ref sammeln; der (sortierte) Bump erfolgt gebuendelt
    // unten.
    refsToBump.add(creatorRef);

    // Codex #6: gesamte betroffene Menge (Assignees + Creator) EINMAL in SORTIERTER Reihenfolge
    // NACH
    // dem TASK-save bumpen (einheitliche Save-dann-Bump- + Lock-Ordnung ueber den zentralen
    // Helfer).
    inboxGenerationService.bumpSorted(tenantId, refsToBump);

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
   * @param tagIds die bereits laengen-/null-validierten, angeforderten Tag-UUIDs (ggf. mit
   *     Duplikaten)
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
   * Patcht eine bestehende Aufgabe (title, dueDate, startDate, description, statusCode, tagIds,
   * priorityCode; mit description auch mentions und divisionMentions).
   *
   * <p>Legt einen Historieneintrag und aktualisiert LAST_ACTIVITY_AT in DERSELBEN Transaktion. Nach
   * Commit: {@link InboxSignalService#invalidate} fuer alle aktiven Teilnehmer.
   *
   * <p><b>statusCode / Transition-Semantik:</b> Es gibt KEINE terminalen Status. Der Patch prueft
   * ausschliesslich, ob der Ziel-Code im globalen Dictionary existiert (unbekannt/leer -&gt; 400) -
   * es gibt KEINEN Transition-Guard. Damit ist ein <b>Reopen</b> ({@code DONE}/{@code CANCELLED}
   * -&gt; {@code OPEN}) bewusst ERLAUBT (eine Aufgabe darf wieder geoeffnet werden); ebenso jeder
   * andere Status-Wechsel in beide Richtungen.
   *
   * <p><b>DONE-Benachrichtigung (Intake #151):</b> Nur beim <i>Uebergang</i> in {@code DONE} (neuer
   * Status DONE, bisheriger nicht) erhaelt jeder aktive Beteiligte (Ersteller, Zugewiesene,
   * Beteiligte) genau eine {@code DONE}-Benachrichtigung inkl. WebSocket-Push nach Commit - ohne
   * den schliessenden Akteur und ohne Personen, die die Aufgabe verlassen haben. Ein Patch ohne
   * Statuswechsel oder in einen anderen Status loest keine DONE-Benachrichtigung aus. Die Emission
   * laeuft in derselben Transaktion wie die Statusaenderung.
   *
   * <p><b>CANCELLED-Benachrichtigung (Intake #157):</b> Spiegelbildlich zu DONE loest der
   * <i>Uebergang</i> in {@code CANCELLED} (neuer Status CANCELLED, bisheriger nicht) genau eine
   * {@code CANCELLED}-Benachrichtigung je Empfaenger aus (Schwere INFO, Vorschau = Titel); dieselbe
   * Empfaengerermittlung wie bei DONE ({@link #findStatusChangeRecipients}). Wie bei DONE zaehlt
   * jeder Uebergang unabhaengig vom Ausgangsstatus: {@code OPEN -> CANCELLED} und {@code DONE ->
   * CANCELLED} loesen CANCELLED aus, {@code CANCELLED -> DONE} loest (unveraendert) DONE aus.
   * {@code CANCELLED -> CANCELLED} ist kein Uebergang; ein Reopen ({@code CANCELLED -> OPEN}) loest
   * nichts aus.
   *
   * <p><b>priorityCode (Intake #183):</b> Absent = Prioritaet unveraendert; ein Code aus {@code
   * DIC_TASK_PRIORITY} (derzeit LOW, NORMAL, HIGH, URGENT) setzt die Prioritaet; {@code null} setzt
   * sie auf {@code NORMAL} zurueck. Unbekannter, leerer oder inaktiver Code fuehrt zu HTTP 400
   * (Meldung {@code Ungueltiger priorityCode: 'code'.}) und wird VOR jeder Mutation geprueft (alles
   * oder nichts, auch zusammen mit anderen Feldern). Verglichen wird ueber die Dictionary-IDs: Eine
   * Aenderung schreibt genau einen Verlaufseintrag "Prioritaet geaendert" mit den kanonischen Codes
   * (z. B. {@code NORMAL -> HIGH}); ein unveraenderter Wert (gleiche ID, auch {@code null} bei
   * bereits NORMAL) schreibt keinen Verlauf und loest allein weder Save noch Bump/Invalidate aus.
   * Eine Prioritaetsaenderung loest keine Benachrichtigung aus.
   *
   * <p><b>Erwaehnungen in der Beschreibung (Intake #190):</b> Mit {@code description} duerfen
   * {@code mentions} und {@code divisionMentions} kommen (ohne {@code description} lehnt schon
   * {@link TaskPatchInput} ab). Der {@link MentionRecipientService} ermittelt die Empfaenger wie
   * beim Kommentar (Abteilungen aus der lokalen Projektion, nur lesend; aktive Mandanten-Nutzer;
   * ohne Akteur; WRITE-Mitglieder als Ziel; Nicht-Mitglieder nur mit {@code TASK_WRITE} als
   * Beteiligte aufgenommen, mit Verlauf "per Erwaehnung"). Der Lesepart ({@code resolve}:
   * Abteilungen, Benutzer, Mitgliedschaft) laeuft direkt nach der Aufloesung der Prioritaet und
   * damit VOR der ersten Mutation (Verlauf, Tags): eine unbekannte oder inaktive Abteilung oder zu
   * viele Personen ergeben 400, bevor etwas geschrieben ist. Die Aufnahme ({@code admit}) folgt
   * nach den uebrigen Mutationen und Reads, vor dem TASK-save. Nach dem save bekommt jedes Ziel
   * eine MENTION "Du wurdest in der Aufgabenbeschreibung erwaehnt." (Vorschau = Titel, trotz Mute).
   * Wer im selben Patch DONE oder CANCELLED bekommt und erwaehnt wird, erhaelt beide
   * Benachrichtigungen unabhaengig voneinander (DONE mit Mute-Filter, MENTION ohne, keine
   * Deduplizierung ueber die Typen hinweg); die Aufgenommenen kommen in Bump und Signal.
   *
   * <p><b>Inbox-Generation-Bump (Finding 2 / Option B, Cursor-Invalidierung):</b> Bei JEDER
   * tatsaechlichen Aenderung ({@code changed}) wird die {@link InboxGenerationService}-Generation
   * JEDES aktiven Teilnehmers der Aufgabe gebumpt - exakt die Menge, fuer die auch {@link
   * InboxSignalService#invalidate} gesendet wird. Begruendung (jeder-{@code changed} statt
   * nur-Status): Zwei asOf-gebundene, requester-gescopte Reads brechen bei einem Patch:
   *
   * <ul>
   *   <li><b>Status-Change</b> ({@code OPEN} &harr; {@code DONE}/{@code CANCELLED}) verschiebt den
   *       Task zwischen aktiver Seite ({@code findActivePage}, OPEN-gefiltert) und Archiv-Zaehlung
   *       ({@code countArchive*}) - eine Membership-/Ergebnismengen-Aenderung fuer jeden aktiven
   *       Teilnehmer.
   *   <li><b>Jeder {@code changed}-Patch</b> setzt {@code LAST_ACTIVITY_AT = now()} (unten) neu. Da
   *       der Keyset-Cursor genau auf {@code (asOf, LAST_ACTIVITY_AT, externalId)} ordnet,
   *       verschiebt das die Position des Tasks im Scroll - ein alter aktiver Cursor koennte den
   *       Task sonst ueberspringen oder doppelt liefern (stale pagination), ohne 410 zu erhalten.
   * </ul>
   *
   * Der Bump laeuft atomar in dieser Transaktion (siehe {@link InboxGenerationService}); ein alter
   * aktiver Cursor jedes betroffenen Teilnehmers wird beim naechsten Folge-Read mit 410 ({@code
   * SnapshotExpired}) abgelehnt und re-bootstrappt gegen den frischen Stand.
   *
   * @param taskExternalId externe UUID der Aufgabe
   * @param input Patch-Eingabe
   * @throws EntityNotFoundException wenn die Aufgabe nicht gefunden wird oder fremdem Tenant
   *     gehoert
   * @throws EntityValidationException bei unbekanntem statusCode, unbekanntem, leerem bzw.
   *     inaktivem priorityCode, unbekannter oder inaktiver erwaehnter Abteilung oder zu vielen
   *     Personen in den erwaehnten Abteilungen
   */
  @Transactional
  public void patch(final UUID taskExternalId, final TaskPatchInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    final long tenantId = actor.tenant().id();
    final UUID actorRef = actor.user().id();

    final Task task = requireTask(taskExternalId, tenantId);
    final Long taskId = task.getId();

    // Standard-Selfservice (Slice 2): WRITE-Member-Guard NACH Tenant-/ACTIVE-Check und VOR jeder
    // Mutation. Nur aktive, nicht ausgetretene PERSON-Mitglieder duerfen patchen; Non-/Ex-Member ->
    // neutrale 404 (keine History, kein Save, kein Bump/Invalidate).
    membershipGuard.requireWriteMember(taskId, actorRef, tenantId);

    // priorityCode (Intake #183): den Ziel-Code VOR jeder Mutation gegen das Dictionary aufloesen,
    // damit ein ungueltiger Code die Aufgabe unveraendert laesst (kein Verlauf, auch nicht fuer
    // zugleich gesendete Felder). Absent -> null (nichts zu tun).
    final Long newPriorityId =
        input.priorityCode().isPresent()
            ? resolvePatchPriorityId(input.priorityCode().get())
            : null;

    // Intake #190: Lesepart der Erwaehnungen in der Beschreibung (Abteilungen aus der lokalen
    // Projektion, aktive Benutzer, Mitgliedschaft der Erwaehnten) ebenfalls VOR der ersten Mutation
    // (Spec: Reads vor der ersten Schreibung). Ein 400 (unbekannte oder inaktive Abteilung, zu
    // viele Personen) kommt so vor jeder Schreibung, auch vor dem Verlauf und den Tags. Die
    // Aufnahme erwaehnter Nicht-Mitglieder folgt unten, vor dem TASK-save. Null ohne Erwaehnung.
    final MentionRecipientService.Candidates mentionCandidates =
        resolveDescriptionMentions(taskId, tenantId, actorRef, input);

    boolean changed = false;
    // Intake #151/#157: Typ-CODE der Statusbenachrichtigung (DONE oder CANCELLED), wenn dieser
    // Patch die Aufgabe in einen dieser Status ueberfuehrt; sonst null.
    String statusNotificationType = null;

    // title
    if (input.title().isPresent()) {
      final String newTitle = input.title().get();
      if (newTitle == null || newTitle.isBlank()) {
        throw new EntityValidationException("Titel darf nicht leer sein.");
      }
      historyService.record(
          taskId,
          tenantId,
          actorRef,
          "Titel geaendert",
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

    // startDate (BE-5): present(date) = setzen, present(null) = loeschen; absent = unberuehrt.
    // Semantik gespiegelt zu dueDate (keine Gleichheitspruefung: ein praesenter Wert erzeugt
    // bewusst
    // History/Save/Invalidate, auch wenn er dem Ist-Wert gleicht). Keine
    // startDate<=dueDate-Pruefung
    // (konsistent zu Create und dem dueDate-PATCH).
    if (input.startDate().isPresent()) {
      final java.time.LocalDate newStartDate = input.startDate().get();
      historyService.record(
          taskId,
          tenantId,
          actorRef,
          "Startdatum geaendert",
          task.getStartDate() + " -> " + newStartDate);
      task.setStartDate(newStartDate);
      changed = true;
    }

    // description (#143): present(text) = setzen, present(null) = loeschen; absent = unberuehrt.
    // Wie bei Create wird der Text unveraendert uebernommen. Der Historieneintrag enthaelt bewusst
    // nur die Laengen (Freitext kann bis zu 4000 Zeichen lang sein).
    if (input.description().isPresent()) {
      final String newDescription = input.description().get();
      historyService.record(
          taskId,
          tenantId,
          actorRef,
          "Beschreibung geaendert",
          length(task.getDescription()) + " -> " + length(newDescription) + " Zeichen");
      task.setDescription(newDescription);
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
        throw new EntityValidationException("Ungueltiger statusCode: '" + newStatusCode + "'.");
      }
      historyService.record(taskId, tenantId, actorRef, "Status geaendert", "-> " + newStatusCode);
      // Uebergang in DONE bzw. CANCELLED: nur wenn der neue Status vom bisherigen abweicht (IDs
      // statt Code-Strings, damit die Pruefung unabhaengig von der Schreibweise des Client-Codes
      // ist).
      if (!newStatusId.equals(task.getTaskStatusId())) {
        if (newStatusId.equals(resolver.resolveTaskStatusDone())) {
          statusNotificationType = NotificationTypeCodes.DONE;
        } else if (newStatusId.equals(resolver.resolveTaskStatusCancelled())) {
          statusNotificationType = NotificationTypeCodes.CANCELLED;
        }
      }
      task.setTaskStatusId(newStatusId);
      changed = true;
    }

    // priorityCode (Intake #183): nur bei geaenderter Dictionary-ID. Verglichen wird ueber die IDs
    // (unabhaengig von der Schreibweise des Client-Codes), der Verlauf nennt die kanonischen Codes
    // des Dictionaries. Gleiche ID (auch null bei bereits NORMAL): kein Verlauf, kein changed.
    // Bewusst keine Benachrichtigung (nur DONE/CANCELLED benachrichtigen).
    if (newPriorityId != null && !newPriorityId.equals(task.getPriorityId())) {
      final String oldPriorityCode = resolver.resolveTaskPriorityCode(task.getPriorityId());
      final String newPriorityCode = resolver.resolveTaskPriorityCode(newPriorityId);
      historyService.record(
          taskId,
          tenantId,
          actorRef,
          "Prioritaet geaendert",
          oldPriorityCode + " -> " + newPriorityCode);
      task.setPriorityId(newPriorityId);
      changed = true;
      log.info(
          "TaskCommandService.patch: Prioritaet der Aufgabe {} von {} auf {} geaendert.",
          taskExternalId,
          oldPriorityCode,
          newPriorityCode);
    }

    // tagIds: PUT-Set-Semantik (R-PATCH)
    if (input.tagIds().isPresent()) {
      final Set<UUID> desiredTagUuids =
          input.tagIds().get() != null ? input.tagIds().get() : Set.of();

      // null-Element-Pruefung: {"tagIds":[null]} -> 400 statt NPE (F-P2b).
      // Iteration statt contains(null): ImmutableSet.contains(null) wuerde NPE ausloesen.
      for (final UUID tagUuid : desiredTagUuids) {
        if (tagUuid == null) {
          throw new EntityValidationException("tagIds darf keine null-Elemente enthalten.");
        }
      }

      // Aktuelle aktive Assignments ZUERST laden - sie bestimmen die Bestandsschutz-Toleranz
      // (Intake #138): eine bereits aktiv zugeordnete Tag-UUID darf resubmittet werden, auch wenn
      // der Katalog-Tag inzwischen DEACTIVE ist (Retire behaelt Zuordnungen). Nur NEU-Hinzufuegen
      // eines inaktiven Tags bleibt 400.
      final List<TaskTagAssignment> currentAssignments =
          tagAssignmentQueryRepository.findActiveByTaskId(taskId);
      final Map<Long, TaskTagAssignment> currentByTagId =
          currentAssignments.stream()
              .collect(Collectors.toMap(TaskTagAssignment::getTagId, a -> a));
      // Externe UUIDs der bereits aktiv zugeordneten Tags (status-agnostisch projiziert) -
      // Grundlage
      // der Resolve-Toleranz: steht eine desired-UUID hier drin, ist sie "bereits zugeordnet".
      final Set<UUID> alreadyAssignedUuids =
          tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(taskId).stream()
              .map(UUID::fromString)
              .collect(Collectors.toCollection(HashSet::new));

      // Alle Tag-UUIDs gegen den globalen Katalog aufloesen. Intake #138 Bestandsschutz-Toleranz:
      // - bereits aktiv zugeordnete UUID -> status-agnostischer Resolve (findByUuid), auch DEACTIVE
      //   wird toleriert (KEIN 400) -> der Resubmit eines retire-ten, aber zugeordneten Tags bleibt
      //   erhalten.
      // - NEUE UUID (nicht zugeordnet) -> weiterhin findActiveByUuid (F-P1): ENTITY_STATUS +
      //   Gueltigkeit; inaktiv/abgelaufen/unbekannt -> 400.
      // Abbruch-sicher: alle validieren BEVOR irgendwas geaendert wird.
      // Intake #134: Zusaetzlich die aufgeloesten Tag-Namen (entryValue, Spalte BEZEICHNUNG) je
      // interner Tag-Id merken, damit der Verlauf bei HINZUGEFUEGTEN Tags den Namen ohne
      // Zusatz-Read
      // schreiben kann.
      final Map<Long, String> tagNameById = new java.util.HashMap<>();
      // Intake #134: UUID (EXTERNALID) je interner Tag-Id fuer die Verlaufs-Fallback-Kette
      // (entryValue -> UUID -> tagId) bei HINZUGEFUEGTEN Tags.
      final Map<Long, String> tagUuidById = new java.util.HashMap<>();
      final Map<UUID, Long> tagUuidToId =
          TenantContextHolder.runWithTenant(
              TenantInfo.global(),
              () -> {
                final Map<UUID, Long> result = new java.util.HashMap<>();
                for (final UUID tagUuid : desiredTagUuids) {
                  final boolean alreadyAssigned = alreadyAssignedUuids.contains(tagUuid);
                  final Optional<de.brasi.nova.taskmanagement.entity.TaskTag> found =
                      alreadyAssigned
                          ? tagRepository.findByUuid(tagUuid.toString())
                          : tagRepository.findActiveByUuid(tagUuid.toString());
                  if (found.isEmpty()) {
                    throw new EntityValidationException(
                        "Unbekannte oder inaktive Tag-UUID: '" + tagUuid + "'.");
                  }
                  result.put(tagUuid, found.get().getId());
                  tagNameById.put(found.get().getId(), found.get().getEntryValue());
                  final UUID resolvedUuid = found.get().getUuid();
                  tagUuidById.put(
                      found.get().getId(), resolvedUuid != null ? resolvedUuid.toString() : null);
                }
                return result;
              });

      // Neue Tag-IDs: gewuenschaft aber noch nicht vorhanden
      final Set<Long> desiredTagIds = new HashSet<>(tagUuidToId.values());
      final Set<Long> toAdd =
          desiredTagIds.stream()
              .filter(id -> !currentByTagId.containsKey(id))
              .collect(Collectors.toSet());

      // Abgewaehlte Tag-IDs: vorhanden aber nicht gewuenscht
      final List<Long> toRemoveIds =
          currentByTagId.entrySet().stream()
              .filter(e -> !desiredTagIds.contains(e.getKey()))
              .map(e -> e.getValue().getId())
              .collect(Collectors.toList());

      // Intake #134: Die TAG-Ids der entfernten Zuordnungen (Map-Key) - fuer die Namensaufloesung
      // des Verlaufs. toRemoveIds enthaelt Assignment-Ids, nicht Tag-Ids.
      final Set<Long> removedTagIds =
          currentByTagId.keySet().stream()
              .filter(tagId -> !desiredTagIds.contains(tagId))
              .collect(Collectors.toCollection(LinkedHashSet::new));

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
        // Intake #134: Namen der ENTFERNTEN Tags VOR der Deaktivierung status-agnostisch aufloesen
        // (findById filtert nicht auf ENTITY_STATUS; loest auch inaktive/abgelaufene Katalog-Tags
        // auf). Deaktiviert wird nur das Assignment, nie der Katalog-Tag - die Reihenfolge ist
        // daher
        // unkritisch; die Aufloesung bleibt aber bewusst vor der Mutation (SPEC #4).
        final Map<Long, String> removedNameById = new java.util.HashMap<>();
        for (final Long tagId : removedTagIds) {
          removedNameById.put(tagId, resolveRemovedTagName(tagId));
        }

        // Abgewaehlte deaktivieren (KSF-idiomatisch)
        if (!toRemoveIds.isEmpty()) {
          tagAssignmentQueryRepository.deactivateAllByIds(toRemoveIds);
        }

        // Intake #134: Verlauf NACH erfolgreicher Mutation in DERSELBEN Transaktion schreiben.
        // Pro hinzugefuegtem Tag (inkl. Reaktivierung) ein "Tag hinzugefuegt", pro entferntem Tag
        // ein "Tag entfernt"; changeDetail = Tag-Name (gekuerzt auf 500). Der History-Eintrag darf
        // nie am fehlenden Namen scheitern (Fallback-Kette: entryValue -> UUID -> tagId).
        for (final Long tagId : toAdd) {
          historyService.record(
              taskId,
              tenantId,
              actorRef,
              "Tag hinzugefuegt",
              truncate(resolveAddedTagName(tagId, tagNameById, tagUuidById), 500));
        }
        for (final Long tagId : removedTagIds) {
          historyService.record(
              taskId,
              tenantId,
              actorRef,
              "Tag entfernt",
              truncate(removedNameById.get(tagId), 500));
        }

        changed = true;
        log.info(
            "TaskCommandService.patch: Tag-Menge fuer Aufgabe {} aktualisiert"
                + " (hinzugefuegt={}, entfernt={}).",
            taskExternalId,
            toAdd.size(),
            toRemoveIds.size());
      }
    }

    if (changed) {
      // Vor dem TASK-save lesen: Exit sperrt Membership-Zeilen vor TASK. Ein nachgelagerter
      // Participant-SELECT unter TASK-X-Lock koennte diese Lock-Reihenfolge umkehren.
      final Set<UUID> readerRefs = new HashSet<>();
      userStateQueryRepository.findActiveUserRefsByTaskId(taskId).stream()
          .map(TaskUserState::getUserRef)
          .forEach(readerRefs::add);
      // Archiv-Leser = Personen, die die Aufgabe laut Code verlassen haben (EXIT_KIND gesetzt ODER
      // UNSUBSCRIBED_AT im UserState); sie werden zusaetzlich fuer die Empfaenger der
      // Statusbenachrichtigung (DONE/CANCELLED) ausgeschlossen.
      final Set<UUID> leftRefs =
          participantQueryRepository.findArchiveReadersByTaskId(taskId).stream()
              .map(TaskParticipant::getParticipantRef)
              .collect(Collectors.toSet());
      readerRefs.addAll(leftRefs);
      // Intake #151/#157: Empfaenger der Statusbenachrichtigung aus demselben Grund ebenfalls VOR
      // dem TASK-save lesen (Membership-Read vor TASK-X-Lock); der Emit erfolgt erst nach dem save.
      // Intake #158: inklusive gebuendelter Mute-Lesung (stumme Personen fallen heraus).
      final List<UUID> statusRecipients =
          statusNotificationType != null
              ? findStatusChangeRecipients(taskId, statusNotificationType, actorRef, leftRefs)
              : List.of();
      // Intake #190: Erwaehnungen in der Beschreibung, Schreibphase. Die Reads (Abteilungen,
      // Benutzer, Mitgliedschaft) liefen schon vor der ersten Mutation; hier nimmt der Dienst die
      // erwaehnten Nicht-Mitglieder auf (Teilnehmer- und State-Writes, Mitgliedschaft vor Aufgabe)
      // und liefert die Ziele der MENTION. Kein REPLY, daher kein ausgenommener Empfaenger.
      final MentionRecipients mentionRecipients = admitDescriptionMentions(mentionCandidates);
      readerRefs.addAll(mentionRecipients.admitted());
      task.setLastActivityAt(LocalDateTime.now());
      taskRepository.save(task);
      log.info(
          "TaskCommandService.patch: Aufgabe {} von Benutzer {} in Mandant {} aktualisiert.",
          taskExternalId,
          actorRef,
          tenantId);
      // Intake #151/#157: Statusbenachrichtigung (DONE/CANCELLED) in DERSELBEN Transaktion wie die
      // Statusaenderung; der WebSocket-Push folgt wie bei allen Typen erst nach Commit
      // (NotificationService).
      for (final UUID recipientRef : statusRecipients) {
        notificationService.emit(
            recipientRef,
            taskId,
            statusNotificationType,
            NotificationSeverityCodes.INFO,
            statusNotificationText(statusNotificationType),
            task.getTitle(),
            actorRef);
      }
      if (statusNotificationType != null) {
        log.info(
            "TaskCommandService.patch: Aufgabe {} in Status {} ueberfuehrt -"
                + " Benachrichtigung an {} Empfaenger emittiert.",
            taskExternalId,
            statusNotificationType,
            statusRecipients.size());
      }
      // Intake #190: MENTION je Ziel der Beschreibung, wie beim Kommentar (gleicher Typ, kein
      // Mute-Filter, kein Selbst-Ping). Unabhaengig von einer Statusbenachrichtigung oben: wer im
      // selben PATCH DONE (oder CANCELLED) bekommt und erwaehnt wird, erhaelt beide, DONE mit
      // Mute-Filter, MENTION ohne; es gibt keine Deduplizierung ueber die Typen hinweg.
      for (final UUID mentionRef : mentionRecipients.notifyTargets()) {
        notificationService.emit(
            mentionRef,
            taskId,
            NotificationTypeCodes.MENTION,
            NotificationSeverityCodes.INFO,
            DESCRIPTION_MENTION_TEXT,
            task.getTitle(),
            actorRef);
      }
      if (!mentionRecipients.notifyTargets().isEmpty()) {
        log.info(
            "TaskCommandService.patch: Erwaehnung in der Beschreibung der Aufgabe {} -"
                + " MENTION an {} Empfaenger, {} Person(en) aufgenommen.",
            taskExternalId,
            mentionRecipients.notifyTargets().size(),
            mentionRecipients.admitted().size());
      }
      // Gleiche deduplizierte Menge fuer atomaren Generation-Bump und Signal nach Commit.
      if (!readerRefs.isEmpty()) {
        inboxGenerationService.bumpSorted(tenantId, readerRefs);
        inboxSignalService.invalidate(tenantId, readerRefs);
      }
    }
  }

  /**
   * Lesephase der Erwaehnungen in der Beschreibung (Intake #190): loest Abteilungen und Personen
   * auf und teilt sie in Mitglieder und Nicht-Mitglieder, ohne etwas zu schreiben. Laeuft vor der
   * ersten Mutation des PATCH. Ohne Erwaehnung wird der Dienst nicht gerufen (kein Read).
   *
   * @param taskId interne Aufgaben-ID
   * @param tenantId interner Mandanten-Schluessel
   * @param actorRef handelnde Person (wird nie Ziel)
   * @param input Patch-Eingabe
   * @return die Kandidaten fuer {@link #admitDescriptionMentions}, {@code null} ohne Erwaehnung
   * @throws EntityValidationException wenn eine erwaehnte Abteilung unbekannt oder nicht aktiv ist
   *     oder die Abteilungen zu viele Personen umfassen (HTTP 400, nichts ist geschrieben)
   */
  private MentionRecipientService.Candidates resolveDescriptionMentions(
      final Long taskId, final long tenantId, final UUID actorRef, final TaskPatchInput input) {
    if (!input.description().isPresent()
        || (input.mentions().isEmpty() && input.divisionMentions().isEmpty())) {
      return null;
    }
    return mentionRecipientService.resolve(
        taskId, tenantId, actorRef, null, input.mentions(), input.divisionMentions());
  }

  /**
   * Schreibphase der Erwaehnungen in der Beschreibung (Intake #190): nimmt erwaehnte
   * Nicht-Mitglieder (mit {@code TASK_WRITE}) als Beteiligte auf und liefert die Ziele der MENTION.
   * Laeuft nach den uebrigen Mutationen und Reads des PATCH, vor dem TASK-save.
   *
   * @param candidates Ergebnis von {@link #resolveDescriptionMentions}, {@code null} ohne
   *     Erwaehnung
   * @return Ziele der MENTION und Aufgenommene, leer ohne Erwaehnung
   */
  private MentionRecipients admitDescriptionMentions(
      final MentionRecipientService.Candidates candidates) {
    if (candidates == null) {
      return new MentionRecipients(Set.of(), Set.of());
    }
    return mentionRecipientService.admit(candidates);
  }

  /**
   * Loest den Ziel-Prioritaetscode eines PATCH auf die Dictionary-ID auf (Intake #183).
   *
   * <p>{@code null} bedeutet "auf NORMAL zuruecksetzen" und geht nie an den Resolver. Leer, blank,
   * unbekannt oder inaktiv (der Resolver-Finder liefert nur aktive, gueltige Eintraege) ist ein
   * Eingabefehler; die Meldung nennt den Originalwert und entspricht der von Create.
   *
   * @param priorityCode Client-Code oder {@code null}
   * @return interne ID der Ziel-Prioritaet (nie null)
   * @throws EntityValidationException bei leerem, unbekanntem oder inaktivem Code (HTTP 400)
   */
  private Long resolvePatchPriorityId(final String priorityCode) {
    if (priorityCode == null) {
      return resolver.resolveTaskPriority(TaskPriorityCodes.NORMAL);
    }
    if (priorityCode.isBlank()) {
      throw new EntityValidationException("Ungueltiger priorityCode: '" + priorityCode + "'.");
    }
    try {
      return resolver.resolveTaskPriority(priorityCode);
    } catch (DictionaryResolverException e) {
      throw new EntityValidationException("Ungueltiger priorityCode: '" + priorityCode + "'.");
    }
  }

  /**
   * Text der Statusbenachrichtigung je Typ-CODE (Intake #151 DONE, #157 CANCELLED).
   *
   * @param typeCode {@link NotificationTypeCodes#DONE} oder {@link NotificationTypeCodes#CANCELLED}
   * @return deutscher Benachrichtigungstext
   */
  private static String statusNotificationText(final String typeCode) {
    return NotificationTypeCodes.CANCELLED.equals(typeCode)
        ? "Die Aufgabe wurde abgebrochen."
        : "Die Aufgabe wurde erledigt.";
  }

  /**
   * Ermittelt die Empfaenger einer Statusbenachrichtigung beim Schliessen (DONE, Intake #151) und
   * beim Abbrechen (CANCELLED, Intake #157): alle aktiven Beteiligten der Aufgabe (Ersteller,
   * Zugewiesene und Beteiligte), jede Person genau einmal - auch bei mehreren Rollen -, ohne den
   * handelnden Akteur und ohne Personen, die die Aufgabe verlassen haben.
   *
   * <p>Bewusst EINE gemeinsame Ermittlung fuer beide Statuswechsel: Anpassungen der Empfaengerlogik
   * (z. B. Stummschalten, Intake #158) gelten damit automatisch fuer Schliessen und Abbrechen.
   *
   * <p>Intake #158: Personen, die die Aufgabe stummgeschaltet haben, erhalten weder DONE noch
   * CANCELLED. Die Regel steht zentral im {@link NotificationMuteFilter}; die Mute-Zustaende aller
   * Kandidaten werden in einer Query gelesen.
   *
   * <p>Muss VOR dem TASK-save aufgerufen werden (Lock-Reihenfolge, siehe {@link #patch}); das gilt
   * auch fuer die Mute-Lesung auf {@code TASK_USER_STATE}.
   *
   * @param taskId interne Aufgaben-ID
   * @param typeCode Typ der Statusbenachrichtigung ({@code DONE} oder {@code CANCELLED})
   * @param actorRef UUID der handelnden Person (wird nicht benachrichtigt)
   * @param leftRefs Personen, die die Aufgabe verlassen haben (EXIT_KIND oder UNSUBSCRIBED_AT)
   * @return Empfaenger-UUIDs ohne Duplikate und ohne stummgeschaltete Personen
   */
  private List<UUID> findStatusChangeRecipients(
      final Long taskId, final String typeCode, final UUID actorRef, final Set<UUID> leftRefs) {
    final List<UUID> candidates =
        participantQueryRepository.findActiveByTaskIds(List.of(taskId)).stream()
            .filter(participant -> participant.getExitKind() == null)
            .map(TaskParticipant::getParticipantRef)
            .filter(ref -> !ref.equals(actorRef))
            .filter(ref -> !leftRefs.contains(ref))
            .distinct()
            .toList();
    return muteFilter.withoutMuted(taskId, typeCode, candidates);
  }

  /**
   * Loest den Verlaufs-Namen eines HINZUGEFUEGTEN Tags auf (Intake #134). Der Name wurde im
   * Resolution-Loop bereits ohne Zusatz-Read aus dem aktiven Katalog geladen ({@code entryValue} =
   * Spalte BEZEICHNUNG). Fallback-Kette, falls der Name fehlt: {@code tagId} als String. Ein
   * fehlender Name darf den History-Eintrag nie verhindern.
   *
   * <p>Fallback-Kette (SPEC #4, konsistent zu {@link #resolveRemovedTagName(Long)}): {@code
   * entryValue} -&gt; {@code UUID} (EXTERNALID) -&gt; {@code tagId} als String. Ein fehlender Name
   * darf den History-Eintrag nie verhindern.
   *
   * @param tagId interne Tag-Id
   * @param tagNameById im Resolution-Loop gefuellte Map (interne Tag-Id -> entryValue)
   * @param tagUuidById im Resolution-Loop gefuellte Map (interne Tag-Id -> UUID-String)
   * @return aufgeloester Tag-Name, nie {@code null}
   */
  private static String resolveAddedTagName(
      final Long tagId, final Map<Long, String> tagNameById, final Map<Long, String> tagUuidById) {
    final String name = tagNameById.get(tagId);
    if (name != null && !name.isBlank()) {
      return name;
    }
    final String uuid = tagUuidById.get(tagId);
    if (uuid != null && !uuid.isBlank()) {
      return uuid;
    }
    return String.valueOf(tagId);
  }

  /**
   * Loest den Verlaufs-Namen eines ENTFERNTEN Tags auf (Intake #134). Fuer entfernte Tags ist nur
   * die interne {@code tagId} bekannt; der Katalog-Tag wird VOR/beim History-Schreiben mit einem
   * status-agnostischen {@code findById} im globalen Tenant-Scope gelesen. {@code findById} ist
   * KSF-Base und filtert NICHT auf {@code ENTITY_STATUS} - so werden auch inaktive/abgelaufene
   * Katalog-Tags aufgeloest ({@code findActiveByUuid} waere hier falsch). Fallback-Kette: {@code
   * entryValue} -&gt; {@code getUuid()} (EXTERNALID) -&gt; {@code tagId} als String.
   *
   * @param tagId interne Tag-Id des entfernten Tags
   * @return aufgeloester Tag-Name, nie {@code null}
   */
  private String resolveRemovedTagName(final Long tagId) {
    final Optional<de.brasi.nova.taskmanagement.entity.TaskTag> found =
        TenantContextHolder.runWithTenant(TenantInfo.global(), () -> tagRepository.findById(tagId));
    if (found.isPresent()) {
      final String name = found.get().getEntryValue();
      if (name != null && !name.isBlank()) {
        return name;
      }
      final UUID uuid = found.get().getUuid();
      if (uuid != null) {
        return uuid.toString();
      }
    }
    return String.valueOf(tagId);
  }

  /**
   * Kuerzt {@code s} auf hoechstens {@code max} UTF-16-Code-Units inkl. abschliessender Ellipse
   * ("...") und schneidet dabei NICHT mitten in ein Surrogatpaar (z.B. Emoji) - sonst entstuende
   * ein ungepaartes Surrogat (korrupter Unicode / Insert-Fehler). Paketsichtbar fuer Unit-Tests.
   *
   * @param s der zu kuerzende Wert (null-sicher)
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
   * Liefert die Laenge eines optionalen Freitexts fuer Historieneintraege.
   *
   * @param s der Text (null-sicher)
   * @return Laenge in UTF-16-Code-Units, 0 bei null
   */
  private static int length(final String s) {
    return s == null ? 0 : s.length();
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
