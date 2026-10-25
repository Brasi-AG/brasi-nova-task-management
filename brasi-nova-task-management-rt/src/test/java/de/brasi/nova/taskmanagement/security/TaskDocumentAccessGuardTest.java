package de.brasi.nova.taskmanagement.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskDocumentAccessGuardTest {

  private static final long TENANT_ID = 581L;
  private static final long FOREIGN_TENANT_ID = 582L;
  private static final long TASK_ID = 581001L;
  private static final UUID ACTOR = UUID.fromString("00000581-0000-0000-0000-000000000001");
  private static final UUID TENANT_UUID = UUID.fromString("00000581-0000-0000-0000-000000000002");

  private TaskParticipantQueryRepository repository;
  private TaskDocumentAccessGuard guard;

  @BeforeEach
  void setUp() {
    repository = mock(TaskParticipantQueryRepository.class);
    guard = new TaskDocumentAccessGuard(repository);
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void active_document_reader_passes() {
    when(repository.existsDocumentReadAccess(TASK_ID, ACTOR)).thenReturn(true);

    assertThatCode(() -> guard.requireRead(TASK_ID, ACTOR, TENANT_ID)).doesNotThrowAnyException();
  }

  @Test
  void denied_document_reader_gets_neutral_not_found() {
    when(repository.existsDocumentReadAccess(TASK_ID, ACTOR)).thenReturn(false);

    assertThatThrownBy(() -> guard.requireRead(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  @Test
  void foreign_tenant_is_rejected_before_repository_lookup() {
    assertThatThrownBy(() -> guard.requireRead(TASK_ID, ACTOR, FOREIGN_TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    verifyNoInteractions(repository);
  }

  @Test
  void missing_context_is_rejected_before_repository_lookup() {
    TenantContextHolder.clear();

    assertThatThrownBy(() -> guard.requireRead(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");

    verifyNoInteractions(repository);
  }
}
