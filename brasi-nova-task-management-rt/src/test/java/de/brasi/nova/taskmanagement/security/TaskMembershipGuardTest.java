package de.brasi.nova.taskmanagement.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link TaskMembershipGuard} (gemocktes Repository, kein DB, kein Web). Deckt die
 * reine Praedikatlogik des objekt-level Membership-Guards ab: aktiver WRITE-Member ist Read- und
 * Write-member; ausgetretener READ-Member darf lesen, aber nicht schreiben; Non-Member wird auf
 * beiden Pfaden mit {@link EntityNotFoundException} (HTTP 404, neutral) abgewiesen. Zusaetzlich der
 * fail-closed Tenant-Vertrag: weicht der uebergebene {@code tenant} vom gebundenen
 * {@link TenantContextHolder}-Mandanten ab, darf die Membership NICHT ausgewertet werden (404 bzw.
 * {@code false}), und das Repository wird nicht angefragt. Die konkrete {@code @NovaQuery}-SQL-
 * Semantik (PERSON, beide Tenant-Spalten, EXIT_KIND) wird separat per real-MSSQL-IT verifiziert;
 * hier nur die Guard-Service-Logik.
 */
class TaskMembershipGuardTest {

  private static final long TENANT_ID = 590L;
  private static final long OTHER_TENANT_ID = 591L;
  private static final Long TASK_ID = 4242L;
  private static final UUID ACTOR = UUID.fromString("00000059-000a-0059-0000-00000000059a");
  private static final UUID TENANT_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f0");

  private TaskParticipantQueryRepository participantQueryRepository;
  private TaskMembershipGuard guard;

  @BeforeEach
  void setUp() {
    participantQueryRepository = mock(TaskParticipantQueryRepository.class);
    guard = new TaskMembershipGuard(participantQueryRepository);
    // Kontext-Mandant == der in den Happy-Path-Tests uebergebene TENANT_ID.
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName("aktiver WRITE-Member: requireWriteMember + requireReadMember passieren ohne Fehler")
  void aktiver_write_member_ok() {
    when(participantQueryRepository.existsWriteMember(TASK_ID, ACTOR)).thenReturn(true);
    when(participantQueryRepository.existsReadMember(TASK_ID, ACTOR)).thenReturn(true);

    assertThatCode(() -> guard.requireWriteMember(TASK_ID, ACTOR, TENANT_ID)).doesNotThrowAnyException();
    assertThatCode(() -> guard.requireReadMember(TASK_ID, ACTOR, TENANT_ID)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("ausgetretener READ-Member: requireReadMember ok, requireWriteMember -> 404")
  void ausgetretener_read_member_read_ok_write_404() {
    // Ex-Member: Zeile bleibt ACTIVE, nur EXIT_KIND gesetzt -> READ-Member true, WRITE-Member false.
    when(participantQueryRepository.existsReadMember(TASK_ID, ACTOR)).thenReturn(true);
    when(participantQueryRepository.existsWriteMember(TASK_ID, ACTOR)).thenReturn(false);

    assertThatCode(() -> guard.requireReadMember(TASK_ID, ACTOR, TENANT_ID)).doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.requireWriteMember(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName("Non-Member: requireReadMember UND requireWriteMember -> 404 (neutral)")
  void non_member_beide_404() {
    when(participantQueryRepository.existsReadMember(TASK_ID, ACTOR)).thenReturn(false);
    when(participantQueryRepository.existsWriteMember(TASK_ID, ACTOR)).thenReturn(false);

    assertThatThrownBy(() -> guard.requireReadMember(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(() -> guard.requireWriteMember(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName("isWriteMember spiegelt das WRITE-Member-Repo-Praedikat (true/false)")
  void is_write_member_true_false() {
    when(participantQueryRepository.existsWriteMember(TASK_ID, ACTOR)).thenReturn(true);
    assertThat(guard.isWriteMember(TASK_ID, ACTOR, TENANT_ID)).isTrue();

    when(participantQueryRepository.existsWriteMember(TASK_ID, ACTOR)).thenReturn(false);
    assertThat(guard.isWriteMember(TASK_ID, ACTOR, TENANT_ID)).isFalse();
  }

  @Test
  @DisplayName("tenant != Kontext-Mandant: requireRead/WriteMember -> 404, Repo NICHT angefragt")
  void tenant_weicht_vom_kontext_ab_require_methoden_404_ohne_repo() {
    // Kontext = TENANT_ID (setUp); uebergeben wird der FREMDE OTHER_TENANT_ID.
    assertThatThrownBy(() -> guard.requireReadMember(TASK_ID, ACTOR, OTHER_TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(() -> guard.requireWriteMember(TASK_ID, ACTOR, OTHER_TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);

    // Fail-closed VOR der Query: kein Membership-Lookup bei abweichendem Tenant.
    verifyNoInteractions(participantQueryRepository);
  }

  @Test
  @DisplayName("tenant != Kontext-Mandant: isWriteMember -> false, Repo NICHT angefragt")
  void tenant_weicht_vom_kontext_ab_is_write_member_false_ohne_repo() {
    assertThat(guard.isWriteMember(TASK_ID, ACTOR, OTHER_TENANT_ID)).isFalse();

    verifyNoInteractions(participantQueryRepository);
  }

  @Test
  @DisplayName("kein Mandanten-Kontext gebunden: alle Methoden fail-closed (404/false), kein Repo")
  void kein_kontext_fail_closed() {
    TenantContextHolder.clear();

    assertThatThrownBy(() -> guard.requireReadMember(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(() -> guard.requireWriteMember(TASK_ID, ACTOR, TENANT_ID))
        .isInstanceOf(EntityNotFoundException.class);
    assertThat(guard.isWriteMember(TASK_ID, ACTOR, TENANT_ID)).isFalse();

    verifyNoInteractions(participantQueryRepository);
  }

  @Test
  @DisplayName("tenant == Kontext-Mandant: normaler Pfad fragt das Repo wie bisher an")
  void tenant_gleich_kontext_normaler_pfad_ruft_repo() {
    when(participantQueryRepository.existsReadMember(TASK_ID, ACTOR)).thenReturn(true);
    when(participantQueryRepository.existsWriteMember(TASK_ID, ACTOR)).thenReturn(true);

    assertThatCode(() -> guard.requireReadMember(TASK_ID, ACTOR, TENANT_ID)).doesNotThrowAnyException();
    assertThatCode(() -> guard.requireWriteMember(TASK_ID, ACTOR, TENANT_ID)).doesNotThrowAnyException();
    assertThat(guard.isWriteMember(TASK_ID, ACTOR, TENANT_ID)).isTrue();

    verify(participantQueryRepository).existsReadMember(TASK_ID, ACTOR);
    verify(participantQueryRepository, never()).existsReadMember(TASK_ID, UUID.randomUUID());
  }
}
