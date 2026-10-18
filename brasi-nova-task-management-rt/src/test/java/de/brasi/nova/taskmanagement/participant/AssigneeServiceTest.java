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
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
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
            userAccessor);

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

  // ---- Test 6: MAJOR 1 - Creator wird nie abgewaehlt ----------------------------------------

  @Test
  @DisplayName(
      "creator_nicht_abwaehlbar: PUT ohne Creator -> Creator-Participant kein EXIT_KIND gesetzt")
  void creator_nicht_abwaehlbar() {
    // Arrange
    final Long taskId = 55L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);

    // Creator-Participant (creatorFlag=true, assigneeFlag=true)
    final TaskParticipant creatorParticipant = new TaskParticipant();
    creatorParticipant.setId(20L);
    creatorParticipant.setTenantId(TENANT_ID);
    creatorParticipant.setTaskId(taskId);
    creatorParticipant.setParticipantRef(ACTOR_REF);
    creatorParticipant.setParticipantKind(ParticipantKind.PERSON);
    creatorParticipant.setCreatorFlag(true);
    creatorParticipant.setAssigneeFlag(true);

    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    // Aktuelle Assignees: nur Creator
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(creatorParticipant));

    // Act: PUT mit leerer Menge (Creator fehlt in desired)
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of()));

    // Assert: save wurde NIE fuer den Creator-Participant aufgerufen (kein EXIT_KIND gesetzt)
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    // UserStateQueryRepository: kein UNSUBSCRIBED_AT fuer Creator
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
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

  // ---- Test 8: MINOR - null-assignees wirft EntityValidationException ----------------------

  @Test
  @DisplayName("null_assignees_wirft_400: AssigneeUpdateInput mit null -> EntityValidationException")
  void null_assignees_wirft_400() {
    assertThatThrownBy(() -> new AssigneeUpdateInput(null))
        .isInstanceOf(
            io.keel.framework.api.validation.EntityValidationException.class)
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

    // Keine Mutation, keine Notification, kein Invalidate
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
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

    // Assert: weder Invalidierung noch Laden der aktiven UserStates.
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    verify(userStateQueryRepository, never()).findActiveUserRefsByTaskId(any());
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
}
