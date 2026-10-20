package de.brasi.nova.taskmanagement.participant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link ParticipantAddInput} (reine Record-Validierung, kein DB-/Spring-Kontext).
 *
 * <p>TDD-Rationale: beweist die Guard-Invarianten der Eingabe analog {@code TaskCreateInputTest}:
 * null -> 400, null-Element -> 400, ROHE Laenge > 100 -> 400 (auch bei Duplikaten; BE-4-Lesson),
 * leeres Array erlaubt (No-Op), gueltige Menge akzeptiert.
 */
class ParticipantAddInputTest {

  @Test
  @DisplayName("null_participantIds_wirft_400")
  void null_participantIds_wirft_400() {
    assertThatThrownBy(() -> new ParticipantAddInput(null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("participantIds");
  }

  @Test
  @DisplayName("null_element_wirft_400")
  void null_element_wirft_400() {
    final List<UUID> withNull = Arrays.asList(UUID.randomUUID(), null);
    assertThatThrownBy(() -> new ParticipantAddInput(withNull))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("participantIds");
  }

  @Test
  @DisplayName("ueber_limit_101_distinct_wirft_400")
  void ueber_limit_101_distinct_wirft_400() {
    final List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      tooMany.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> new ParticipantAddInput(tooMany))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("participantIds");
  }

  @Test
  @DisplayName("ueber_limit_101_duplikate_wirft_400_rohe_laenge")
  void ueber_limit_101_duplikate_wirft_400() {
    // BE-4-Lesson: 101 IDENTISCHE UUIDs duerfen die MAX=100-Grenze NICHT ueber die Dedup umgehen -
    // die ROHE Laenge wird geprueft, bevor dedupliziert wird.
    final UUID same = UUID.randomUUID();
    final List<UUID> duplicates = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      duplicates.add(same);
    }
    assertThatThrownBy(() -> new ParticipantAddInput(duplicates))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("participantIds");
  }

  @Test
  @DisplayName("leeres_array_erlaubt_noop")
  void leeres_array_erlaubt() {
    assertThatCode(() -> new ParticipantAddInput(List.of())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("gueltige_menge_akzeptiert")
  void gueltige_menge_akzeptiert() {
    final List<UUID> refs = List.of(UUID.randomUUID(), UUID.randomUUID());
    final ParticipantAddInput input = new ParticipantAddInput(refs);
    assertThat(input.participantIds()).containsExactlyElementsOf(refs);
  }

  @Test
  @DisplayName("genau_100_erlaubt_grenze")
  void genau_100_erlaubt() {
    final List<UUID> exactly100 = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      exactly100.add(UUID.randomUUID());
    }
    assertThatCode(() -> new ParticipantAddInput(exactly100)).doesNotThrowAnyException();
  }
}
