package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.InboxGenerationService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
import de.brasi.nova.taskmanagement.refentity.User;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskParticipantRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import de.brasi.nova.taskmanagement.task.model.TaskPatchInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit-Tests fuer {@link TaskCommandService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die drei Kern-Invarianten:
 * <ol>
 *   <li>Anlage erzwingt CUSTOMER_INTERNAL als Direction (serverseitig)
 *   <li>Creator-Participant und UserState werden in DERSELBEN Tx angelegt
 *   <li>snapshot-invalidated Signal wird nach Commit via After-Commit-Seam registriert
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskCommandServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID USER_REF = UUID.randomUUID();

  private static final long DIRECTION_ID_CUSTOMER_INTERNAL = 301L;
  private static final long STATUS_ID_OPEN = 101L;
  private static final long PRIORITY_ID_NORMAL = 202L;

  @Mock private BaseTaskRepository taskRepository;
  @Mock private BaseTaskParticipantRepository participantRepository;
  @Mock private TaskUserStateQueryRepository userStateQueryRepository;
  @Mock private DictionaryCodeResolver resolver;
  @Mock private UserAccessor userAccessor;
  @Mock private UserStateService userStateService;
  @Mock private TaskHistoryService historyService;
  @Mock private InboxSignalService inboxSignalService;
  @Mock private TaskTagRepository tagRepository;
  @Mock private TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;
  @Mock private UserReadRepository userReadRepository;
  @Mock private NotificationService notificationService;
  @Mock private InboxGenerationService inboxGenerationService;

  private TaskCommandService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskCommandService(
            taskRepository,
            participantRepository,
            userStateQueryRepository,
            resolver,
            userAccessor,
            userStateService,
            historyService,
            inboxSignalService,
            tagRepository,
            tagAssignmentQueryRepository,
            userReadRepository,
            notificationService,
            inboxGenerationService);

    // CurrentActor: Mandant + Benutzer
    final TenantInfo tenant = new TenantInfo(TENANT_UUID, TENANT_ID);
    final AuthUser user = new AuthUser(USER_REF, "test@brasi.de");
    when(userAccessor.requireCurrentUser()).thenReturn(user);

    // TenantContextHolder setzen fuer CurrentActor.require
    io.keel.framework.api.security.tenant.TenantContextHolder.setTenant(tenant);

    // Dictionary-Resolver: minimale Stubs
    when(resolver.resolveTaskDirection(TaskDirectionCodes.CUSTOMER_INTERNAL))
        .thenReturn(DIRECTION_ID_CUSTOMER_INTERNAL);
    when(resolver.resolveTaskStatus(TaskStatusCodes.OPEN)).thenReturn(STATUS_ID_OPEN);
    when(resolver.resolveTaskPriority(TaskPriorityCodes.NORMAL)).thenReturn(PRIORITY_ID_NORMAL);
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    io.keel.framework.api.security.tenant.TenantContextHolder.clear();
    // TransactionSynchronization-Registry aufraeuemen
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- Test 1: Direction=CUSTOMER_INTERNAL erzwingen -------------------------------------------

  @Test
  @DisplayName("anlage_erzwingt_customer_internal: Direction-ID wird auf CUSTOMER_INTERNAL gesetzt")
  void anlage_erzwingt_customer_internal() {
    // Arrange: save gibt eine Task mit ID zurueck
    final Task savedTask = taskWithId(42L, UUID.randomUUID());
    when(taskRepository.save(any(Task.class))).thenReturn(savedTask);

    // Act
    service.create(new TaskCreateInput("Testtitel", null, null, null, null, null, null, null, null));

    // Assert: gespeicherte Task hat DIRECTION_ID = CUSTOMER_INTERNAL
    final ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
    verify(taskRepository).save(captor.capture());
    assertThat(captor.getValue().getDirectionId())
        .as("DIRECTION_ID muss CUSTOMER_INTERNAL (%d) sein", DIRECTION_ID_CUSTOMER_INTERNAL)
        .isEqualTo(DIRECTION_ID_CUSTOMER_INTERNAL);

    // Auch: resolver.resolveTaskDirection wurde mit CUSTOMER_INTERNAL aufgerufen
    verify(resolver).resolveTaskDirection(TaskDirectionCodes.CUSTOMER_INTERNAL);
  }

  // ---- Test 2: Creator-Participant + UserState in DERSELBEN Tx anlegen -------------------------

  @Test
  @DisplayName(
      "anlage_legt_creator_participant_und_userstate: TaskParticipant(CREATOR+ASSIGNEE)"
          + " + UserStateService.ensure in gleicher Tx")
  void anlage_legt_creator_participant_und_userstate() {
    final Long taskId = 77L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.save(any(Task.class))).thenReturn(taskWithId(taskId, taskUuid));

    service.create(new TaskCreateInput("Testtitel", null, null, null, null, null, null, null, null));

    // TaskParticipant: CREATOR_FLAG=1, ASSIGNEE_FLAG=1, PARTICIPANT_REF=USER_REF
    final ArgumentCaptor<TaskParticipant> pCaptor = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantRepository).save(pCaptor.capture());
    final TaskParticipant p = pCaptor.getValue();
    assertThat(p.isCreatorFlag())
        .as("CREATOR_FLAG muss true sein")
        .isTrue();
    assertThat(p.isAssigneeFlag())
        .as("ASSIGNEE_FLAG muss true sein")
        .isTrue();
    assertThat(p.getParticipantRef())
        .as("PARTICIPANT_REF muss USER_REF sein")
        .isEqualTo(USER_REF);
    assertThat(p.getTaskId())
        .as("TASK_ID muss die gespeicherte Task-ID sein")
        .isEqualTo(taskId);

    // UserStateService.ensure wird in DERSELBEN Tx (vor Commit) aufgerufen
    verify(userStateService).ensure(eq(taskId), eq(USER_REF), eq(TENANT_ID));
  }

  // ---- Test 3: snapshot-invalidated nach Commit (After-Commit-Seam) ----------------------------

  @Test
  @DisplayName(
      "mutation_ruft_snapshot_invalidate_after_commit: InboxSignalService.invalidate"
          + " wird nach Commit-Hook registriert")
  void mutation_ruft_snapshot_invalidate_after_commit() {
    // Transaktions-Synchronisation aktivieren (simuliert aktive Tx)
    TransactionSynchronizationManager.initSynchronization();

    try {
      final Task savedTask = taskWithId(10L, UUID.randomUUID());
      when(taskRepository.save(any(Task.class))).thenReturn(savedTask);

      service.create(new TaskCreateInput("Invalidierungstest", null, null, null, null, null, null, null, null));

      // InboxSignalService.invalidate wurde mit tenantId + [USER_REF] aufgerufen
      @SuppressWarnings("unchecked")
      final ArgumentCaptor<Collection<UUID>> refsCaptor =
          ArgumentCaptor.forClass(Collection.class);
      verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());

      assertThat(refsCaptor.getValue())
          .as("invalidate muss USER_REF des Creators enthalten")
          .contains(USER_REF);

      // InboxSignalService selbst registriert den After-Commit-Hook intern via
      // TransactionSynchronizationManager.registerSynchronization; kein weiterer Beweis noetig,
      // da InboxSignalService bereits in InboxSignalServiceTest getestet wird.
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- BE-4: Create-Enrichment (assignees/tagIds/marker, All-or-Nothing) -----------------------

  @Test
  @DisplayName(
      "create_mit_assignee_legt_participant_ensure_notify_bump_an: Nicht-Creator-Assignee ->"
          + " Participant(ASSIGNEE)+ensure+ASSIGN-Notify+Bump; Creator+Assignee invalidiert")
  void create_mit_assignee_legt_participant_ensure_notify_bump_an() {
    final Long taskId = 70L;
    final UUID assigneeRef = UUID.randomUUID();
    when(taskRepository.save(any(Task.class))).thenReturn(taskWithId(taskId, UUID.randomUUID()));

    final User activeAssignee = org.mockito.Mockito.mock(User.class);
    when(activeAssignee.getUuid()).thenReturn(assigneeRef);
    when(userReadRepository.findActiveByUuids(Set.of(assigneeRef)))
        .thenReturn(List.of(activeAssignee));

    service.create(
        new TaskCreateInput(
            "Titel", null, null, null, null, List.of(assigneeRef), null, null, null));

    // Zwei Participant-Rows: Creator (CREATOR_FLAG) + Assignee (ASSIGNEE_FLAG, nicht Creator)
    final ArgumentCaptor<TaskParticipant> pc = ArgumentCaptor.forClass(TaskParticipant.class);
    verify(participantRepository, org.mockito.Mockito.times(2)).save(pc.capture());
    final List<TaskParticipant> saved = pc.getAllValues();
    assertThat(saved)
        .as("Creator-Participant vorhanden")
        .anySatisfy(
            p -> {
              assertThat(p.isCreatorFlag()).isTrue();
              assertThat(p.getParticipantRef()).isEqualTo(USER_REF);
            });
    assertThat(saved)
        .as("Assignee-Participant (ASSIGNEE_FLAG, nicht Creator) fuer A2")
        .anySatisfy(
            p -> {
              assertThat(p.isCreatorFlag()).isFalse();
              assertThat(p.isAssigneeFlag()).isTrue();
              assertThat(p.isParticipantFlag()).isTrue();
              assertThat(p.getParticipantRef()).isEqualTo(assigneeRef);
              assertThat(p.getTaskId()).isEqualTo(taskId);
            });

    // ensure fuer Creator UND Assignee (inbox-sichtbar)
    verify(userStateService).ensure(taskId, USER_REF, TENANT_ID);
    verify(userStateService).ensure(taskId, assigneeRef, TENANT_ID);

    // ASSIGN-Notification fuer Assignee, Akteur = Creator (kein Self-Notify)
    verify(notificationService)
        .emit(
            eq(assigneeRef),
            eq(taskId),
            eq(NotificationTypeCodes.ASSIGN),
            any(),
            any(),
            any(),
            eq(USER_REF));

    // Generation-Bump fuer Creator (D3b) UND Assignee (D3)
    verify(inboxGenerationService).bump(TENANT_ID, USER_REF);
    verify(inboxGenerationService).bump(TENANT_ID, assigneeRef);

    // invalidate Creator + Assignee
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refs = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refs.capture());
    assertThat(refs.getValue()).containsExactlyInAnyOrder(USER_REF, assigneeRef);
  }

  @Test
  @DisplayName(
      "create_ohne_zusatz_nur_creator_und_creator_bump: title-only -> nur Creator,"
          + " kein Notify, findActiveByUuids nicht aufgerufen, genau 1 Creator-Bump (D3b)")
  void create_ohne_zusatz_nur_creator_und_creator_bump() {
    final Long taskId = 71L;
    when(taskRepository.save(any(Task.class))).thenReturn(taskWithId(taskId, UUID.randomUUID()));

    service.create(new TaskCreateInput("Titel", null, null, null, null, null, null, null, null));

    verify(participantRepository, org.mockito.Mockito.times(1)).save(any(TaskParticipant.class));
    verify(userStateService).ensure(taskId, USER_REF, TENANT_ID);
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(inboxGenerationService).bump(TENANT_ID, USER_REF);
    verify(inboxGenerationService, org.mockito.Mockito.times(1)).bump(anyLong(), any());
  }

  @Test
  @DisplayName(
      "create_creator_in_assignees_wird_uebersprungen (D2): kein zweiter Participant,"
          + " kein Self-Notify, findActiveByUuids nicht aufgerufen (leere Rest-Menge)")
  void create_creator_in_assignees_wird_uebersprungen() {
    final Long taskId = 72L;
    when(taskRepository.save(any(Task.class))).thenReturn(taskWithId(taskId, UUID.randomUUID()));

    service.create(
        new TaskCreateInput("Titel", null, null, null, null, List.of(USER_REF), null, null, null));

    verify(participantRepository, org.mockito.Mockito.times(1)).save(any(TaskParticipant.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(userReadRepository, never()).findActiveByUuids(any());
    verify(inboxGenerationService).bump(TENANT_ID, USER_REF);
    verify(inboxGenerationService, org.mockito.Mockito.times(1)).bump(anyLong(), any());
  }

  @Test
  @DisplayName(
      "create_unbekannter_assignee_liefert_400_keine_anlage (D1): 400 VOR jeder Mutation"
          + " (kein Task, kein Participant, kein Notify, kein Bump)")
  void create_unbekannter_assignee_liefert_400_keine_anlage() {
    final UUID unknown = UUID.randomUUID();
    when(userReadRepository.findActiveByUuids(Set.of(unknown))).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.create(
                    new TaskCreateInput(
                        "Titel", null, null, null, null, List.of(unknown), null, null, null)))
        .isInstanceOf(EntityValidationException.class);

    verify(taskRepository, never()).save(any(Task.class));
    verify(participantRepository, never()).save(any(TaskParticipant.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
  }

  @Test
  @DisplayName(
      "create_unbekannter_tag_mit_gueltigem_assignee_liefert_400_keine_anlage (D1): auch mit"
          + " gueltigem Assignee -> 400, KEINE Task-/Participant-/Notify-/Bump-Anlage")
  void create_unbekannter_tag_mit_gueltigem_assignee_liefert_400_keine_anlage() {
    final UUID assigneeRef = UUID.randomUUID();
    final UUID unknownTag = UUID.randomUUID();
    final User activeAssignee = org.mockito.Mockito.mock(User.class);
    when(activeAssignee.getUuid()).thenReturn(assigneeRef);
    when(userReadRepository.findActiveByUuids(Set.of(assigneeRef)))
        .thenReturn(List.of(activeAssignee));
    when(tagRepository.findActiveByUuid(unknownTag.toString())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    new TaskCreateInput(
                        "Titel",
                        null,
                        null,
                        null,
                        null,
                        List.of(assigneeRef),
                        List.of(unknownTag),
                        null,
                        null)))
        .isInstanceOf(EntityValidationException.class);

    verify(taskRepository, never()).save(any(Task.class));
    verify(participantRepository, never()).save(any(TaskParticipant.class));
    verify(notificationService, never()).emit(any(), any(), any(), any(), any(), any(), any());
    verify(inboxGenerationService, never()).bump(anyLong(), any());
  }

  @Test
  @DisplayName(
      "create_setzt_marker_auf_creator_userstate (D5): isPinned/isFlagged werden auf die"
          + " Creator-UserState gesetzt und gespeichert")
  void create_setzt_marker_auf_creator_userstate() {
    final Long taskId = 73L;
    when(taskRepository.save(any(Task.class))).thenReturn(taskWithId(taskId, UUID.randomUUID()));

    final TaskUserState creatorState = new TaskUserState();
    creatorState.setTaskId(taskId);
    creatorState.setUserRef(USER_REF);
    creatorState.setTenantId(TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(taskId, USER_REF))
        .thenReturn(List.of(creatorState));

    service.create(
        new TaskCreateInput(
            "Titel", null, null, null, null, null, null, Boolean.TRUE, Boolean.TRUE));

    assertThat(creatorState.isPinned()).as("IS_PINNED auf Creator-State").isTrue();
    assertThat(creatorState.isFlagged()).as("IS_FLAGGED auf Creator-State").isTrue();
    verify(userStateQueryRepository).save(creatorState);
  }

  // ---- MAJOR 1: patch auf unbekannte UUID -> 404 -----------------------------------------------

  @Test
  @DisplayName("patch_nicht_gefundene_uuid_liefert_404: EntityNotFoundException bei unbekannter UUID")
  void patch_nicht_gefundene_uuid_liefert_404() {
    when(taskRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.patch(UUID.randomUUID(), new TaskPatchInput(PatchValue.absent(), PatchValue.absent(), PatchValue.absent(), PatchValue.absent())))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  @Test
  @DisplayName("patch_fremder_tenant_liefert_404: EntityNotFoundException bei fremdem Mandant,"
      + " identische Meldung wie nicht gefunden")
  void patch_fremder_tenant_liefert_404() {
    // Task gehoert Mandant TENANT_ID+1 (anderer Tenant als der aktuelle Benutzer)
    final Task foreignTask = taskWithId(99L, UUID.randomUUID());
    foreignTask.setTenantId(TENANT_ID + 1);
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(foreignTask));

    assertThatThrownBy(
            () -> service.patch(foreignTask.getUuid(), new TaskPatchInput(PatchValue.absent(), PatchValue.absent(), PatchValue.absent(), PatchValue.absent())))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- MAJOR 2: patch invalidiert alle aktiven Teilnehmer ----------------------------------------

  @Test
  @DisplayName("patch_invalidiert_alle_aktiven_teilnehmer: A und B werden invalidiert wenn B patcht")
  void patch_invalidiert_alle_aktiven_teilnehmer() {
    final UUID creatorRef = UUID.randomUUID();
    final UUID patcherRef = USER_REF; // aktueller Benutzer (aus setUp)

    final Long taskId = 55L;
    final Task task = taskWithId(taskId, UUID.randomUUID());
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));
    when(taskRepository.save(any(Task.class))).thenReturn(task);

    // Zwei aktive Teilnehmer: Creator + Patcher
    final TaskUserState stateCreator = new TaskUserState();
    stateCreator.setUserRef(creatorRef);
    stateCreator.setTaskId(taskId);
    stateCreator.setTenantId(TENANT_ID);
    final TaskUserState statePatcher = new TaskUserState();
    statePatcher.setUserRef(patcherRef);
    statePatcher.setTaskId(taskId);
    statePatcher.setTenantId(TENANT_ID);

    when(userStateQueryRepository.findActiveUserRefsByTaskId(taskId))
        .thenReturn(List.of(stateCreator, statePatcher));

    when(resolver.resolveTaskStatus(TaskStatusCodes.OPEN)).thenReturn(STATUS_ID_OPEN);

    service.patch(
        task.getUuid(),
        new TaskPatchInput(
            PatchValue.of("Neuer Titel"), PatchValue.absent(), PatchValue.absent(), PatchValue.absent()));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(inboxSignalService).invalidate(eq(TENANT_ID), refsCaptor.capture());

    assertThat(refsCaptor.getValue())
        .as("invalidate muss sowohl Creator als auch Patcher enthalten")
        .containsExactlyInAnyOrder(creatorRef, patcherRef);
  }

  // ---- MAJOR 3: unbekannte Client-Codes -> 400 -------------------------------------------------

  @Test
  @DisplayName("create_ungültiger_priorityCode_liefert_400: EntityValidationException bei unbekanntem Code")
  void create_ungultiger_priority_code_liefert_400() {
    when(resolver.resolveTaskPriority("UNBEKANNT_CODE"))
        .thenThrow(new DictionaryResolverException("CODE 'UNBEKANNT_CODE' nicht gefunden."));

    assertThatThrownBy(
            () -> service.create(
                new TaskCreateInput("Titel", null, "UNBEKANNT_CODE", null, null, null, null, null, null)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("UNBEKANNT_CODE");
  }

  @Test
  @DisplayName("patch_ungültiger_statusCode_liefert_400: EntityValidationException bei unbekanntem statusCode")
  void patch_ungultiger_status_code_liefert_400() {
    final Task task = taskWithId(77L, UUID.randomUUID());
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));

    when(resolver.resolveTaskStatus("UNGUELTIG_STATUS"))
        .thenThrow(new DictionaryResolverException("CODE 'UNGUELTIG_STATUS' nicht gefunden."));

    assertThatThrownBy(
            () -> service.patch(
                task.getUuid(),
                new TaskPatchInput(
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.of("UNGUELTIG_STATUS"),
                    PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("UNGUELTIG_STATUS");
  }

  @Test
  @DisplayName("patch_leerer_statusCode_liefert_400: EntityValidationException bei leerem statusCode")
  void patch_leerer_status_code_liefert_400() {
    final Task task = taskWithId(78L, UUID.randomUUID());
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));

    assertThatThrownBy(
            () -> service.patch(
                task.getUuid(),
                new TaskPatchInput(
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.of(""),
                    PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- F-P1: inaktive Tag-UUID -> 400 (findByUuid durch findActiveByUuid ersetzen) --------------

  @Test
  @DisplayName("patch_inaktive_tag_uuid_liefert_400: findActiveByUuid liefert empty -> 400 (F-P1)")
  void patch_inaktive_tag_uuid_liefert_400() {
    final Task task = taskWithId(80L, UUID.randomUUID());
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));

    final UUID inactiveTagUuid = UUID.randomUUID();
    // findActiveByUuid liefert empty (Tag inaktiv oder abgelaufen)
    when(tagRepository.findActiveByUuid(inactiveTagUuid.toString())).thenReturn(java.util.Optional.empty());
    when(tagAssignmentQueryRepository.findActiveByTaskId(task.getId())).thenReturn(List.of());

    assertThatThrownBy(
            () -> service.patch(
                task.getUuid(),
                new TaskPatchInput(
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.of(java.util.Set.of(inactiveTagUuid)))))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining(inactiveTagUuid.toString());
  }

  // ---- F-P2b: null-Element in tagIds -> 400 ----------------------------------------------------

  @Test
  @DisplayName("patch_null_element_in_tag_ids_liefert_400: Set mit null-Element -> 400 statt NPE (F-P2b)")
  void patch_null_element_in_tag_ids_liefert_400() {
    final Task task = taskWithId(81L, UUID.randomUUID());
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));

    // Set mit null-Element (deserialisiert aus {"tagIds":[null]})
    final java.util.Set<UUID> tagIdsWithNull = new java.util.HashSet<>();
    tagIdsWithNull.add(null);

    assertThatThrownBy(
            () -> service.patch(
                task.getUuid(),
                new TaskPatchInput(
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.of(tagIdsWithNull))))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- P2-D: Title-History-Detail bounded 500 Zeichen -----------------------------------------

  @Test
  @DisplayName("patch_langer_titel_trunciert_change_detail: changeDetail ueberschreitet nicht 500 Zeichen")
  void patch_langer_titel_trunciert_change_detail() {
    final String longTitle = "A".repeat(500);
    final Task task = taskWithId(90L, UUID.randomUUID());
    task.setTitle(longTitle);
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));
    when(taskRepository.save(any(Task.class))).thenReturn(task);

    final String newLongTitle = "B".repeat(500);
    final List<TaskUserState> noStates = List.of();
    when(userStateQueryRepository.findActiveUserRefsByTaskId(task.getId())).thenReturn(noStates);

    service.patch(
        task.getUuid(),
        new TaskPatchInput(
            PatchValue.of(newLongTitle),
            PatchValue.absent(),
            PatchValue.absent(),
            PatchValue.absent()));

    final ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
    verify(historyService).record(any(), any(), any(), any(), detailCaptor.capture());
    assertThat(detailCaptor.getValue().length())
        .as("changeDetail darf 500 Zeichen nicht ueberschreiten")
        .isLessThanOrEqualTo(500);
  }

  // ---- P2-C: Blank-Titel ablehnen (create + patch) --------------------------------------------

  @Test
  @DisplayName("create_leerer_titel_liefert_400: Whitespace-only Titel -> EntityValidationException")
  void create_leerer_titel_liefert_400() {
    assertThatThrownBy(() -> service.create(new TaskCreateInput("   ", null, null, null, null, null, null, null, null)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("patch_leerer_titel_liefert_400: Whitespace-only Titel beim Patch -> EntityValidationException")
  void patch_leerer_titel_liefert_400() {
    final Task task = taskWithId(82L, UUID.randomUUID());
    when(taskRepository.findByUuid(any())).thenReturn(Optional.of(task));

    assertThatThrownBy(
            () -> service.patch(
                task.getUuid(),
                new TaskPatchInput(
                    PatchValue.of("   "),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- Surrogat-sichere Truncation (Codex Re-Gate 93781f4, Finding #1) --------------------

  @Test
  @DisplayName(
      "truncate_zerschneidet_kein_surrogatpaar_vor_ellipse: "
          + "Emoji am Kappungsrand -> kein ungepaartes High-Surrogate vor der Ellipse")
  void truncate_zerschneidet_kein_surrogatpaar_vor_ellipse() {
    // max=500 -> Inhalt wird bei max-3=497 gekappt (plus "..."). Das Emoji (U+1F600 = D83D DE00)
    // sitzt so, dass sein High-Surrogate das letzte eingeschlossene Zeichen (Index 496) waere und
    // das Low-Surrogate (497) abgeschnitten wuerde -> ungepaartes Surrogat vor der Ellipse.
    final String emoji = "😀";
    final String value = "a".repeat(496) + emoji + "b".repeat(50);

    final String result = TaskCommandService.truncate(value, 500);

    assertThat(result).endsWith("...");
    final String body = result.substring(0, result.length() - 3);
    assertThat(Character.isHighSurrogate(body.charAt(body.length() - 1)))
        .as("kein ungepaartes High-Surrogate vor der Ellipse")
        .isFalse();
    assertThat(result.codePoints().anyMatch(cp -> cp == 0xD83D || cp == 0xDE00))
        .as("kein halbes Surrogat im Ergebnis")
        .isFalse();
  }

  @Test
  @DisplayName("truncate_null_kurz_und_vollstaendiges_paar_bleiben_intakt")
  void truncate_null_kurz_und_vollstaendiges_paar_bleiben_intakt() {
    assertThat(TaskCommandService.truncate(null, 500)).isNull();
    assertThat(TaskCommandService.truncate("kurz", 500)).isEqualTo("kurz");
    // Emoji endet VOR der Kappungsgrenze -> vollstaendiges Paar bleibt erhalten, Ellipse angehaengt.
    final String value = "a".repeat(400) + "😀" + "b".repeat(200);
    final String result = TaskCommandService.truncate(value, 500);
    assertThat(result).endsWith("...");
    assertThat(result.codePoints().filter(cp -> cp == 0x1F600).count()).isEqualTo(1L);
  }

  // ---- Hilfsmethode ---------------------------------------------------------------------------

  private static Task taskWithId(final Long id, final UUID uuid) {
    final Task task = new Task();
    task.setId(id);
    task.setUuid(uuid);
    task.setTenantId(TENANT_ID);
    task.setEntityState(io.keel.framework.api.model.EntityState.ACTIVE);
    task.setTitle("Testtitel");
    task.setLastActivityAt(java.time.LocalDateTime.now());
    task.setCommentCount(0);
    return task;
  }
}
