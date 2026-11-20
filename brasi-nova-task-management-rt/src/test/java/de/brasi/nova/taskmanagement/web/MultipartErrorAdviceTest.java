package de.brasi.nova.taskmanagement.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.brasi.nova.taskmanagement.affordanceprobe.MultipartErrorProbeController;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.NovaExceptionHandlingProperties;
import io.keel.framework.web.exception.NovaExceptionMetrics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

/**
 * HTTP-Test der Multipart-Fehlerbehandlung (Intake #152, Task A7, Review Focus 2): ein
 * Probe-Handler ({@link MultipartErrorProbeController}) wirft die Ausnahme, die Spring beim Lesen
 * eines Multipart-Requests meldet; davor stehen {@link MultipartErrorAdvice} und die
 * KSF-Fehlerbehandlung ({@link GlobalApiExceptionHandler}) in einem eigenstaendigen MockMvc wie in
 * {@link LockConflictHttpTest}. Ohne Datenbank und ohne Spring-Kontext.
 *
 * <p>Erwartet: 400 im KSF-Format mit {@code errorKey} {@code NOVA_VALIDATION_FAILED} (wie jede
 * {@code EntityValidationException}) statt 500 aus dem Catch-all; {@code message} ist der Text der
 * Advice. Die harte Obergrenze kommt aus {@link AttachmentProperties} (15 MB), nie als Literal.
 */
class MultipartErrorAdviceTest {

  private static MockMvc mvc() {
    final GlobalApiExceptionHandler handler =
        new GlobalApiExceptionHandler(
            new NovaExceptionHandlingProperties(), NovaExceptionMetrics.noop());
    return MockMvcBuilders.standaloneSetup(new MultipartErrorProbeController())
        .setControllerAdvice(new MultipartErrorAdvice(handler, properties()), handler)
        .build();
  }

  @Test
  @DisplayName(
      "i152_max_upload_size_is_400_validation: MaxUploadSizeExceededException -> 400,"
          + " NOVA_VALIDATION_FAILED, Text mit der harten Obergrenze")
  void i152_max_upload_size_is_400_validation() throws Exception {
    mvc()
        .perform(post("/probe/too-large"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Die Anfrage oder eine Datei ist zu groß."
                        + " Je Datei sind höchstens 15 MB zulässig."));
  }

  @Test
  @DisplayName(
      "i152_missing_part_is_400: MissingServletRequestPartException(files) -> 400,"
          + " NOVA_VALIDATION_FAILED, Text nennt den Teil")
  void i152_missing_part_is_400() throws Exception {
    mvc()
        .perform(post("/probe/missing-part"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Der Teil „files“ fehlt in der Anfrage."));
  }

  @Test
  @DisplayName(
      "i152_multipart_exception_is_400: MultipartException -> 400, NOVA_VALIDATION_FAILED,"
          + " neutraler Text")
  void i152_multipart_exception_is_400() throws Exception {
    mvc()
        .perform(post("/probe/broken"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.message")
                .value("Die Anfrage konnte nicht als multipart/form-data gelesen werden."));
  }

  /** Properties mit den Vorgaben aus application.properties (harte Obergrenze 15 MB). */
  private static AttachmentProperties properties() {
    return new AttachmentProperties(
        new AttachmentProperties.Storage(null),
        List.of(),
        20,
        new AttachmentProperties.Limits(
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(15),
            DataSize.ofMegabytes(15)),
        new AttachmentProperties.Thumbnail(600, 0.85f, 50_000_000L),
        new AttachmentProperties.Cache(Duration.ofDays(1), Duration.ofDays(10)));
  }
}
