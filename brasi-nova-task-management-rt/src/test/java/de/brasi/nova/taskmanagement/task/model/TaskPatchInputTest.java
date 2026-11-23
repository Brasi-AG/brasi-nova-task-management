package de.brasi.nova.taskmanagement.task.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.validation.EntityValidationException;
import io.keel.framework.web.jackson.PatchValueDeserializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Deserialisierung von {@link TaskPatchInput}: absent / Wert / explizites null je Feld.
 *
 * <p>Registriert den KSF-{@link PatchValueDeserializer} von Hand; dass die Anwendung ihn
 * verdrahtet, belegt der Controller-IT (PATCH description).
 *
 * <p>Intake #190 (Task 10): {@code mentions} und {@code divisionMentions} sind optionale Listen
 * ({@code null} und fehlend = leer), nach Rohlaenge begrenzt (100 bzw. 10, auch bei identischen
 * UUIDs), ohne {@code null}-Elemente und nur zusammen mit einem vorhandenen {@code description}
 * zulaessig (auch wenn dieser die Beschreibung leert); sonst 400.
 */
class TaskPatchInputTest {

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ObjectMapper mapper() {
    return Jackson2ObjectMapperBuilder.json()
        .modulesToInstall(
            new SimpleModule()
                .addDeserializer(
                    (Class) PatchValue.class, (JsonDeserializer) new PatchValueDeserializer<>()))
        .build();
  }

  @Test
  void descriptionIsDeserializedAsPresentValueOrExplicitNull() throws Exception {
    final ObjectMapper mapper = mapper();

    final TaskPatchInput gesetzt =
        mapper.readValue("{\"description\":\"Neuer Text\"}", TaskPatchInput.class);
    assertThat(gesetzt.description().isPresent()).isTrue();
    assertThat(gesetzt.description().get()).isEqualTo("Neuer Text");

    final TaskPatchInput geloescht =
        mapper.readValue("{\"description\":null}", TaskPatchInput.class);
    assertThat(geloescht.description().isPresent()).isTrue();
    assertThat(geloescht.description().get()).isNull();

    final TaskPatchInput ohne = mapper.readValue("{\"title\":\"T\"}", TaskPatchInput.class);
    assertThat(ohne.description().isPresent()).isFalse();
  }

  @Test
  void emptyLeavesDescriptionAbsent() {
    assertThat(TaskPatchInput.empty().description().isPresent()).isFalse();
  }

  @Test
  void priorityCodeIsDeserializedAsPresentValueOrExplicitNull() throws Exception {
    final ObjectMapper mapper = mapper();

    final TaskPatchInput gesetzt =
        mapper.readValue("{\"priorityCode\":\"HIGH\"}", TaskPatchInput.class);
    assertThat(gesetzt.priorityCode().isPresent()).isTrue();
    assertThat(gesetzt.priorityCode().get()).isEqualTo("HIGH");

    final TaskPatchInput zurueckgesetzt =
        mapper.readValue("{\"priorityCode\":null}", TaskPatchInput.class);
    assertThat(zurueckgesetzt.priorityCode().isPresent()).isTrue();
    assertThat(zurueckgesetzt.priorityCode().get()).isNull();

    final TaskPatchInput ohne = mapper.readValue("{\"title\":\"T\"}", TaskPatchInput.class);
    assertThat(ohne.priorityCode().isPresent()).isFalse();
  }

  @Test
  void emptyLeavesPriorityCodeAbsent() {
    assertThat(TaskPatchInput.empty().priorityCode().isPresent()).isFalse();
  }

  // ---- Intake #190: Erwaehnungen in der Beschreibung ------------------------------------------

  /** Text der 400, wenn Erwaehnungen ohne Aenderung der Beschreibung gesendet werden. */
  private static final String MENTIONS_WITHOUT_DESCRIPTION =
      "Erwaehnungen sind nur zusammen mit einer Aenderung der Beschreibung zulaessig.";

  @Test
  @DisplayName(
      "mentions oder divisionMentions ohne description -> 400 mit festem Text; leere Listen ohne"
          + " description sind erlaubt")
  void i190_mentions_without_description_400() {
    assertThatThrownBy(() -> patch(PatchValue.absent(), List.of(UUID.randomUUID()), List.of()))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage(MENTIONS_WITHOUT_DESCRIPTION);
    assertThatThrownBy(() -> patch(PatchValue.absent(), List.of(), List.of(UUID.randomUUID())))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage(MENTIONS_WITHOUT_DESCRIPTION);
    assertThatThrownBy(
            () ->
                new TaskPatchInput(
                    PatchValue.of("Neuer Titel"),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.absent(),
                    PatchValue.of("HIGH"),
                    List.of(UUID.randomUUID()),
                    null))
        .as("andere Felder ersetzen die Beschreibung nicht")
        .isInstanceOf(EntityValidationException.class)
        .hasMessage(MENTIONS_WITHOUT_DESCRIPTION);

    final TaskPatchInput ohneErwaehnung = patch(PatchValue.absent(), List.of(), null);
    assertThat(ohneErwaehnung.mentions()).isEmpty();
    assertThat(ohneErwaehnung.divisionMentions()).isEmpty();
  }

