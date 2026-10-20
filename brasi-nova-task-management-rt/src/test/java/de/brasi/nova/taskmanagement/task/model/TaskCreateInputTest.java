package de.brasi.nova.taskmanagement.task.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.keel.framework.api.validation.EntityValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer die Eingabe-Guards des {@link TaskCreateInput}-Records (BE-4, P1).
 *
 * <p>Beweist den kompakten Konstruktor (D6): {@code assignees}/{@code tagIds} null -&gt; leere Liste,
 * null-Elemente -&gt; 400, Obergrenze {@link TaskCreateInput#MAX_IDS} auf der ROHEN Array-Laenge
 * (auch Duplikate zaehlen) -&gt; 400; {@code isPinned}/{@code isFlagged} bleiben nullable
 * (null = Default beim Anlegen).
 */
class TaskCreateInputTest {

  // ---- Normalisierung: null-Listen -> leer (Rueckwaertskompatibilitaet) ------------------------

  @Test
  @DisplayName("null_listen_werden_zu_leeren_listen: assignees/tagIds null -> leere Liste, nie null")
  void null_listen_werden_zu_leeren_listen() {
    final TaskCreateInput input =
        new TaskCreateInput("Titel", null, null, null, null, null, null, null, null);

    assertThat(input.assignees()).as("assignees null -> leer").isNotNull().isEmpty();
    assertThat(input.tagIds()).as("tagIds null -> leer").isNotNull().isEmpty();
    assertThat(input.isPinned()).as("isPinned bleibt nullable").isNull();
    assertThat(input.isFlagged()).as("isFlagged bleibt nullable").isNull();
  }

  // ---- null-Element -> 400 ---------------------------------------------------------------------

  @Test
  @DisplayName("null_element_in_assignees_liefert_400: Liste mit null-Element -> EntityValidationException")
  void null_element_in_assignees_liefert_400() {
    final List<UUID> withNull = Arrays.asList(UUID.randomUUID(), null);

    assertThatThrownBy(
            () -> new TaskCreateInput("Titel", null, null, null, null, withNull, null, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("assignees");
  }

  @Test
  @DisplayName("null_element_in_tagIds_liefert_400: Liste mit null-Element -> EntityValidationException")
  void null_element_in_tagIds_liefert_400() {
    final List<UUID> withNull = Arrays.asList((UUID) null);

    assertThatThrownBy(
            () -> new TaskCreateInput("Titel", null, null, null, null, null, withNull, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("tagIds");
  }

  // ---- Obergrenze MAX_IDS auf ROHER Laenge -> 400 (distinkt UND Duplikate) ---------------------

  @Test
  @DisplayName("zu_viele_distinkte_assignees_liefert_400: > MAX_IDS distinkte Eintraege -> 400")
  void zu_viele_distinkte_assignees_liefert_400() {
    final List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < TaskCreateInput.MAX_IDS + 1; i++) {
      tooMany.add(UUID.randomUUID());
    }

    assertThatThrownBy(
            () -> new TaskCreateInput("Titel", null, null, null, null, tooMany, null, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("assignees");
  }

  @Test
  @DisplayName("zu_viele_distinkte_tagIds_liefert_400: > MAX_IDS distinkte Eintraege -> 400")
  void zu_viele_distinkte_tagIds_liefert_400() {
    final List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < TaskCreateInput.MAX_IDS + 1; i++) {
      tooMany.add(UUID.randomUUID());
    }

    assertThatThrownBy(
            () -> new TaskCreateInput("Titel", null, null, null, null, null, tooMany, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("tagIds");
  }

  @Test
  @DisplayName(
      "zu_viele_duplikate_assignees_liefert_400 (Codex-MAJOR): ROHE Laenge zaehlt, Dedup umgeht die"
          + " Grenze NICHT -> 101x dieselbe UUID -> 400")
  void zu_viele_duplikate_assignees_liefert_400() {
    final List<UUID> dup = Collections.nCopies(TaskCreateInput.MAX_IDS + 1, UUID.randomUUID());

    assertThatThrownBy(
            () -> new TaskCreateInput("Titel", null, null, null, null, dup, null, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("assignees");
  }

  @Test
  @DisplayName(
      "zu_viele_duplikate_tagIds_liefert_400 (Codex-MAJOR): 101x dieselbe Tag-UUID -> 400")
  void zu_viele_duplikate_tagIds_liefert_400() {
    final List<UUID> dup = Collections.nCopies(TaskCreateInput.MAX_IDS + 1, UUID.randomUUID());

    assertThatThrownBy(
            () -> new TaskCreateInput("Titel", null, null, null, null, null, dup, null, null))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("tagIds");
  }

  // ---- Gueltige Werte werden uebernommen -------------------------------------------------------

  @Test
  @DisplayName("gueltige_listen_und_marker_werden_uebernommen: Felder bleiben erhalten")
  void gueltige_listen_und_marker_werden_uebernommen() {
    final UUID a1 = UUID.randomUUID();
    final UUID a2 = UUID.randomUUID();
    final UUID tag = UUID.randomUUID();

    final TaskCreateInput input =
        new TaskCreateInput(
            "Titel", null, null, null, null, List.of(a1, a2), List.of(tag), Boolean.TRUE, Boolean.FALSE);

    assertThat(input.assignees()).containsExactlyInAnyOrder(a1, a2);
    assertThat(input.tagIds()).containsExactly(tag);
    assertThat(input.isPinned()).isTrue();
    assertThat(input.isFlagged()).isFalse();
  }

  @Test
  @DisplayName("exakt_MAX_IDS_ist_erlaubt: genau die Obergrenze wird akzeptiert")
  void exakt_max_ids_ist_erlaubt() {
    final List<UUID> atLimit = new ArrayList<>();
    for (int i = 0; i < TaskCreateInput.MAX_IDS; i++) {
      atLimit.add(UUID.randomUUID());
    }

    final TaskCreateInput input =
        new TaskCreateInput("Titel", null, null, null, null, atLimit, null, null, null);
    assertThat(input.assignees()).hasSize(TaskCreateInput.MAX_IDS);
  }
}
