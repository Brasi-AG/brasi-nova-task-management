package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit-Tests fuer {@link NotificationMuteFilter} (Intake #158): welche Typen ein stummgeschalteter
 * Empfaenger nicht erhaelt, und dass die Mute-Zustaende je Ereignis in genau einer Abfrage gelesen
 * werden.
 */
class NotificationMuteFilterTest {

  private static final long TASK_ID = 42L;
  private static final UUID MUTED = UUID.randomUUID();
  private static final UUID LOUD = UUID.randomUUID();
  private static final UUID NO_STATE = UUID.randomUUID();

  private final TaskUserStateQueryRepository repository = mock(TaskUserStateQueryRepository.class);
  private final NotificationMuteFilter filter = new NotificationMuteFilter(repository);

  @ParameterizedTest
  @ValueSource(
      strings = {
        NotificationTypeCodes.COMMENT,
        NotificationTypeCodes.DONE,
        NotificationTypeCodes.CANCELLED,
        NotificationTypeCodes.CHANGE,
        NotificationTypeCodes.DUE,
        NotificationTypeCodes.SYSTEM
      })
  @DisplayName(
      "stummschaltbare Typen: stummer Empfaenger faellt heraus, andere bleiben in Reihenfolge")
  void stummschaltbarer_typ_filtert_stumme_empfaenger(final String typeCode) {
    when(repository.findMutedByTaskId(eq(TASK_ID))).thenReturn(List.of(mutedState(MUTED)));

    final List<UUID> result =
        filter.withoutMuted(TASK_ID, typeCode, List.of(LOUD, MUTED, NO_STATE));

    assertThat(NotificationMuteFilter.isMutable(typeCode)).isTrue();
    assertThat(result).containsExactly(LOUD, NO_STATE);
    verify(repository, times(1)).findMutedByTaskId(eq(TASK_ID));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        NotificationTypeCodes.MENTION,
        NotificationTypeCodes.REPLY,
        NotificationTypeCodes.ASSIGN,
        NotificationTypeCodes.HANDOVER,
        NotificationTypeCodes.PARTICIPANT,
        NotificationTypeCodes.REMOVED,
        // Intake #188: bewusst als Literal (nicht NotificationTypeCodes.UNASSIGN), damit der
        // Code-Wert festgenagelt bleibt; der Katalog-Seed V16 haengt an genau diesem String.
        "UNASSIGN"
      })
  @DisplayName("ausgenommene Typen: stumme Empfaenger bleiben erhalten, keine Abfrage")
  void ausgenommener_typ_filtert_nicht_und_liest_nicht(final String typeCode) {
    when(repository.findMutedByTaskId(anyLong())).thenReturn(List.of(mutedState(MUTED)));

    final List<UUID> result = filter.withoutMuted(TASK_ID, typeCode, List.of(MUTED, LOUD));

    assertThat(NotificationMuteFilter.isMutable(typeCode)).isFalse();
    assertThat(result).containsExactly(MUTED, LOUD);
    assertThat(filter.findMuted(TASK_ID, typeCode, List.of(MUTED))).isEmpty();
    verify(repository, never()).findMutedByTaskId(anyLong());
  }

  @Test
  @DisplayName("Ausnahmen und stummschaltbare Typen decken alle Typ-CODEs ab, ohne Ueberschneidung")
  void regel_deckt_alle_typen_ab() {
    assertThat(NotificationMuteFilter.EXEMPT_TYPES)
        .containsExactlyInAnyOrder(
            NotificationTypeCodes.MENTION,
            NotificationTypeCodes.REPLY,
            NotificationTypeCodes.ASSIGN,
            NotificationTypeCodes.HANDOVER,
            NotificationTypeCodes.PARTICIPANT,
            NotificationTypeCodes.REMOVED,
            "UNASSIGN");
  }

  @Test
  @DisplayName("leere Kandidatenmenge oder fehlende Aufgabe: keine Abfrage (kein IN ())")
  void leere_menge_ohne_abfrage() {
    assertThat(filter.withoutMuted(TASK_ID, NotificationTypeCodes.COMMENT, List.of())).isEmpty();
    assertThat(filter.findMuted(null, NotificationTypeCodes.SYSTEM, List.of(MUTED))).isEmpty();
    verify(repository, never()).findMutedByTaskId(any());
  }

  @Test
  @DisplayName(
      "findMuted liefert nur Kandidaten mit IS_MUTED; Kandidaten ohne State gelten als laut")
  void find_muted_liefert_nur_stumme_kandidaten() {
    when(repository.findMutedByTaskId(eq(TASK_ID)))
        .thenReturn(List.of(mutedState(MUTED), mutedState(UUID.randomUUID())));

    final Set<UUID> muted =
        filter.findMuted(TASK_ID, NotificationTypeCodes.DUE, List.of(MUTED, NO_STATE));

    assertThat(muted).containsExactly(MUTED);
  }

  @Test
  @DisplayName(
      "mehr als 2100 Kandidaten: genau eine Abfrage ohne Kandidatenliste (kein Parameter-Limit),"
          + " Ergebnis richtig gefiltert")
  void sehr_viele_kandidaten_eine_abfrage_ohne_kandidatenliste() {
    final List<UUID> candidates = new ArrayList<>();
    for (int i = 0; i < 2500; i++) {
      candidates.add(UUID.randomUUID());
    }
    final UUID mutedFirst = candidates.get(0);
    final UUID mutedLast = candidates.get(candidates.size() - 1);
    final UUID mutedElsewhere = UUID.randomUUID();
    when(repository.findMutedByTaskId(TASK_ID))
        .thenReturn(
            List.of(mutedState(mutedFirst), mutedState(mutedLast), mutedState(mutedElsewhere)));

    final List<UUID> result = filter.withoutMuted(TASK_ID, NotificationTypeCodes.DONE, candidates);

    assertThat(result)
        .hasSize(candidates.size() - 2)
        .doesNotContain(mutedFirst, mutedLast)
        .containsExactlyElementsOf(candidates.subList(1, candidates.size() - 1));
    verify(repository, times(1)).findMutedByTaskId(TASK_ID);
    verifyNoMoreInteractions(repository);
  }

  private static TaskUserState mutedState(final UUID userRef) {
    final TaskUserState state = new TaskUserState();
    state.setTaskId(TASK_ID);
    state.setUserRef(userRef);
    state.setMuted(true);
    return state;
  }
}
