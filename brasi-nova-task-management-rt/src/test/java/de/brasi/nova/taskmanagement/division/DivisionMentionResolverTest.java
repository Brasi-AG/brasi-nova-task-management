package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskDivision;
import de.brasi.nova.taskmanagement.repository.TaskDivisionMemberQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Prueft {@link DivisionMentionResolver} (Intake #190, Task 8) mit Mockito: Aufloesung erwaehnter
 * Abteilungen auf die Personen der lokalen Projektion.
 *
 * <p>Regeln: leere Eingabe ergibt eine leere Menge ohne jede Query; jede erwaehnte Abteilung muss
 * im Mandanten aktiv sein, sonst 400 mit genau einem Text fuer unbekannt, Grabstein und fremden
 * Mandanten; die Personen aller Abteilungen werden vereinigt und dedupliziert; die Grenze {@code
 * max-persons} zaehlt die verschiedenen {@code MITARBEITER_REF} (vor dem Filter auf aktive
 * Benutzer, den {@code CommentService} danach anwendet).
 *
 * <p>Angenommene Signaturen: {@code DivisionMentionResolver(TaskDivisionQueryRepository,
 * TaskDivisionMemberQueryRepository, DivisionMentionProperties)}; {@code Set<UUID>
 * resolve(Collection<UUID>)}; {@code record DivisionMentionProperties(int maxPersons)}; {@code
 * TaskDivisionQueryRepository#findActiveByRefs(Collection<UUID>)}; {@code
 * TaskDivisionMemberQueryRepository#findDistinctEmployeeRefsByDivisionIds(Collection<Long>)}.
 */
class DivisionMentionResolverTest {

  private static final String UNKNOWN_MESSAGE = "Unbekannte oder nicht aktive Abteilung.";

  private final TaskDivisionQueryRepository divisionRepo = mock(TaskDivisionQueryRepository.class);
  private final TaskDivisionMemberQueryRepository memberRepo =
      mock(TaskDivisionMemberQueryRepository.class);

  @Test
  @DisplayName(
      "eine erwaehnte Abteilung fehlt unter den aktiven -> 400 mit einheitlichem Text, keine"
          + " Mitglieder-Query")
  void i190_unknown_division_rejected() {
    final UUID known = UUID.randomUUID();
    final UUID unknown = UUID.randomUUID();
    when(divisionRepo.findActiveByRefs(anyCollection())).thenReturn(List.of(division(1L, known)));

    assertThatThrownBy(() -> resolver(200).resolve(List.of(known, unknown)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage(UNKNOWN_MESSAGE);
    verify(memberRepo, never()).findDistinctEmployeeRefsByDivisionIds(any());
  }

  @Test
  @DisplayName("keine einzige aktive Abteilung gefunden -> 400 mit demselben Text")
  void i190_no_active_division_rejected() {
    when(divisionRepo.findActiveByRefs(anyCollection())).thenReturn(List.of());

    assertThatThrownBy(() -> resolver(200).resolve(List.of(UUID.randomUUID())))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage(UNKNOWN_MESSAGE);
    verify(memberRepo, never()).findDistinctEmployeeRefsByDivisionIds(any());
  }

  @Test
  @DisplayName(
      "Grenze zaehlt verschiedene Personen: zwei Abteilungen je 150, Schnitt 120 -> 180"
          + " verschiedene; max 200 und max 180 ok, max 179 -> 400 mit Grenze im Text")
  void i190_cap_counts_distinct_persons() {
    final UUID refA = UUID.randomUUID();
    final UUID refB = UUID.randomUUID();
    when(divisionRepo.findActiveByRefs(anyCollection()))
        .thenReturn(List.of(division(11L, refA), division(12L, refB)));
    final List<UUID> shared = uuids(120);
    final List<UUID> onlyA = uuids(30);
    final List<UUID> onlyB = uuids(30);
    // Die Query liefert hier absichtlich die Personen beider Abteilungen mit Ueberschneidung (300
    // Zeilen, 180 verschiedene): die Grenze muss die verschiedenen Personen zaehlen.
    final List<UUID> rows = new ArrayList<>();
    rows.addAll(shared);
    rows.addAll(onlyA);
    rows.addAll(shared);
    rows.addAll(onlyB);
    when(memberRepo.findDistinctEmployeeRefsByDivisionIds(anyCollection())).thenReturn(rows);

    final Set<UUID> resolved = resolver(200).resolve(List.of(refA, refB));
    assertThat(resolved).hasSize(180).containsAll(shared).containsAll(onlyA).containsAll(onlyB);

    assertThat(resolver(180).resolve(List.of(refA, refB))).as("Grenze inklusive").hasSize(180);

    assertThatThrownBy(() -> resolver(179).resolve(List.of(refA, refB)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Die erwaehnten Abteilungen umfassen mehr als 179 Personen.");
  }

  @Test
  @DisplayName("leere Eingabe -> leere Menge ohne jede Query")
  void i190_empty_input_runs_no_query() {
    assertThat(resolver(200).resolve(List.of())).isEmpty();
    verifyNoInteractions(divisionRepo, memberRepo);
  }

  @Test
  @DisplayName(
      "doppelt erwaehnte Abteilung gilt nicht als unbekannt; Mitglieder-Query mit den internen"
          + " IDs der gefundenen Abteilungen")
  void i190_duplicate_division_refs_are_deduplicated() {
    final UUID ref = UUID.randomUUID();
    final UUID person = UUID.randomUUID();
    when(divisionRepo.findActiveByRefs(anyCollection())).thenReturn(List.of(division(21L, ref)));
    when(memberRepo.findDistinctEmployeeRefsByDivisionIds(anyCollection()))
        .thenReturn(List.of(person));

    final Set<UUID> resolved = resolver(200).resolve(List.of(ref, ref));

    assertThat(resolved).containsExactly(person);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
    verify(memberRepo).findDistinctEmployeeRefsByDivisionIds(ids.capture());
    assertThat(ids.getValue()).containsExactly(21L);
  }

  @Test
  @DisplayName("aktive Abteilung ohne Mitglieder -> leere Menge, kein Fehler")
  void i190_empty_division_resolves_to_nobody() {
    final UUID ref = UUID.randomUUID();
    when(divisionRepo.findActiveByRefs(anyCollection())).thenReturn(List.of(division(31L, ref)));
    when(memberRepo.findDistinctEmployeeRefsByDivisionIds(anyCollection())).thenReturn(List.of());

    assertThat(resolver(200).resolve(List.of(ref))).isEmpty();
  }

  @Test
  @DisplayName("zwei Abteilungen: Personen werden vereinigt, die Query bekommt beide internen IDs")
  void i190_members_of_all_divisions_are_united() {
    final UUID refA = UUID.randomUUID();
    final UUID refB = UUID.randomUUID();
    final UUID p1 = UUID.randomUUID();
    final UUID p2 = UUID.randomUUID();
    final UUID p3 = UUID.randomUUID();
    when(divisionRepo.findActiveByRefs(anyCollection()))
        .thenReturn(List.of(division(41L, refA), division(42L, refB)));
    when(memberRepo.findDistinctEmployeeRefsByDivisionIds(anyCollection()))
        .thenReturn(List.of(p1, p2, p3));

    assertThat(resolver(200).resolve(List.of(refA, refB))).containsExactlyInAnyOrder(p1, p2, p3);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<UUID>> refs = ArgumentCaptor.forClass(Collection.class);
    verify(divisionRepo).findActiveByRefs(refs.capture());
    assertThat(refs.getValue()).containsExactlyInAnyOrder(refA, refB);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
    verify(memberRepo).findDistinctEmployeeRefsByDivisionIds(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder(41L, 42L);
  }

  private DivisionMentionResolver resolver(final int maxPersons) {
    return new DivisionMentionResolver(
        divisionRepo, memberRepo, new DivisionMentionProperties(maxPersons));
  }

  private static TaskDivision division(final long id, final UUID ref) {
    final TaskDivision division = new TaskDivision();
    division.setId(id);
    division.setDivisionRef(ref);
    division.setName("Abteilung " + id);
    return division;
  }

  private static List<UUID> uuids(final int count) {
    final List<UUID> uuids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      uuids.add(UUID.randomUUID());
    }
    return uuids;
  }
}
