package de.brasi.nova.taskmanagement.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.controller.binding.NovaTenantBindingRequestBodyAdvice;
import io.keel.framework.web.exception.NovaExceptionHandlingProperties;
import io.keel.framework.web.exception.NovaExceptionMetrics;
import io.keel.framework.web.traversal.NovaTraversalProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

/**
 * HTTP-Test von {@code POST /api/v1/tasks/{id}/comments/with-attachments} mit der echten KSF-{@link
 * NovaTenantBindingRequestBodyAdvice} im MVC-Kontext (Intake #152 Teil B, Live-Befund 500 auf
 * dev-ui). Ohne Datenbank und ohne Spring-Kontext: ein eigenstaendiges MockMvc mit dem echten
 * {@link CommentController}, dem echten {@link CommentPartReader}, der KSF-Fehlerbehandlung, {@link
 * MultipartErrorAdvice} und der KSF-Advice; die Services sind gemockt, ein Mandant steht im Kontext
 * wie im Dienst.
 *
 * <p>Hintergrund: Die KSF-Advice laeuft nach jedem Lesen ueber einen MessageConverter und behandelt
 * jedes Array als {@code Object[]}. Wird der Teil {@code comment} als {@code byte[]} gebunden,
 * endet jede Anfrage mit {@code ClassCastException} und 500 {@code NOVA_INTERNAL_ERROR}. Die
 * bisherigen MockMvc-Tests hatten die Advice nicht im Kontext und blieben gruen.
 *
 * <p>Erwartet: Der Teil {@code comment} wird mit {@code application/json}, ohne Content-Type und
 * als {@code text/plain} angenommen (201); ungueltiges JSON ergibt 400 mit {@code errorKey} {@code
 * NOVA_MESSAGE_NOT_READABLE}; ein leerer oder fehlender Teil ergibt wie bisher 400 mit {@code
 * NOVA_VALIDATION_FAILED}.
 */
class CommentWithAttachmentsKsfAdviceTest {

  private static final UUID TASK_ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
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
            .setControllerAdvice(
                new MultipartErrorAdvice(handler, properties()),
                handler,
                new NovaTenantBindingRequestBodyAdvice(new NovaTraversalProperties()))
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
    TenantContextHolder.setTenant(new TenantInfo(UUID.randomUUID(), 42L));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName("#152 B Teil comment mit application/json und KSF-Advice -> 201")
  void i152b_json_part_is_accepted() throws Exception {
    post("application/json", "{\"text\":\"Mit JSON-Typ\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Mit JSON-Typ"));

    assertThat(capturedInput().text()).isEqualTo("Mit JSON-Typ");
  }

  @Test
  @DisplayName("#152 B Teil comment ohne Content-Type (FormData) und KSF-Advice -> 201, UTF-8")
  void i152b_part_without_content_type_is_accepted() throws Exception {
    final UUID mention = UUID.randomUUID();

    post(null, "{\"text\":\"Größe prüfen – bitte\",\"mentions\":[\"" + mention + "\"]}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.text").value("Größe prüfen – bitte"));

    final CommentCreateInput input = capturedInput();
    assertThat(input.text()).isEqualTo("Größe prüfen – bitte");
    assertThat(input.mentions()).containsExactly(mention);
  }

  @Test
  @DisplayName("#152 B Teil comment als text/plain und KSF-Advice -> 201")
  void i152b_text_plain_part_is_accepted() throws Exception {
    post("text/plain", "{\"text\":\"Als Klartext gesendet\"}").andExpect(status().isCreated());

    assertThat(capturedInput().text()).isEqualTo("Als Klartext gesendet");
  }

  @Test
  @DisplayName(
      "#152 B ungueltiges JSON mit KSF-Advice -> 400 NOVA_MESSAGE_NOT_READABLE (nicht 500),"
          + " kein Service-Aufruf")
  void i152b_invalid_json_is_400() throws Exception {
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
      "#152 B leerer Teil comment mit KSF-Advice -> 400 NOVA_VALIDATION_FAILED wie ein fehlender"
          + " Teil (unveraendert)")
  void i152b_empty_part_counts_as_missing() throws Exception {
    post("application/json", "")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Der Teil „comment“ fehlt in der Anfrage."));

    verify(attachmentService, never()).createCommentWithAttachments(any(), any(), any());
  }

  @Test
  @DisplayName("#152 B fehlender Teil comment mit KSF-Advice -> 400 NOVA_VALIDATION_FAILED")
  void i152b_missing_part_is_400() throws Exception {
    final MockMultipartHttpServletRequestBuilder request = multipart(URL, TASK_ID);
    request.file(new MockMultipartFile("files", "Bericht.pdf", "application/pdf", PDF_BYTES));

    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorKey").value("NOVA_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Der Teil „comment“ fehlt in der Anfrage."));

    verify(attachmentService, never()).createCommentWithAttachments(any(), any(), any());
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
    verify(attachmentService, atLeastOnce())
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