  @Test
  @DisplayName(
      "description vorhanden (Text oder null zum Leeren) erlaubt Erwaehnungen; Listen werden"
          + " uebernommen")
  void i190_mentions_with_present_description_accepted() {
    final UUID person = UUID.randomUUID();
    final UUID division = UUID.randomUUID();

    final TaskPatchInput mitText =
        patch(PatchValue.of("Bitte ansehen."), List.of(person), List.of(division));
    assertThat(mitText.mentions()).containsExactly(person);
    assertThat(mitText.divisionMentions()).containsExactly(division);

    final TaskPatchInput geleert = patch(PatchValue.ofNull(), List.of(person), List.of(division));
    assertThat(geleert.description().isPresent()).isTrue();
    assertThat(geleert.mentions()).containsExactly(person);
    assertThat(geleert.divisionMentions()).containsExactly(division);
  }

  @Test
  @DisplayName(
      "101 mentions oder 11 divisionMentions (auch identische) -> 400 mit Grenze im Text; 100 bzw."
          + " 10 sind erlaubt")
  void i190_patch_mentions_limits() {
    final PatchValue<String> description = PatchValue.of("Text.");

    assertThatThrownBy(() -> patch(description, uuids(101), List.of()))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("mentions darf hoechstens 100 Eintraege enthalten.");
    final UUID same = UUID.randomUUID();
    assertThatThrownBy(() -> patch(description, Collections.nCopies(101, same), List.of()))
        .as("Rohlaenge zaehlt, nicht die Zahl verschiedener UUIDs")
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("mentions darf hoechstens 100 Eintraege enthalten.");

    assertThatThrownBy(() -> patch(description, List.of(), uuids(11)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("divisionMentions darf hoechstens 10 Eintraege enthalten.");
    assertThatThrownBy(() -> patch(description, List.of(), Collections.nCopies(11, same)))
        .as("Rohlaenge zaehlt, nicht die Zahl verschiedener UUIDs")
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("divisionMentions darf hoechstens 10 Eintraege enthalten.");

    final TaskPatchInput grenze = patch(description, uuids(100), uuids(10));
    assertThat(grenze.mentions()).hasSize(100);
    assertThat(grenze.divisionMentions()).hasSize(10);
  }

  @Test
  @DisplayName("null-Element in mentions oder divisionMentions -> 400")
  void i190_patch_mentions_reject_null_elements() {
    final PatchValue<String> description = PatchValue.of("Text.");

    assertThatThrownBy(() -> patch(description, Arrays.asList(UUID.randomUUID(), null), List.of()))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("mentions darf keine null-Elemente enthalten.");
    assertThatThrownBy(() -> patch(description, List.of(), Arrays.asList(null, UUID.randomUUID())))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("divisionMentions darf keine null-Elemente enthalten.");
  }

  @Test
  @DisplayName(
      "Listen werden unveraenderlich kopiert, Duplikate bleiben (Deduplizierung im Service);"
          + " empty() hat leere Listen")
  void i190_patch_mentions_copied_immutably() {
    final UUID person = UUID.randomUUID();
    final List<UUID> source = new ArrayList<>(List.of(person, person));

    final TaskPatchInput input = patch(PatchValue.of("Text."), source, null);
    source.clear();

    assertThat(input.mentions()).containsExactly(person, person);
    assertThat(input.divisionMentions()).isEmpty();
    assertThatThrownBy(() -> input.mentions().add(UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThat(TaskPatchInput.empty().mentions()).isEmpty();
    assertThat(TaskPatchInput.empty().divisionMentions()).isEmpty();
  }

  @Test
  @DisplayName(
      "JSON: fehlende oder null-Listen ergeben leere Listen, Arrays werden gelesen; Erwaehnung"
          + " ohne description scheitert schon beim Lesen")
  void i190_json_mentions_optional() throws Exception {
    final ObjectMapper mapper = mapper();
    final UUID person = UUID.randomUUID();
    final UUID division = UUID.randomUUID();

    final TaskPatchInput ohne = mapper.readValue("{\"title\":\"T\"}", TaskPatchInput.class);
    assertThat(ohne.mentions()).isEmpty();
    assertThat(ohne.divisionMentions()).isEmpty();

    final TaskPatchInput nullListen =
        mapper.readValue(
            "{\"description\":\"D\",\"mentions\":null,\"divisionMentions\":null}",
            TaskPatchInput.class);
    assertThat(nullListen.mentions()).isEmpty();
    assertThat(nullListen.divisionMentions()).isEmpty();

    final TaskPatchInput gelesen =
        mapper.readValue(
            "{\"description\":\"D\",\"mentions\":[\""
                + person
                + "\"],\"divisionMentions\":[\""
                + division
                + "\"]}",
            TaskPatchInput.class);
    assertThat(gelesen.mentions()).containsExactly(person);
    assertThat(gelesen.divisionMentions()).containsExactly(division);

    assertThatThrownBy(
            () ->
                mapper.readValue(
                    "{\"title\":\"T\",\"mentions\":[\"" + person + "\"]}", TaskPatchInput.class))
        .hasRootCauseInstanceOf(EntityValidationException.class)
        .hasRootCauseMessage(MENTIONS_WITHOUT_DESCRIPTION);
  }

  /** Patch-Eingabe nur mit Beschreibung und Erwaehnungen (alle anderen Felder absent). */
  private static TaskPatchInput patch(
      final PatchValue<String> description,
      final List<UUID> mentions,
      final List<UUID> divisionMentions) {
    return new TaskPatchInput(
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        PatchValue.absent(),
        description,
        PatchValue.absent(),
        mentions,
        divisionMentions);
  }

  private static List<UUID> uuids(final int count) {
    final List<UUID> uuids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      uuids.add(UUID.randomUUID());
    }
    return uuids;
  }
}
