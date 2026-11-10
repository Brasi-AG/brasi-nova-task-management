package de.brasi.nova.taskmanagement.task.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.web.jackson.PatchValueDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Deserialisierung von {@link TaskPatchInput}: absent / Wert / explizites null je Feld.
 *
 * <p>Registriert den KSF-{@link PatchValueDeserializer} von Hand; dass die Anwendung ihn
 * verdrahtet, belegt der Controller-IT (PATCH description).
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
}
