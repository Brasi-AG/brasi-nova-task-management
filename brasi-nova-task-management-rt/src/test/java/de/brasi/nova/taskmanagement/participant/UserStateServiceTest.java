package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import io.keel.framework.api.exception.BusinessRuleException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-Tests fuer {@link UserStateService}.
 *
 * <p>Beweist insbesondere das idempotente Verhalten von {@link UserStateService#ensure} unter
 * Nebenlaeufigkeit (P2-F: Re-Read-Translate bei BusinessRuleException).
 */
@ExtendWith(MockitoExtension.class)
class UserStateServiceTest {

  private static final Long TASK_ID = 42L;
  private static final UUID USER_REF = UUID.fromString("aaaabbbb-cccc-dddd-eeee-ffffaaaabbbb");
  private static final Long TENANT_ID = 500L;

  @Mock private TaskUserStateQueryRepository repository;

  private UserStateService service;

  @BeforeEach
  void setUp() {
    service = new UserStateService(repository);
  }

  // ---- Normalfall -----------------------------------------------------------------------

  @Test
  @DisplayName("ensure_neu_legt_eintrag_an_und_liefert_true")
  void ensure_neu_legt_eintrag_an_und_liefert_true() {
    when(repository.findByTaskIdAndUserRef(TASK_ID, USER_REF)).thenReturn(List.of());

    final boolean result = service.ensure(TASK_ID, USER_REF, TENANT_ID);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("ensure_eintrag_existiert_liefert_false_ohne_insert")
  void ensure_eintrag_existiert_liefert_false_ohne_insert() {
    when(repository.findByTaskIdAndUserRef(TASK_ID, USER_REF))
        .thenReturn(List.of(new TaskUserState()));

    final boolean result = service.ensure(TASK_ID, USER_REF, TENANT_ID);

    assertThat(result).isFalse();
  }

  // ---- P2-F: Race-safe ensure -----------------------------------------------------------

  @Test
  @DisplayName(
      "ensure_race_nebenlaeufiger_insert_liefert_false_ohne_exception: "
          + "save() wirft BusinessRuleException -> Re-Read findet Eintrag -> idempotent false")
  void ensure_race_nebenlaeufiger_insert_liefert_false_ohne_exception() {
    // Erste findByTaskIdAndUserRef-Anfrage: leer (beide Threads sehen noch keine Zeile).
    // Zweite Anfrage (Re-Read nach BusinessRuleException): Zeile bereits vorhanden.
    when(repository.findByTaskIdAndUserRef(TASK_ID, USER_REF))
        .thenReturn(List.of())
        .thenReturn(List.of(new TaskUserState()));

    // save() schlaegt fehl weil ein nebenlaeufiger Aufruf bereits committet hat.
    doThrow(new BusinessRuleException("Concurrent insert - UNIQUE constraint violation."))
        .when(repository)
        .save(any());

    final boolean result = service.ensure(TASK_ID, USER_REF, TENANT_ID);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName(
      "ensure_race_unverwandter_konflikt_wird_durchgereicht: "
          + "save() wirft BusinessRuleException und Re-Read ist leer -> BusinessRuleException")
  void ensure_race_unverwandter_konflikt_wird_durchgereicht() {
    when(repository.findByTaskIdAndUserRef(TASK_ID, USER_REF))
        .thenReturn(List.of())
        .thenReturn(List.of());

    doThrow(new BusinessRuleException("Unrelated conflict.")).when(repository).save(any());

    assertThatThrownBy(() -> service.ensure(TASK_ID, USER_REF, TENANT_ID))
        .isInstanceOf(BusinessRuleException.class);
  }

  // ---- Intake #158: Reihenfolge Mute und (Wieder-)Zuweisung -----------------------------

  @Test
  @DisplayName(
      "reactivate_behaelt_mute: erneute Zuweisung setzt nur UNSUBSCRIBED_AT zurueck, IS_MUTED"
          + " bleibt gesetzt (ASSIGN kommt trotzdem an, weil ausgenommen)")
  void reactivate_behaelt_mute_eines_vorhandenen_states() {
    final TaskUserState state = new TaskUserState();
    state.setTaskId(TASK_ID);
    state.setUserRef(USER_REF);
    state.setMuted(true);
    state.setUnsubscribedAt(java.time.LocalDateTime.now().minusDays(1));
    when(repository.findByTaskIdAndUserRef(TASK_ID, USER_REF)).thenReturn(List.of(state));

    service.reactivate(TASK_ID, USER_REF, TENANT_ID);

    assertThat(state.getUnsubscribedAt()).isNull();
    assertThat(state.isMuted()).isTrue();
  }

  @Test
  @DisplayName("reactivate_neu: ein neu angelegter State startet nicht stumm")
  void reactivate_legt_neuen_state_nicht_stumm_an() {
    when(repository.findByTaskIdAndUserRef(TASK_ID, USER_REF)).thenReturn(List.of());
    final org.mockito.ArgumentCaptor<TaskUserState> captor =
        org.mockito.ArgumentCaptor.forClass(TaskUserState.class);

    service.reactivate(TASK_ID, USER_REF, TENANT_ID);

    org.mockito.Mockito.verify(repository).save(captor.capture());
    assertThat(captor.getValue().isMuted()).isFalse();
  }
}
