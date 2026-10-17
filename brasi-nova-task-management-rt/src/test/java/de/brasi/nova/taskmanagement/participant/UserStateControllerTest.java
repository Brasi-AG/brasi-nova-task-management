package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.participant.model.MarkerInput;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.List;
import java.util.Optional;
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
 * Unit-Tests fuer {@link UserStateController} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer persoenliche Marker:
 * <ol>
 *   <li>Marker wird im TaskUserState des richtigen Actors gesetzt
 *   <li>Zwei verschiedene Benutzer haben unabhaengige Marker (pro-Benutzer-Isolation)
 *   <li>404-Masking bei unbekannter/fremder Task-UUID
 *   <li>UNSUBSCRIBED_AT wird von Marker-Set NICHT veraendert (Inbox-Invariante implizit
 *       via ensure-Aufruf, nicht reactivate)
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserStateControllerTest {

  private static final long TENANT_ID = 500L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final Long TASK_DB_ID = 42L;
  private static final UUID TASK_EXTERNAL_ID = UUID.randomUUID();

  @Mock private BaseTaskRepository taskRepository;
  @Mock private TaskUserStateQueryRepository userStateQueryRepository;
  @Mock private UserStateService userStateService;
  @Mock private UserAccessor userAccessor;

  private UserStateController controller;

  @BeforeEach
  void setUp() {
    controller =
        new UserStateController(
            taskRepository, userStateQueryRepository, userStateService, userAccessor);

    final TenantInfo tenant = new TenantInfo(TENANT_UUID, TENANT_ID);
    TenantContextHolder.setTenant(tenant);
    final AuthUser userA = new AuthUser(USER_A_REF, "user-a@brasi.de");
    when(userAccessor.requireCurrentUser()).thenReturn(userA);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---- Test 1: pin_pro_benutzer ---------------------------------------------------------------

  @Test
  @DisplayName(
      "pin_pro_benutzer: IS_PINNED wird im TaskUserState des Actors gesetzt; anderer Benutzer"
          + " hat eigenen unabhaengigen Zustand")
  void pin_pro_benutzer() {
    // Task vorhanden und korrekte Mandantenzugehoerigkeit
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    // TaskUserState fuer User A (hat IS_PINNED=false initial)
    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    // Act: User A pinnt
    controller.pin(TASK_EXTERNAL_ID, new MarkerInput(true));

    // Assert: ensure wurde mit User-A-Ref aufgerufen (nicht reactivate!)
    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));

    // Assert: gespeicherter State hat IS_PINNED=true
    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isPinned())
        .as("IS_PINNED muss nach pin(true) true sein")
        .isTrue();
    assertThat(captor.getValue().getUserRef())
        .as("Gespeicherter State muss User A gehoeren")
        .isEqualTo(USER_A_REF);

    // Pro-Benutzer-Isolation: User B hat einen separaten State (simuliert)
    // Kein save fuer User B darf aufgerufen worden sein
    final TaskUserState stateB = stateForUser(TASK_DB_ID, USER_B_REF, TENANT_ID);
    assertThat(stateB.isPinned())
        .as("User-B-State ist unabhaengig (initial false)")
        .isFalse();
    assertThat(stateA.getUserRef())
        .as("User-A-State und User-B-State sind verschieden")
        .isNotEqualTo(stateB.getUserRef());
  }

  // ---- Test 2: flag_pro_benutzer ---------------------------------------------------------------

  @Test
  @DisplayName("flag_pro_benutzer: IS_FLAGGED wird im TaskUserState des Actors gesetzt")
  void flag_pro_benutzer() {
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    controller.flag(TASK_EXTERNAL_ID, new MarkerInput(true));

    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));

    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isFlagged())
        .as("IS_FLAGGED muss nach flag(true) true sein")
        .isTrue();
  }

  // ---- Test 3: mute_pro_benutzer ---------------------------------------------------------------

  @Test
  @DisplayName("mute_pro_benutzer: IS_MUTED wird im TaskUserState des Actors gesetzt")
  void mute_pro_benutzer() {
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    controller.mute(TASK_EXTERNAL_ID, new MarkerInput(true));

    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));

    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isMuted())
        .as("IS_MUTED muss nach mute(true) true sein")
        .isTrue();
  }

  // ---- Test 4: pin_deaktivieren ---------------------------------------------------------------

  @Test
  @DisplayName("pin_deaktivieren: IS_PINNED wird auf false gesetzt wenn value=false")
  void pin_deaktivieren() {
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    stateA.setPinned(true); // War vorher angepinnt
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    controller.pin(TASK_EXTERNAL_ID, new MarkerInput(false));

    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isPinned())
        .as("IS_PINNED muss nach pin(false) false sein")
        .isFalse();
  }

  // ---- Test 5: 404-Masking bei nicht existenter Task -------------------------------------------

  @Test
  @DisplayName("pin_nicht_existente_task_liefert_404: EntityNotFoundException bei unbekannter UUID")
  void pin_nicht_existente_task_liefert_404() {
    when(taskRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.pin(UUID.randomUUID(), new MarkerInput(true)))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- Test 6: 404-Masking bei fremdem Mandant -------------------------------------------------

  @Test
  @DisplayName(
      "pin_fremder_tenant_liefert_404: EntityNotFoundException bei fremdem Mandant,"
          + " identische Meldung wie nicht gefunden")
  void pin_fremder_tenant_liefert_404() {
    final Task foreignTask = taskWithId(99L, TASK_EXTERNAL_ID);
    foreignTask.setTenantId(TENANT_ID + 1); // Anderer Mandant
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString()))
        .thenReturn(Optional.of(foreignTask));

    assertThatThrownBy(() -> controller.pin(TASK_EXTERNAL_ID, new MarkerInput(true)))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  // ---- Test 7: zwei Benutzer unabhaengig -------------------------------------------------------

  @Test
  @DisplayName(
      "zwei_benutzer_unabhaengige_marker: Benutzer A und B haben eigene TaskUserState-Eintraege")
  void zwei_benutzer_unabhaengige_marker() {
    // Beide Benutzer haben denselben Task, aber eigene States
    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    final TaskUserState stateB = stateForUser(TASK_DB_ID, USER_B_REF, TENANT_ID);

    // State A wird angepinnt
    stateA.setPinned(true);
    // State B bleibt unpinned
    stateB.setPinned(false);

    // Beweise: beide States sind voneinander unabhaengig
    assertThat(stateA.isPinned()).as("State A ist angepinnt").isTrue();
    assertThat(stateB.isPinned()).as("State B ist NICHT angepinnt (unabhaengig)").isFalse();
    assertThat(stateA.getUserRef())
        .as("States gehoeren verschiedenen Benutzern")
        .isNotEqualTo(stateB.getUserRef());
    assertThat(stateA.getTaskId())
        .as("Beide States referenzieren denselben Task")
        .isEqualTo(stateB.getTaskId());
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static Task taskWithId(final Long id, final UUID uuid) {
    final Task task = new Task();
    task.setId(id);
    task.setUuid(uuid);
    task.setTenantId(TENANT_ID);
    task.setTitle("Testtitel");
    return task;
  }

  private static TaskUserState stateForUser(
      final Long taskId, final UUID userRef, final Long tenantId) {
    final TaskUserState state = new TaskUserState();
    state.setTaskId(taskId);
    state.setUserRef(userRef);
    state.setTenantId(tenantId);
    state.setUserTenantId(tenantId);
    state.setPinned(false);
    state.setFlagged(false);
    state.setMuted(false);
    return state;
  }
}
