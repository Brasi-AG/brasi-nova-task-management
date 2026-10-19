package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
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

  private BaseTaskRepository taskRepository;
  private TaskHistoryEntryQueryRepository historyRepository;
  private TaskParticipantQueryRepository participantQueryRepository;
  private TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;
  private DictionaryCodeResolver resolver;
  private TaskDetailService service;

  @BeforeEach
  void setUp() {
    taskRepository = mock(BaseTaskRepository.class);
    historyRepository = mock(TaskHistoryEntryQueryRepository.class);
    participantQueryRepository = mock(TaskParticipantQueryRepository.class);
    tagAssignmentQueryRepository = mock(TaskTagAssignmentQueryRepository.class);
    resolver = mock(DictionaryCodeResolver.class);

    service =
        new TaskDetailService(
            taskRepository,
            historyRepository,
            participantQueryRepository,
            tagAssignmentQueryRepository,
            resolver,
            new AuditTimeConverter("Europe/Berlin"));

    when(resolver.resolveTaskStatusCode(any())).thenReturn("OPEN");
    when(resolver.resolveTaskPriorityCode(any())).thenReturn("NORMAL");
    when(resolver.resolveTaskDirectionCode(any())).thenReturn("CUSTOMER_INTERNAL");
    when(historyRepository.findByTaskIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
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

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID);

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

    final TaskDetailModel detail = service.loadDetail(taskUuid, TENANT_ID);

    assertThat(detail.assignees()).isEmpty();
    assertThat(detail.tagIds()).isEmpty();
  }

  // ---- Hilfsmethoden ---------------------------------------------------------------------------

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
