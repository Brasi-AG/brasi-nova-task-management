package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.division.model.DivisionCandidateModel;
import de.brasi.nova.taskmanagement.division.model.DivisionCandidatePage;
import de.brasi.nova.taskmanagement.repository.DivisionCandidateRow;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Prueft {@link DivisionCandidateService} (Intake #190, Task 9) mit Mockito: Eingabepruefung der
 * Vorschlags-API {@code GET /api/v1/tasks/divisions} und Abbildung der Zeilen.
 *
 * <p>Regeln: {@code limit} 1 bis 100 (sonst 400, keine Query); {@code ids} hoechstens 50 (sonst
 * 400); {@code q} wird getrimmt, klein geschrieben und als woertlicher Teilstring in ein
 * LIKE-Muster gefasst ({@code %}, {@code _}, {@code [} und das Escape-Zeichen {@code !} mit
 * vorangestelltem {@code !} entschaerft, Muster wie {@code ArchiveSearchTerm}, Lesson {@code
 * ksf-novaquery-pitfalls} Punkt 9); leeres oder fehlendes {@code q} heisst kein Namensfilter
 * ({@code like = null}); mit {@code ids} laeuft die Vorschau ueber {@code findCandidatesByRefs}.
 *
 * <p>Angenommene Signaturen: {@code DivisionCandidateService(TaskDivisionQueryRepository)}; {@code
 * DivisionCandidatePage find(String q, List<UUID> ids, int limit)} (Mandant aus dem Kontext, die
 * Queries binden {@code :tenantId}); {@code record
 * DivisionCandidatePage(List<DivisionCandidateModel> items)}; {@code record
 * DivisionCandidateModel(UUID divisionId, String name, int memberCount)}; {@code record
 * DivisionCandidateRow(UUID divisionRef, String name, int memberCount)}; {@code
 * TaskDivisionQueryRepository#findCandidates(String like, int limit)} und {@code
 * #findCandidatesByRefs(Collection<UUID> refs)}.
 */
class DivisionCandidateServiceTest {

  private final TaskDivisionQueryRepository divisionRepo = mock(TaskDivisionQueryRepository.class);
  private final DivisionCandidateService service = new DivisionCandidateService(divisionRepo);

  @Test
  @DisplayName("limit ausserhalb 1..100 -> 400 ohne Query; 1 und 100 sind erlaubt")
  void i190_limit_out_of_range_400() {
    for (final int limit : new int[] {0, -1, 101}) {
      assertThatThrownBy(() -> service.find(null, null, limit))
          .as("limit %d", limit)
          .isInstanceOf(EntityValidationException.class);
    }
    verifyNoInteractions(divisionRepo);

    when(divisionRepo.findCandidates(any(), anyInt())).thenReturn(List.of());
    service.find(null, null, 1);
    service.find(null, null, 100);
    verify(divisionRepo).findCandidates(null, 1);
    verify(divisionRepo).findCandidates(null, 100);
  }

  @Test
  @DisplayName("mehr als 50 ids -> 400 ohne Query; 50 sind erlaubt")
  void i190_ids_over_50_400() {
    assertThatThrownBy(() -> service.find(null, uuids(51), 20))
        .isInstanceOf(EntityValidationException.class);
    verifyNoInteractions(divisionRepo);

    when(divisionRepo.findCandidatesByRefs(anyCollection())).thenReturn(List.of());
    assertThat(service.find(null, uuids(50), 20).items()).isEmpty();
  }

  @Test
  @DisplayName(
      "q wird getrimmt, klein geschrieben und woertlich gesucht: %, _, [ und ! werden mit !"
          + " entschaerft")
  void i190_q_is_trimmed_lowercased_and_escaped() {
    when(divisionRepo.findCandidates(any(), anyInt())).thenReturn(List.of());

    service.find("  Ver_Trieb 50%! [Neu]  ", null, 20);

    verify(divisionRepo).findCandidates("%ver!_trieb 50!%!! ![neu]%", 20);
  }

  @Test
  @DisplayName("fehlendes, leeres oder nur aus Leerzeichen bestehendes q -> kein Namensfilter")
  void i190_blank_q_means_no_filter() {
    when(divisionRepo.findCandidates(any(), anyInt())).thenReturn(List.of());

    service.find(null, null, 20);
    service.find("", List.of(), 20);
    service.find("   ", null, 20);

    verify(divisionRepo, times(3)).findCandidates(isNull(), eq(20));
    verify(divisionRepo, never()).findCandidatesByRefs(anyCollection());
  }

  @Test
  @DisplayName("ids gesetzt -> Vorschau ueber findCandidatesByRefs, keine Namenssuche")
  void i190_ids_use_preview_query() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    when(divisionRepo.findCandidatesByRefs(anyCollection()))
        .thenReturn(List.of(new DivisionCandidateRow(a, "Einkauf", 3)));

    final DivisionCandidatePage page = service.find(null, List.of(a, b), 20);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refs = ArgumentCaptor.forClass(Collection.class);
    verify(divisionRepo).findCandidatesByRefs(refs.capture());
    assertThat(refs.getValue()).containsExactlyInAnyOrder(a, b);
    verify(divisionRepo, never()).findCandidates(any(), anyInt());
    assertThat(page.items()).containsExactly(new DivisionCandidateModel(a, "Einkauf", 3));
  }

  @Test
  @DisplayName(
      "Zeilen werden in der Reihenfolge der Query abgebildet, ein fehlender Name bleibt null")
  void i190_rows_are_mapped_in_order() {
    final UUID first = UUID.randomUUID();
    final UUID second = UUID.randomUUID();
    final UUID unnamed = UUID.randomUUID();
    when(divisionRepo.findCandidates(any(), anyInt()))
        .thenReturn(
            List.of(
                new DivisionCandidateRow(first, "Alpha", 2),
                new DivisionCandidateRow(second, "Beta", 0),
                new DivisionCandidateRow(unnamed, null, 5)));

    final DivisionCandidatePage page = service.find(null, null, 20);

    assertThat(page.items())
        .containsExactly(
            new DivisionCandidateModel(first, "Alpha", 2),
            new DivisionCandidateModel(second, "Beta", 0),
            new DivisionCandidateModel(unnamed, null, 5));
  }

  private static List<UUID> uuids(final int count) {
    final List<UUID> uuids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      uuids.add(UUID.randomUUID());
    }
    return uuids;
  }
}
