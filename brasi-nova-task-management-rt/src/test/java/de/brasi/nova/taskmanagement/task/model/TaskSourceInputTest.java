package de.brasi.nova.taskmanagement.task.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.validation.EntityValidationException;
import io.keel.framework.web.jackson.PatchValueDeserializer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class TaskSourceInputTest {
  @Test
  void sourceRequiresCompleteExactTuple() {
    assertThatThrownBy(() -> new TaskSourceInput(null, "customer", UUID.randomUUID()))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> new TaskSourceInput("application", "", UUID.randomUUID()))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> new TaskSourceInput("application", "customer", null))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> new TaskSourceInput("Application", "customer", UUID.randomUUID()))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  void clientSuppliedSourceHrefIsRejectedEvenWithBootUnknownPropertyPolicy() {
    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final String json = "{\"title\":\"Titel\",\"source\":{\"module\":\"application\","
        + "\"entityType\":\"customer\",\"entityRef\":\"" + UUID.randomUUID()
        + "\",\"href\":\"https://evil.invalid/customer\"}}";

    assertThatThrownBy(() -> mapper.readValue(json, TaskCreateInput.class))
        .hasRootCauseInstanceOf(EntityValidationException.class);
  }

  @Test
  void rootUrlAliasesAreRejectedWithoutChangingOtherManualUnknownFields() throws Exception {
    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    for (final String key : new String[] {"url", "href", "sourceUrl", "sourceHref",
        "source_url", "source_href", "sourceLink", "source_context"}) {
      assertThatThrownBy(() -> mapper.readValue(
              "{\"title\":\"Titel\",\"" + key + "\":\"https://evil.invalid\"}",
              TaskCreateInput.class))
          .hasRootCauseInstanceOf(EntityValidationException.class);
    }
    // Existing manual clients may carry unrelated fields; do not tighten their whole contract.
    mapper.readValue("{\"title\":\"Titel\",\"futureField\":true}", TaskCreateInput.class);
  }

  @Test
  void patchCannotClaimToReplaceImmutableSource() {
    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final String body = "{\"source\":{\"module\":\"fixture\",\"entityType\":\"item\","
        + "\"entityRef\":\"" + UUID.randomUUID() + "\"}}";

    assertThatThrownBy(() -> mapper.readValue(body, TaskPatchInput.class))
        .hasRootCauseInstanceOf(EntityValidationException.class);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void patchDescriptionIsDeserializedAsPresentValueOrExplicitNull() throws Exception {
    // Wie in der Anwendung: KSF-Deserializer fuer PatchValue (absent/present/null).
    final ObjectMapper mapper =
        Jackson2ObjectMapperBuilder.json()
            .modulesToInstall(
                new SimpleModule()
                    .addDeserializer(
                        (Class) PatchValue.class, (JsonDeserializer) new PatchValueDeserializer<>()))
            .build();

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
}
