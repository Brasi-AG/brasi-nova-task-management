package de.brasi.nova.taskmanagement.participant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer das BE-7-erweiterte {@link ExitInput} (Form-Validierung im compact-ctor).
 *
 * <p>Der compact-ctor prueft ausschliesslich die Form (exitKind non-null; handedOverToIds null->[]
 * mit roher Laenge &le; 100 und ohne null-Element; shouldStay null->false). Die fachliche Semantik
 * (UNSUBSCRIBED-mit-Zielen, Selbstziel, Creator-409, aktive Benutzer) bleibt bewusst im Service
 * (SPEC F4), nicht im DTO.
 */
class ExitInputTest {

  @Test
  @DisplayName("exitKind null -> 400 (EntityValidationException)")
  void exitKind_null_wirft_400() {
    assertThatThrownBy(() -> new ExitInput(null, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("exitKind");
  }

  @Test
  @DisplayName("handedOverToIds null -> normalisiert auf leere Liste; shouldStay null -> false")
  void null_felder_normalisiert() {
    final ExitInput in = new ExitInput(ExitKind.HANDOVER, null, null);
    assertThat(in.handedOverToIds()).isNotNull().isEmpty();
    assertThat(in.shouldStay()).isNotNull().isFalse();
  }

  @Test
  @DisplayName("handedOverToIds kopiert defensiv und bleibt erhalten")
  void handedOverToIds_bleibt_erhalten() {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final ExitInput in = new ExitInput(ExitKind.HANDOVER, List.of(a, b), true);
    assertThat(in.handedOverToIds()).containsExactly(a, b);
    assertThat(in.shouldStay()).isTrue();
  }

  @Test
  @DisplayName("rohe Laenge > 100 (vor Dedup) -> 400")
  void rohe_laenge_ueber_100_wirft_400() {
    final List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      tooMany.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> new ExitInput(ExitKind.HANDOVER, tooMany, false))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("handedOverToIds");
  }

  @Test
  @DisplayName("genau 100 Ziele (rohe Laenge) -> zulaessig")
  void genau_100_zulaessig() {
    final List<UUID> exactly = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      exactly.add(UUID.randomUUID());
    }
    final ExitInput in = new ExitInput(ExitKind.HANDOVER, exactly, false);
    assertThat(in.handedOverToIds()).hasSize(100);
  }

  @Test
  @DisplayName("null-Element in handedOverToIds -> 400")
  void null_element_wirft_400() {
    final List<UUID> withNull = new ArrayList<>();
    withNull.add(UUID.randomUUID());
    withNull.add(null);
    assertThatThrownBy(() -> new ExitInput(ExitKind.HANDOVER, withNull, false))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("handedOverToIds");
  }
}
