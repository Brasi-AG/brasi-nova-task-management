package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.entity.enums.ParticipantKind;
import de.brasi.nova.taskmanagement.inbox.InboxEnrichmentAssembler;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.HandoverTargetRow;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link TaskDetailService} (gemockt, kein DB-Zugriff).
 *
 * <p>Fokus P2 (per-Task-Read-Contract): GET /tasks/{id} liefert die aktuellen Assignee- und
 * Tag-GUID-Sets (stabil sortiert, non-null), damit ein Client vor PUT /assignees bzw. PATCH tagIds
 * read-modify-write fahren kann. Die Tag-GUIDs kommen faithful aus einer JOIN-Projektion
 * ({@code findActiveTagUuidsByTaskId}) - unabhaengig von Katalog-Status/Visibility.
 */
class TaskDetailServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
  private static final LocalDateTime PAST = LocalDateTime.of(2020, 1, 1, 0, 0);

  private BaseTaskRepository taskRepository;
  private TaskHistoryEntryQueryRepository historyRepository;
  private TaskParticipantQueryRepository participantQueryRepository;
  private TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;
  private TaskUserStateQueryRepository userStateQueryRepository;
  private TaskHandoverQueryRepository handoverQueryRepository;
  private DictionaryCodeResolver resolver;
  private TaskDetailService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(BaseTaskRepository.class);
    historyRepository = mock(TaskHistoryEntryQueryRepository.class);
    participantQueryRepository = mock(TaskParticipantQueryRepository.class);
    tagAssignmentQueryRepository = mock(TaskTagAssignmentQueryRepository.class);
    userStateQueryRepository = mock(TaskUserStateQueryRepository.class);
    handoverQueryRepository = mock(TaskHandoverQueryRepository.class);
    resolver = mock(DictionaryCodeResolver.class);

    service =
        new TaskDetailService(
            taskRepository,
            historyRepository,
            participantQueryRepository,
            tagAssignmentQueryRepository,
            userStateQueryRepository,
            handoverQueryRepository,
            new InboxEnrichmentAssembler(),
            resolver,
            new AuditTimeConverter("Europe/Berlin"));

    when(resolver.resolveTaskStatusCode(any())).thenReturn("OPEN");
    when(resolver.resolveTaskPriorityCode(any())).thenReturn("NORMAL");
    when(resolver.resolveTaskDirectionCode(any())).thenReturn("CUSTOMER_INTERNAL");
    when(historyRepository.findByTaskIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
    // Default: BE-3-Anreicherungs-Batch-Querys liefern leer (EMPTY-Anreicherung).
    when(participantQueryRepository.findActiveByTaskIds(any())).thenReturn(List.of());
    when(userStateQueryRepository.findByTaskIdsAndUserRef(any(), any())).thenReturn(List.of());
    // BE-7: Default kein sichtbares Handover-Ereignis.
    when(handoverQueryRepository.findVisibleHandoverTargets(any(), any(), any()))
        .thenReturn(List.of());
  }

  @Test
  @DisplayName("detail_liefert_assignees_und_tagIds_stabil_sortiert")
  void detail_liefert_assignees_und_tagIds_sortiert() {
    final long taskId = 1000L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task(taskId, taskUuid)));

    // Zwei Assignees in unsortierter Reihenfolge
    final UUID assigneeHi = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    final UUID assigneeLo = UUID.fromString("00000000-0000-0000-0000-000000000002");
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId))
        .thenReturn(List.of(participant(assigneeHi), participant(assigneeLo)));

    // Zwei aktiv zugeordnete Tags (EXTERNALID-Strings, unsortiert) aus der JOIN-Projektion
    final UUID tagHi = UUID.fromString("ffffffff-0000-0000-0000-0000000000aa");
    final UUID tagLo = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(taskId))
        .thenReturn(List.of(tagHi.toString(), tagLo.toString()));

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID, USER_REF);

    assertThat(detail.assignees()).containsExactly(assigneeLo, assigneeHi); // stabil sortiert
    assertThat(detail.tagIds()).containsExactly(tagLo, tagHi); // stabil sortiert
  }

  @Test
  @DisplayName("detail_leere_sets")
  void detail_leere_sets() {
    final long taskId = 1001L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task(taskId, taskUuid)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(taskId)).thenReturn(List.of());

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID, USER_REF);

    assertThat(detail.assignees()).isEmpty();
    assertThat(detail.tagIds()).isEmpty();
    // EMPTY-Anreicherung (keine Participant-/UserState-Rows gestubbt)
    assertThat(detail.createdById()).isNull();
    assertThat(detail.participantIds()).isEmpty();
    assertThat(detail.isPinned()).isFalse();
    assertThat(detail.exit()).isNull();
  }

  // ---- BE-3: Actor-/Personen-Anreicherung ----------------------------------------------------

  @Test
  @DisplayName("detail_reichert_actor_felder_an: createdById/participantIds/Marker/exit (handover)")
  void detail_reichert_actor_felder_an() {
    final long taskId = 1002L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    final UUID participantP = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task(taskId, taskUuid)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findActiveByTaskIds(List.of(taskId)))
        .thenReturn(
            List.of(
                participantRow(taskId, creator, true, false, false, null),
                participantRow(taskId, participantP, false, false, true, null),
                // Actor ist Teilnehmer, aber ausgetreten (HANDOVER) -> aus participantIds raus, exit gesetzt
                participantRow(taskId, USER_REF, false, false, true, ExitKind.HANDOVER)));
    when(userStateQueryRepository.findByTaskIdsAndUserRef(List.of(taskId), USER_REF))
        .thenReturn(List.of(userState(taskId, USER_REF, true, false, true)));
    // BE-7: sichtbares Austritts-Handover-Ereignis mit zwei Zielen (LIVE, asOf=now).
    final UUID targetHi = UUID.fromString("ffffffff-0000-0000-0000-00000000aaaa");
    final UUID targetLo = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    when(handoverQueryRepository.findVisibleHandoverTargets(
            org.mockito.ArgumentMatchers.eq(List.of(taskId)),
            org.mockito.ArgumentMatchers.eq(USER_REF),
            any()))
        .thenReturn(
            List.of(new HandoverTargetRow(taskId, targetHi), new HandoverTargetRow(taskId, targetLo)));

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID, USER_REF);

    assertThat(detail.createdById()).isEqualTo(creator);
    assertThat(detail.participantIds()).containsExactly(participantP);
    assertThat(detail.isPinned()).isTrue();
    assertThat(detail.isFlagged()).isFalse();
    assertThat(detail.isMuted()).isTrue();
    assertThat(detail.exit()).isNotNull();
    assertThat(detail.exit().kind()).isEqualTo("handover");
    // BE-7: Ziele stabil sortiert (BY_STRING): Lo vor Hi.
    assertThat(detail.exit().handedOverToIds()).containsExactly(targetLo, targetHi);
  }

  @Test
  @DisplayName("BE-7: detail handover ohne Ziele -> exit.handedOverToIds leer")
  void detail_handover_ohne_ziele_leere_targets() {
    final long taskId = 1004L;
    final UUID taskUuid = UUID.randomUUID();
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task(taskId, taskUuid)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findActiveByTaskIds(List.of(taskId)))
        .thenReturn(
            List.of(participantRow(taskId, USER_REF, false, false, true, ExitKind.HANDOVER)));
    when(userStateQueryRepository.findByTaskIdsAndUserRef(List.of(taskId), USER_REF))
        .thenReturn(List.of(userState(taskId, USER_REF, false, false, false)));
    // LEFT-JOIN-Zeile ohne Ziel (Selbst-Handover ohne Ziele): toRef=null.
    when(handoverQueryRepository.findVisibleHandoverTargets(
            org.mockito.ArgumentMatchers.eq(List.of(taskId)),
            org.mockito.ArgumentMatchers.eq(USER_REF),
            any()))
        .thenReturn(List.of(new HandoverTargetRow(taskId, null)));

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID, USER_REF);

    assertThat(detail.exit()).isNotNull();
    assertThat(detail.exit().kind()).isEqualTo("handover");
    assertThat(detail.exit().handedOverToIds()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("detail_nicht_teilnehmer_actor: createdById/participantIds gesetzt, Marker false, exit null")
  void detail_nicht_teilnehmer_actor() {
    final long taskId = 1003L;
    final UUID taskUuid = UUID.randomUUID();
    final UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    final UUID participantP = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    when(taskRepository.findByUuid(taskUuid.toString())).thenReturn(Optional.of(task(taskId, taskUuid)));
    when(participantQueryRepository.findActiveAssigneesByTaskId(taskId)).thenReturn(List.of());
    when(tagAssignmentQueryRepository.findActiveTagUuidsByTaskId(taskId)).thenReturn(List.of());
    when(participantQueryRepository.findActiveByTaskIds(List.of(taskId)))
        .thenReturn(
            List.of(
                participantRow(taskId, creator, true, false, false, null),
                participantRow(taskId, participantP, false, false, true, null)));
    // Actor hat KEINE UserState-Row (kein Teilnehmer)
    when(userStateQueryRepository.findByTaskIdsAndUserRef(List.of(taskId), USER_REF))
        .thenReturn(List.of());

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID, USER_REF);

    assertThat(detail.createdById()).isEqualTo(creator);
    assertThat(detail.participantIds()).containsExactly(participantP);
    assertThat(detail.isPinned()).isFalse();
    assertThat(detail.isFlagged()).isFalse();
    assertThat(detail.isMuted()).isFalse();
    assertThat(detail.exit()).isNull();
  }

  // ---- 404-Maske (fehlend / fremder Mandant / DEACTIVE) --------------------------------------

  @Test
  @DisplayName("loadDetail_404_je_getrennt: fehlende / fremde / DEACTIVE Aufgabe -> EntityNotFoundException")
  void loadDetail_404_je_getrennt() {
    final UUID missing = UUID.randomUUID();
    when(taskRepository.findByUuid(missing.toString())).thenReturn(Optional.empty());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.loadDetail(missing, TENANT_ID, USER_REF))
        .isInstanceOf(io.keel.framework.api.exception.EntityNotFoundException.class);

    final UUID foreign = UUID.randomUUID();
    final Task foreignTask = task(2000L, foreign);
    foreignTask.setTenantId(999L); // fremder Mandant
    when(taskRepository.findByUuid(foreign.toString())).thenReturn(Optional.of(foreignTask));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.loadDetail(foreign, TENANT_ID, USER_REF))
        .isInstanceOf(io.keel.framework.api.exception.EntityNotFoundException.class);

    final UUID deactive = UUID.randomUUID();
    final Task deactiveTask = task(2001L, deactive);
    deactiveTask.setEntityState(io.keel.framework.api.model.EntityState.DEACTIVE);
    when(taskRepository.findByUuid(deactive.toString())).thenReturn(Optional.of(deactiveTask));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.loadDetail(deactive, TENANT_ID, USER_REF))
        .isInstanceOf(io.keel.framework.api.exception.EntityNotFoundException.class);
  }

  // ---- Hilfsmethoden ---------------------------------------------------------------------------

  private static TaskParticipant participantRow(
      final long taskId,
      final UUID ref,
      final boolean creator,
      final boolean assignee,
      final boolean participant,
      final ExitKind exitKind) {
    final TaskParticipant p = new TaskParticipant();
    p.setTaskId(taskId);
    p.setParticipantRef(ref);
    p.setParticipantKind(ParticipantKind.PERSON);
    p.setCreatorFlag(creator);
    p.setAssigneeFlag(assignee);
    p.setParticipantFlag(participant);
    p.setExitKind(exitKind);
    p.setCreatedAt(PAST);
    return p;
  }

  private static TaskUserState userState(
      final long taskId,
      final UUID userRef,
      final boolean pinned,
      final boolean flagged,
      final boolean muted) {
    final TaskUserState s = new TaskUserState();
    s.setTaskId(taskId);
    s.setUserRef(userRef);
    s.setPinned(pinned);
    s.setFlagged(flagged);
    s.setMuted(muted);
    s.setCreatedAt(PAST);
    return s;
  }

  private static Task task(final long id, final UUID uuid) {
    final Task t = new Task();
    t.setId(id);
    t.setUuid(uuid);
    t.setTenantId(TENANT_ID);
    t.setEntityState(io.keel.framework.api.model.EntityState.ACTIVE);
    t.setTitle("Detailtitel");
    t.setTaskStatusId(1L);
    t.setPriorityId(10L);
    t.setDirectionId(20L);
    t.setLastActivityAt(LocalDateTime.of(2026, 10, 18, 12, 0, 0));
    t.setCommentCount(0);
    return t;
  }

  private static TaskParticipant participant(final UUID ref) {
    final TaskParticipant p = new TaskParticipant();
    p.setParticipantRef(ref);
    return p;
  }
}
