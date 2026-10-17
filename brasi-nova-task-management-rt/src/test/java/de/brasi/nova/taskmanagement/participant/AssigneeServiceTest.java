package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.validation.EntityValidationException;
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
  @Mock private UserAccessor userAccessor;

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
            userAccessor);

    // CurrentActor: Mandant + Benutzer
    final TenantInfo tenant = new TenantInfo(TENANT_UUID, TENANT_ID);
    final AuthUser user = new AuthUser(ACTOR_REF, "actor@brasi.de");
    when(userAccessor.requireCurrentUser()).thenReturn(user);
    TenantContextHolder.setTenant(tenant);

    // Notification-Service: Stub fuer emit (gibt UUID zurueck)
    when(notificationService.emit(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(UUID.randomUUID());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---- Test 1: Zuweisung -> ASSIGNEE_FLAG + UserState.ensure + ASSIGN-Notification + invalidate ---

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

    // UserState.ensure aufgerufen
    verify(userStateService).ensure(eq(taskId), eq(ASSIGNEE_REF), eq(TENANT_ID));

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

    // Kein UserState (Best-Effort)
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of());

    // Act
    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER));

    // Assert: EXIT_KIND=HANDOVER + EXIT_AT gesetzt
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.getExitKind()).as("EXIT_KIND muss HANDOVER sein").isEqualTo(ExitKind.HANDOVER);
    assertThat(saved.getExitAt()).as("EXIT_AT muss gesetzt sein").isNotNull();

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

  // ---- Hilfsmethode ---------------------------------------------------------------------------

  private static Task taskWithId(final Long id, final UUID uuid) {
    final Task task = new Task();
    task.setId(id);
    task.setUuid(uuid);
    task.setTenantId(TENANT_ID);
    task.setTitle("Testtitel");
    task.setLastActivityAt(java.time.LocalDateTime.now());
    task.setCommentCount(0);
    return task;
  }
}
