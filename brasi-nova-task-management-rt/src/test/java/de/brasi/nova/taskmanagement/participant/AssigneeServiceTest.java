package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.entity.TaskHandover;
import de.brasi.nova.taskmanagement.entity.TaskHandoverTarget;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit-Tests fuer {@link AssigneeService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer Assignee-Zuweisung und Austritt:
 * <ol>
 *   <li>Neuer Assignee -> ASSIGNEE_FLAG gesetzt + UserState.ensure + ASSIGN-Notification + invalidate
 *   <li>Exit HANDOVER -> EXIT_KIND/EXIT_AT gesetzt + HANDOVER-Notification + invalidate
 *   <li>404-Masking bei unbekannter/fremder Task-UUID
 *   <li>400 bei Exit wenn kein Participant
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssigneeServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID ACTOR_REF = UUID.randomUUID();
  private static final UUID ASSIGNEE_REF = UUID.randomUUID();

  @Mock private BaseTaskRepository taskRepository;
  @Mock private TaskParticipantQueryRepository participantQueryRepository;
  @Mock private TaskUserStateQueryRepository userStateQueryRepository;
  @Mock private UserStateService userStateService;
  @Mock private NotificationService notificationService;
  @Mock private InboxSignalService inboxSignalService;
  @Mock private UserReadRepository userReadRepository;
  @Mock private UserAccessor userAccessor;
  @Mock private InboxGenerationService inboxGenerationService;
  @Mock private TaskHandoverQueryRepository handoverRepository;
  @Mock private BaseTaskHandoverTargetRepository handoverTargetRepository;
  @Mock private TaskHistoryService taskHistoryService;

  private AssigneeService service;

  @BeforeEach
  void setUp() {
    service =
        new AssigneeService(
            taskRepository,
            participantQueryRepository,
            userStateQueryRepository,
            userStateService,
            notificationService,
            inboxSignalService,
            userReadRepository,
            userAccessor,
            inboxGenerationService,
            handoverRepository,
            handoverTargetRepository,
            taskHistoryService);

    // Handover-Ereignis-Save gibt das Ereignis mit gesetzter ID zurueck (fuer die TARGET-FK).
    when(handoverRepository.save(any(TaskHandover.class)))
        .thenAnswer(
            inv -> {
              final TaskHandover e = inv.getArgument(0);
              if (e.getId() == null) {
                e.setId(9001L);
              }
              return e;
            });

    // Final-Review P1-2: Validierung angeforderter Assignees -> hier echot der Mock die UUIDs als
    // aktive Benutzer zurueck (die Unit-Tests gehen von gueltigen Benutzern aus; die Ablehnung
    // unbekannter UUIDs ist im AssigneeControllerIT real gegen MSSQL geprueft).
    when(userReadRepository.findActiveByUuids(any()))
        .thenAnswer(
            inv -> {
              final Collection<UUID> requested = inv.getArgument(0);
              final List<User> users = new ArrayList<>();
              for (final UUID ref : requested) {
                final User u = org.mockito.Mockito.mock(User.class);
                when(u.getUuid()).thenReturn(ref);
                users.add(u);
              }
              return users;
            });

    // CurrentActor: Mandant + Benutzer
    final TenantInfo tenant = new TenantInfo(TENANT_UUID, TENANT_ID);
    final AuthUser user = new AuthUser(ACTOR_REF, "actor@brasi.de");
    when(userAccessor.requireCurrentUser()).thenReturn(user);
    TenantContextHolder.setTenant(tenant);

    // Notification-Service: Stub fuer emit (gibt UUID zurueck)
    when(notificationService.emit(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());

    // Codex #6: Multi-Ref-Bumps laufen jetzt ueber den zentralen Helfer bumpSorted (delegiert produktiv
    // je Ref sortiert an bump). Der gemockte Helfer an denselben Mock delegieren, damit die bestehenden
    // je-Ref bump(..)-Verifikationen (WELCHE Refs, times/never) unveraendert gueltig bleiben.
    org.mockito.Mockito.doAnswer(
            inv -> {
              final long t = inv.getArgument(0);
              final Collection<UUID> c = inv.getArgument(1);
              if (c != null) {
                c.stream().sorted().forEach(r -> inboxGenerationService.bump(t, r));
              }
              return null;
            })
        .when(inboxGenerationService)
        .bumpSorted(anyLong(), any());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---- Test 1: Zuweisung -> ASSIGNEE_FLAG + UserState.reactivate + ASSIGN-Notification + invalidate ---

  @Test
  @DisplayName(
      "zuweisung_userstate_und_notification: neuer Assignee -> ASSIGNEE_FLAG=1 + UserState.ensure"
          + " + ASSIGN-Notification + invalidate")
  void zuweisung_userstate_und_notification() {
    // Arrange
    final Long taskId = 42L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // Keine bestehenden Assignees
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    // Kein vorhandener Participant fuer den neuen Assignee
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of());
    // save gibt den Participant zurueck
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Act
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ASSIGNEE_REF)));

    // Assert: TaskParticipant mit ASSIGNEE_FLAG=1 gespeichert
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.isAssigneeFlag()).as("ASSIGNEE_FLAG muss true sein").isTrue();
    assertThat(saved.getParticipantRef()).as("PARTICIPANT_REF muss ASSIGNEE_REF sein")
        .isEqualTo(ASSIGNEE_REF);
    assertThat(saved.getParticipantKind()).as("PARTICIPANT_KIND muss PERSON sein")
        .isEqualTo(ParticipantKind.PERSON);

    // UserState.reactivate aufgerufen (MAJOR 3: Reassign reaktiviert UNSUBSCRIBED_AT)
    verify(userStateService).reactivate(eq(taskId), eq(ASSIGNEE_REF), eq(TENANT_ID));

    // ASSIGN-Notification emittiert
    verify(notificationService).emit(
        eq(ASSIGNEE_REF),
        eq(taskId),
        eq(NotificationTypeCodes.ASSIGN),
        eq(NotificationSeverityCodes.INFO),
        any(),
        any(),
        eq(ACTOR_REF));

    // Invalidate aufgerufen
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue())
        .as("invalidate muss ASSIGNEE_REF enthalten")
        .contains(ASSIGNEE_REF);

    // Finding 2 / Option B: Zuweisung bumpt die Generation des neuen Assignees (toAdd).
    verify(inboxGenerationService).bump(TENANT_ID, ASSIGNEE_REF);

    // P2 #3: optimistischer TASK-Lock -> bei tatsaechlicher Aenderung wird die Aufgabe gespeichert
    // (VERSION-Bump serialisiert nebenlaeufige Ersetzungen).
    verify(taskRepository).save(task);
  }

  // ---- Test 2: exit HANDOVER -> EXIT_KIND/EXIT_AT + HANDOVER-Notification + invalidate -----------

  @Test
  @DisplayName(
      "exit_handover: exit HANDOVER -> EXIT_KIND=HANDOVER + EXIT_AT gesetzt"
          + " + HANDOVER-Notification + invalidate")
  void exit_handover() {
    // Arrange
    final Long taskId = 77L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Vorhandener Participant des Akteurs
    final TaskParticipant existingParticipant = new TaskParticipant();
    existingParticipant.setId(10L);
    existingParticipant.setTenantId(TENANT_ID);
    existingParticipant.setTaskId(taskId);
    existingParticipant.setParticipantRef(ACTOR_REF);
    existingParticipant.setParticipantKind(ParticipantKind.PERSON);
    existingParticipant.setAssigneeFlag(true);

    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(existingParticipant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // UserState vorhanden -> UNSUBSCRIBED_AT muss auch bei HANDOVER gesetzt werden (MAJOR 2)
    final TaskUserState userState = new TaskUserState();
    userState.setId(77L);
    userState.setTaskId(taskId);
    userState.setUserRef(ACTOR_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userState));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Act
    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER));

    // Assert: EXIT_KIND=HANDOVER + EXIT_AT gesetzt
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.getExitKind()).as("EXIT_KIND muss HANDOVER sein").isEqualTo(ExitKind.HANDOVER);
    assertThat(saved.getExitAt()).as("EXIT_AT muss gesetzt sein").isNotNull();

    // MAJOR 2: UNSUBSCRIBED_AT muss auch bei HANDOVER im UserState gesetzt sein
    final ArgumentCaptor<TaskUserState> stateCaptor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(stateCaptor.capture());
    assertThat(stateCaptor.getValue().getUnsubscribedAt())
        .as("UNSUBSCRIBED_AT muss auch bei HANDOVER gesetzt sein")
        .isNotNull();

    // HANDOVER-Notification emittiert (Akteur als Empfaenger)
    verify(notificationService).emit(
        eq(ACTOR_REF),
        eq(taskId),
        eq(NotificationTypeCodes.HANDOVER),
        eq(NotificationSeverityCodes.INFO),
        any(),
        any(),
        eq(ACTOR_REF));

    // Invalidate aufgerufen
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue())
        .as("invalidate muss ACTOR_REF enthalten")
        .contains(ACTOR_REF);

    // Finding 2 / Option B: Austritt bumpt die Generation des Akteurs.
    verify(inboxGenerationService).bump(TENANT_ID, ACTOR_REF);
  }

  // ---- Test 3: exit UNSUBSCRIBED -> kein HANDOVER-Notification -----------------------------------

  @Test
  @DisplayName(
      "exit_unsubscribed_kein_handover_notification: exit UNSUBSCRIBED -> keine HANDOVER-Notification")
  void exit_unsubscribed_kein_handover_notification() {
    // Arrange
    final Long taskId = 88L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant existingParticipant = new TaskParticipant();
    existingParticipant.setId(11L);
    existingParticipant.setTenantId(TENANT_ID);
    existingParticipant.setTaskId(taskId);
    existingParticipant.setParticipantRef(ACTOR_REF);
    existingParticipant.setParticipantKind(ParticipantKind.PERSON);
    existingParticipant.setAssigneeFlag(true);

    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(existingParticipant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Vorhandener UserState -> UNSUBSCRIBED_AT soll gesetzt werden
    final TaskUserState userState = new TaskUserState();
    userState.setId(50L);
    userState.setTaskId(taskId);
    userState.setUserRef(ACTOR_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userState));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Act
    service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED));

    // Assert: keine HANDOVER-Notification (notificationService.emit NICHT mit HANDOVER aufgerufen)
    verify(notificationService, never()).emit(
        any(),
        any(),
        eq(NotificationTypeCodes.HANDOVER),
        any(),
        any(),
        any(),
        any());

    // EXIT_KIND=UNSUBSCRIBED
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    assertThat(pCaptor.getValue().getExitKind())
        .as("EXIT_KIND muss UNSUBSCRIBED sein")
        .isEqualTo(ExitKind.UNSUBSCRIBED);

    // UNSUBSCRIBED_AT im UserState gesetzt
    final ArgumentCaptor<TaskUserState> stateCaptor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(stateCaptor.capture());
    assertThat(stateCaptor.getValue().getUnsubscribedAt())
        .as("UNSUBSCRIBED_AT muss gesetzt sein")
        .isNotNull();
  }

  // ---- Test RG3: exit bumpt die TASK-Version (gemeinsamer Guard gegen PUT /assignees) ----------

  /**
   * Final-Re-Gate (P2): exit muss am gemeinsamen TASK-Version-Guard teilnehmen, damit ein
   * nebenlaeufiger {@code PUT /assignees} (der die TASK-Version bumpt) und ein Austritt eines
   * beibehaltenen Assignees nicht beide committen und den PUT-Ziel-Satz verletzen. Wiring-Beweis:
   * ein erfolgreicher Austritt ruft {@code taskRepository.save(task)} (VERSION-Bump). Der
   * Lock-Mechanismus selbst (zweiter save derselben Version -> BusinessRuleException) ist in
   * {@code TaskOptimisticLockIT} deterministisch gegen echtes MSSQL bewiesen.
   */
  @Test
  @DisplayName("exit_bumpt_task_version_guard: erfolgreicher Austritt ruft taskRepository.save(task)")
  void exit_bumpt_task_version_guard() {
    final Long taskId = 94L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant participant = new TaskParticipant();
    participant.setId(12L);
    participant.setTenantId(TENANT_ID);
    participant.setTaskId(taskId);
    participant.setParticipantRef(ACTOR_REF);
    participant.setParticipantKind(ParticipantKind.PERSON);
    participant.setAssigneeFlag(true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(participant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED));

    // Gemeinsamer Guard: die TASK-Version wird gebumpt (serialisiert gegen nebenlaeufiges PUT).
    verify(taskRepository).save(task);
  }

  // ---- Test 4: 404-Masking bei unbekannter UUID ------------------------------------------------

  @Test
  @DisplayName("updateAssignees_unbekannte_uuid_liefert_404: EntityNotFoundException bei unbekannter UUID")
  void updateAssignees_unbekannte_uuid_liefert_404() {
    when(taskRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.updateAssignees(
                    UUID.randomUUID(), new AssigneeUpdateInput(Set.of(ASSIGNEE_REF))))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- Test 5: 400 bei exit ohne Participant --------------------------------------------------

  @Test
  @DisplayName("exit_ohne_participant_liefert_400: EntityValidationException wenn kein Participant")
  void exit_ohne_participant_liefert_400() {
    final Long taskId = 99L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER)))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Test 6 (BE-7 A3/U13b): Creator-Schutz = Mitgliedschaftsschutz ------------------------

  @Test
  @DisplayName(
      "creator_abwahl_deassignt_ohne_exit (A3): PUT ohne Creator -> Creator ASSIGNEE_FLAG=false,"
          + " aber CREATOR_FLAG+PARTICIPANT_FLAG+EXIT_KIND=null bleiben; kein UNSUBSCRIBED_AT")
  void creator_abwahl_deassignt_ohne_exit() {
    // Arrange
    final Long taskId = 55L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    // Creator-Participant (creatorFlag=true, assigneeFlag=true, participantFlag=true)
    final TaskParticipant creatorParticipant = new TaskParticipant();
    creatorParticipant.setId(20L);
    creatorParticipant.setTenantId(TENANT_ID);
    creatorParticipant.setTaskId(taskId);
    creatorParticipant.setParticipantRef(ACTOR_REF);
    creatorParticipant.setParticipantKind(ParticipantKind.PERSON);
    creatorParticipant.setCreatorFlag(true);
    creatorParticipant.setAssigneeFlag(true);
    creatorParticipant.setParticipantFlag(true);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(creatorParticipant));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(creatorParticipant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    // Act: PUT mit leerer Menge (Creator fehlt in desired)
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of()));

    // Assert: Creator wird DE-ASSIGNT (ASSIGNEE_FLAG=false), behaelt aber Membership + kein EXIT.
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.isAssigneeFlag()).as("ASSIGNEE_FLAG=false (de-assignt)").isFalse();
    assertThat(saved.isCreatorFlag()).as("CREATOR_FLAG bleibt").isTrue();
    assertThat(saved.isParticipantFlag()).as("PARTICIPANT_FLAG bleibt").isTrue();
    assertThat(saved.getExitKind()).as("KEIN Austritt (EXIT_KIND=null)").isNull();
    // Kein UNSUBSCRIBED_AT fuer Creator (kein UserState-Save).
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
    // A3: eine echte Membership-Aenderung -> TASK-VERSION-Bump.
    verify(taskRepository).save(task);
    verify(inboxGenerationService).bump(TENANT_ID, ACTOR_REF);
  }

  // ---- Test 7: MAJOR 3 - Reassign ruft reactivate auf ----------------------------------------

  @Test
  @DisplayName(
      "reassign_ruft_reactivate_auf: Reaktivierung eines abgewaehlten Assignees -> reactivate")
  void reassign_ruft_reactivate_auf() {
    // Arrange
    final Long taskId = 66L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // Keine aktuellen Assignees
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());

    // Bestehender (deaktivierter) Participant-Eintrag fuer ASSIGNEE_REF
    final TaskParticipant deactivatedParticipant = new TaskParticipant();
    deactivatedParticipant.setId(30L);
    deactivatedParticipant.setTenantId(TENANT_ID);
    deactivatedParticipant.setTaskId(taskId);
    deactivatedParticipant.setParticipantRef(ASSIGNEE_REF);
    deactivatedParticipant.setExitKind(ExitKind.UNSUBSCRIBED);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(deactivatedParticipant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Act: ASSIGNEE_REF erneut zuweisen
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ASSIGNEE_REF)));

    // Assert: reactivate (nicht ensure) aufgerufen -> UNSUBSCRIBED_AT wird zurueckgesetzt
    verify(userStateService).reactivate(eq(taskId), eq(ASSIGNEE_REF), eq(TENANT_ID));
    verify(userStateService, never()).ensure(any(), any(), any());
  }

  // ---- Test: Abwahl (toRemove) bumpt Generation des entfernten Refs --------------------------

  @Test
  @DisplayName("abwahl_bumpt_generation: PUT entfernt aktiven Assignee -> bump(removedRef)")
  void abwahl_bumpt_generation() {
    final Long taskId = 150L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID removedRef = ASSIGNEE_REF;
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Aktiver Nicht-Creator-Assignee A; Ziel-Menge leer -> A wird abgewaehlt (toRemove).
    final TaskParticipant participantA = new TaskParticipant();
    participantA.setParticipantRef(removedRef);
    participantA.setAssigneeFlag(true);
    participantA.setCreatorFlag(false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(participantA));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, removedRef))
        .thenReturn(List.of(participantA));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, removedRef)).thenReturn(List.of());
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of()));

    verify(inboxGenerationService).bump(TENANT_ID, removedRef);
  }

  // ---- Test 8: MINOR - null-assignees wirft EntityValidationException ----------------------

  @Test
  @DisplayName("null_assignees_wirft_400: AssigneeUpdateInput mit null -> EntityValidationException")
  void null_assignees_wirft_400() {
    assertThatThrownBy(() -> new AssigneeUpdateInput(null))
        .isInstanceOf(
            io.keel.framework.api.validation.EntityValidationException.class)
        .hasMessageContaining("assignees");
  }

  // ---- Test 8b: Re-Gate-P2 - Assignee-Obergrenze (100) -> 400 (SQL-Parameterlimit-Schutz) ------

  @Test
  @DisplayName("assignees_ueber_limit_400: 101 Assignees -> EntityValidationException (Bound-Schutz)")
  void assignees_ueber_limit_400() {
    final java.util.Set<UUID> tooMany = new java.util.LinkedHashSet<>();
    for (int i = 0; i < 101; i++) {
      tooMany.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> new AssigneeUpdateInput(tooMany))
        .isInstanceOf(io.keel.framework.api.validation.EntityValidationException.class)
        .hasMessageContaining("assignees");
  }

  // ---- Test 9: P2-4 - Creator darf die Aufgabe nicht verlassen (409) --------------------------

  @Test
  @DisplayName(
      "exit_creator_wird_abgelehnt: Creator ruft exit auf -> BusinessRuleException (409),"
          + " kein EXIT_KIND/Notification/Invalidate")
  void exit_creator_wird_abgelehnt() {
    // Arrange
    final Long taskId = 123L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Akteur ist der Creator-Participant (creatorFlag=true)
    final TaskParticipant creatorParticipant = new TaskParticipant();
    creatorParticipant.setId(70L);
    creatorParticipant.setTenantId(TENANT_ID);
    creatorParticipant.setTaskId(taskId);
    creatorParticipant.setParticipantRef(ACTOR_REF);
    creatorParticipant.setParticipantKind(ParticipantKind.PERSON);
    creatorParticipant.setCreatorFlag(true);
    creatorParticipant.setAssigneeFlag(true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(creatorParticipant));

    // Act + Assert: Creator-Exit wird als Geschaeftsregel-Verstoss (409) abgelehnt
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER)))
        .isInstanceOf(BusinessRuleException.class);

    // Keine Mutation, keine Notification, kein Invalidate, kein Generation-Bump
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
  }

  // ---- Test 10: P2-5 - bereits ausgetretener Teilnehmer kann nicht erneut austreten (400) -----

  @Test
  @DisplayName(
      "exit_bereits_ausgetreten_wird_abgelehnt: Participant mit EXIT_KIND -> EntityValidationException"
          + " (400), keine zweite Notification/zweites EXIT_AT")
  void exit_bereits_ausgetreten_wird_abgelehnt() {
    // Arrange
    final Long taskId = 124L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Akteur ist ein Nicht-Creator-Participant, der bereits ausgetreten ist (EXIT_KIND gesetzt)
    final TaskParticipant exitedParticipant = new TaskParticipant();
    exitedParticipant.setId(71L);
    exitedParticipant.setTenantId(TENANT_ID);
    exitedParticipant.setTaskId(taskId);
    exitedParticipant.setParticipantRef(ACTOR_REF);
    exitedParticipant.setParticipantKind(ParticipantKind.PERSON);
    exitedParticipant.setCreatorFlag(false);
    exitedParticipant.setAssigneeFlag(true);
    exitedParticipant.setExitKind(ExitKind.HANDOVER);
    exitedParticipant.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(exitedParticipant));

    // Act + Assert: erneuter Austritt -> 400 (kein aktiver Teilnehmer)
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER)))
        .isInstanceOf(EntityValidationException.class);

    // Kein zweites EXIT_AT, keine zweite HANDOVER-Notification, kein Invalidate
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  // ---- Test 11: P2-5 Nebenlaeufigkeit - Wettlauf-Verlierer wird als 400 uebersetzt -----------

  @Test
  @DisplayName(
      "exit_nebenlaeufiger_konflikt_wird_als_400_uebersetzt: save -> BusinessRuleException +"
          + " Teilnehmer inzwischen ausgetreten -> EntityValidationException (400), keine Notification")
  void exit_nebenlaeufiger_konflikt_wird_als_400_uebersetzt() {
    // Arrange
    final Long taskId = 131L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Erst aktiver Nicht-Creator-Participant; nach dem (verlorenen) save ist er ausgetreten.
    final TaskParticipant active = new TaskParticipant();
    active.setId(80L);
    active.setTenantId(TENANT_ID);
    active.setTaskId(taskId);
    active.setParticipantRef(ACTOR_REF);
    active.setParticipantKind(ParticipantKind.PERSON);
    active.setAssigneeFlag(true);

    final TaskParticipant exitedAfterRace = new TaskParticipant();
    exitedAfterRace.setId(80L);
    exitedAfterRace.setTenantId(TENANT_ID);
    exitedAfterRace.setTaskId(taskId);
    exitedAfterRace.setParticipantRef(ACTOR_REF);
    exitedAfterRace.setParticipantKind(ParticipantKind.PERSON);
    exitedAfterRace.setAssigneeFlag(true);
    exitedAfterRace.setExitKind(ExitKind.UNSUBSCRIBED);
    exitedAfterRace.setExitAt(java.time.LocalDateTime.now());

    // Erster Lookup -> aktiv; Re-Check nach dem Konflikt -> ausgetreten
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(active), List.of(exitedAfterRace));
    // save verliert den Versionsvergleich -> KSF BusinessRuleException (409)
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("Versionskonflikt"));

    // Act + Assert: Wettlauf-Verlierer wird neutral als 400 abgelehnt
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER)))
        .isInstanceOf(EntityValidationException.class);

    // Keine Notification, kein Invalidate, kein UserState-Save
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
  }

  // ---- Test 12: unverwandter Konflikt (Teilnehmer bleibt aktiv) wird durchgereicht -----------

  @Test
  @DisplayName(
      "exit_unverwandter_konflikt_wird_durchgereicht: save -> BusinessRuleException +"
          + " Teilnehmer weiterhin aktiv -> BusinessRuleException propagiert (kein 400-Maskieren)")
  void exit_unverwandter_konflikt_wird_durchgereicht() {
    // Arrange
    final Long taskId = 132L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant active = new TaskParticipant();
    active.setId(81L);
    active.setTenantId(TENANT_ID);
    active.setTaskId(taskId);
    active.setParticipantRef(ACTOR_REF);
    active.setParticipantKind(ParticipantKind.PERSON);
    active.setAssigneeFlag(true);

    // Re-Check liefert eine FRISCHE, weiterhin aktive Zeile (EXIT_KIND null) - eigenes Objekt,
    // damit die vor save() gesetzte In-Memory-Mutation des ersten Lookups das Ergebnis nicht faelscht.
    final TaskParticipant stillActive = new TaskParticipant();
    stillActive.setId(81L);
    stillActive.setTenantId(TENANT_ID);
    stillActive.setTaskId(taskId);
    stillActive.setParticipantRef(ACTOR_REF);
    stillActive.setParticipantKind(ParticipantKind.PERSON);
    stillActive.setAssigneeFlag(true);

    // Erster Lookup -> aktiv; Re-Check nach dem Konflikt -> weiterhin aktiv
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(active), List.of(stillActive));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("unverwandter Konflikt"));

    // Act + Assert: kein Austritt feststellbar -> urspruengliche BusinessRuleException propagiert
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER)))
        .isInstanceOf(BusinessRuleException.class);

    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  // ---- Test RG1: Invalidate fuer ALLE aktiven Teilnehmer (nicht nur geaenderte Refs) -----------

  /**
   * Whole-Branch-Re-Gate (P2): Beim Hinzufuegen eines Assignees aendert sich
   * {@code participantSummaries} fuer JEDEN verbleibenden aktiven Teilnehmer, nicht nur fuer den
   * neu hinzugefuegten. Deshalb muss {@code invalidate} die Vereinigung aus geaenderten Refs UND
   * allen aktuell aktiven Teilnehmer-Refs der Aufgabe erhalten - sonst bleibt der Snapshot eines
   * unveraendert zugewiesenen Benutzers (A) ohne den neuen Teilnehmer (B) veraltet.
   */
  @Test
  @DisplayName(
      "invalidate_fuer_alle_aktiven_teilnehmer: Hinzufuegen von B invalidiert auch den bereits"
          + " aktiven A (participantSummaries aendern sich fuer beide)")
  void invalidate_fuer_alle_aktiven_teilnehmer() {
    // Arrange
    final Long taskId = 77L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID existingRefA = UUID.randomUUID();
    final UUID newRefB = ASSIGNEE_REF;
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // A ist bereits aktiver Assignee der Aufgabe (bleibt unveraendert in der Ziel-Menge).
    final TaskParticipant participantA = new TaskParticipant();
    participantA.setParticipantRef(existingRefA);
    participantA.setAssigneeFlag(true);
    participantA.setCreatorFlag(false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(participantA));

    // B ist neu -> kein vorhandener Participant.
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, newRefB))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Nach der Mutation sind A und B die aktiven Teilnehmer der Aufgabe.
    final TaskUserState stateA = new TaskUserState();
    stateA.setUserRef(existingRefA);
    final TaskUserState stateB = new TaskUserState();
    stateB.setUserRef(newRefB);
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(stateA, stateB));

    // Act: Ziel-Menge {A, B} -> A bleibt, B kommt neu hinzu.
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(existingRefA, newRefB)));

    // Assert: invalidate muss BEIDE Refs enthalten (A als Mitbetroffener + B als neu).
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue())
        .as("invalidate muss den neuen B UND den bereits aktiven A enthalten")
        .contains(existingRefA, newRefB);
  }

  // ---- Test RG1b: No-Op-PUT loest KEINE Invalidierung aus --------------------------------------

  /**
   * Whole-Branch-Re-Gate (P2, Folge-Auflage): Ein idempotenter PUT ohne tatsaechliche Aenderung
   * (Ziel-Menge == aktueller Stand, inkl. Creator-Schutz-No-Ops) darf WEDER aktive UserStates laden
   * NOCH eine Invalidierung senden - sonst entstehen vermeidbare DB-Last, Push-Traffic und
   * Client-Snapshot-Reloads. Guard auf {@code affectedRefs} (actual-change), analog
   * {@code TaskCommandService.patch}.
   */
  @Test
  @DisplayName(
      "noop_put_loest_keine_invalidierung_aus: Ziel-Menge == aktueller Stand -> kein invalidate,"
          + " kein Laden der aktiven UserStates")
  void noop_put_loest_keine_invalidierung_aus() {
    // Arrange
    final Long taskId = 88L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID existingRefA = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // A ist bereits aktiver Assignee; die Ziel-Menge ist identisch -> keine Aenderung.
    final TaskParticipant participantA = new TaskParticipant();
    participantA.setParticipantRef(existingRefA);
    participantA.setAssigneeFlag(true);
    participantA.setCreatorFlag(false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(participantA));

    // Act: idempotenter PUT mit identischer Ziel-Menge {A}.
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(existingRefA)));

    // Assert: weder Invalidierung noch Laden der aktiven UserStates - und KEIN Generation-Bump
    // (No-Op-PUT ist keine destruktive Membership-Aenderung).
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    verify(userStateQueryRepository, never()).findActiveUserRefsByTaskId(any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    // P2 #3: No-Op-PUT bumpt die TASK-Version NICHT (kein optimistischer Lock-Write ohne Aenderung).
    verify(taskRepository, never()).save(any(Task.class));
  }

  // ---- Test RG-exit: exit invalidiert Akteur UND verbleibende aktive Teilnehmer ----------------

  /**
   * Whole-Branch-Re-Gate (P2): Beim Verlassen einer Aufgabe entfernt sich der Akteur aus
   * {@code participantSummaries} - das veraendert den Snapshot JEDES verbleibenden aktiven
   * Teilnehmers. Deshalb muss {@code exit} den Akteur gemeinsam mit den verbleibenden aktiven
   * Teilnehmern invalidieren (analog {@code updateAssignees}), sonst behalten diese veraltete Daten.
   */
  @Test
  @DisplayName(
      "exit_invalidiert_auch_verbleibende_teilnehmer: Akteur verlaesst Aufgabe -> invalidate"
          + " enthaelt Akteur UND verbleibenden aktiven Teilnehmer B")
  void exit_invalidiert_auch_verbleibende_teilnehmer() {
    // Arrange
    final Long taskId = 91L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID remainingRefB = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Akteur ist aktiver Nicht-Creator-Teilnehmer (darf austreten).
    final TaskParticipant actorParticipant = new TaskParticipant();
    actorParticipant.setParticipantRef(ACTOR_REF);
    actorParticipant.setParticipantKind(ParticipantKind.PERSON);
    actorParticipant.setAssigneeFlag(true);
    actorParticipant.setCreatorFlag(false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actorParticipant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final TaskUserState actorState = new TaskUserState();
    actorState.setUserRef(ACTOR_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actorState));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Nach dem Austritt ist B der verbleibende aktive Teilnehmer.
    final TaskUserState stateB = new TaskUserState();
    stateB.setUserRef(remainingRefB);
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(stateB));

    // Act
    service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED));

    // Assert: invalidate muss Akteur UND verbleibenden Teilnehmer B enthalten.
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue())
        .as("invalidate muss den austretenden Akteur UND den verbleibenden aktiven B enthalten")
        .contains(ACTOR_REF, remainingRefB);
  }

  // ============================================================================================
  // BE-7: exit() Handover (handedOverToIds + shouldStay) - Unit U1-U13
  // ============================================================================================

  @Test
  @DisplayName(
      "U1: HANDOVER 2 Ziele shouldStay=false Nicht-Creator -> Akteur ASSIGNEE_FLAG=false +"
          + " EXIT=HANDOVER; Ziele Assignee; 1 Event + 2 Targets; 2 Ziel-Notify + 1 Selbst;"
          + " LAST_ACTIVITY_AT + VERSION-save; History")
  void u1_handover_zwei_ziele_nicht_creator() {
    final Long taskId = 300L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final UUID t2 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    // Ziele: keine bestehende Zeile -> neu als Assignee.
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1)).thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t2)).thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1, t2), false));

    // 1 Handover-Ereignis + 2 Targets
    verify(handoverRepository).save(any(TaskHandover.class));
    verify(handoverTargetRepository, times(2)).save(any());
    // 2 Ziel-Notify (HANDOVER) + 1 Selbst-Notify (HANDOVER) = 3 HANDOVER-Emits
    verify(notificationService, times(3)).emit(
        any(), eq(taskId), eq(NotificationTypeCodes.HANDOVER), eq(NotificationSeverityCodes.INFO),
        any(), any(), eq(ACTOR_REF));
    // Akteur-Save: ASSIGNEE_FLAG=false + EXIT=HANDOVER
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, org.mockito.Mockito.atLeastOnce()).save(pCaptor.capture());
    final TaskParticipant savedActor =
        pCaptor.getAllValues().stream()
            .filter(p -> ACTOR_REF.equals(p.getParticipantRef()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(savedActor.isAssigneeFlag()).isFalse();
    assertThat(savedActor.getExitKind()).isEqualTo(ExitKind.HANDOVER);
    assertThat(savedActor.getExitAt()).isNotNull();
    // LAST_ACTIVITY_AT + VERSION-save; History
    assertThat(task.getLastActivityAt()).isNotNull();
    verify(taskRepository).save(task);
    verify(taskHistoryService).record(eq(taskId), eq(TENANT_ID), eq(ACTOR_REF), any(), any());
    // Ziel-Bumps + Akteur-Bump
    verify(inboxGenerationService).bump(TENANT_ID, t1);
    verify(inboxGenerationService).bump(TENANT_ID, t2);
    verify(inboxGenerationService).bump(TENANT_ID, ACTOR_REF);
  }

  @Test
  @DisplayName(
      "U2: shouldStay=true Nicht-Creator -> Akteur bleibt (ASSIGNEE_FLAG=false, kein EXIT);"
          + " Ziele Assignee; VERSION-save; kein Akteur-Bump; History 'uebergeben'")
  void u2_handover_stay_nicht_creator() {
    final Long taskId = 301L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1)).thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), true));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, org.mockito.Mockito.atLeastOnce()).save(pCaptor.capture());
    final TaskParticipant savedActor =
        pCaptor.getAllValues().stream()
            .filter(p -> ACTOR_REF.equals(p.getParticipantRef()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(savedActor.isAssigneeFlag()).isFalse();
    assertThat(savedActor.getExitKind()).as("shouldStay -> kein Austritt").isNull();
    verify(taskRepository).save(task);
    // Kein UNSUBSCRIBED_AT, kein Akteur-Bump (kein Austritt).
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
    verify(inboxGenerationService, never()).bump(TENANT_ID, ACTOR_REF);
    verify(taskHistoryService).record(eq(taskId), eq(TENANT_ID), eq(ACTOR_REF), any(), any());
  }

  @Test
  @DisplayName(
      "U3: zwei Stay-Handover desselben Akteurs (versch. Ziele) -> ZWEI Ereignisse; Ziele NICHT"
          + " vermischt (t1->Ereignis1, t2->Ereignis2)")
  void u3_zwei_stay_handover_ziele_nicht_vermischt() {
    final Long taskId = 303L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final UUID t2 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // shouldStay=true -> der Akteur bleibt aktiv und kann ein zweites Mal uebergeben.
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1)).thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t2)).thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    // Distinkte Ereignis-IDs je save (statt konstant), um Vermischen zweifelsfrei auszuschliessen.
    final java.util.concurrent.atomic.AtomicLong seq =
        new java.util.concurrent.atomic.AtomicLong(9000L);
    when(handoverRepository.save(any(TaskHandover.class)))
        .thenAnswer(
            inv -> {
              final TaskHandover e = inv.getArgument(0);
              e.setId(seq.incrementAndGet());
              return e;
            });

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), true));
    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t2), true));

    // ZWEI getrennte Ereignisse desselben Akteurs, beide shouldStay=true.
    final ArgumentCaptor<TaskHandover> eventCaptor = ArgumentCaptor.forClass(TaskHandover.class);
    verify(handoverRepository, times(2)).save(eventCaptor.capture());
    final List<TaskHandover> events = eventCaptor.getAllValues();
    assertThat(events).extracting(TaskHandover::getFromRef).containsExactly(ACTOR_REF, ACTOR_REF);
    assertThat(events).allSatisfy(e -> assertThat(e.isShouldStay()).isTrue());
    final Long event1Id = events.get(0).getId();
    final Long event2Id = events.get(1).getId();
    assertThat(event1Id).as("verschiedene Ereignis-IDs").isNotEqualTo(event2Id);

    // Ziele NICHT vermischt: t1 haengt an Ereignis1, t2 an Ereignis2.
    final ArgumentCaptor<TaskHandoverTarget> targetCaptor =
        ArgumentCaptor.forClass(TaskHandoverTarget.class);
    verify(handoverTargetRepository, times(2)).save(targetCaptor.capture());
    final List<TaskHandoverTarget> targets = targetCaptor.getAllValues();
    final TaskHandoverTarget target1 =
        targets.stream().filter(t -> t1.equals(t.getToRef())).findFirst().orElseThrow();
    final TaskHandoverTarget target2 =
        targets.stream().filter(t -> t2.equals(t.getToRef())).findFirst().orElseThrow();
    assertThat(target1.getHandoverId()).as("t1 gehoert zu Ereignis1").isEqualTo(event1Id);
    assertThat(target2.getHandoverId()).as("t2 gehoert zu Ereignis2").isEqualTo(event2Id);
  }

  @Test
  @DisplayName(
      "U4: Reaktivierung ueber den ECHTEN Pfad (updateAssignees) nach Austritt -> erneuter"
          + " Handover-Austritt -> NEUES Ereignis, EXIT erneut gesetzt")
  void u4_reaktivierung_dann_erneuter_austritt() {
    final Long taskId = 304L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final UUID t2 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1)).thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t2)).thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    final java.util.concurrent.atomic.AtomicLong seq =
        new java.util.concurrent.atomic.AtomicLong(9000L);
    when(handoverRepository.save(any(TaskHandover.class)))
        .thenAnswer(
            inv -> {
              final TaskHandover e = inv.getArgument(0);
              e.setId(seq.incrementAndGet());
              return e;
            });

    // 1. Austritt per Handover (!shouldStay) -> EXIT_KIND=HANDOVER.
    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false));
    assertThat(actor.getExitKind()).as("nach erstem Austritt EXIT=HANDOVER").isEqualTo(ExitKind.HANDOVER);

    // 2. ECHTER Reaktivierungspfad: erneute Zuweisung via updateAssignees -> der reactivate-Zweig
    //    (ASSIGNEE_FLAG=true, EXIT_KIND=null) laeuft auf DERSELBEN Participant-Zeile (kein manueller
    //    Feld-Reset). Nach dem Austritt ist ACTOR kein aktiver Assignee mehr -> toAdd={ACTOR}.
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ACTOR_REF)));
    assertThat(actor.getExitKind()).as("Reaktivierung via updateAssignees -> wieder aktiv").isNull();
    assertThat(actor.isAssigneeFlag()).as("Reaktivierung -> ASSIGNEE_FLAG=true").isTrue();

    // 3. Erneuter Austritt per Handover -> NEUES Ereignis + EXIT erneut gesetzt.
    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t2), false));

    final ArgumentCaptor<TaskHandover> eventCaptor = ArgumentCaptor.forClass(TaskHandover.class);
    verify(handoverRepository, times(2)).save(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .extracting(TaskHandover::getId)
        .as("zwei verschiedene Ereignisse (Reaktivierung erzeugt KEIN Merge)")
        .doesNotHaveDuplicates();
    assertThat(actor.getExitKind())
        .as("nach erneutem Austritt wieder EXIT=HANDOVER").isEqualTo(ExitKind.HANDOVER);
    assertThat(actor.getExitAt()).isNotNull();
  }

  @Test
  @DisplayName("U5: Creator HANDOVER shouldStay=true -> Ziele Assignee; Creator ASSIGNEE_FLAG=false,"
      + " CREATOR+PARTICIPANT+UserState aktiv; kein 409")
  void u5_creator_handover_stay() {
    final Long taskId = 305L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant creator = new TaskParticipant();
    creator.setId(1L);
    creator.setTaskId(taskId);
    creator.setParticipantRef(ACTOR_REF);
    creator.setParticipantKind(ParticipantKind.PERSON);
    creator.setCreatorFlag(true);
    creator.setAssigneeFlag(true);
    creator.setParticipantFlag(true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(creator));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1)).thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), true));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, org.mockito.Mockito.atLeastOnce()).save(pCaptor.capture());
    final TaskParticipant savedCreator =
        pCaptor.getAllValues().stream()
            .filter(p -> ACTOR_REF.equals(p.getParticipantRef()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(savedCreator.isAssigneeFlag()).isFalse();
    assertThat(savedCreator.isCreatorFlag()).isTrue();
    assertThat(savedCreator.isParticipantFlag()).isTrue();
    assertThat(savedCreator.getExitKind()).isNull();
    // Kein Akteur-Austritt -> kein UNSUBSCRIBED_AT, kein Akteur-Bump.
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
    verify(inboxGenerationService, never()).bump(TENANT_ID, ACTOR_REF);
  }

  @Test
  @DisplayName("U6: Creator HANDOVER shouldStay=false -> 409; Creator UNSUBSCRIBED -> 409")
  void u6_creator_ohne_stay_409() {
    final Long taskId = 306L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant creator = new TaskParticipant();
    creator.setTaskId(taskId);
    creator.setParticipantRef(ACTOR_REF);
    creator.setCreatorFlag(true);
    creator.setAssigneeFlag(true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(creator));

    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(), false)))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED)))
        .isInstanceOf(BusinessRuleException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(handoverRepository, never()).save(any());
  }

  @Test
  @DisplayName("U7: UNSUBSCRIBED mit Zielen/shouldStay -> 400 (keine Mutation)")
  void u7_unsubscribed_mit_zielen_400() {
    final Long taskId = 307L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    assertThatThrownBy(
            () ->
                service.exit(
                    taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED, List.of(UUID.randomUUID()), false)))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED, List.of(), true)))
        .isInstanceOf(EntityValidationException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
  }

  @Test
  @DisplayName("U8: HANDOVER shouldStay=true leere Ziele -> 400 (auch Creator: leer-stay 400 VOR 409)")
  void u8_stay_leere_ziele_400() {
    final Long taskId = 308L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // Creator-Participant: leer-stay muss 400 (Eingabe) VOR 409 (Creator) werfen.
    final TaskParticipant creator = new TaskParticipant();
    creator.setTaskId(taskId);
    creator.setParticipantRef(ACTOR_REF);
    creator.setCreatorFlag(true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(creator));

    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(), true)))
        .isInstanceOf(EntityValidationException.class);
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
  }

  @Test
  @DisplayName("U9: HANDOVER shouldStay=false leere Ziele Nicht-Creator -> Selbst-Handover (EXIT, Selbst-Notify)")
  void u9_selbst_handover_leer() {
    final Long taskId = 309L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(), false));

    // Event ohne Targets; EXIT=HANDOVER; 1 Selbst-Notify (keine Ziel-Notify).
    verify(handoverRepository).save(any(TaskHandover.class));
    verify(handoverTargetRepository, never()).save(any());
    verify(notificationService, times(1)).emit(
        eq(ACTOR_REF), eq(taskId), eq(NotificationTypeCodes.HANDOVER), any(), any(), any(), eq(ACTOR_REF));
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    assertThat(pCaptor.getValue().getExitKind()).isEqualTo(ExitKind.HANDOVER);
  }

  @Test
  @DisplayName("U10: Self-Ziel -> 400; unbekannt/fremd -> 400 (keine Mutation)")
  void u10_self_und_unbekannt_400() {
    final Long taskId = 310L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Self-Ziel -> 400 (vor Participant-Load).
    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(ACTOR_REF), false)))
        .isInstanceOf(EntityValidationException.class);

    // Unbekanntes Ziel -> 400. findActiveByUuids liefert den Ref NICHT.
    final UUID unknown = UUID.randomUUID();
    org.mockito.Mockito.doReturn(List.of()).when(userReadRepository).findActiveByUuids(any());
    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(unknown), false)))
        .isInstanceOf(EntityValidationException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(handoverRepository, never()).save(any());
  }

  @Test
  @DisplayName("U11: Ziel bereits Assignee -> idempotent (ASSIGNEE_FLAG bleibt true), trotzdem TARGET + Notify")
  void u11_ziel_bereits_assignee_idempotent() {
    final Long taskId = 311L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    // t1 ist bereits aktiver Assignee.
    final TaskParticipant existingTarget = activeAssignee(taskId, t1, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of(existingTarget));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false));

    // TARGET-Row + Ziel-Notify trotz bereits bestehender Assignee-Zeile.
    verify(handoverTargetRepository, times(1)).save(any());
    verify(notificationService, times(1)).emit(
        eq(t1), eq(taskId), eq(NotificationTypeCodes.HANDOVER), any(), any(), any(), eq(ACTOR_REF));
    assertThat(existingTarget.isAssigneeFlag()).as("bleibt Assignee").isTrue();
  }

  @Test
  @DisplayName("U12: Doppel-Austritt (EXIT_KIND gesetzt) / Nicht-Teilnehmer -> 400")
  void u12_doppel_austritt_400() {
    final Long taskId = 312L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Nicht-Teilnehmer -> 400.
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED)))
        .isInstanceOf(EntityValidationException.class);

    // Bereits ausgetreten -> 400.
    final TaskParticipant exited = activeAssignee(taskId, ACTOR_REF, false);
    exited.setExitKind(ExitKind.HANDOVER);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(exited));
    assertThatThrownBy(() -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED)))
        .isInstanceOf(EntityValidationException.class);

    verify(handoverRepository, never()).save(any());
  }

  @Test
  @DisplayName("U-dedup: HANDOVER mit [t1, t1] -> genau 1 TARGET + 1 Ziel-Notify + 1 Ziel-Bump (Dedup)")
  void u_dedup_ziele() {
    final Long taskId = 313L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1)).thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.save(any(TaskUserState.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1, t1), false));

    verify(handoverTargetRepository, times(1)).save(any());
    verify(inboxGenerationService, times(1)).bump(TENANT_ID, t1);
    verify(notificationService, times(1)).emit(
        eq(t1), eq(taskId), eq(NotificationTypeCodes.HANDOVER), any(), any(), any(), eq(ACTOR_REF));
  }

  @Test
  @DisplayName("U13a: per Handover de-assignter Creator bleibt bei PUT-ohne-ihn aktiver Participant (kein EXIT)")
  void u13a_handover_deassignter_creator_bleibt_bei_put() {
    // Creator wurde per Handover shouldStay=true de-assignt: CREATOR+PARTICIPANT aktiv, ASSIGNEE_FLAG=false.
    // Ein PUT ohne ihn findet ihn NICHT in findActiveAssigneesByTaskId -> kein toRemove -> kein EXIT.
    final Long taskId = 314L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // Keine aktiven Assignees (Creator ist de-assignt -> nicht in findActiveAssigneesByTaskId).
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of()));

    // Kein Save (Creator wird von PUT nicht angefasst), kein EXIT.
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(taskRepository, never()).save(any(Task.class));
  }

  // ============================================================================================
  // BE-6: addParticipants (additiv, idempotent, ausschliesslich Teilnehmer-Rolle)
  // ============================================================================================

  @Test
  @DisplayName(
      "addParticipants_neuer_ref: neuer Ref -> Teilnehmer (PARTICIPANT_FLAG=1, kein Assignee/Creator)"
          + " + ensure + bump + invalidate; KEINE Notification, KEIN Task-VERSION-Bump")
  void addParticipants_neuer_ref() {
    final Long taskId = 200L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.isParticipantFlag()).as("PARTICIPANT_FLAG=1").isTrue();
    assertThat(saved.isAssigneeFlag()).as("ASSIGNEE_FLAG=0 (nie Assignee)").isFalse();
    assertThat(saved.isCreatorFlag()).as("CREATOR_FLAG=0").isFalse();
    assertThat(saved.getParticipantKind()).isEqualTo(ParticipantKind.PERSON);
    assertThat(saved.getParticipantRef()).isEqualTo(ref);
    assertThat(saved.getExitKind()).isNull();

    verify(userStateService).ensure(eq(taskId), eq(ref), eq(TENANT_ID));
    verify(inboxGenerationService).bump(TENANT_ID, ref);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue()).contains(ref);

    // D5: keine Notification; D8: kein Task-VERSION-Bump (kommutative Union).
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(taskRepository, never()).save(any(Task.class));
  }

  @Test
  @DisplayName(
      "addParticipants_aktiver_ref_skip: bereits aktiver Ref (Creator/Assignee/Teilnehmer)"
          + " -> idempotenter SKIP (kein save/ensure/bump/invalidate)")
  void addParticipants_aktiver_ref_skip() {
    final Long taskId = 201L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant active = new TaskParticipant();
    active.setId(1L);
    active.setTaskId(taskId);
    active.setParticipantRef(ref);
    active.setParticipantFlag(true); // EXIT_KIND null -> aktiv
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(active));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateService, never()).ensure(any(), any(), any());
    verify(userStateService, never()).reactivate(any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  @Test
  @DisplayName(
      "addParticipants_exited_ref_reactivate: ausgetretene Zeile -> als reiner Teilnehmer reaktiviert"
          + " (PARTICIPANT_FLAG=1, EXIT_KIND null) + reactivate + bump")
  void addParticipants_exited_ref_reactivate() {
    final Long taskId = 202L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant exited = new TaskParticipant();
    exited.setId(2L);
    exited.setTaskId(taskId);
    exited.setParticipantRef(ref);
    exited.setParticipantFlag(true);
    exited.setAssigneeFlag(false);
    exited.setExitKind(ExitKind.UNSUBSCRIBED);
    exited.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(exited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.isParticipantFlag()).isTrue();
    assertThat(saved.getExitKind()).as("EXIT_KIND zurueckgesetzt").isNull();
    assertThat(saved.getExitAt()).isNull();

    verify(userStateService).reactivate(eq(taskId), eq(ref), eq(TENANT_ID));
    verify(inboxGenerationService).bump(TENANT_ID, ref);
  }

  @Test
  @DisplayName(
      "addParticipants_exited_ex_assignee_clears_flag (D4-BLOCKER): exited Ex-Assignee"
          + " -> reaktiviert mit ASSIGNEE_FLAG=false")
  void addParticipants_exited_ex_assignee_clears_flag() {
    final Long taskId = 203L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant exitedAssignee = new TaskParticipant();
    exitedAssignee.setId(3L);
    exitedAssignee.setTaskId(taskId);
    exitedAssignee.setParticipantRef(ref);
    exitedAssignee.setAssigneeFlag(true); // war Assignee
    exitedAssignee.setParticipantFlag(true);
    exitedAssignee.setExitKind(ExitKind.UNSUBSCRIBED);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(exitedAssignee));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.isAssigneeFlag())
        .as("ASSIGNEE_FLAG muss geloescht sein (Add-Participants verleiht nie Assignee)")
        .isFalse();
    assertThat(saved.isParticipantFlag()).isTrue();
    assertThat(saved.getExitKind()).isNull();
  }

  @Test
  @DisplayName("addParticipants_unbekannter_ref_400: unbekannter Ref -> 400, keine Mutation")
  void addParticipants_unbekannter_ref_400() {
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(204L, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // unbekannt -> findActiveByUuids liefert den Ref NICHT. doReturn (statt when/thenReturn), weil der
    // setUp-Stub thenAnswer nutzt: ein when(...)-Re-Stub wuerde die alte Answer-Lambda ausloesen
    // (verschachteltes when()) -> Mockito-Fehler.
    org.mockito.Mockito.doReturn(List.of())
        .when(userReadRepository)
        .findActiveByUuids(any());

    assertThatThrownBy(
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref))))
        .isInstanceOf(EntityValidationException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(inboxGenerationService, never()).bump(anyLong(), any());
  }

  @Test
  @DisplayName("addParticipants_leeres_array_noop: [] -> No-Op (keine Validierung/Mutation/Signal)")
  void addParticipants_leeres_array_noop() {
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(205L, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of()));

    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  @Test
  @DisplayName(
      "addParticipants_race_catch_skip (Auflage 3): save -> BRE(cause=DuplicateKey) + Re-Read liefert"
          + " aktiven Gewinner -> idempotenter SKIP (kein ensure/bump/invalidate, keine Exception)")
  void addParticipants_race_catch_skip() {
    final Long taskId = 206L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant winner = new TaskParticipant();
    winner.setId(9L);
    winner.setTaskId(taskId);
    winner.setParticipantRef(ref);
    winner.setParticipantFlag(true); // aktiver Gewinner, EXIT_KIND null
    // Erst leer (neu-Pfad), nach Konflikt Re-Read -> Gewinner-Zeile.
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(), List.of(winner));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("UNIQUE", new DuplicateKeyException("dup")));

    // darf NICHT werfen (idempotenter SKIP)
    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    verify(userStateService, never()).ensure(any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  @Test
  @DisplayName(
      "addParticipants_race_recheck_leer_rethrow: save -> BRE(cause=DuplicateKey) + Re-Read leer"
          + " -> urspruengliche Exception propagiert (kein stilles Schlucken)")
  void addParticipants_race_recheck_leer_rethrow() {
    final Long taskId = 207L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(), List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("UNIQUE", new DuplicateKeyException("dup")));

    assertThatThrownBy(
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref))))
        .isInstanceOf(BusinessRuleException.class);
  }

  @Test
  @DisplayName(
      "addParticipants_unverwandte_ursache_rethrow (Auflage 3): BRE OHNE DuplicateKey/OptimisticLock-"
          + "cause TROTZ passendem Re-Read -> rethrow (nur passender Konflikt wird zu SKIP)")
  void addParticipants_unverwandte_ursache_rethrow() {
    final Long taskId = 208L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant activeWinner = new TaskParticipant();
    activeWinner.setId(10L);
    activeWinner.setTaskId(taskId);
    activeWinner.setParticipantRef(ref);
    activeWinner.setParticipantFlag(true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(), List.of(activeWinner));
    // Unverwandte BusinessRuleException (z.B. Validator-Guard) OHNE DAO-Ursache.
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("unverwandter Validator-Verstoss"));

    assertThatThrownBy(
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref))))
        .isInstanceOf(BusinessRuleException.class);

    verify(userStateService, never()).ensure(any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
  }

  @Test
  @DisplayName(
      "addParticipants_dedupliziert: [ref, ref] -> genau ein save/bump (Duplikate zusammengefuehrt)")
  void addParticipants_dedupliziert() {
    final Long taskId = 209L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref, ref)));

    verify(participantQueryRepository).save(any(TaskParticipant.class)); // genau 1x
    verify(inboxGenerationService).bump(TENANT_ID, ref); // genau 1x
  }

  // ---- Hilfsmethode ---------------------------------------------------------------------------

  private static Task taskWithId(final Long id, final UUID uuid) {
    final Task task = new Task();
    task.setId(id);
    task.setUuid(uuid);
    task.setTenantId(TENANT_ID);
    task.setEntityState(EntityState.ACTIVE);
    task.setTitle("Testtitel");
    task.setLastActivityAt(java.time.LocalDateTime.now());
    task.setCommentCount(0);
    return task;
  }

  /** Aktiver (Nicht-Creator-)Assignee-Participant fuer die BE-7-Exit-Unit-Tests. */
  private static TaskParticipant activeAssignee(
      final Long taskId, final UUID ref, final boolean creator) {
    final TaskParticipant p = new TaskParticipant();
    p.setId(1L);
    p.setTenantId(TENANT_ID);
    p.setTaskId(taskId);
    p.setParticipantRef(ref);
    p.setParticipantKind(ParticipantKind.PERSON);
    p.setCreatorFlag(creator);
    p.setAssigneeFlag(true);
    p.setParticipantFlag(true);
    return p;
  }

  private static TaskUserState userStateFor(final Long taskId, final UUID ref) {
    final TaskUserState s = new TaskUserState();
    s.setId(1L);
    s.setTaskId(taskId);
    s.setUserRef(ref);
    return s;
  }
}
