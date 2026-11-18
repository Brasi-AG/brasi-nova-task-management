package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.security.permission.PermissionDeniedException;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit-Tests fuer {@link AssigneeService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer Assignee-Zuweisung und Austritt:
 *
 * <ol>
 *   <li>Neuer Assignee -> ASSIGNEE_FLAG gesetzt + UserState.ensure + ASSIGN-Notification +
 *       invalidate
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
  @Mock private TaskMembershipGuard membershipGuard;
  @Mock private PermissionEvaluator permissionEvaluator;

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
            taskHistoryService,
            membershipGuard,
            permissionEvaluator);

    // Slice-4-Baseline: bestehende BE-7-HANDOVER-Unit-Tests liefen implizit hinter dem
    // controller-seitigen @RequiresPermission(TASK_WRITE)-Gate (ihre HANDOVER-Ziele sind neu, also
    // Nicht-WRITE-Member). Mit dem programmatischen Grant-Check im Service wird dieselbe
    // TASK_WRITE-Realitaet durch den Default-Stub abgebildet; Slice-4-Tests zur Handover-Grenze
    // ueberschreiben diese Stubs gezielt. requireWriteMember (Akteur-Guard) ist per Mockito-Default
    // ein No-Op (void) -> wirft nicht; die 404-Faelle stubben es explizit auf throw.
    when(permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenReturn(true);

    // ensureAssignee waehlt die Ziel-Zeile seit Slice 4 Unique-Key-exakt via findRowByUniqueKey
    // (NICHT mehr findByTaskIdAndParticipantRef). Default: Unique-Key frei -> INSERT-Pfad (neue
    // PERSON-Assignee-Zeile), passend zu den bestehenden BE-7-HANDOVER-Tests mit neuen Zielen.
    // Tests, die eine vorhandene Ziel-Zeile (reaktivieren / TEAM-409 / fremd-Tenant-409) pruefen,
    // ueberschreiben diesen Default gezielt.
    when(participantQueryRepository.findRowByUniqueKey(anyLong(), any())).thenReturn(List.of());

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

    // Codex #6: Multi-Ref-Bumps laufen jetzt ueber den zentralen Helfer bumpSorted (delegiert
    // produktiv
    // je Ref sortiert an bump). Der gemockte Helfer an denselben Mock delegieren, damit die
    // bestehenden
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

  // ---- Test 1: Zuweisung -> ASSIGNEE_FLAG + UserState.reactivate + ASSIGN-Notification +
  // invalidate ---

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
    assertThat(saved.getParticipantRef())
        .as("PARTICIPANT_REF muss ASSIGNEE_REF sein")
        .isEqualTo(ASSIGNEE_REF);
    assertThat(saved.getParticipantKind())
        .as("PARTICIPANT_KIND muss PERSON sein")
        .isEqualTo(ParticipantKind.PERSON);

    // UserState als Zuweisung reaktiviert (MAJOR 3: UNSUBSCRIBED_AT zurueck; #158: Mute aufgehoben)
    verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(ASSIGNEE_REF), eq(TENANT_ID), any());
    verify(userStateService, never()).reactivate(any(), any(), any(), any());

    // ASSIGN-Notification emittiert
    verify(notificationService)
        .emit(
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

  @Test
  @DisplayName(
      "lock_order_update_assignees_state_read_vor_task_save (Intake #156/#167): die Lesung der"
          + " aktiven States fuer Bump und Signal laeuft VOR der ersten Teilnehmer-Schreibung und"
          + " damit vor taskRepository.save, danach erst der sortierte Bump (sonst wartet die"
          + " Lesung auf die Zeilen eines parallelen Aufrufs -> Deadlock)")
  void lock_order_update_assignees_state_read_vor_task_save() {
    final Long taskId = 43L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ASSIGNEE_REF)));

    final InOrder order =
        inOrder(
            participantQueryRepository,
            userStateQueryRepository,
            taskRepository,
            inboxGenerationService);
    order.verify(userStateQueryRepository).findActiveUserRefsByTaskId(taskId);
    order.verify(participantQueryRepository).save(any(TaskParticipant.class));
    order.verify(taskRepository).save(task);
    order.verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), any());
  }

  // ---- Intake #156: gleiche Sperr-Reihenfolge (UUID aufsteigend) in allen mehrzeiligen Pfaden ---

  /**
   * Zwei UUIDs, deren HashSet-Reihenfolge der UUID-Sortierung entgegenlaeuft: ORDER_HIGH liegt im
   * HashSet (Standardgroesse) vor ORDER_LOW. Eine Verarbeitung ueber ein HashSet wuerde den Test
   * also rot machen.
   */
  private static final UUID ORDER_LOW = UUID.fromString("00000059-00f0-0059-0000-000000000595");

  private static final UUID ORDER_HIGH = UUID.fromString("00000059-00f1-0059-0000-000000000595");

  @Test
  @DisplayName(
      "lock_order_update_assignees_neue_personen_aufsteigend (Intake #156): neue Assignees werden"
          + " nach UUID sortiert geschrieben (Teilnehmer- und State-Zeile je Person), unabhaengig"
          + " von der Reihenfolge der Eingabemenge")
  void lock_order_update_assignees_neue_personen_aufsteigend() {
    final Long taskId = 44L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(anyLong(), any()))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ORDER_HIGH, ORDER_LOW)));

    final ArgumentCaptor<TaskParticipant> saves = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, times(2)).save(saves.capture());
    assertThat(saves.getAllValues().stream().map(TaskParticipant::getParticipantRef).toList())
        .containsExactly(ORDER_LOW, ORDER_HIGH);
    final InOrder order = inOrder(userStateService);
    order
        .verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(ORDER_LOW), eq(TENANT_ID), any());
    order
        .verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(ORDER_HIGH), eq(TENANT_ID), any());
  }

  @Test
  @DisplayName(
      "lock_order_update_assignees_abgewaehlte_personen_aufsteigend (Intake #156): abgewaehlte"
          + " Assignees werden nach UUID sortiert ausgetreten")
  void lock_order_update_assignees_abgewaehlte_personen_aufsteigend() {
    final Long taskId = 45L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    final TaskParticipant high = activeAssignee(taskId, ORDER_HIGH, false);
    final TaskParticipant low = activeAssignee(taskId, ORDER_LOW, false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(high, low));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ORDER_HIGH))
        .thenReturn(List.of(high));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ORDER_LOW))
        .thenReturn(List.of(low));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of()));

    final ArgumentCaptor<TaskParticipant> saves = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, times(2)).save(saves.capture());
    assertThat(saves.getAllValues().stream().map(TaskParticipant::getParticipantRef).toList())
        .containsExactly(ORDER_LOW, ORDER_HIGH);
  }

  @Test
  @DisplayName(
      "lock_order_exit_handover_ziele_aufsteigend (Intake #156): Uebergabeziele werden nach UUID"
          + " sortiert geschrieben (Ziel-Zeile, Teilnehmer, State, Benachrichtigung), unabhaengig"
          + " von der Eingabereihenfolge")
  void lock_order_exit_handover_ziele_aufsteigend() {
    final Long taskId = 46L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(ORDER_HIGH, ORDER_LOW), true));

    final ArgumentCaptor<TaskHandoverTarget> targets =
        ArgumentCaptor.forClass(TaskHandoverTarget.class);
    verify(handoverTargetRepository, times(2)).save(targets.capture());
    assertThat(targets.getAllValues().stream().map(TaskHandoverTarget::getToRef).toList())
        .containsExactly(ORDER_LOW, ORDER_HIGH);
    final InOrder order = inOrder(userStateService, notificationService);
    order
        .verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(ORDER_LOW), eq(TENANT_ID), any());
    order
        .verify(notificationService)
        .emit(
            eq(ORDER_LOW),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    order
        .verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(ORDER_HIGH), eq(TENANT_ID), any());
    order
        .verify(notificationService)
        .emit(
            eq(ORDER_HIGH),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  // ---- Test 2: exit HANDOVER -> EXIT_KIND/EXIT_AT + HANDOVER-Notification + invalidate
  // -----------

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
    verify(notificationService)
        .emit(
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
    assertThat(refsCaptor.getValue()).as("invalidate muss ACTOR_REF enthalten").contains(ACTOR_REF);

    // Finding 2 / Option B: Austritt bumpt die Generation des Akteurs.
    verify(inboxGenerationService).bump(TENANT_ID, ACTOR_REF);
  }

  // ---- Test 3: exit UNSUBSCRIBED -> kein HANDOVER-Notification
  // -----------------------------------

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
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.HANDOVER), any(), any(), any(), any());

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
   * Lock-Mechanismus selbst (zweiter save derselben Version -> BusinessRuleException) ist in {@code
   * TaskOptimisticLockIT} deterministisch gegen echtes MSSQL bewiesen.
   */
  @Test
  @DisplayName(
      "exit_bumpt_task_version_guard: erfolgreicher Austritt ruft taskRepository.save(task)")
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
  @DisplayName(
      "updateAssignees_unbekannte_uuid_liefert_404: EntityNotFoundException bei unbekannter UUID")
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
    deactivatedParticipant.setParticipantKind(ParticipantKind.PERSON);
    deactivatedParticipant.setExitKind(ExitKind.UNSUBSCRIBED);
    // Intake #163: die Zeile einer neu zuzuweisenden Person kommt aus dem Unique-Key-Lookup.
    when(participantQueryRepository.findRowByUniqueKey(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(deactivatedParticipant));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    // Act: ASSIGNEE_REF erneut zuweisen
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ASSIGNEE_REF)));

    // Assert: reactivateAsAssignee (nicht ensure) aufgerufen -> UNSUBSCRIBED_AT wird zurueckgesetzt
    // und der Mute aufgehoben (#158-Folge)
    verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(ASSIGNEE_REF), eq(TENANT_ID), any());
    verify(userStateService, never()).reactivate(any(), any(), any(), any());
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
  @DisplayName(
      "null_assignees_wirft_400: AssigneeUpdateInput mit null -> EntityValidationException")
  void null_assignees_wirft_400() {
    assertThatThrownBy(() -> new AssigneeUpdateInput(null))
        .isInstanceOf(io.keel.framework.api.validation.EntityValidationException.class)
        .hasMessageContaining("assignees");
  }

  // ---- Test 8b: Re-Gate-P2 - Assignee-Obergrenze (100) -> 400 (SQL-Parameterlimit-Schutz) ------

  @Test
  @DisplayName(
      "assignees_ueber_limit_400: 101 Assignees -> EntityValidationException (Bound-Schutz)")
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
    // damit die vor save() gesetzte In-Memory-Mutation des ersten Lookups das Ergebnis nicht
    // faelscht.
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
   * Whole-Branch-Re-Gate (P2): Beim Hinzufuegen eines Assignees aendert sich {@code
   * participantSummaries} fuer JEDEN verbleibenden aktiven Teilnehmer, nicht nur fuer den neu
   * hinzugefuegten. Deshalb muss {@code invalidate} die Vereinigung aus geaenderten Refs UND allen
   * aktuell aktiven Teilnehmer-Refs der Aufgabe erhalten - sonst bleibt der Snapshot eines
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
   * Client-Snapshot-Reloads. Guard auf {@code affectedRefs} (actual-change), analog {@code
   * TaskCommandService.patch}.
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
    // P2 #3: No-Op-PUT bumpt die TASK-Version NICHT (kein optimistischer Lock-Write ohne
    // Aenderung).
    verify(taskRepository, never()).save(any(Task.class));
  }

  // ---- Test RG-exit: exit invalidiert Akteur UND verbleibende aktive Teilnehmer ----------------

  /**
   * Whole-Branch-Re-Gate (P2): Beim Verlassen einer Aufgabe entfernt sich der Akteur aus {@code
   * participantSummaries} - das veraendert den Snapshot JEDES verbleibenden aktiven Teilnehmers.
   * Deshalb muss {@code exit} den Akteur gemeinsam mit den verbleibenden aktiven Teilnehmern
   * invalidieren (analog {@code updateAssignees}), sonst behalten diese veraltete Daten.
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
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of(stateB));
    final UUID archivedRef = UUID.randomUUID();
    final TaskParticipant archivedReader = new TaskParticipant();
    archivedReader.setParticipantRef(archivedRef);
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId))
        .thenReturn(List.of(archivedReader));

    // Act
    service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED));

    // Assert: invalidate muss Akteur UND verbleibenden Teilnehmer B enthalten.
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());
    assertThat(refsCaptor.getValue())
        .as("Akteur, verbleibenden aktiven und historischen Leser invalidieren")
        .containsExactlyInAnyOrder(ACTOR_REF, remainingRefB, archivedRef);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> bumpCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), bumpCaptor.capture());
    assertThat(bumpCaptor.getValue()).containsExactlyInAnyOrderElementsOf(refsCaptor.getValue());
    verify(inboxGenerationService).bump(TENANT_ID, ACTOR_REF);
    verify(inboxGenerationService).bump(TENANT_ID, remainingRefB);
    verify(inboxGenerationService).bump(TENANT_ID, archivedRef);
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
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t2))
        .thenReturn(List.of());
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
    verify(notificationService, times(3))
        .emit(
            any(),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
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
    // Ziele werden als Zuweisung reaktiviert (Mute aufgehoben, #158-Folge), nicht als Teilnehmer
    verify(userStateService).reactivateAsAssignee(eq(taskId), eq(t1), eq(TENANT_ID), any());
    verify(userStateService).reactivateAsAssignee(eq(taskId), eq(t2), eq(TENANT_ID), any());
    verify(userStateService, never()).reactivate(any(), any(), any(), any());
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
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of());
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
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t2))
        .thenReturn(List.of());
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
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of());
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t2))
        .thenReturn(List.of());
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
    assertThat(actor.getExitKind())
        .as("nach erstem Austritt EXIT=HANDOVER")
        .isEqualTo(ExitKind.HANDOVER);

    // 2. ECHTER Reaktivierungspfad: erneute Zuweisung via updateAssignees -> der reactivate-Zweig
    //    (ASSIGNEE_FLAG=true, EXIT_KIND=null) laeuft auf DERSELBEN Participant-Zeile (kein
    // manueller
    //    Feld-Reset). Nach dem Austritt ist ACTOR kein aktiver Assignee mehr -> toAdd={ACTOR}.
    //    Intake #163: updateAssignees liest die Zeile ueber den Unique-Key-Lookup.
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findRowByUniqueKey(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ACTOR_REF)));
    assertThat(actor.getExitKind())
        .as("Reaktivierung via updateAssignees -> wieder aktiv")
        .isNull();
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
        .as("nach erneutem Austritt wieder EXIT=HANDOVER")
        .isEqualTo(ExitKind.HANDOVER);
    assertThat(actor.getExitAt()).isNotNull();
  }

  @Test
  @DisplayName(
      "U5: Creator HANDOVER shouldStay=true -> Ziele Assignee; Creator ASSIGNEE_FLAG=false,"
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
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of());
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
                    taskUuid,
                    new ExitInput(ExitKind.UNSUBSCRIBED, List.of(UUID.randomUUID()), false)))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED, List.of(), true)))
        .isInstanceOf(EntityValidationException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
  }

  @Test
  @DisplayName(
      "U8: HANDOVER shouldStay=true leere Ziele -> 400 (auch Creator: leer-stay 400 VOR 409)")
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
  @DisplayName(
      "U9: HANDOVER shouldStay=false leere Ziele Nicht-Creator -> Selbst-Handover (EXIT, Selbst-Notify)")
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
    verify(notificationService, times(1))
        .emit(
            eq(ACTOR_REF),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
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
            () ->
                service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(ACTOR_REF), false)))
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
  @DisplayName(
      "U11: Ziel bereits Assignee -> idempotent (ASSIGNEE_FLAG bleibt true), trotzdem TARGET + Notify")
  void u11_ziel_bereits_assignee_idempotent() {
    final Long taskId = 311L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    // t1 ist bereits aktiver Assignee. ensureAssignee waehlt die Ziel-Zeile seit Slice 4
    // Unique-Key-exakt via findRowByUniqueKey (task-tenant/PERSON -> reaktivieren, bleibt
    // Assignee).
    final TaskParticipant existingTarget = activeAssignee(taskId, t1, false);
    existingTarget.setEntityState(EntityState.ACTIVE);
    when(participantQueryRepository.findRowByUniqueKey(taskId, t1))
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
    verify(notificationService, times(1))
        .emit(
            eq(t1),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    assertThat(existingTarget.isAssigneeFlag()).as("bleibt Assignee").isTrue();
    // Keine neue Zuweisung (war schon aktiver Assignee): nur reactivate, der Mute bleibt (#158).
    verify(userStateService).reactivate(eq(taskId), eq(t1), eq(TENANT_ID), any());
    verify(userStateService, never()).reactivateAsAssignee(any(), any(), any(), any());
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
  @DisplayName(
      "U-dedup: HANDOVER mit [t1, t1] -> genau 1 TARGET + 1 Ziel-Notify + 1 Ziel-Bump (Dedup)")
  void u_dedup_ziele() {
    final Long taskId = 313L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, t1))
        .thenReturn(List.of());
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
    verify(notificationService, times(1))
        .emit(
            eq(t1),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  @Test
  @DisplayName(
      "U13a: per Handover de-assignter Creator bleibt bei PUT-ohne-ihn aktiver Participant (kein EXIT)")
  void u13a_handover_deassignter_creator_bleibt_bei_put() {
    // Creator wurde per Handover shouldStay=true de-assignt: CREATOR+PARTICIPANT aktiv,
    // ASSIGNEE_FLAG=false.
    // Ein PUT ohne ihn findet ihn NICHT in findActiveAssigneesByTaskId -> kein toRemove -> kein
    // EXIT.
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

  // ---- Intake #156: gemeinsamer Aufnahme-Kern (Kommentar mit Erwaehnung nutzt ihn mit) ----------

  @Test
  @DisplayName(
      "addAsParticipants_neuer_ref: legt reinen Teilnehmer samt State an und meldet ihn zurueck;"
          + " KEINE Benachrichtigung, KEIN Bump, KEIN Signal, KEIN Task-Save (das erledigt der"
          + " Aufrufer in seiner Lock-Reihenfolge)")
  void addAsParticipants_neuer_ref() {
    final Long taskId = 210L;
    final UUID ref = UUID.randomUUID();
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final List<UUID> affected = service.addAsParticipants(taskId, TENANT_ID, List.of(ref));

    assertThat(affected).containsExactly(ref);
    final ArgumentCaptor<TaskParticipant> saved = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(saved.capture());
    assertThat(saved.getValue().isParticipantFlag()).isTrue();
    assertThat(saved.getValue().isAssigneeFlag()).as("nie Assignee").isFalse();
    assertThat(saved.getValue().isCreatorFlag()).isFalse();
    assertThat(saved.getValue().getParticipantKind()).isEqualTo(ParticipantKind.PERSON);
    verify(userStateService).ensure(eq(taskId), eq(ref), eq(TENANT_ID));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxGenerationService, never()).bumpSorted(anyLong(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    verify(taskRepository, never()).save(any(Task.class));
  }

  @Test
  @DisplayName(
      "addAsParticipants_ausgetretener_ref: reaktiviert als reiner Teilnehmer (Ex-Assignee ohne"
          + " Assignee-Flag) per reactivate, nie reactivateAsAssignee (ein Mute bleibt)")
  void addAsParticipants_ausgetretener_ref() {
    final Long taskId = 211L;
    final UUID ref = UUID.randomUUID();
    final TaskParticipant exitedAssignee = personRow(taskId, ref, EntityState.ACTIVE);
    exitedAssignee.setId(4L);
    exitedAssignee.setAssigneeFlag(true);
    exitedAssignee.setExitKind(ExitKind.UNSUBSCRIBED);
    exitedAssignee.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref))
        .thenReturn(List.of(exitedAssignee));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final List<UUID> affected = service.addAsParticipants(taskId, TENANT_ID, List.of(ref));

    assertThat(affected).containsExactly(ref);
    assertThat(exitedAssignee.isAssigneeFlag()).as("Assignee-Flag geloescht").isFalse();
    assertThat(exitedAssignee.getExitKind()).isNull();
    assertThat(exitedAssignee.getExitAt()).isNull();
    verify(userStateService).reactivate(eq(taskId), eq(ref), eq(TENANT_ID));
    verify(userStateService, never()).reactivateAsAssignee(any(), any(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "addAsParticipants_aktiver_ref_und_race_verlierer: aktive Zeile und gleichzeitiger Insert"
          + " (Duplicate-Key, Gewinner aktiv) werden uebersprungen und nicht gemeldet")
  void addAsParticipants_aktiver_ref_und_race_verlierer() {
    final Long taskId = 212L;
    final UUID activeRef = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    final UUID raceRef = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    final UUID newRef = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    final TaskParticipant active = personRow(taskId, activeRef, EntityState.ACTIVE);
    active.setId(5L);
    when(participantQueryRepository.findRowByUniqueKey(taskId, activeRef))
        .thenReturn(List.of(active));

    // raceRef: der Vorab-Read findet nichts (Default), der Insert scheitert am Unique-Key, der
    // Re-Read im Konfliktzweig zeigt den aktiven Gewinner.
    final TaskParticipant winner = personRow(taskId, raceRef, EntityState.ACTIVE);
    winner.setId(6L);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, raceRef))
        .thenReturn(List.of(winner));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(
            inv -> {
              final TaskParticipant p = inv.getArgument(0);
              if (raceRef.equals(p.getParticipantRef())) {
                throw new BusinessRuleException(
                    "Konflikt", new DuplicateKeyException("UQ_TASK_PARTICIPANT_TASK_USER"));
              }
              return p;
            });

    final List<UUID> affected =
        service.addAsParticipants(taskId, TENANT_ID, List.of(newRef, raceRef, activeRef));

    assertThat(affected).containsExactly(newRef);
    verify(userStateService).ensure(eq(taskId), eq(newRef), eq(TENANT_ID));
    verify(userStateService, never()).ensure(eq(taskId), eq(raceRef), eq(TENANT_ID));
    verify(userStateService, never()).ensure(eq(taskId), eq(activeRef), eq(TENANT_ID));
  }

  @Test
  @DisplayName(
      "addAsParticipants_dedupliziert_und_sortiert: doppelte Refs einmal, Schreibreihenfolge nach"
          + " UUID (gleiche Zeilen-Lock-Reihenfolge wie addParticipants)")
  void addAsParticipants_dedupliziert_und_sortiert() {
    final Long taskId = 213L;
    final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    when(participantQueryRepository.findByTaskIdAndParticipantRef(anyLong(), any()))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final List<UUID> affected =
        service.addAsParticipants(taskId, TENANT_ID, List.of(second, first, second));

    assertThat(affected).containsExactly(first, second);
    final ArgumentCaptor<TaskParticipant> saves = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, times(2)).save(saves.capture());
    assertThat(saves.getAllValues().stream().map(TaskParticipant::getParticipantRef).toList())
        .containsExactly(first, second);
  }

  @Test
  @DisplayName(
      "mayAddParticipants: spiegelt die Berechtigung des Endpunkts POST /participants"
          + " (exakte Authority TASK_WRITE)")
  void mayAddParticipants_spiegelt_task_write() {
    when(permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenReturn(true);
    assertThat(service.mayAddParticipants()).isTrue();

    when(permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenReturn(false);
    assertThat(service.mayAddParticipants()).isFalse();
  }

  @Test
  @DisplayName(
      "addParticipants_nutzt_gemeinsamen_kern: addParticipants ruft addAsParticipants und"
          + " benachrichtigt genau die zurueckgemeldeten Personen (PARTICIPANT, Akteur = Ausloeser)")
  void addParticipants_nutzt_gemeinsamen_kern() {
    final Long taskId = 214L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    final AssigneeService spied = org.mockito.Mockito.spy(service);
    org.mockito.Mockito.doReturn(List.of(ref))
        .when(spied)
        .addAsParticipants(eq(taskId), eq(TENANT_ID), any());

    spied.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    verify(spied).addAsParticipants(eq(taskId), eq(TENANT_ID), any());
    verify(notificationService, times(1))
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(inboxGenerationService).bump(TENANT_ID, ref);
  }

  @Test
  @DisplayName(
      "addParticipants verarbeitet inverse Mehrfach-Ref-Inputs in gleicher UUID-Reihenfolge")
  void addParticipants_inverse_refs_haben_gleiche_mutationsreihenfolge() {
    final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    final UUID firstTaskUuid = UUID.randomUUID();
    final UUID secondTaskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(firstTaskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(201L, firstTaskUuid)));
    when(taskRepository.findByUuid(secondTaskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(202L, secondTaskUuid)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(anyLong(), any()))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(firstTaskUuid, new ParticipantAddInput(List.of(second, first)));
    service.addParticipants(secondTaskUuid, new ParticipantAddInput(List.of(first, second)));

    final ArgumentCaptor<TaskParticipant> saves = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, times(4)).save(saves.capture());
    assertThat(saves.getAllValues().stream().map(TaskParticipant::getParticipantRef).toList())
        .containsExactly(first, second, first, second);

    final ArgumentCaptor<UUID> stateRefs = ArgumentCaptor.forClass(UUID.class);
    verify(userStateService, times(4)).ensure(anyLong(), stateRefs.capture(), eq(TENANT_ID));
    assertThat(stateRefs.getAllValues()).containsExactly(first, second, first, second);
  }

  @Test
  @DisplayName(
      "addParticipants_neuer_ref: neuer Ref -> Teilnehmer (PARTICIPANT_FLAG=1, kein Assignee/Creator)"
          + " + ensure + bump + invalidate + genau eine PARTICIPANT-Notification + Task-VERSION-Bump"
          + " (Intake #167)")
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

    // Intake #147: genau eine PARTICIPANT-Notification an die neue Person (Akteur ist der
    // Ausloeser, Vorschau ist der Aufgabentitel). Intake #167: bei echter Aenderung speichert das
    // Hinzufuegen die Aufgabe (VERSION-Bump), parallele Aufnahmen serialisieren wie PUT.
    verify(notificationService, times(1))
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            eq("Du wurdest als Beteiligter zur Aufgabe hinzugefuegt."),
            eq("Testtitel"),
            eq(ACTOR_REF));
    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), any(), any(), any(), any(), any());
    verify(taskRepository).save(task);
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

    final TaskParticipant active = personRow(taskId, ref, EntityState.ACTIVE); // EXIT_KIND null
    active.setId(1L);
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref)).thenReturn(List.of(active));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateService, never()).ensure(any(), any(), any());
    verify(userStateService, never()).reactivate(any(), any(), any());
    verify(userStateService, never()).reactivateAsAssignee(any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    // Intake #147: ein schon aktives Mitglied wird nicht erneut benachrichtigt.
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "addParticipants_exited_ref_reactivate: ausgetretene Zeile -> als reiner Teilnehmer reaktiviert"
          + " (PARTICIPANT_FLAG=1, EXIT_KIND null) + reactivate + bump + PARTICIPANT-Notification")
  void addParticipants_exited_ref_reactivate() {
    final Long taskId = 202L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant exited = personRow(taskId, ref, EntityState.ACTIVE);
    exited.setId(2L);
    exited.setAssigneeFlag(false);
    exited.setExitKind(ExitKind.UNSUBSCRIBED);
    exited.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref)).thenReturn(List.of(exited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(pCaptor.capture());
    final TaskParticipant saved = pCaptor.getValue();
    assertThat(saved.isParticipantFlag()).isTrue();
    assertThat(saved.getExitKind()).as("EXIT_KIND zurueckgesetzt").isNull();
    assertThat(saved.getExitAt()).isNull();

    // Reiner Teilnehmer-Wiedereintritt ist keine Zuweisung: Mute bleibt (nur reactivate).
    verify(userStateService).reactivate(eq(taskId), eq(ref), eq(TENANT_ID));
    verify(userStateService, never()).reactivateAsAssignee(any(), any(), any());
    verify(inboxGenerationService).bump(TENANT_ID, ref);
    // Intake #147: auch die aus dem Austritt reaktivierte Person bekommt die Benachrichtigung.
    verify(notificationService, times(1))
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
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

    final TaskParticipant exitedAssignee = personRow(taskId, ref, EntityState.ACTIVE);
    exitedAssignee.setId(3L);
    exitedAssignee.setAssigneeFlag(true); // war Assignee
    exitedAssignee.setExitKind(ExitKind.UNSUBSCRIBED);
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref))
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
    // unbekannt -> findActiveByUuids liefert den Ref NICHT. doReturn (statt when/thenReturn), weil
    // der
    // setUp-Stub thenAnswer nutzt: ein when(...)-Re-Stub wuerde die alte Answer-Lambda ausloesen
    // (verschachteltes when()) -> Mockito-Fehler.
    org.mockito.Mockito.doReturn(List.of()).when(userReadRepository).findActiveByUuids(any());

    assertThatThrownBy(
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref))))
        .isInstanceOf(EntityValidationException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
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
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
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

    final TaskParticipant winner = personRow(taskId, ref, EntityState.ACTIVE); // aktiver Gewinner
    winner.setId(9L);
    // Vorab-Read (Unique-Key) leer (neu-Pfad, Default), nach dem Konflikt liefert der Re-Read die
    // Gewinner-Zeile.
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(winner));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("UNIQUE", new DuplicateKeyException("dup")));

    // darf NICHT werfen (idempotenter SKIP)
    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    verify(userStateService, never()).ensure(any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
    // Intake #147: der Race-Verlierer meldet nichts; die Benachrichtigung kommt vom Gewinner.
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
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

    final TaskParticipant activeWinner = personRow(taskId, ref, EntityState.ACTIVE);
    activeWinner.setId(10L);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of(activeWinner));
    // Unverwandte BusinessRuleException (z.B. Validator-Guard) OHNE DAO-Ursache.
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenThrow(new BusinessRuleException("unverwandter Validator-Verstoss"));

    assertThatThrownBy(
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref))))
        .isInstanceOf(BusinessRuleException.class);

    verify(userStateService, never()).ensure(any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
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
    // Intake #147: auch die Benachrichtigung genau einmal.
    verify(notificationService, times(1))
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  // ---- Intake #156: DEACTIVE-PERSON-Zeile wird reaktiviert statt neu eingefuegt -----------------

  @Test
  @DisplayName(
      "addParticipants_deactive_zeile_wird_reaktiviert: vorhandene DEACTIVE-PERSON-Zeile (Unique-Key"
          + " belegt, vom ACTIVE-Lookup nicht gesehen) -> ENTITY_STATUS wieder ACTIVE, reiner"
          + " Teilnehmer, KEIN Insert; PARTICIPANT, Bump und Signal wie bei jeder Aufnahme")
  void addParticipants_deactive_zeile_wird_reaktiviert() {
    final Long taskId = 230L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    final TaskParticipant deactive = personRow(taskId, ref, EntityState.DEACTIVE);
    deactive.setId(31L);
    deactive.setAssigneeFlag(true); // alter Assignee-Rest
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref)).thenReturn(List.of(deactive));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    final ArgumentCaptor<TaskParticipant> saves = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(saves.capture());
    assertThat(saves.getValue()).as("dieselbe Zeile, kein Insert").isSameAs(deactive);
    assertThat(deactive.getEntityState()).isEqualTo(EntityState.ACTIVE);
    assertThat(deactive.isAssigneeFlag()).as("nie Assignee").isFalse();
    assertThat(deactive.isParticipantFlag()).isTrue();
    assertThat(deactive.getExitKind()).isNull();
    verify(userStateService).reactivate(eq(taskId), eq(ref), eq(TENANT_ID));
    verify(userStateService, never()).reactivateAsAssignee(any(), any(), any());
    verify(inboxGenerationService).bump(TENANT_ID, ref);
    verify(notificationService, times(1))
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  @Test
  @DisplayName(
      "addAsParticipants_deactive_und_ausgetretene_zeile: DEACTIVE-Zeile und ausgetretene Zeile"
          + " werden beide reaktiviert und gemeldet, eine aktive Zeile uebersprungen")
  void addAsParticipants_deactive_und_ausgetretene_zeile() {
    final Long taskId = 231L;
    final UUID activeRef = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    final UUID deactiveRef = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    final UUID exitedRef = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    final TaskParticipant active = personRow(taskId, activeRef, EntityState.ACTIVE);
    final TaskParticipant deactive = personRow(taskId, deactiveRef, EntityState.DEACTIVE);
    deactive.setExitKind(ExitKind.UNSUBSCRIBED); // inaktiv UND ausgetreten
    final TaskParticipant exited = personRow(taskId, exitedRef, EntityState.ACTIVE);
    exited.setExitKind(ExitKind.HANDOVER);
    when(participantQueryRepository.findRowByUniqueKey(taskId, activeRef))
        .thenReturn(List.of(active));
    when(participantQueryRepository.findRowByUniqueKey(taskId, deactiveRef))
        .thenReturn(List.of(deactive));
    when(participantQueryRepository.findRowByUniqueKey(taskId, exitedRef))
        .thenReturn(List.of(exited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final List<UUID> affected =
        service.addAsParticipants(taskId, TENANT_ID, List.of(exitedRef, activeRef, deactiveRef));

    assertThat(affected).containsExactly(deactiveRef, exitedRef);
    assertThat(deactive.getEntityState()).isEqualTo(EntityState.ACTIVE);
    assertThat(deactive.getExitKind()).isNull();
    assertThat(exited.getExitKind()).isNull();
    verify(participantQueryRepository, times(2)).save(any(TaskParticipant.class));
    verify(userStateService).reactivate(eq(taskId), eq(deactiveRef), eq(TENANT_ID));
    verify(userStateService).reactivate(eq(taskId), eq(exitedRef), eq(TENANT_ID));
    verify(userStateService, never()).reactivate(eq(taskId), eq(activeRef), eq(TENANT_ID));
  }

  @Test
  @DisplayName(
      "addAsParticipants_team_oder_fremde_zeile_409_keine_mutation: die Unique-Key-Zeile ist eine"
          + " TEAM-Zeile bzw. traegt ein fremdes Basis-TENANT_ID -> 409 fail-closed wie bei der"
          + " Uebergabe, nichts wird gespeichert (ein PERSON-Insert braeche den Unique-Key)")
  void addAsParticipants_team_oder_fremde_zeile_409_keine_mutation() {
    final Long taskId = 232L;
    final UUID teamRef = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    final UUID foreignRef = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    final TaskParticipant team = personRow(taskId, teamRef, EntityState.ACTIVE);
    team.setParticipantKind(ParticipantKind.TEAM);
    final TaskParticipant foreign = personRow(taskId, foreignRef, EntityState.ACTIVE);
    foreign.setTenantId(TENANT_ID + 1);
    when(participantQueryRepository.findRowByUniqueKey(taskId, teamRef)).thenReturn(List.of(team));
    when(participantQueryRepository.findRowByUniqueKey(taskId, foreignRef))
        .thenReturn(List.of(foreign));

    assertThatThrownBy(() -> service.addAsParticipants(taskId, TENANT_ID, List.of(teamRef)))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(() -> service.addAsParticipants(taskId, TENANT_ID, List.of(foreignRef)))
        .isInstanceOf(BusinessRuleException.class);

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateService, never()).ensure(any(), any(), any());
    verify(userStateService, never()).reactivate(any(), any(), any());
  }

  // ---- Intake #147: PARTICIPANT-Benachrichtigung an neu hinzugefuegte Personen ----------------

  @Test
  @DisplayName(
      "i147_gemischte_menge_nur_neue_und_reaktivierte_in_uuid_reihenfolge: aktives Mitglied wird"
          + " uebersprungen, neue und aus dem Austritt reaktivierte Person bekommen je genau eine"
          + " PARTICIPANT-Benachrichtigung, in UUID-Reihenfolge")
  void i147_mixed_set_notifies_only_new_and_reactivated_in_uuid_order() {
    final Long taskId = 220L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID activeRef = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID newRef = UUID.fromString("00000000-0000-0000-0000-000000000002");
    final UUID exitedRef = UUID.fromString("00000000-0000-0000-0000-000000000003");
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));

    final TaskParticipant active = personRow(taskId, activeRef, EntityState.ACTIVE);
    active.setId(11L);
    when(participantQueryRepository.findRowByUniqueKey(taskId, activeRef))
        .thenReturn(List.of(active));
    final TaskParticipant exited = personRow(taskId, exitedRef, EntityState.ACTIVE);
    exited.setId(12L);
    exited.setExitKind(ExitKind.UNSUBSCRIBED);
    exited.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findRowByUniqueKey(taskId, exitedRef))
        .thenReturn(List.of(exited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(
        taskUuid, new ParticipantAddInput(List.of(exitedRef, activeRef, newRef)));

    final InOrder order = inOrder(notificationService);
    order
        .verify(notificationService)
        .emit(
            eq(newRef),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    order
        .verify(notificationService)
        .emit(
            eq(exitedRef),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, never())
        .emit(eq(activeRef), any(), any(), any(), any(), any(), any());
    verify(notificationService, times(2)).emit(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "i147_akteur_in_der_menge_wird_nie_benachrichtigt: auch wenn der Akteur in der Menge steht"
          + " und angelegt wird, geht keine PARTICIPANT-Benachrichtigung an ihn")
  void i147_actor_in_set_is_never_notified() {
    final Long taskId = 221L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID otherRef = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(anyLong(), any()))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ACTOR_REF, otherRef)));

    verify(notificationService, times(1))
        .emit(
            eq(otherRef),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "i147_reihenfolge_save_emit_bump_invalidate: Participant-Save und State vor der"
          + " Benachrichtigung, danach TASK-Save (Intake #167), Generation-Bump und Signal")
  void i147_order_save_emit_bump_invalidate() {
    final Long taskId = 222L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ref))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    final InOrder order =
        inOrder(
            participantQueryRepository,
            userStateService,
            notificationService,
            taskRepository,
            inboxGenerationService,
            inboxSignalService);
    order.verify(participantQueryRepository).save(any(TaskParticipant.class));
    order.verify(userStateService).ensure(taskId, ref, TENANT_ID);
    order
        .verify(notificationService)
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    order.verify(taskRepository).save(task);
    order.verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), any());
    order.verify(inboxSignalService).invalidate(eq(TENANT_ID), any());
  }

  // ============================================================================================
  // Slice 4 (Standard-Selfservice): exit-Guard-404 + Handover-Autz-Grenze + ensureAssignee-Vertrag
  // ============================================================================================

  // ---- exit-Guard ganz vorne: Non-/Ex-Member -> 404 VOR jeder Eingabe-/Zielvalidierung ---------

  @Test
  @DisplayName(
      "s4_exit_guard_404_vor_zielvalidierung: requireWriteMember wirft 404 -> keine"
          + " Ziel-/Akteur-Validierung, keine Mutation (kein 400, kein Ziel-Leak)")
  void s4_exit_guard_404_vor_zielvalidierung() {
    final Long taskId = 400L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // Guard: Non-/Ex-Member -> 404 (GANZ VORNE, vor findActiveByUuids-Zielvalidierung).
    org.mockito.Mockito.doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(membershipGuard)
        .requireWriteMember(taskId, ACTOR_REF, TENANT_ID);

    // HANDOVER mit (hier egal gueltigen) Zielen -> der Guard muss VOR der Zielvalidierung greifen.
    assertThatThrownBy(
            () ->
                service.exit(
                    taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(UUID.randomUUID()), false)))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    // Keine Zielvalidierung, kein Akteur-Load, keine Mutation, keine Notification/Invalidate.
    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(participantQueryRepository, never())
        .findByTaskIdAndParticipantRef(any(Long.class), any());
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(handoverRepository, never()).save(any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  // ---- Handover-Entscheidungstabelle ----------------------------------------------------------

  @Test
  @DisplayName(
      "s4_handover_alle_ziele_write_member_grantfrei_204: alle Ziele WRITE-Member -> kein"
          + " TASK_WRITE noetig, Mutation laeuft (grant-frei)")
  void s4_handover_alle_ziele_write_member_grantfrei_204() {
    final Long taskId = 410L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final UUID t2 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    // BEIDE Ziele sind bereits WRITE-Member -> grant-frei.
    when(membershipGuard.isWriteMember(taskId, t1, TENANT_ID)).thenReturn(true);
    when(membershipGuard.isWriteMember(taskId, t2, TENANT_ID)).thenReturn(true);
    // Grant NICHT vorhanden -> beweist, dass grant-frei ausreicht.
    when(permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenReturn(false);
    // Ziele sind bereits aktive PERSON-Mitglieder (task-tenant) -> reaktivieren (bleiben Assignee).
    when(participantQueryRepository.findRowByUniqueKey(taskId, t1))
        .thenReturn(List.of(activeAssignee(taskId, t1, false)));
    when(participantQueryRepository.findRowByUniqueKey(taskId, t2))
        .thenReturn(List.of(activeAssignee(taskId, t2, false)));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1, t2), false));

    // Grant-frei: KEIN Grant-Check-Deny, Handover-Ereignis + 2 Targets persistiert.
    verify(handoverRepository).save(any(TaskHandover.class));
    verify(handoverTargetRepository, times(2)).save(any());
  }

  @Test
  @DisplayName(
      "s4_handover_ziel_nicht_write_member_ohne_grant_403: ein Ziel kein WRITE-Member + kein"
          + " TASK_WRITE -> 403, KEINE Mutation")
  void s4_handover_ziel_nicht_write_member_ohne_grant_403() {
    final Long taskId = 411L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    // t1 ist NICHT WRITE-Member (neu/ausgetreten/inaktiv/TEAM/fremd) -> isWriteMember=false
    // (Default).
    when(permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenReturn(false);

    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false)))
        .isInstanceOf(PermissionDeniedException.class);

    // KEINE Mutation (All-or-Nothing, Grant-Check VOR jeder Mutation).
    verify(handoverRepository, never()).save(any());
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  @Test
  @DisplayName(
      "s4_handover_ziel_nicht_write_member_mit_grant_204: ein Ziel kein WRITE-Member, aber Akteur"
          + " hat TASK_WRITE -> erlaubt (Mutation laeuft)")
  void s4_handover_ziel_nicht_write_member_mit_grant_204() {
    final Long taskId = 412L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    // t1 kein WRITE-Member (isWriteMember=false Default), aber TASK_WRITE vorhanden
    // (setUp-Default).
    // t1 hat keine Unique-Key-Zeile -> INSERT (findRowByUniqueKey=List.of() Default).
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false));

    verify(handoverRepository).save(any(TaskHandover.class));
    verify(handoverTargetRepository, times(1)).save(any());
  }

  @Test
  @DisplayName(
      "s4_handover_gemischte_ziele_ohne_grant_403_keine_teilmutation: ein WRITE-Member + ein"
          + " Nicht-WRITE-Member, kein Grant -> 403, KEINE (Teil-)Mutation (All-or-Nothing)")
  void s4_handover_gemischte_ziele_ohne_grant_403_keine_teilmutation() {
    final Long taskId = 413L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID writeMember = UUID.randomUUID();
    final UUID nonMember = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    when(membershipGuard.isWriteMember(taskId, writeMember, TENANT_ID)).thenReturn(true);
    when(membershipGuard.isWriteMember(taskId, nonMember, TENANT_ID)).thenReturn(false);
    when(permissionEvaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                service.exit(
                    taskUuid,
                    new ExitInput(ExitKind.HANDOVER, List.of(writeMember, nonMember), false)))
        .isInstanceOf(PermissionDeniedException.class);

    // Keine Teilmutation: weder Handover-Ereignis noch irgendein Participant-save (auch nicht fuer
    // das gueltige WRITE-Member-Ziel).
    verify(handoverRepository, never()).save(any());
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  @Test
  @DisplayName(
      "s4_exit_unsubscribed_grantfrei_kein_grant_check: UNSUBSCRIBED hat keine Ziele -> grant-frei"
          + " (isWriteMember/hasExactAuthority nie konsultiert), 204")
  void s4_exit_unsubscribed_grantfrei_kein_grant_check() {
    final Long taskId = 414L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF)).thenReturn(List.of());
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED));

    // UNSUBSCRIBED: kein Grant-Check-Pfad (keine Ziele).
    verify(membershipGuard, never()).isWriteMember(any(Long.class), any(), any(Long.class));
    verify(permissionEvaluator, never()).hasExactAuthority(any());
    verify(taskRepository).save(task);
  }

  // ---- ensureAssignee-Auswahlvertrag (Unique-Key-exakt, Klassifikation) -----------------------

  @Test
  @DisplayName(
      "s4_ensureAssignee_reaktiviert_inaktive_exited_person_auf_active: vorhandene PERSON-Zeile"
          + " (task-tenant, inaktiv+exited) -> ASSIGNEE/PARTICIPANT=1, EXIT=null, ENTITY_STATUS=ACTIVE")
  void s4_ensureAssignee_reaktiviert_inaktive_exited_person_auf_active() {
    final Long taskId = 420L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));

    // Ziel-Zeile: PERSON, task-tenant, aber INAKTIV (ENTITY_STATUS != ACTIVE) + ausgetreten.
    final TaskParticipant inactiveExited = new TaskParticipant();
    inactiveExited.setId(99L);
    inactiveExited.setTenantId(TENANT_ID);
    inactiveExited.setParticipantTenantId(TENANT_ID);
    inactiveExited.setTaskId(taskId);
    inactiveExited.setParticipantRef(t1);
    inactiveExited.setParticipantKind(ParticipantKind.PERSON);
    inactiveExited.setAssigneeFlag(false);
    inactiveExited.setParticipantFlag(false);
    inactiveExited.setExitKind(ExitKind.UNSUBSCRIBED);
    inactiveExited.setExitAt(java.time.LocalDateTime.now().minusDays(3));
    inactiveExited.setEntityState(EntityState.DEACTIVE);
    when(participantQueryRepository.findRowByUniqueKey(taskId, t1))
        .thenReturn(List.of(inactiveExited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false));

    // VOLLE Reaktivierung der exakt dieser Unique-Key-Zeile.
    assertThat(inactiveExited.isAssigneeFlag()).as("ASSIGNEE_FLAG=1").isTrue();
    assertThat(inactiveExited.isParticipantFlag()).as("PARTICIPANT_FLAG=1").isTrue();
    assertThat(inactiveExited.getExitKind()).as("EXIT_KIND=null").isNull();
    assertThat(inactiveExited.getExitAt()).as("EXIT_AT=null").isNull();
    assertThat(inactiveExited.getEntityState())
        .as("ENTITY_STATUS=ACTIVE (voll reaktiviert)")
        .isEqualTo(EntityState.ACTIVE);
    // Die gefundene Zeile wird gespeichert (keine neue INSERT-Zeile).
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, org.mockito.Mockito.atLeastOnce()).save(pCaptor.capture());
    assertThat(pCaptor.getAllValues())
        .as("reaktivierte Ziel-Zeile ist unter den gespeicherten Objekten")
        .contains(inactiveExited);
  }

  @Test
  @DisplayName(
      "s4_ensureAssignee_team_zeile_409_keine_mutation: Unique-Key-Zeile ist TEAM -> 409"
          + " (BusinessRuleException), KEINE Mutation der Zeile, voller Rollback")
  void s4_ensureAssignee_team_zeile_409_keine_mutation() {
    final Long taskId = 421L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));

    // Ziel-Zeile ist TEAM (task-tenant) -> fail-closed 409.
    final TaskParticipant teamRow = new TaskParticipant();
    teamRow.setId(88L);
    teamRow.setTenantId(TENANT_ID);
    teamRow.setParticipantTenantId(TENANT_ID);
    teamRow.setTaskId(taskId);
    teamRow.setParticipantRef(t1);
    teamRow.setParticipantKind(ParticipantKind.TEAM);
    teamRow.setEntityState(EntityState.ACTIVE);
    when(participantQueryRepository.findRowByUniqueKey(taskId, t1)).thenReturn(List.of(teamRow));

    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false)))
        .isInstanceOf(BusinessRuleException.class);

    // KEINE Mutation der TEAM-Zeile (kein PERSON-Flip), kein save.
    assertThat(teamRow.getParticipantKind()).as("TEAM bleibt TEAM").isEqualTo(ParticipantKind.TEAM);
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
  }

  @Test
  @DisplayName(
      "s4_ensureAssignee_fremdes_basis_tenant_409_keine_mutation: Unique-Key-Zeile ist PERSON, aber"
          + " Basis-TENANT_ID != task-tenant -> 409, KEINE Mutation")
  void s4_ensureAssignee_fremdes_basis_tenant_409_keine_mutation() {
    final Long taskId = 422L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));

    // Ziel-Zeile: PERSON, PARTICIPANT_TENANT_ID=task-tenant (vom Unique-Key-Lookup getroffen),
    // aber fremdes Basis-TENANT_ID (Anomalie) -> fail-closed 409.
    final TaskParticipant foreignBase = new TaskParticipant();
    foreignBase.setId(77L);
    foreignBase.setTenantId(999L); // fremdes Basis-TENANT_ID
    foreignBase.setParticipantTenantId(TENANT_ID);
    foreignBase.setTaskId(taskId);
    foreignBase.setParticipantRef(t1);
    foreignBase.setParticipantKind(ParticipantKind.PERSON);
    foreignBase.setEntityState(EntityState.ACTIVE);
    when(participantQueryRepository.findRowByUniqueKey(taskId, t1))
        .thenReturn(List.of(foreignBase));

    assertThatThrownBy(
            () -> service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false)))
        .isInstanceOf(BusinessRuleException.class);

    assertThat(foreignBase.getTenantId())
        .as("fremdes Basis-TENANT_ID unveraendert")
        .isEqualTo(999L);
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
  }

  @Test
  @DisplayName(
      "s4_ensureAssignee_absent_insert_neue_person_zeile: kein Unique-Key-Treffer -> NEUE"
          + " PERSON-Assignee-Zeile (TENANT_ID=PARTICIPANT_TENANT_ID=task-tenant, ACTIVE-Insert)")
  void s4_ensureAssignee_absent_insert_neue_person_zeile() {
    final Long taskId = 423L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID t1 = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant actor = activeAssignee(taskId, ACTOR_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actor));
    // findRowByUniqueKey(t1) -> List.of() (Default: Unique-Key frei) -> INSERT.
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(t1), false));

    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository, org.mockito.Mockito.atLeastOnce()).save(pCaptor.capture());
    final TaskParticipant insertedTarget =
        pCaptor.getAllValues().stream()
            .filter(p -> t1.equals(p.getParticipantRef()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(insertedTarget.getId()).as("neue Zeile (keine Id)").isNull();
    assertThat(insertedTarget.getParticipantKind()).isEqualTo(ParticipantKind.PERSON);
    assertThat(insertedTarget.getTenantId()).as("Basis-TENANT_ID=task-tenant").isEqualTo(TENANT_ID);
    assertThat(insertedTarget.getParticipantTenantId())
        .as("PARTICIPANT_TENANT_ID=task-tenant")
        .isEqualTo(TENANT_ID);
    assertThat(insertedTarget.isAssigneeFlag()).as("ASSIGNEE_FLAG=1").isTrue();
    assertThat(insertedTarget.isParticipantFlag()).as("PARTICIPANT_FLAG=1").isTrue();
    assertThat(insertedTarget.isCreatorFlag()).as("CREATOR_FLAG=0").isFalse();
  }

  // ============================================================================================
  // Intake #158 (Folge): eine NEUE Zuweisung hebt Mute auf; Teilnehmer-Pfad und unveraenderte
  // Assignees nicht. Hier laeuft der ECHTE UserStateService (Repository gemockt), damit der
  // Zustand des Markers nachgewiesen wird und nicht nur der Aufruf.
  // ============================================================================================

  @Test
  @DisplayName(
      "mute_put_stummer_ex_assignee_wird_neu_zugewiesen_und_entstummt: ausgetretener, stummer"
          + " Ex-Assignee wird per PUT erneut zugewiesen -> IS_MUTED=false, UNSUBSCRIBED_AT=null")
  void mute_put_stummer_ex_assignee_wird_neu_zugewiesen_und_entstummt() {
    final Long taskId = 700L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID exAssigneeRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());

    final TaskParticipant exited = new TaskParticipant();
    exited.setId(70L);
    exited.setTenantId(TENANT_ID);
    exited.setTaskId(taskId);
    exited.setParticipantRef(exAssigneeRef);
    exited.setAssigneeFlag(true);
    exited.setExitKind(ExitKind.UNSUBSCRIBED);
    exited.setExitAt(java.time.LocalDateTime.now().minusDays(2));
    exited.setParticipantKind(ParticipantKind.PERSON);
    // Intake #163: die Zeile einer neu zuzuweisenden Person kommt aus dem Unique-Key-Lookup.
    when(participantQueryRepository.findRowByUniqueKey(taskId, exAssigneeRef))
        .thenReturn(List.of(exited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final TaskUserState mutedState = mutedState(taskId, exAssigneeRef);
    mutedState.setUnsubscribedAt(java.time.LocalDateTime.now().minusDays(2));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, exAssigneeRef))
        .thenReturn(List.of(mutedState));

    serviceWithRealUserState()
        .updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(exAssigneeRef)));

    assertThat(mutedState.isMuted()).as("neue Zuweisung hebt Mute auf").isFalse();
    assertThat(mutedState.getUnsubscribedAt()).as("wieder in der aktiven Inbox").isNull();
    verify(userStateQueryRepository).save(mutedState);
    // ASSIGN ist ohnehin vom Mute ausgenommen und kommt weiterhin an.
    verify(notificationService)
        .emit(
            eq(exAssigneeRef),
            eq(taskId),
            eq(NotificationTypeCodes.ASSIGN),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  @Test
  @DisplayName(
      "mute_put_unveraenderter_assignee_bleibt_stumm: PUT mit einem unveraendert bleibenden"
          + " stummen Assignee und einer neuen Person -> nur die neue Person wird angefasst")
  void mute_put_unveraenderter_assignee_bleibt_stumm() {
    final Long taskId = 701L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID unchangedRef = UUID.randomUUID();
    final UUID newRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(activeAssignee(taskId, unchangedRef, false)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, newRef))
        .thenReturn(List.of());
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final TaskUserState unchangedState = mutedState(taskId, unchangedRef);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, unchangedRef))
        .thenReturn(List.of(unchangedState));
    final TaskUserState newState = mutedState(taskId, newRef);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, newRef))
        .thenReturn(List.of(newState));

    serviceWithRealUserState()
        .updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(unchangedRef, newRef)));

    assertThat(unchangedState.isMuted()).as("unveraenderter Assignee bleibt stumm").isTrue();
    verify(userStateQueryRepository, never()).save(unchangedState);
    assertThat(newState.isMuted()).as("neu zugewiesene Person ist entstummt").isFalse();
    verify(userStateQueryRepository).save(newState);
  }

  @Test
  @DisplayName(
      "mute_handover_ziel_wird_entstummt: HANDOVER-Ziel war kein aktiver Assignee (ausgetretener"
          + " Ex-Assignee mit stummem State) -> IS_MUTED=false")
  void mute_handover_ziel_wird_entstummt() {
    final Long taskId = 702L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID targetRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, false)));
    // Ziel-Zeile: ausgetretener Ex-Assignee (PERSON, task-tenant) -> wird neu zugewiesen.
    final TaskParticipant exitedTarget = activeAssignee(taskId, targetRef, false);
    exitedTarget.setEntityState(EntityState.ACTIVE);
    exitedTarget.setExitKind(ExitKind.UNSUBSCRIBED);
    exitedTarget.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findRowByUniqueKey(taskId, targetRef))
        .thenReturn(List.of(exitedTarget));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final TaskUserState targetState = mutedState(taskId, targetRef);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, targetRef))
        .thenReturn(List.of(targetState));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));

    serviceWithRealUserState()
        .exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(targetRef), false));

    assertThat(targetState.isMuted()).as("Uebergabe hebt Mute des Ziels auf").isFalse();
    assertThat(targetState.getUnsubscribedAt()).isNull();
    verify(userStateQueryRepository).save(targetState);
    // HANDOVER ist ohnehin vom Mute ausgenommen und kommt weiterhin an.
    verify(notificationService)
        .emit(
            eq(targetRef),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  @Test
  @DisplayName(
      "mute_handover_ziel_bereits_aktiver_assignee_bleibt_stumm: HANDOVER an eine Person, die"
          + " schon aktiver Assignee ist, ist keine neue Zuweisung -> IS_MUTED bleibt true")
  void mute_handover_ziel_bereits_aktiver_assignee_bleibt_stumm() {
    final Long taskId = 704L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID targetRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, false)));
    // Ziel-Zeile: bereits aktiver Assignee (PERSON, task-tenant, ACTIVE, kein Austritt).
    final TaskParticipant activeTarget = activeAssignee(taskId, targetRef, false);
    activeTarget.setEntityState(EntityState.ACTIVE);
    when(participantQueryRepository.findRowByUniqueKey(taskId, targetRef))
        .thenReturn(List.of(activeTarget));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final TaskUserState targetState = mutedState(taskId, targetRef);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, targetRef))
        .thenReturn(List.of(targetState));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));

    serviceWithRealUserState()
        .exit(taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(targetRef), false));

    assertThat(targetState.isMuted())
        .as("bereits aktiver Assignee bleibt bei der Uebergabe stumm")
        .isTrue();
    // HANDOVER ist ohnehin vom Mute ausgenommen und kommt weiterhin an.
    verify(notificationService)
        .emit(
            eq(targetRef),
            eq(taskId),
            eq(NotificationTypeCodes.HANDOVER),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  @Test
  @DisplayName(
      "mute_add_participants_laesst_mute_stehen: ausgetretener stummer Teilnehmer wird per"
          + " addParticipants wieder aufgenommen -> IS_MUTED bleibt true (keine Zuweisung)")
  void mute_add_participants_laesst_mute_stehen() {
    final Long taskId = 703L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));

    final TaskParticipant exited = personRow(taskId, ref, EntityState.ACTIVE);
    exited.setId(73L);
    exited.setAssigneeFlag(false);
    exited.setExitKind(ExitKind.UNSUBSCRIBED);
    exited.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref)).thenReturn(List.of(exited));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    final TaskUserState mutedState = mutedState(taskId, ref);
    mutedState.setUnsubscribedAt(java.time.LocalDateTime.now().minusDays(1));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ref))
        .thenReturn(List.of(mutedState));

    serviceWithRealUserState().addParticipants(taskUuid, new ParticipantAddInput(List.of(ref)));

    assertThat(mutedState.isMuted()).as("addParticipants hebt Mute nicht auf").isTrue();
    assertThat(mutedState.getUnsubscribedAt()).as("wieder aktiv als Teilnehmer").isNull();
    // Intake #147: PARTICIPANT ist direkt persoenlich und vom Mute ausgenommen; die Person bekommt
    // die Benachrichtigung trotz Stummschaltung (Endpunkt-Beweis: CommentControllerIT).
    verify(notificationService, times(1))
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.PARTICIPANT),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
  }

  // ============================================================================================
  // Intake #163: PUT /assignees sucht die Zeile einer neu zuzuweisenden Person ueber den Unique-
  // Key (wie addParticipants seit #156). Eine DEACTIVE-PERSON-Zeile wird reaktiviert statt per
  // Insert den Unique-Key zu brechen; TEAM-Zeile oder fremdes Basis-TENANT_ID -> 409 ohne Mutation.
  // ============================================================================================

  @Test
  @DisplayName(
      "i163_put_reaktiviert_deactive_person_zeile_als_assignee: die Person hat eine DEACTIVE-"
          + "PERSON-Zeile (vom ACTIVE-Lookup nicht gesehen) -> dieselbe Zeile wird ACTIVE und"
          + " Assignee, kein Insert; reactivateAsAssignee, ASSIGN, Bump, Task-save und Signal wie"
          + " fuer jeden neu Hinzukommenden")
  void i163_put_reactivates_deactive_person_row_as_assignee() {
    final Long taskId = 1630L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    final TaskParticipant deactive = personRow(taskId, ref, EntityState.DEACTIVE);
    deactive.setAssigneeFlag(false);
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref)).thenReturn(List.of(deactive));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ref)));

    final ArgumentCaptor<TaskParticipant> saved = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantQueryRepository).save(saved.capture());
    assertThat(saved.getValue()).as("dieselbe Zeile, kein Insert").isSameAs(deactive);
    assertThat(deactive.getEntityState()).isEqualTo(EntityState.ACTIVE);
    assertThat(deactive.isAssigneeFlag()).isTrue();
    assertThat(deactive.isParticipantFlag()).isTrue();
    assertThat(deactive.getExitKind()).isNull();
    assertThat(deactive.getExitAt()).isNull();
    verify(userStateService).reactivateAsAssignee(eq(taskId), eq(ref), eq(TENANT_ID), any());
    verify(notificationService)
        .emit(
            eq(ref),
            eq(taskId),
            eq(NotificationTypeCodes.ASSIGN),
            eq(NotificationSeverityCodes.INFO),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(inboxGenerationService).bump(TENANT_ID, ref);
    verify(taskRepository).save(task);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), any());
  }

  @Test
  @DisplayName(
      "i163_put_reaktiviert_deactive_und_ausgetretene_zeile: DEACTIVE-PERSON-Zeile mit gesetztem"
          + " Austritt -> ACTIVE, Austritt geloescht, Assignee")
  void i163_put_reactivates_deactive_and_exited_row() {
    final Long taskId = 1631L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID ref = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    final TaskParticipant row = personRow(taskId, ref, EntityState.DEACTIVE);
    row.setAssigneeFlag(true);
    row.setExitKind(ExitKind.UNSUBSCRIBED);
    row.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    when(participantQueryRepository.findRowByUniqueKey(taskId, ref)).thenReturn(List.of(row));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ref)));

    verify(participantQueryRepository).save(row);
    assertThat(row.getEntityState()).isEqualTo(EntityState.ACTIVE);
    assertThat(row.isAssigneeFlag()).isTrue();
    assertThat(row.getExitKind()).isNull();
    assertThat(row.getExitAt()).isNull();
    verify(userStateService).reactivateAsAssignee(eq(taskId), eq(ref), eq(TENANT_ID), any());
  }

  @Test
  @DisplayName(
      "i163_put_team_zeile_oder_fremder_mandant_409_ohne_mutation: liegt unter dem Unique-Key eine"
          + " TEAM-Zeile oder eine Zeile mit fremdem Basis-TENANT_ID -> 409 (BusinessRuleException),"
          + " kein Save, keine Benachrichtigung, kein Task-save, kein Signal")
  void i163_put_team_row_or_foreign_tenant_409_without_mutation() {
    final Long taskId = 1632L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID teamRef = UUID.randomUUID();
    final UUID foreignRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    final TaskParticipant team = personRow(taskId, teamRef, EntityState.ACTIVE);
    team.setParticipantKind(ParticipantKind.TEAM);
    final TaskParticipant foreign = personRow(taskId, foreignRef, EntityState.ACTIVE);
    foreign.setTenantId(TENANT_ID + 1);
    when(participantQueryRepository.findRowByUniqueKey(taskId, teamRef)).thenReturn(List.of(team));
    when(participantQueryRepository.findRowByUniqueKey(taskId, foreignRef))
        .thenReturn(List.of(foreign));

    assertThatThrownBy(
            () -> service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(teamRef))))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(
            () -> service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(foreignRef))))
        .isInstanceOf(BusinessRuleException.class);

    assertThat(team.getParticipantKind()).isEqualTo(ParticipantKind.TEAM);
    assertThat(team.isAssigneeFlag()).isFalse();
    assertThat(foreign.isAssigneeFlag()).isFalse();
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateService, never()).reactivateAsAssignee(any(), any(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(taskRepository, never()).save(any(Task.class));
    verify(inboxSignalService, never()).invalidate(anyLong(), any());
  }

  // ============================================================================================
  // Intake #145: DELETE /tasks/{id}/participants/{userId} (Beteiligten entfernen)
  // ============================================================================================

  @Test
  @DisplayName(
      "i145_ersteller_entfernt_assignee: Austritt UNSUBSCRIBED, Zuweisung weg, State abgemeldet,"
          + " Historie, REMOVED nur an die entfernte Person, Aufgabe gespeichert, Bump und Signal"
          + " fuer die entfernte Person, die aktiven Leser und die ausgetretenen Archivleser")
  void i145_creator_removes_assignee() {
    final Long taskId = 1451L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID otherReader = UUID.randomUUID();
    final UUID archivedReader = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant actorRow = activeAssignee(taskId, ACTOR_REF, true);
    final TaskParticipant target = activeAssignee(taskId, ASSIGNEE_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actorRow));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(target));
    final TaskUserState state = userStateFor(taskId, ASSIGNEE_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(state));
    // Gelesen wird vor dem Austritt (Review #145 Runde 2): die entfernte Person steht noch unter
    // den aktiven Lesern, unter den Archivlesern nur ein frueher ausgetretener Leser.
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(
            List.of(
                userStateFor(taskId, ACTOR_REF),
                userStateFor(taskId, otherReader),
                userStateFor(taskId, ASSIGNEE_REF)));
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId))
        .thenReturn(List.of(exitedRow(taskId, archivedReader)));

    service.removeParticipant(taskUuid, ASSIGNEE_REF);

    assertThat(target.getExitKind()).isEqualTo(ExitKind.UNSUBSCRIBED);
    assertThat(target.getExitAt()).isNotNull();
    assertThat(target.isAssigneeFlag()).as("die Zuweisung entfaellt").isFalse();
    assertThat(target.isParticipantFlag()).isTrue();
    assertThat(target.getEntityState())
        .as("die Zeile bleibt ACTIVE (Archivzugriff)")
        .isNotEqualTo(EntityState.DEACTIVE);
    assertThat(state.getUnsubscribedAt()).isNotNull();
    assertThat(actorRow.getExitKind()).isNull();

    final InOrder order =
        inOrder(
            participantQueryRepository,
            userStateQueryRepository,
            taskHistoryService,
            notificationService,
            taskRepository,
            inboxGenerationService,
            inboxSignalService);
    // Alle Reads vor der ersten Schreibung (Review #145 Runde 2), dann die Schreibungen, dann der
    // TASK-save, dann Bump und Signal.
    order.verify(userStateQueryRepository).findByTaskIdAndUserRef(taskId, ASSIGNEE_REF);
    order.verify(userStateQueryRepository).findActiveUserRefsByTaskId(taskId);
    order.verify(participantQueryRepository).findArchiveReadersByTaskId(taskId);
    order.verify(participantQueryRepository).save(target);
    order.verify(userStateQueryRepository).save(state);
    order.verify(taskHistoryService).record(eq(taskId), eq(TENANT_ID), eq(ACTOR_REF), any(), any());
    order
        .verify(notificationService)
        .emit(
            eq(ASSIGNEE_REF),
            eq(taskId),
            eq(NotificationTypeCodes.REMOVED),
            eq(NotificationSeverityCodes.INFO),
            any(),
            eq("Testtitel"),
            eq(ACTOR_REF));
    order.verify(taskRepository).save(task);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> bumped = ArgumentCaptor.forClass(Collection.class);
    order.verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), bumped.capture());
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> invalidated = ArgumentCaptor.forClass(Collection.class);
    order.verify(inboxSignalService).invalidate(eq(TENANT_ID), invalidated.capture());
    assertThat(bumped.getValue())
        .as(
            "entfernte Person, aktive Leser und Archivleser, jede genau einmal (die entfernte"
                + " Person steht in beiden Lesungen)")
        .containsExactlyInAnyOrder(ASSIGNEE_REF, ACTOR_REF, otherReader, archivedReader);
    assertThat(invalidated.getValue())
        .as("dieselbe Menge wie der Bump")
        .containsExactlyInAnyOrderElementsOf(bumped.getValue());
    verify(notificationService, times(1)).emit(any(), any(), any(), any(), any(), any(), any());
    verify(notificationService, never())
        .emit(eq(ACTOR_REF), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "i145_entfernen_alle_reads_vor_der_ersten_schreibung: kein Lesezugriff (Aufgabe, Guard,"
          + " Teilnehmer, States, Benutzer) nach der ersten Teilnehmer-, State-, Historien- oder"
          + " Benachrichtigungs-Schreibung; der Seam liegt dazwischen; danach TASK-save, Bump, Signal")
  void i145_removal_reads_before_first_write() {
    final Long taskId = 14510L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID otherReader = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, true)));
    final TaskParticipant target = activeAssignee(taskId, ASSIGNEE_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(target));
    final TaskUserState state = userStateFor(taskId, ASSIGNEE_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(state));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(userStateFor(taskId, otherReader), state));
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId)).thenReturn(List.of());

    final Object[] observed = {
      taskRepository,
      membershipGuard,
      participantQueryRepository,
      userStateQueryRepository,
      userReadRepository,
      taskHistoryService,
      notificationService,
      inboxGenerationService,
      inboxSignalService
    };
    // Seam-Beobachtung: zum Zeitpunkt des Seams sind alle Reads gelaufen und noch nichts
    // geschrieben.
    final List<String> atSeam = new ArrayList<>();
    final AssigneeService seamed =
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
            taskHistoryService,
            membershipGuard,
            permissionEvaluator) {
          @Override
          protected void onBeforeRemoveMutationForTest(final Long id, final UUID ref) {
            orderedCalls(observed).forEach(atSeam::add);
          }
        };
    org.mockito.Mockito.clearInvocations(observed);

    seamed.removeParticipant(taskUuid, ASSIGNEE_REF);

    final List<String> calls = orderedCalls(observed);
    final Set<String> reads =
        Set.of(
            "findByUuid",
            "requireWriteMember",
            "findByTaskIdAndParticipantRef",
            "findByTaskIdAndUserRef",
            "findActiveByUuids",
            "findActiveUserRefsByTaskId",
            "findArchiveReadersByTaskId");
    final Set<String> membershipWrites = Set.of("save", "record", "emit");
    int lastRead = -1;
    int firstWrite = Integer.MAX_VALUE;
    for (int i = 0; i < calls.size(); i++) {
      final String method = calls.get(i).substring(calls.get(i).indexOf('#') + 1);
      if (reads.contains(method)) {
        lastRead = i;
      } else if (membershipWrites.contains(method)) {
        firstWrite = Math.min(firstWrite, i);
      }
    }
    assertThat(lastRead)
        .as("letzter Read vor der ersten Schreibung, Aufrufe: %s", calls)
        .isLessThan(firstWrite);
    assertThat(atSeam)
        .as("am Seam sind Ziel-State, Namen und beide Leser-Lesungen gelaufen")
        .contains(
            "TaskUserStateQueryRepository#findByTaskIdAndUserRef",
            "UserReadRepository#findActiveByUuids",
            "TaskUserStateQueryRepository#findActiveUserRefsByTaskId",
            "TaskParticipantQueryRepository#findArchiveReadersByTaskId");
    assertThat(atSeam)
        .as("am Seam ist noch nichts geschrieben")
        .noneMatch(c -> membershipWrites.contains(c.substring(c.indexOf('#') + 1)));
    // Schreibreihenfolge: Teilnehmer, State, Historie, Benachrichtigung, Aufgabe, Bump, Signal.
    assertThat(calls.subList(firstWrite, calls.size()))
        .containsSubsequence(
            "TaskParticipantQueryRepository#save",
            "TaskUserStateQueryRepository#save",
            "TaskHistoryService#record",
            "NotificationService#emit",
            "BaseTaskRepository#save",
            "InboxGenerationService#bumpSorted",
            "InboxSignalService#invalidate");
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> bumped = ArgumentCaptor.forClass(Collection.class);
    verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), bumped.capture());
    assertThat(bumped.getValue())
        .as("Menge unveraendert: entfernte Person und aktive Leser")
        .containsExactlyInAnyOrder(ASSIGNEE_REF, otherReader);
  }

  /**
   * Alle bisherigen Aufrufe der beobachteten Mocks in globaler Reihenfolge als {@code Typ#methode}
   * (Mockito-Sequenznummer; Typ ist das gemockte Interface bzw. die Klasse).
   */
  // ---- Intake #166/#167: Lock-Reihenfolge und Lesermenge von PUT, Hinzufuegen und Austritt ------

  /** Lesende Mock-Methoden der Mitgliedschaftspfade (Aufgabe, Guard, Teilnehmer, States, Namen). */
  private static final Set<String> MEMBERSHIP_READS =
      Set.of(
          "findByUuid",
          "requireWriteMember",
          "isWriteMember",
          "findActiveAssigneesByTaskId",
          "findByTaskIdAndParticipantRef",
          "findRowByUniqueKey",
          "findByTaskIdAndUserRef",
          "findActiveByUuids",
          "findActiveUserRefsByTaskId",
          "findArchiveReadersByTaskId");

  /** Schreibende Mock-Methoden (Teilnehmer, State, Historie, Benachrichtigung, Aufgabe). */
  private static final Set<String> MEMBERSHIP_WRITES =
      Set.of("save", "record", "emit", "reactivate", "reactivateAsAssignee", "ensure");

  /** Beobachtete Mocks der Reihenfolge-Tests (in Aufrufreihenfolge ausgewertet). */
  private Object[] orderObserved() {
    return new Object[] {
      taskRepository,
      membershipGuard,
      participantQueryRepository,
      userStateQueryRepository,
      userReadRepository,
      userStateService,
      handoverRepository,
      handoverTargetRepository,
      taskHistoryService,
      notificationService,
      inboxGenerationService,
      inboxSignalService
    };
  }

  /** Service, der am Sync-1-Seam die bis dahin gelaufenen Aufrufe festhaelt. */
  private AssigneeService serviceRecordingAtSeam(final List<String> atSeam) {
    final Object[] observed = orderObserved();
    return new AssigneeService(
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
        taskHistoryService,
        membershipGuard,
        permissionEvaluator) {
      @Override
      protected void onBeforeMembershipWritesForTest(final Long id) {
        atSeam.clear();
        atSeam.addAll(orderedCalls(observed));
      }
    };
  }

  /** Letzter Read vor der ersten Schreibung; am Seam ist nichts geschrieben. */
  private static void assertReadsBeforeFirstWrite(
      final List<String> calls, final List<String> atSeam) {
    int lastRead = -1;
    int firstWrite = Integer.MAX_VALUE;
    for (int i = 0; i < calls.size(); i++) {
      final String method = calls.get(i).substring(calls.get(i).indexOf('#') + 1);
      if (MEMBERSHIP_READS.contains(method)) {
        lastRead = i;
      } else if (MEMBERSHIP_WRITES.contains(method)) {
        firstWrite = Math.min(firstWrite, i);
      }
    }
    assertThat(lastRead)
        .as("letzter Read vor der ersten Schreibung, Aufrufe: %s", calls)
        .isLessThan(firstWrite);
    assertThat(atSeam)
        .as("am Seam sind beide Leser-Lesungen gelaufen")
        .contains(
            "TaskUserStateQueryRepository#findActiveUserRefsByTaskId",
            "TaskParticipantQueryRepository#findArchiveReadersByTaskId");
    assertThat(atSeam)
        .as("am Seam ist noch nichts geschrieben")
        .noneMatch(call -> MEMBERSHIP_WRITES.contains(call.substring(call.indexOf('#') + 1)));
  }

  /** Menge des Generation-Bumps; das Signal geht an dieselbe Menge. */
  @SuppressWarnings("unchecked")
  private Collection<UUID> capturedBump() {
    final ArgumentCaptor<Collection<UUID>> bumped = ArgumentCaptor.forClass(Collection.class);
    verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), bumped.capture());
    final ArgumentCaptor<Collection<UUID>> invalidated = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), invalidated.capture());
    assertThat(invalidated.getValue())
        .as("Signal fuer dieselbe Menge wie der Bump")
        .containsExactlyInAnyOrderElementsOf(bumped.getValue());
    return bumped.getValue();
  }

  @Test
  @DisplayName(
      "i167_put_alle_reads_vor_der_ersten_schreibung: Zeilen und States der neuen und der"
          + " abgewaehlten Person sowie beide Leser-Lesungen laufen vor dem Sync-1-Seam; danach nur"
          + " Schreibungen, TASK-save, Bump, Signal; Bump und Signal enthalten den Archivleser (#166)")
  void i167_put_reads_before_first_write_and_archive_reader_bumped() {
    final Long taskId = 16701L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID newRef = UUID.randomUUID();
    final UUID activeReader = UUID.randomUUID();
    final UUID archiveReader = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant removed = activeAssignee(taskId, ASSIGNEE_REF, false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, true), removed));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(removed));
    final TaskUserState removedState = userStateFor(taskId, ASSIGNEE_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(removedState));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(
            List.of(
                userStateFor(taskId, ACTOR_REF), userStateFor(taskId, activeReader), removedState));
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId))
        .thenReturn(List.of(exitedRow(taskId, archiveReader)));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    final List<String> atSeam = new ArrayList<>();
    final AssigneeService seamed = serviceRecordingAtSeam(atSeam);
    final Object[] observed = orderObserved();
    org.mockito.Mockito.clearInvocations(observed);

    seamed.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(ACTOR_REF, newRef)));

    final List<String> calls = orderedCalls(observed);
    assertReadsBeforeFirstWrite(calls, atSeam);
    assertThat(atSeam)
        .as("am Seam sind Zeile und State der neuen und State der abgewaehlten Person gelesen")
        .contains(
            "TaskParticipantQueryRepository#findRowByUniqueKey",
            "TaskParticipantQueryRepository#findByTaskIdAndParticipantRef",
            "TaskUserStateQueryRepository#findByTaskIdAndUserRef");
    assertThat(calls)
        .containsSubsequence(
            "UserStateService#reactivateAsAssignee",
            "TaskParticipantQueryRepository#save",
            "TaskUserStateQueryRepository#save",
            "BaseTaskRepository#save",
            "InboxGenerationService#bumpSorted",
            "InboxSignalService#invalidate");
    verify(userStateService)
        .reactivateAsAssignee(eq(taskId), eq(newRef), eq(TENANT_ID), eq(List.of()));
    assertThat(removedState.getUnsubscribedAt())
        .as("State der Abgewaehlten abgemeldet")
        .isNotNull();
    assertThat(capturedBump())
        .as("geaenderte Personen, aktive Leser und Archivleser (Intake #166)")
        .containsExactlyInAnyOrder(newRef, ASSIGNEE_REF, ACTOR_REF, activeReader, archiveReader);
  }

  @Test
  @DisplayName(
      "i167_exit_alle_reads_vor_der_ersten_schreibung: State des Akteurs, Namen und beide"
          + " Leser-Lesungen laufen vor dem Sync-1-Seam; danach Teilnehmer, State, Historie,"
          + " TASK-save, Bump, Signal")
  void i167_exit_reads_before_first_write() {
    final Long taskId = 16702L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID otherReader = UUID.randomUUID();
    final UUID archiveReader = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, false)));
    final TaskUserState actorState = userStateFor(taskId, ACTOR_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ACTOR_REF))
        .thenReturn(List.of(actorState));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(actorState, userStateFor(taskId, otherReader)));
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId))
        .thenReturn(List.of(exitedRow(taskId, archiveReader)));
    final List<String> atSeam = new ArrayList<>();
    final AssigneeService seamed = serviceRecordingAtSeam(atSeam);
    final Object[] observed = orderObserved();
    org.mockito.Mockito.clearInvocations(observed);

    seamed.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED));

    final List<String> calls = orderedCalls(observed);
    assertReadsBeforeFirstWrite(calls, atSeam);
    assertThat(atSeam)
        .as("am Seam sind State und Namen des Akteurs gelesen")
        .contains(
            "TaskUserStateQueryRepository#findByTaskIdAndUserRef",
            "UserReadRepository#findActiveByUuids");
    assertThat(calls)
        .containsSubsequence(
            "TaskParticipantQueryRepository#save",
            "TaskUserStateQueryRepository#save",
            "TaskHistoryService#record",
            "BaseTaskRepository#save",
            "InboxGenerationService#bumpSorted",
            "InboxSignalService#invalidate");
    assertThat(actorState.getUnsubscribedAt()).as("State des Akteurs abgemeldet").isNotNull();
    assertThat(capturedBump())
        .as("Akteur, aktive Leser und Archivleser")
        .containsExactlyInAnyOrder(ACTOR_REF, otherReader, archiveReader);
  }

  @Test
  @DisplayName(
      "i167_hinzufuegen_leser_vor_der_ersten_schreibung_und_task_save: beide Leser-Lesungen vor"
          + " dem Sync-1-Seam, keine danach; Aufnahme, Benachrichtigung, TASK-save, Bump und"
          + " Signal mit Archivleser (#166)")
  void i167_add_readers_before_first_write_task_saved_archive_reader_bumped() {
    final Long taskId = 16703L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID newRef = UUID.randomUUID();
    final UUID archiveReader = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(userStateFor(taskId, ACTOR_REF)));
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId))
        .thenReturn(List.of(exitedRow(taskId, archiveReader)));
    when(participantQueryRepository.save(any(TaskParticipant.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    final List<String> atSeam = new ArrayList<>();
    final AssigneeService seamed = serviceRecordingAtSeam(atSeam);
    final Object[] observed = orderObserved();
    org.mockito.Mockito.clearInvocations(observed);

    seamed.addParticipants(taskUuid, new ParticipantAddInput(List.of(newRef)));

    final List<String> calls = orderedCalls(observed);
    assertThat(atSeam)
        .as("am Seam sind beide Leser-Lesungen gelaufen")
        .contains(
            "TaskUserStateQueryRepository#findActiveUserRefsByTaskId",
            "TaskParticipantQueryRepository#findArchiveReadersByTaskId");
    assertThat(atSeam)
        .as("am Seam ist noch nichts geschrieben")
        .noneMatch(call -> MEMBERSHIP_WRITES.contains(call.substring(call.indexOf('#') + 1)));
    assertThat(calls.subList(atSeam.size(), calls.size()))
        .as("nach dem Seam keine Leser-Lesung mehr")
        .doesNotContain(
            "TaskUserStateQueryRepository#findActiveUserRefsByTaskId",
            "TaskParticipantQueryRepository#findArchiveReadersByTaskId");
    assertThat(calls)
        .containsSubsequence(
            "TaskParticipantQueryRepository#save",
            "UserStateService#ensure",
            "NotificationService#emit",
            "BaseTaskRepository#save",
            "InboxGenerationService#bumpSorted",
            "InboxSignalService#invalidate");
    verify(taskRepository).save(task);
    assertThat(capturedBump())
        .as("neue Person, aktive Leser und Archivleser (Intake #166)")
        .containsExactlyInAnyOrder(newRef, ACTOR_REF, archiveReader);
  }

  @Test
  @DisplayName(
      "i167_hinzufuegen_ohne_aenderung: schon aktive Person -> kein TASK-save, keine"
          + " Benachrichtigung, kein Bump, kein Signal (204, auch fuer die Verliererin eines"
          + " gleich-Ref-Race)")
  void i167_add_without_change_saves_nothing() {
    final Long taskId = 16704L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID activeRef = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    when(participantQueryRepository.findRowByUniqueKey(taskId, activeRef))
        .thenReturn(List.of(personRow(taskId, activeRef, EntityState.ACTIVE)));

    service.addParticipants(taskUuid, new ParticipantAddInput(List.of(activeRef)));

    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(taskRepository, never()).save(any(Task.class));
    verify(inboxGenerationService, never()).bumpSorted(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(anyLong(), any());
  }

  private static List<String> orderedCalls(final Object... mocks) {
    final List<org.mockito.invocation.Invocation> all = new ArrayList<>();
    for (final Object m : mocks) {
      all.addAll(org.mockito.Mockito.mockingDetails(m).getInvocations());
    }
    all.sort(
        java.util.Comparator.comparingInt(org.mockito.invocation.Invocation::getSequenceNumber));
    final List<String> out = new ArrayList<>();
    for (final org.mockito.invocation.Invocation inv : all) {
      final Class<?> type =
          org.mockito.Mockito.mockingDetails(inv.getMock())
              .getMockCreationSettings()
              .getTypeToMock();
      out.add(type.getSimpleName() + "#" + inv.getMethod().getName());
    }
    return out;
  }

  @Test
  @DisplayName(
      "i145_entfernen_bumpt_entfernte_person_auch_ohne_treffer_in_den_leserlesungen: ohne"
          + " Archivleser und ohne aktive Leser bleibt die entfernte Person in Bump und Signal,"
          + " fremde Personen kommen nicht hinzu")
  void i145_removed_person_is_always_in_affected_set() {
    final Long taskId = 1459L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID participantRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, true)));
    final TaskParticipant target = personRow(taskId, participantRef, EntityState.ACTIVE);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, participantRef))
        .thenReturn(List.of(target));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, participantRef))
        .thenReturn(List.of());
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId)).thenReturn(List.of());

    service.removeParticipant(taskUuid, participantRef);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> bumped = ArgumentCaptor.forClass(Collection.class);
    verify(inboxGenerationService).bumpSorted(eq(TENANT_ID), bumped.capture());
    assertThat(bumped.getValue()).containsExactly(participantRef);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> invalidated = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), invalidated.capture());
    assertThat(invalidated.getValue()).containsExactly(participantRef);
  }

  @Test
  @DisplayName(
      "i145_aktiver_assignee_entfernt_reinen_beteiligten: ein Nicht-Ersteller mit aktiver"
          + " Zuweisung darf entfernen")
  void i145_active_assignee_removes_pure_participant() {
    final Long taskId = 1452L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID participantRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, false)));
    final TaskParticipant target = personRow(taskId, participantRef, EntityState.ACTIVE);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, participantRef))
        .thenReturn(List.of(target));
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, participantRef))
        .thenReturn(List.of());
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.removeParticipant(taskUuid, participantRef);

    assertThat(target.getExitKind()).isEqualTo(ExitKind.UNSUBSCRIBED);
    verify(participantQueryRepository).save(target);
    verify(notificationService)
        .emit(
            eq(participantRef),
            eq(taskId),
            eq(NotificationTypeCodes.REMOVED),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    verify(taskRepository).save(task);
  }

  @Test
  @DisplayName(
      "i145_entfernen_stummgeschaltete_person: REMOVED wird trotz IS_MUTED genau einmal emittiert,"
          + " ohne Mute-Lesung; der Mute-Marker bleibt gesetzt, der State wird abgemeldet")
  void i145_remove_muted_person_still_emits_removed_and_keeps_mute_marker() {
    final Long taskId = 1460L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID participantRef = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, true)));
    final TaskParticipant target = personRow(taskId, participantRef, EntityState.ACTIVE);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, participantRef))
        .thenReturn(List.of(target));
    final TaskUserState mutedState = mutedState(taskId, participantRef);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, participantRef))
        .thenReturn(List.of(mutedState));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findArchiveReadersByTaskId(taskId)).thenReturn(List.of());

    service.removeParticipant(taskUuid, participantRef);

    verify(notificationService, times(1))
        .emit(
            eq(participantRef),
            eq(taskId),
            eq(NotificationTypeCodes.REMOVED),
            any(),
            any(),
            any(),
            eq(ACTOR_REF));
    assertThat(mutedState.isMuted()).as("das Entfernen aendert den Mute-Marker nicht").isTrue();
    assertThat(mutedState.getUnsubscribedAt()).as("der State wird abgemeldet").isNotNull();
    verify(userStateQueryRepository).save(mutedState);
    verify(userStateQueryRepository, never()).findMutedByTaskId(any());
    assertThat(target.getExitKind()).isEqualTo(ExitKind.UNSUBSCRIBED);
  }

  @Test
  @DisplayName(
      "i145_anderes_write_mitglied_403_ohne_mutation: reiner Beteiligter (weder Ersteller noch"
          + " aktiver Assignee) -> PermissionDeniedException, nichts geschrieben")
  void i145_other_write_member_403_without_mutation() {
    final Long taskId = 1453L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(personRow(taskId, ACTOR_REF, EntityState.ACTIVE)));
    final TaskParticipant target = activeAssignee(taskId, ASSIGNEE_REF, false);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(target));

    assertThatThrownBy(() -> service.removeParticipant(taskUuid, ASSIGNEE_REF))
        .isInstanceOf(PermissionDeniedException.class);

    assertThat(target.getExitKind()).isNull();
    assertThat(target.isAssigneeFlag()).isTrue();
    assertNoRemovalMutation();
  }

  @Test
  @DisplayName("i145_ersteller_als_ziel_409: der Ersteller kann nicht entfernt werden")
  void i145_creator_target_409() {
    final Long taskId = 1454L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID creatorRef = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, false)));
    final TaskParticipant creator = activeAssignee(taskId, creatorRef, true);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, creatorRef))
        .thenReturn(List.of(creator));

    assertThatThrownBy(() -> service.removeParticipant(taskUuid, creatorRef))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("Ersteller");

    assertThat(creator.getExitKind()).isNull();
    assertThat(creator.isAssigneeFlag()).isTrue();
    assertNoRemovalMutation();
  }

  @Test
  @DisplayName(
      "i145_sich_selbst_entfernen_400: Hinweis auf POST /exit, vor der Rechtepruefung, ohne"
          + " Mutation")
  void i145_self_removal_400() {
    final Long taskId = 1455L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(personRow(taskId, ACTOR_REF, EntityState.ACTIVE)));

    assertThatThrownBy(() -> service.removeParticipant(taskUuid, ACTOR_REF))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("/exit");

    assertNoRemovalMutation();
  }

  @Test
  @DisplayName(
      "i145_idempotent: unbekannte Person, nie beteiligt oder schon ausgetreten -> keine"
          + " Mutation, keine Benachrichtigung, kein Signal")
  void i145_idempotent_unknown_or_exited() {
    final Long taskId = 1456L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID unknownRef = UUID.randomUUID();
    final UUID exitedRef = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ACTOR_REF))
        .thenReturn(List.of(activeAssignee(taskId, ACTOR_REF, true)));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, unknownRef))
        .thenReturn(List.of());
    final TaskParticipant exited = activeAssignee(taskId, exitedRef, false);
    exited.setExitKind(ExitKind.UNSUBSCRIBED);
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, exitedRef))
        .thenReturn(List.of(exited));

    service.removeParticipant(taskUuid, unknownRef);
    service.removeParticipant(taskUuid, exitedRef);

    assertThat(exited.isAssigneeFlag()).as("ausgetretene Zeile unveraendert").isTrue();
    assertNoRemovalMutation();
  }

  @Test
  @DisplayName(
      "i145_guard_404_vor_allem: kein WRITE-Mitglied -> 404, weder Rechte- noch Ziellesung")
  void i145_guard_404_first() {
    final Long taskId = 1457L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString()))
        .thenReturn(Optional.of(taskWithId(taskId, taskUuid)));
    org.mockito.Mockito.doThrow(new EntityNotFoundException("Aufgabe nicht gefunden."))
        .when(membershipGuard)
        .requireWriteMember(taskId, ACTOR_REF, TENANT_ID);

    assertThatThrownBy(() -> service.removeParticipant(taskUuid, ACTOR_REF))
        .isInstanceOf(EntityNotFoundException.class);

    verify(participantQueryRepository, never()).findByTaskIdAndParticipantRef(any(), any());
    assertNoRemovalMutation();
  }

  @Test
  @DisplayName(
      "i145_put_weglassen_unveraendert: das Weglassen in PUT /assignees nutzt denselben"
          + " Austrittskern, behaelt aber sein Verhalten (ASSIGNEE_FLAG bleibt, keine Historie, kein"
          + " REMOVED)")
  void i145_put_omission_behaviour_unchanged() {
    final Long taskId = 1458L;
    final UUID taskUuid = UUID.randomUUID();
    final Task task = taskWithId(taskId, taskUuid);
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task));
    final TaskParticipant removed = activeAssignee(taskId, ASSIGNEE_REF, false);
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(removed));
    when(participantQueryRepository.findByTaskIdAndParticipantRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(removed));
    final TaskUserState state = userStateFor(taskId, ASSIGNEE_REF);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, ASSIGNEE_REF))
        .thenReturn(List.of(state));
    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId)).thenReturn(List.of());

    service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of()));

    assertThat(removed.getExitKind()).isEqualTo(ExitKind.UNSUBSCRIBED);
    assertThat(removed.getExitAt()).isNotNull();
    assertThat(removed.isAssigneeFlag()).isTrue();
    assertThat(state.getUnsubscribedAt()).isNotNull();
    verify(taskHistoryService, never()).record(any(), any(), any(), any(), any());
    verify(notificationService, never())
        .emit(any(), any(), eq(NotificationTypeCodes.REMOVED), any(), any(), any(), any());
    verify(taskRepository).save(task);
  }

  /** Kein Schreibzugriff, keine Benachrichtigung, kein Bump und kein Signal des Entfernens. */
  private void assertNoRemovalMutation() {
    verify(participantQueryRepository, never()).save(any(TaskParticipant.class));
    verify(userStateQueryRepository, never()).save(any(TaskUserState.class));
    verify(taskHistoryService, never()).record(any(), any(), any(), any(), any());
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(taskRepository, never()).save(any(Task.class));
    verify(userStateQueryRepository, never()).findActiveUserRefsByTaskId(any());
    verify(participantQueryRepository, never()).findArchiveReadersByTaskId(any());
    verify(inboxGenerationService, never()).bumpSorted(anyLong(), any());
    verify(inboxSignalService, never()).invalidate(anyLong(), any());
  }

  // ---- Hilfsmethode ---------------------------------------------------------------------------

  /** Service mit ECHTEM {@link UserStateService} auf dem gemockten State-Repository. */
  private AssigneeService serviceWithRealUserState() {
    return new AssigneeService(
        taskRepository,
        participantQueryRepository,
        userStateQueryRepository,
        new UserStateService(userStateQueryRepository),
        notificationService,
        inboxSignalService,
        userReadRepository,
        userAccessor,
        inboxGenerationService,
        handoverRepository,
        handoverTargetRepository,
        taskHistoryService,
        membershipGuard,
        permissionEvaluator);
  }

  /** Aktiver, stummgeschalteter {@link TaskUserState} (persoenlicher Marker IS_MUTED=true). */
  private static TaskUserState mutedState(final Long taskId, final UUID ref) {
    final TaskUserState s = userStateFor(taskId, ref);
    s.setMuted(true);
    return s;
  }

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

  /**
   * PERSON-Teilnehmerzeile des Task-Mandanten mit PARTICIPANT_FLAG (kein Assignee, kein Creator,
   * nicht ausgetreten) und dem gegebenen {@code ENTITY_STATUS}: so liefert sie der Unique-Key-
   * Lookup der Aufnahme ({@code findRowByUniqueKey}), auch als {@code DEACTIVE}-Zeile.
   */
  private static TaskParticipant personRow(
      final Long taskId, final UUID ref, final EntityState entityState) {
    final TaskParticipant p = new TaskParticipant();
    p.setId(1L);
    p.setTenantId(TENANT_ID);
    p.setParticipantTenantId(TENANT_ID);
    p.setTaskId(taskId);
    p.setParticipantRef(ref);
    p.setParticipantKind(ParticipantKind.PERSON);
    p.setParticipantFlag(true);
    p.setEntityState(entityState);
    return p;
  }

  /**
   * Ausgetretene (UNSUBSCRIBED) PERSON-Teilnehmerzeile, die {@code ACTIVE} bleibt: ein Archivleser
   * (READ-Mitglied, Archiv "verlassen").
   */
  private static TaskParticipant exitedRow(final Long taskId, final UUID ref) {
    final TaskParticipant p = personRow(taskId, ref, EntityState.ACTIVE);
    p.setExitKind(ExitKind.UNSUBSCRIBED);
    p.setExitAt(java.time.LocalDateTime.now().minusDays(1));
    return p;
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
