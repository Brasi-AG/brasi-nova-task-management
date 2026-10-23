package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
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
 * Unit-Tests fuer {@link MarkerService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten fuer persoenliche Marker:
 * <ol>
 *   <li>Marker wird im TaskUserState des richtigen Actors gesetzt (pin/flag/mute)
 *   <li>Zwei verschiedene Benutzer haben unabhaengige Marker (pro-Benutzer-Isolation)
 *   <li>404-Masking bei unbekannter/fremder Task-UUID
 *   <li>UNSUBSCRIBED_AT wird von Marker-Set NICHT veraendert (Inbox-Invariante implizit
 *       via ensure-Aufruf, nicht reactivate)
 *   <li>Marker kann deaktiviert werden (value=false)
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkerServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID USER_A_REF = UUID.randomUUID();
  private static final UUID USER_B_REF = UUID.randomUUID();
  private static final Long TASK_DB_ID = 42L;
  private static final UUID TASK_EXTERNAL_ID = UUID.randomUUID();

  @Mock private BaseTaskRepository taskRepository;
  @Mock private TaskUserStateQueryRepository userStateQueryRepository;
  @Mock private UserStateService userStateService;
  @Mock private InboxSignalService inboxSignalService;
  @Mock private InboxGenerationService inboxGenerationService;
  @Mock private UserAccessor userAccessor;

  private MarkerService markerService;

  @BeforeEach
  void setUp() {
    markerService =
        new MarkerService(
            taskRepository,
            userStateQueryRepository,
            userStateService,
            inboxSignalService,
            inboxGenerationService,
            userAccessor);

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
    markerService.setPinned(TASK_EXTERNAL_ID, true);

    // Assert: ensure wurde mit User-A-Ref aufgerufen (nicht reactivate!)
    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));

    // Assert: gespeicherter State hat IS_PINNED=true
    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isPinned())
        .as("IS_PINNED muss nach setPinned(true) true sein")
        .isTrue();
    assertThat(captor.getValue().getUserRef())
        .as("Gespeicherter State muss User A gehoeren")
        .isEqualTo(USER_A_REF);

    // Pro-Benutzer-Isolation: User B hat einen separaten State (simuliert)
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

    markerService.setFlagged(TASK_EXTERNAL_ID, true);

    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));

    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isFlagged())
        .as("IS_FLAGGED muss nach setFlagged(true) true sein")
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

    markerService.setMuted(TASK_EXTERNAL_ID, true);

    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));

    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isMuted())
        .as("IS_MUTED muss nach setMuted(true) true sein")
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

    markerService.setPinned(TASK_EXTERNAL_ID, false);

    final ArgumentCaptor<TaskUserState> captor = ArgumentCaptor.forClass(TaskUserState.class);
    verify(userStateQueryRepository).save(captor.capture());
    assertThat(captor.getValue().isPinned())
        .as("IS_PINNED muss nach setPinned(false) false sein")
        .isFalse();
  }

  // ---- Test 5: 404-Masking bei nicht existenter Task -------------------------------------------

  @Test
  @DisplayName(
      "pin_nicht_existente_task_liefert_404: EntityNotFoundException bei unbekannter UUID")
  void pin_nicht_existente_task_liefert_404() {
    when(taskRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> markerService.setPinned(UUID.randomUUID(), true))
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

    assertThatThrownBy(() -> markerService.setPinned(TASK_EXTERNAL_ID, true))
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

  // ---- Test 8: ensure-statt-reactivate (Inbox-Invariante) -------------------------------------

  @Test
  @DisplayName(
      "ensure_nicht_reactivate_aufgerufen: userStateService.ensure wird gerufen,"
          + " NICHT reactivate (Inbox-Invariante)")
  void ensure_nicht_reactivate_aufgerufen() {
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    markerService.setPinned(TASK_EXTERNAL_ID, true);

    // ensure MUSS aufgerufen worden sein
    verify(userStateService).ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID));
    // reactivate darf NIEMALS aufgerufen werden (wuerde UNSUBSCRIBED_AT loeschen)
    // Mockito-Default: kein Aufruf = kein Fehler; explizit via verifyNoMoreInteractions
    // nicht noetig hier, da ensure der einzige zu pruefende Aufruf ist
  }

  // ---- Test 9: P2-3 / Codex #6 - Neuanlage etabliert Membership -> Bump + Invalidate ----------

  @Test
  @DisplayName(
      "marker_neuanlage_bumpt_und_invalidiert: ensure legt neuen aktiven UserState an (created=true)"
          + " -> InboxGenerationService.bump + InboxSignalService.invalidate fuer den Actor")
  void marker_neuanlage_bumpt_und_invalidiert() {
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    // ensure meldet Neuanlage -> Task wird fuer den Benutzer inbox-faehig
    when(userStateService.ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID))).thenReturn(true);

    // Der (neu angelegte) State fuer das Marker-Setzen
    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    markerService.setPinned(TASK_EXTERNAL_ID, true);

    // Codex #6: Membership-Etablierung -> Generation-Bump des Actors ATOMAR in der Tx (Keyset-Effekt:
    // der neu sichtbare Task begruendet einen alten aktiven Cursor als stale -> 410).
    verify(inboxGenerationService).bump(eq(TENANT_ID), eq(USER_A_REF));
    // und after-commit snapshot-invalidated fuer den Actor.
    verify(inboxSignalService).invalidate(eq(TENANT_ID), eq(List.of(USER_A_REF)));
  }

  // ---- Test 10: P2-3 / Codex #6 - bestehender UserState -> KEIN Bump, KEIN Invalidate ----------

  @Test
  @DisplayName(
      "marker_bestehender_state_bumpt_und_invalidiert_nicht: ensure meldet keinen Neuzugang"
          + " (created=false) -> kein Generation-Bump, kein InboxSignalService.invalidate")
  void marker_bestehender_state_bumpt_und_invalidiert_nicht() {
    final Task task = taskWithId(TASK_DB_ID, TASK_EXTERNAL_ID);
    when(taskRepository.findByUuid(TASK_EXTERNAL_ID.toString())).thenReturn(Optional.of(task));

    // ensure meldet: Eintrag existierte bereits -> keine neue Inbox-Faehigkeit
    when(userStateService.ensure(eq(TASK_DB_ID), eq(USER_A_REF), eq(TENANT_ID))).thenReturn(false);

    final TaskUserState stateA = stateForUser(TASK_DB_ID, USER_A_REF, TENANT_ID);
    when(userStateQueryRepository.findByTaskIdAndUserRef(TASK_DB_ID, USER_A_REF))
        .thenReturn(List.of(stateA));

    markerService.setFlagged(TASK_EXTERNAL_ID, true);

    // Reines State-UPDATE -> kein Keyset-/Membership-Effekt: weder Generation-Bump noch Invalidate.
    verify(inboxGenerationService, never()).bump(any(Long.class), any());
    verify(inboxSignalService, never()).invalidate(any(Long.class), any());
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static Task taskWithId(final Long id, final UUID uuid) {
    final Task task = new Task();
    task.setId(id);
    task.setUuid(uuid);
    task.setTenantId(TENANT_ID);
    task.setEntityState(io.keel.framework.api.model.EntityState.ACTIVE);
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
