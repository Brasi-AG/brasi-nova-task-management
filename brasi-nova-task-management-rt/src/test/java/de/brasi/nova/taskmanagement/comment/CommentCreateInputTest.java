package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prueft das Feld {@code divisionMentions} von {@link CommentCreateInput} (Intake #190, Task 8):
 * optional ({@code null} und fehlend = leere Liste), hoechstens 10 Eintraege nach Rohlaenge (auch
 * bei identischen UUIDs, Lesson {@code input-dto-patterns}), keine {@code null}-Elemente, danach
 * unveraenderliche Kopie. Die Grenze von {@code mentions} (100) bleibt davon unberuehrt.
 */
class CommentCreateInputTest {

  @Test
  @DisplayName("divisionMentions null ergibt eine leere Liste")
  void i190_division_mentions_null_is_empty() {
    assertThat(input(null).divisionMentions()).isEmpty();
  }

  @Test
  @DisplayName(
      "divisionMentions mit 11 Eintraegen (auch 11 identischen) -> 400 mit Grenze im Text; 10"
          + " sind erlaubt")
  void i190_division_mentions_max_10() {
    assertThatThrownBy(() -> input(uuids(11)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("divisionMentions darf hoechstens 10 Eintraege enthalten.");

    final UUID same = UUID.randomUUID();
    assertThatThrownBy(() -> input(Collections.nCopies(11, same)))
        .as("Rohlaenge zaehlt, nicht die Zahl verschiedener UUIDs")
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("divisionMentions darf hoechstens 10 Eintraege enthalten.");

    assertThat(input(uuids(10)).divisionMentions()).hasSize(10);
  }

  @Test
  @DisplayName("divisionMentions mit null-Element -> 400")
  void i190_division_mentions_reject_null_element() {
    assertThatThrownBy(() -> input(Arrays.asList(UUID.randomUUID(), null)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("divisionMentions darf keine null-Elemente enthalten.");
  }

  @Test
  @DisplayName(
      "divisionMentions wird unveraenderlich kopiert; Duplikate bleiben erhalten (die fachliche"
          + " Deduplizierung macht der Service)")
  void i190_division_mentions_copied_immutably() {
    final UUID division = UUID.randomUUID();
    final List<UUID> source = new ArrayList<>(List.of(division, division));

    final CommentCreateInput input = input(source);
    source.clear();

    assertThat(input.divisionMentions()).containsExactly(division, division);
    assertThatThrownBy(() -> input.divisionMentions().add(UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName(
      "JSON ohne divisionMentions oder mit null ergibt eine leere Liste, ein Array wird gelesen")
  void i190_json_division_mentions_optional() throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final UUID division = UUID.randomUUID();

    assertThat(mapper.readValue("{\"text\":\"Hallo\"}", CommentCreateInput.class).divisionMentions())
        .isEmpty();
    assertThat(
            mapper
                .readValue(
                    "{\"text\":\"Hallo\",\"divisionMentions\":null}", CommentCreateInput.class)
                .divisionMentions())
        .isEmpty();
    assertThat(
            mapper
                .readValue(
                    "{\"text\":\"Hallo\",\"divisionMentions\":[\"" + division + "\"]}",
                    CommentCreateInput.class)
                .divisionMentions())
        .containsExactly(division);
  }

  @Test
  @DisplayName(
      "mentions behaelt seine eigene Grenze 100, unabhaengig von divisionMentions (null = leer)")
  void i190_mentions_limit_unchanged() {
    final CommentCreateInput input = new CommentCreateInput("Text.", null, uuids(100), null);
    assertThat(input.mentions()).hasSize(100);
    assertThat(input.divisionMentions()).isEmpty();
    assertThatThrownBy(() -> new CommentCreateInput("Text.", null, uuids(101), List.of()))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("mentions darf hoechstens 100 Eintraege enthalten.");
  }

  private static CommentCreateInput input(final List<UUID> divisionMentions) {
    return new CommentCreateInput("Text.", null, List.of(), divisionMentions);
  }

  private static List<UUID> uuids(final int count) {
    final List<UUID> uuids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      uuids.add(UUID.randomUUID());
    }
    return uuids;
  }
}
