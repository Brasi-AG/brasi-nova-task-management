package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import io.keel.framework.api.validation.EntityValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * Unit-Tests fuer {@link CommentPartReader} ohne Spring-Kontext (Intake #152, Nachbesserung P415):
 * der Teil {@code comment} wird als JSON gelesen, gleich mit welchem Content-Type er ankam; Fehler
 * kommen als dieselbe {@link HttpMessageNotReadableException} wie beim JSON-Weg ({@code POST
 * /api/v1/tasks/{id}/comments}), damit die KSF-Fehlerbehandlung unveraendert antwortet.
 */
class CommentPartReaderTest {

  private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();
  private static final CommentPartReader READER = new CommentPartReader(MAPPER);

  @Test
  @DisplayName(
      "#152 P415 gueltiges JSON mit Text, Eltern-Kommentar und Erwaehnungen wird wie beim JSON-Weg"
          + " gelesen")
  void i152_valid_json_is_read() {
    final UUID parent = UUID.randomUUID();
    final UUID mention = UUID.randomUUID();

    final CommentCreateInput input =
        READER.read(
            bytes(
                "{\"text\":\"Bitte prüfen\",\"parentCommentId\":\""
                    + parent
                    + "\",\"mentions\":[\""
                    + mention
                    + "\"]}"));

    assertThat(input.text()).isEqualTo("Bitte prüfen");
    assertThat(input.parentCommentId()).isEqualTo(parent);
    assertThat(input.mentions()).containsExactly(mention);
  }

  @Test
  @DisplayName("#152 P415 nur Text genuegt; mentions fehlt -> leere Liste")
  void i152_text_only() {
    final CommentCreateInput input = READER.read(bytes("{\"text\":\"Nur Text\"}"));

    assertThat(input.text()).isEqualTo("Nur Text");
    assertThat(input.parentCommentId()).isNull();
    assertThat(input.mentions()).isEmpty();
  }

  @Test
  @DisplayName(
      "#152 P415 Umlaute und Sonderzeichen bleiben erhalten (UTF-8, unabhaengig vom"
          + " Standardzeichensatz)")
  void i152_utf8_is_preserved() {
    final String text = "Größe ändern: äöüß – „fertig“ ✓";

    final CommentCreateInput input = READER.read(bytes("{\"text\":\"" + text + "\"}"));

    assertThat(input.text()).isEqualTo(text);
  }

  @Test
  @DisplayName(
      "#152 P415 leerer Text -> HttpMessageNotReadableException mit der Regel des kompakten"
          + " Konstruktors als Ursache (wie beim JSON-Weg)")
  void i152_blank_text_is_not_readable() throws Exception {
    final byte[] part = bytes("{\"text\":\"  \"}");

    final HttpMessageNotReadableException viaReader = failureOf(() -> READER.read(part));
    final HttpMessageNotReadableException viaConverter = converterFailure(part);

    assertThat(viaReader.getMostSpecificCause()).isInstanceOf(EntityValidationException.class);
    assertThat(viaReader.getMostSpecificCause().getMessage())
        .isEqualTo("Das Feld 'text' darf nicht null oder leer sein.");
    // Dieselbe Ausnahmeklasse und dieselbe Ursache wie beim JSON-Weg: KSF antwortet identisch.
    assertThat(viaReader.getClass()).isEqualTo(viaConverter.getClass());
    assertThat(viaReader.getMostSpecificCause().getMessage())
        .isEqualTo(viaConverter.getMostSpecificCause().getMessage());
  }

  @Test
  @DisplayName("#152 P415 fehlender Text und mehr als 100 Erwaehnungen sind nicht lesbar")
  void i152_rule_violations_are_not_readable() {
    final StringBuilder mentions = new StringBuilder();
    for (int i = 0; i < 101; i++) {
      mentions.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }

    assertThatThrownBy(() -> READER.read(bytes("{\"mentions\":[]}")))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasRootCauseInstanceOf(EntityValidationException.class);
    assertThatThrownBy(
            () -> READER.read(bytes("{\"text\":\"Zu viele\",\"mentions\":[" + mentions + "]}")))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasRootCauseInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "#152 P415 kein gueltiges JSON -> HttpMessageNotReadableException mit Jackson-Ursache")
  void i152_invalid_json_is_not_readable() {
    final HttpMessageNotReadableException failure =
        failureOf(() -> READER.read(bytes("{\"text\": nicht-json")));

    assertThat(failure.getMostSpecificCause()).isInstanceOf(JsonProcessingException.class);
    assertThat(failure.getHttpInputMessage()).isNotNull();
  }

  @Test
  @DisplayName("#152 P415 Text statt JSON-Objekt und JSON null sind nicht lesbar")
  void i152_non_object_is_not_readable() {
    assertThatThrownBy(() -> READER.read(bytes("kein json")))
        .isInstanceOf(HttpMessageNotReadableException.class);
    assertThatThrownBy(() -> READER.read(bytes("\"nur ein String\"")))
        .isInstanceOf(HttpMessageNotReadableException.class);
    assertThatThrownBy(() -> READER.read(bytes("null")))
        .isInstanceOf(HttpMessageNotReadableException.class);
  }

  @Test
  @DisplayName(
      "#152 FIX2 Lesefehler am Teil -> HttpMessageNotReadableException mit neutralem Text, ohne"
          + " IOException als Ursache und ohne Pfad")
  void i152_unreadable_part_has_neutral_message_and_no_cause() {
    assertThatThrownBy(() -> READER.read(new UnreadablePart()))
        .isInstanceOfSatisfying(
            HttpMessageNotReadableException.class,
            failure -> {
              // KSF schreibt die tiefste Ursache nach details: das muss die Ausnahme selbst sein.
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getMostSpecificCause()).isSameAs(failure);
              assertThat(failure.getMostSpecificCause().getMessage())
                  .isEqualTo("Teil 'comment' konnte nicht gelesen werden.")
                  .doesNotContain("upload_123")
                  .doesNotContain("IOException");
            });
  }

  // ---- Hilfen ---------------------------------------------------------------------------------

  private static byte[] bytes(final String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static HttpMessageNotReadableException failureOf(final Runnable action) {
    try {
      action.run();
    } catch (HttpMessageNotReadableException e) {
      return e;
    }
    throw new AssertionError("Erwartet: HttpMessageNotReadableException");
  }

  /** Der JSON-Weg: der Jackson-Konverter liest dieselben Bytes als {@code application/json}. */
  private static HttpMessageNotReadableException converterFailure(final byte[] part)
      throws IOException {
    final HttpInputMessage message =
        new HttpInputMessage() {
          @Override
          public InputStream getBody() {
            return new java.io.ByteArrayInputStream(part);
          }

          @Override
          public HttpHeaders getHeaders() {
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
          }
        };
    try {
      new MappingJackson2HttpMessageConverter(MAPPER).read(CommentCreateInput.class, message);
    } catch (HttpMessageNotReadableException e) {
      return e;
    }
    throw new AssertionError("Erwartet: HttpMessageNotReadableException");
  }
}
