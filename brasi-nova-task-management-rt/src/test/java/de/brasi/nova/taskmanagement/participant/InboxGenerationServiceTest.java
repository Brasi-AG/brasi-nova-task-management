package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskInboxGeneration;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import io.keel.framework.api.exception.BusinessRuleException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Unit-Tests fuer {@link InboxGenerationService} (Repository gemockt, kein DB).
 *
 * <p>TDD-Rationale (Finding 2 / Option B, Slice 1):
 * <ol>
 *   <li>currentGeneration: 0 bei fehlender Zeile, Wert bei vorhandener.
 *   <li>bump: legt Zeile an (Start 0) + atomarer Increment; bei vorhandener Zeile nur Increment.
 *   <li>Re-Read-Translate: UNIQUE-Race beim Erst-Insert idempotent abfangen.
 *   <li>Rowcount-Enforcement (Codex-Auflage 2): incrementGeneration != 1 -> IllegalStateException.
 * </ol>
 */
class InboxGenerationServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID USER_REF = UUID.randomUUID();

  private TaskInboxGenerationQueryRepository repository;
  private InboxGenerationService service;

  @BeforeEach
  void setUp() {
    repository = mock(TaskInboxGenerationQueryRepository.class);
    service = new InboxGenerationService(repository);
  }

  private static TaskInboxGeneration row(final long generation) {
    final TaskInboxGeneration r = new TaskInboxGeneration();
    r.setTenantId(TENANT_ID);
    r.setUserRef(USER_REF);
    r.setGeneration(generation);
    return r;
  }

  // ---- currentGeneration ----------------------------------------------------------------------

  @Test
  @DisplayName("currentGeneration_leer_liefert_0")
  void currentGeneration_leer_liefert_0() {
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of());
    assertThat(service.currentGeneration(USER_REF)).isZero();
  }

  @Test
  @DisplayName("currentGeneration_liefert_wert_der_zeile")
  void currentGeneration_liefert_wert_der_zeile() {
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of(row(7L)));
    assertThat(service.currentGeneration(USER_REF)).isEqualTo(7L);
  }

  // ---- bump: Anlage + Increment ---------------------------------------------------------------

  @Test
  @DisplayName("bump_legt_zeile_an_und_erhoeht_atomar")
  void bump_legt_zeile_an_und_erhoeht_atomar() {
    // Keine Zeile vorhanden -> ensure legt sie an (Start 0), danach atomarer +1.
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of());
    when(repository.incrementGeneration(USER_REF)).thenReturn(1);

    service.bump(TENANT_ID, USER_REF);

    final ArgumentCaptor<TaskInboxGeneration> captor =
        ArgumentCaptor.forClass(TaskInboxGeneration.class);
    verify(repository).save(captor.capture());
    final TaskInboxGeneration saved = captor.getValue();
    assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(saved.getUserRef()).isEqualTo(USER_REF);
    assertThat(saved.getGeneration()).as("Erstzeile startet bei 0").isZero();
    verify(repository).incrementGeneration(USER_REF);
  }

  @Test
  @DisplayName("bump_vorhandene_zeile_nur_increment_kein_save")
  void bump_vorhandene_zeile_nur_increment_kein_save() {
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of(row(3L)));
    when(repository.incrementGeneration(USER_REF)).thenReturn(1);

    service.bump(TENANT_ID, USER_REF);

    verify(repository, never()).save(any());
    verify(repository).incrementGeneration(USER_REF);
  }

  // ---- Re-Read-Translate (UNIQUE-Race) --------------------------------------------------------

  @Test
  @DisplayName("bump_unique_race_wird_idempotent_abgefangen")
  void bump_unique_race_wird_idempotent_abgefangen() {
    // ensure: erst leer (Insert-Versuch), save wirft BusinessRuleException (nebenlaeufiger Insert
    // committet), Re-Read liefert die Zeile -> kein Rethrow, Increment laeuft.
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of(), List.of(row(0L)));
    when(repository.save(any())).thenThrow(new BusinessRuleException("UNIQUE-Verletzung"));
    when(repository.incrementGeneration(USER_REF)).thenReturn(1);

    service.bump(TENANT_ID, USER_REF);

    verify(repository).incrementGeneration(USER_REF);
  }

  @Test
  @DisplayName("bump_unique_race_recheck_leer_wirft_rethrow")
  void bump_unique_race_recheck_leer_wirft_rethrow() {
    // save wirft, aber Re-Read bleibt leer -> echter Fehler, rethrow (kein stilles Schlucken).
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of(), List.of());
    when(repository.save(any())).thenThrow(new BusinessRuleException("echter Fehler"));

    assertThatThrownBy(() -> service.bump(TENANT_ID, USER_REF))
        .isInstanceOf(BusinessRuleException.class);
    verify(repository, never()).incrementGeneration(any());
  }

  // ---- Rowcount-Enforcement (Codex-Auflage 2) -------------------------------------------------

  @Test
  @DisplayName("bump_increment_rowcount_nicht_1_wirft_IllegalState")
  void bump_increment_rowcount_nicht_1_wirft_IllegalState() {
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of(row(5L)));
    when(repository.incrementGeneration(USER_REF)).thenReturn(0);

    assertThatThrownBy(() -> service.bump(TENANT_ID, USER_REF))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("erwartet genau 1");
  }

  @Test
  @DisplayName("bump_increment_rowcount_zwei_wirft_IllegalState")
  void bump_increment_rowcount_zwei_wirft_IllegalState() {
    when(repository.findByUserRef(USER_REF)).thenReturn(List.of(row(5L)));
    when(repository.incrementGeneration(eq(USER_REF))).thenReturn(2);

    assertThatThrownBy(() -> service.bump(TENANT_ID, USER_REF))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---- bumpSorted: deterministische Lock-Ordnung (Codex #6) -----------------------------------

  @Test
  @DisplayName(
      "bumpSorted_bumpt_in_deterministisch_sortierter_reihenfolge: unabhaengig von der"
          + " Eingabe-Reihenfolge wird je Ref GENAU EINMAL und in UUID.compareTo-Ordnung gebumpt")
  void bumpSorted_bumpt_in_deterministisch_sortierter_reihenfolge() {
    // Drei feste UUIDs mit bekannter Sortier-Ordnung (compareTo). Jede Zeile vorhanden -> nur
    // Increment (kein Insert), incrementGeneration liefert je 1.
    final UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    final UUID u3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    when(repository.findByUserRef(any())).thenReturn(List.of(row(0L)));
    when(repository.incrementGeneration(any())).thenReturn(1);

    // Eingabe absichtlich UNSORTIERT (u3, u1, u2) -> erwartete Bump-Reihenfolge u1, u2, u3.
    service.bumpSorted(TENANT_ID, Arrays.asList(u3, u1, u2));

    final InOrder inOrder = inOrder(repository);
    inOrder.verify(repository).incrementGeneration(u1);
    inOrder.verify(repository).incrementGeneration(u2);
    inOrder.verify(repository).incrementGeneration(u3);
    // Keine Doppel-Bumps.
    verify(repository, org.mockito.Mockito.times(3)).incrementGeneration(any());
  }

  @Test
  @DisplayName("bumpSorted_leere_menge_ist_noop")
  void bumpSorted_leere_menge_ist_noop() {
    service.bumpSorted(TENANT_ID, List.of());
    verify(repository, never()).incrementGeneration(any());
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("bumpSorted_null_ist_noop")
  void bumpSorted_null_ist_noop() {
    service.bumpSorted(TENANT_ID, null);
    verifyNoInteractions(repository);
  }
}
