package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import de.brasi.nova.taskmanagement.comment.model.CommentModel;
import de.brasi.nova.taskmanagement.web.MultipartErrorAdvice;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.NovaExceptionHandlingProperties;
import io.keel.framework.web.exception.NovaExceptionMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP-Test des Teils {@code comment} von {@code POST /api/v1/tasks/{id}/comments/with-attachments}
 * (Intake #152, Nachbesserung P415) ohne Datenbank und ohne Spring-Kontext: ein eigenstaendiges
 * MockMvc mit dem echten {@link CommentController}, dem echten {@link CommentPartReader}, der
 * KSF-Fehlerbehandlung und {@link MultipartErrorAdvice}; die Services sind gemockt.
 *
 * <p>Erwartet: Der Teil wird als JSON angenommen, gleich ob er mit {@code application/json}, mit
 * {@code text/plain} oder ohne Content-Type ankommt (ein Browser sendet {@code
 * FormData.append('comment', JSON.stringify(...))} ohne Typ und ohne Dateinamen; der Test sendet
 * den Teil deshalb als {@link MockPart} ohne Dateinamen); vorher endeten die beiden letzten Faelle
 * mit 415. Ungueltiges JSON und ein leerer Text ergeben 400 im KSF-Format mit dem {@code errorKey}
 * {@code NOVA_MESSAGE_NOT_READABLE}, ohne dass ein Service aufgerufen wird.
 */
class CommentWithAttachmentsPartTest {

  private static final UUID TASK_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
  private static final String URL = "/api/v1/tasks/{id}/comments/with-attachments";
  private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

  private final CommentAttachmentService attachmentService = mock(CommentAttachmentService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    final CommentController controller =
        new CommentController(
            mock(CommentService.class),
            mock(CommentQueryService.class),
            attachmentService,
            new CommentPartReader(Jackson2ObjectMapperBuilder.json().build()));
    final GlobalApiExceptionHandler handler =
        new GlobalApiExceptionHandler(
            new NovaExceptionHandlingProperties(), NovaExceptionMetrics.noop());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new MultipartErrorAdvice(handler, properties()), handler)
            .build();
    when(attachmentService.createCommentWithAttachments(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new CommentModel(
                    UUID.randomUUID(),
                    TASK_ID,
                    UUID.randomUUID(),
                    null,
                    ((CommentCreateInput) invocation.getArgument(1)).text(),
                    false,
                    Instant.parse("2026-11-20T08:00:00Z"),
                    false,
                    List.of()));
  }

  @Test
  @DisplayName("#152 P415 Teil comment mit Content-Type application/json -> 201 (wie bisher)")
  void i152_json_part_is_accepted() throws Exception {
    post("application/json", "{\"text\":\"Mit JSON-Typ\"}").andExpect(status().isCreated());

    assertThat(capturedInput().text()).isEqualTo("Mit JSON-Typ");
  }

  @Test
  @DisplayName(
      "#152 P415 Teil comment ohne Content-Type -> 201, Text mit Umlauten unveraendert (FormData)")
  void i152_part_without_content_type_is_accepted() throws Exception {
    final UUID mention = UUID.randomUUID();

    post(null, "{\"text\":\"Größe prüfen – bitte\",\"mentions\":[\"" + mention + "\"]}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Größe prüfen – bitte"));

    final CommentCreateInput input = capturedInput();
    assertThat(input.text()).isEqualTo("Größe prüfen – bitte");
    assertThat(input.mentions()).containsExactly(mention);
  }

  @Test
  @DisplayName("#152 P415 Teil comment als text/plain -> 201")
  void i152_text_plain_part_is_accepted() throws Exception {
    post("text/plain", "{\"text\":\"Als Klartext gesendet\"}").andExpect(status().isCreated());

    assertThat(capturedInput().text()).isEqualTo("Als Klartext gesendet");
  }

  @Test
  @DisplayName(
      "#152 P415 text/plain mit Zeichensatz-Angabe und andere Typen: der Inhalt gilt als JSON"
          + " (UTF-8), der Typ ist nicht massgeblich")
  void i152_content_type_is_not_decisive() throws Exception {
    post("text/plain;charset=UTF-8", "{\"text\":\"Mit Zeichensatz äöü\"}")
        .andExpect(status().isCreated());
    post("application/octet-stream", "{\"text\":\"Als Bytes\"}").andExpect(status().isCreated());
  }

  @Test
  @DisplayName(
      "#152 P415 ungueltiges JSON (ohne Typ und mit text/plain) -> 400 im KSF-Format,"
          + " errorKey NOVA_MESSAGE_NOT_READABLE, kein Service-Aufruf")
  void i152_invalid_json_is_400_in_ksf_format() throws Exception {
    for (final String contentType : new String[] {null, "text/plain", "application/json"}) {
      post(contentType, "{\"text\": kein json")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.errorKey").value("NOVA_MESSAGE_NOT_READABLE"))
          .andExpect(
              jsonPath("$.path").value("/api/v1/tasks/" + TASK_ID + "/comments/with-attachments"));
    }

    verify(attachmentService, never()).createCommentWithAttachments(any(), any(), any());
  }

  @Test
  @DisplayName(
      "#152 P415 leerer Text (ohne Typ) -> 400 wie beim JSON-Typ, errorKey"
          + " NOVA_MESSAGE_NOT_READABLE, die Regel steht in details, kein Service-Aufruf")
  void i152_blank_text_is_400_like_the_json_variant() throws Exception {
    for (final String contentType : new String[] {null, "text/plain", "application/json"}) {
      post(contentType, "{\"text\":\" \"}")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorKey").value("NOVA_MESSAGE_NOT_READABLE"))
          .andExpect(
              jsonPath("$.details[0]").value("Das Feld 'text' darf nicht null oder leer sein."));
    }

    verify(attachmentService, never()).createCommentWithAttachments(any(), any(), any());
  }

  @Test
  @DisplayName(
      "#152 FIX2 Lesefehler am Teil comment -> 400 NOVA_MESSAGE_NOT_READABLE mit neutralem Text,"
          + " weder Pfad noch Exception-Text in der Antwort, kein Service-Aufruf")
  void i152_unreadable_part_is_400_without_internal_details() throws Exception {
    final MockMultipartHttpServletRequestBuilder request = multipart(URL, TASK_ID);
    // Der Teil kommt direkt in die Anfrage: der Request-Builder liest Teile beim Aufbau selbst.
    request.with(
        servletRequest -> {
          ((MockMultipartHttpServletRequest) servletRequest).addPart(new UnreadablePart());
          return servletRequest;
        });
    request.file(new MockMultipartFile("files", "Bericht.pdf", "application/pdf", PDF_BYTES));

    final String body =
        mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errorKey").value("NOVA_MESSAGE_NOT_READABLE"))
            .andExpect(
                jsonPath("$.details[0]").value("Teil 'comment' konnte nicht gelesen werden."))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(body)
        .doesNotContain("upload_123")
        .doesNotContain("C:")
        .doesNotContain("IOException")
        .doesNotContain("Das System kann die Datei nicht finden");
    verify(attachmentService, never()).createCommentWithAttachments(any(), any(), any());
  }

  @Test
  @DisplayName("#152 P415 fehlender Teil comment bleibt 400 im KSF-Format (Teil genannt)")
  void i152_missing_comment_part_is_still_400() throws Exception {
    final MockMultipartHttpServletRequestBuilder request = multipart(URL, TASK_ID);
    request.file(new MockMultipartFile("files", "Bericht.pdf", "application/pdf", PDF_BYTES));

    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Der Teil „comment“ fehlt in der Anfrage."));

    verify(attachmentService, never()).createCommentWithAttachments(any(), any(), any());
  }

  @Test
  @DisplayName("#152 P415 die Dateien erreichen den Service unveraendert")
  void i152_files_reach_the_service() throws Exception {
    post(null, "{\"text\":\"Mit Datei\"}").andExpect(status().isCreated());

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
    verify(attachmentService)
        .createCommentWithAttachments(eq(TASK_ID), any(CommentCreateInput.class), files.capture());
    assertThat(files.getValue()).hasSize(1);
    assertThat(files.getValue().get(0).getOriginalFilename()).isEqualTo("Bericht.pdf");
  }

  // ---- Hilfen ---------------------------------------------------------------------------------

  /**
   * Sendet den Teil {@code comment} wie ein Browser mit {@code FormData.append}: ohne Dateinamen
   * und mit dem Typ (bei {@code null} ganz ohne Content-Type), dazu eine PDF-Datei.
   */
  private ResultActions post(final String contentType, final String commentJson) throws Exception {
    final MockMultipartHttpServletRequestBuilder request = multipart(URL, TASK_ID);
    final MockPart comment = new MockPart("comment", commentJson.getBytes(StandardCharsets.UTF_8));
    if (contentType != null) {
      comment.getHeaders().setContentType(MediaType.parseMediaType(contentType));
    }
    request.part(comment);
    request.file(new MockMultipartFile("files", "Bericht.pdf", "application/pdf", PDF_BYTES));
    return mvc.perform(request);
  }

  private CommentCreateInput capturedInput() {
    final ArgumentCaptor<CommentCreateInput> input =
        ArgumentCaptor.forClass(CommentCreateInput.class);
    verify(attachmentService, org.mockito.Mockito.atLeastOnce())
        .createCommentWithAttachments(eq(TASK_ID), input.capture(), any());
    return input.getValue();
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
