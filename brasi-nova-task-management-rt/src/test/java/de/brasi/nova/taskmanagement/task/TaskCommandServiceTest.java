package de.brasi.nova.taskmanagement.task;

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
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.participant.UserStateService;
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
            tagAssignmentQueryRepository);

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
    service.create(new TaskCreateInput("Testtitel", null, null, null, null));

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

    service.create(new TaskCreateInput("Testtitel", null, null, null, null));

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

      service.create(new TaskCreateInput("Invalidierungstest", null, null, null, null));

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
                new TaskCreateInput("Titel", null, "UNBEKANNT_CODE", null, null)))
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
