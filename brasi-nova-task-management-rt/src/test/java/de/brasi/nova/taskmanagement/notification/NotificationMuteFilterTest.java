package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
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
    when(repository.findMutedByTaskIdAndUserRefs(eq(TASK_ID), any()))
        .thenReturn(List.of(mutedState(MUTED)));

    final List<UUID> result =
        filter.withoutMuted(TASK_ID, typeCode, List.of(LOUD, MUTED, NO_STATE));

    assertThat(NotificationMuteFilter.isMutable(typeCode)).isTrue();
    assertThat(result).containsExactly(LOUD, NO_STATE);
    verify(repository, times(1)).findMutedByTaskIdAndUserRefs(eq(TASK_ID), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        NotificationTypeCodes.MENTION,
        NotificationTypeCodes.REPLY,
        NotificationTypeCodes.ASSIGN,
        NotificationTypeCodes.HANDOVER
      })
  @DisplayName("ausgenommene Typen: stumme Empfaenger bleiben erhalten, keine Abfrage")
  void ausgenommener_typ_filtert_nicht_und_liest_nicht(final String typeCode) {
    when(repository.findMutedByTaskIdAndUserRefs(anyLong(), any()))
        .thenReturn(List.of(mutedState(MUTED)));

    final List<UUID> result = filter.withoutMuted(TASK_ID, typeCode, List.of(MUTED, LOUD));

    assertThat(NotificationMuteFilter.isMutable(typeCode)).isFalse();
    assertThat(result).containsExactly(MUTED, LOUD);
    assertThat(filter.findMuted(TASK_ID, typeCode, List.of(MUTED))).isEmpty();
    verify(repository, never()).findMutedByTaskIdAndUserRefs(anyLong(), any());
  }

  @Test
  @DisplayName("Ausnahmen und stummschaltbare Typen decken alle Typ-CODEs ab, ohne Ueberschneidung")
  void regel_deckt_alle_typen_ab() {
    assertThat(NotificationMuteFilter.EXEMPT_TYPES)
        .containsExactlyInAnyOrder(
            NotificationTypeCodes.MENTION,
            NotificationTypeCodes.REPLY,
            NotificationTypeCodes.ASSIGN,
            NotificationTypeCodes.HANDOVER);
  }

  @Test
  @DisplayName("leere Kandidatenmenge oder fehlende Aufgabe: keine Abfrage (kein IN ())")
  void leere_menge_ohne_abfrage() {
    assertThat(filter.withoutMuted(TASK_ID, NotificationTypeCodes.COMMENT, List.of())).isEmpty();
    assertThat(filter.findMuted(null, NotificationTypeCodes.SYSTEM, List.of(MUTED))).isEmpty();
    verify(repository, never()).findMutedByTaskIdAndUserRefs(any(), any());
  }

  @Test
  @DisplayName(
      "findMuted liefert nur Kandidaten mit IS_MUTED; Kandidaten ohne State gelten als laut")
  void find_muted_liefert_nur_stumme_kandidaten() {
    when(repository.findMutedByTaskIdAndUserRefs(eq(TASK_ID), any()))
        .thenReturn(List.of(mutedState(MUTED), mutedState(UUID.randomUUID())));

    final Set<UUID> muted =
        filter.findMuted(TASK_ID, NotificationTypeCodes.DUE, List.of(MUTED, NO_STATE));

    assertThat(muted).containsExactly(MUTED);
  }

  private static TaskUserState mutedState(final UUID userRef) {
    final TaskUserState state = new TaskUserState();
    state.setTaskId(TASK_ID);
    state.setUserRef(userRef);
    state.setMuted(true);
    return state;
  }
}
