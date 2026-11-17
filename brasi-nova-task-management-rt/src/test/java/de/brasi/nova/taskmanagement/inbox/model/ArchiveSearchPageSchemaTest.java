package de.brasi.nova.taskmanagement.inbox.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Haelt das Doku-Schema der Archivsuche ({@link ArchiveSearchPageSchema.Item}) deckungsgleich mit
 * der Wire-Form von {@link TaskSummaryModel}: kommt dort ein Feld hinzu oder faellt eines weg, muss
 * die OpenAPI-Beschreibung nachgezogen werden.
 */
class ArchiveSearchPageSchemaTest {

  /** Von BaseModel/RepresentationModel geerbte Wire-Felder (HAL). */
  private static final Set<String> INHERITED_WIRE_FIELDS = Set.of("id", "modelState", "_links");

  @Test
  @DisplayName("item_schema_hat_genau_die_wire_felder_von_task_summary_model")
  void item_schema_hat_genau_die_wire_felder_von_task_summary_model() throws Exception {
    final Set<String> wire = new HashSet<>(INHERITED_WIRE_FIELDS);
    for (final Field field : TaskSummaryModel.class.getDeclaredFields()) {
      final JsonProperty property = field.getAnnotation(JsonProperty.class);
      if (property != null) {
        wire.add(property.value());
      }
    }
    final Set<String> documented = new HashSet<>();
    for (final RecordComponent component :
        ArchiveSearchPageSchema.Item.class.getRecordComponents()) {
      // @JsonProperty landet am generierten Feld (kein RECORD_COMPONENT-Target).
      final JsonProperty property =
          ArchiveSearchPageSchema.Item.class
              .getDeclaredField(component.getName())
              .getAnnotation(JsonProperty.class);
      documented.add(property != null ? property.value() : component.getName());
    }
    assertThat(documented).containsExactlyInAnyOrderElementsOf(wire);
  }

  @Test
  @DisplayName("exit_schema_hat_die_felder_von_exit_model")
  void exit_schema_hat_die_felder_von_exit_model() {
    assertThat(
            Arrays.stream(ArchiveSearchPageSchema.Exit.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactlyElementsOf(
            Arrays.stream(ExitModel.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
  }

  @Test
  @DisplayName("page_schema_hat_die_cursor_felder")
  void page_schema_hat_die_cursor_felder() {
    assertThat(
            Arrays.stream(ArchiveSearchPageSchema.Page.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactly("embedded", "links", "nextCursor", "hasMore", "asOf");
  }
}
