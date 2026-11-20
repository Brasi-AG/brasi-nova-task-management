package de.brasi.nova.taskmanagement.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Prueft den Aufbau der Auslieferung im {@link AttachmentController} ohne Spring-Kontext (Intake
 * #152, Fix-Runde nach A8): Der im Service geoeffnete Strom darf nicht offen bleiben, wenn der Bau
 * der Header scheitert; sonst bleibt er bis zum Lauf des Bodys geoeffnet und wird dort geschlossen.
 */
class AttachmentControllerDeliveryTest {

  private static final UUID TASK_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
  private static final UUID ATTACHMENT_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
  private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

  private final AttachmentQueryService queryService = mock(AttachmentQueryService.class);
  private final AttachmentController controller =
      new AttachmentController(
          mock(AttachmentUploadService.class), mock(AttachmentCommandService.class), queryService);

  @Test
  void i152_invalid_content_type_closes_the_opened_stream() {
    final TrackingStream stream = new TrackingStream(PDF_BYTES, false);
    when(queryService.serveContent(TASK_ID, ATTACHMENT_ID, null))
        .thenReturn(delivery("kein-gueltiger-typ", stream));

    assertThatThrownBy(() -> controller.content(TASK_ID, ATTACHMENT_ID, null))
        .isInstanceOf(InvalidMediaTypeException.class);

    assertThat(stream.closed).isTrue();
  }

  @Test
  void i152_failure_while_closing_is_attached_to_the_original_error() {
    final TrackingStream stream = new TrackingStream(PDF_BYTES, true);
    when(queryService.serveThumbnail(TASK_ID, ATTACHMENT_ID, null))
        .thenReturn(delivery("kein-gueltiger-typ", stream));

    assertThatThrownBy(() -> controller.thumbnail(TASK_ID, ATTACHMENT_ID, null))
        .isInstanceOf(InvalidMediaTypeException.class)
        .hasSuppressedException(stream.closeFailure);
    assertThat(stream.closed).isTrue();
  }

  @Test
  void i152_valid_delivery_keeps_stream_open_until_the_body_runs() throws IOException {
    final TrackingStream stream = new TrackingStream(PDF_BYTES, false);
    when(queryService.serveContent(TASK_ID, ATTACHMENT_ID, null))
        .thenReturn(delivery("application/pdf", stream));

    final ResponseEntity<StreamingResponseBody> response =
        controller.content(TASK_ID, ATTACHMENT_ID, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentLength()).isEqualTo(PDF_BYTES.length);
    assertThat(stream.closed).isFalse();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    response.getBody().writeTo(out);
    assertThat(out.toByteArray()).isEqualTo(PDF_BYTES);
    assertThat(stream.closed).isTrue();
  }

  @Test
  void i152_not_modified_has_no_body_and_no_stream() {
    when(queryService.serveContent(TASK_ID, ATTACHMENT_ID, "W/\"x\""))
        .thenReturn(AttachmentDelivery.unchanged("W/\"x\"", Duration.ofDays(1)));

    final ResponseEntity<StreamingResponseBody> response =
        controller.content(TASK_ID, ATTACHMENT_ID, "W/\"x\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getBody()).isNull();
  }

  private static AttachmentDelivery delivery(final String mimeType, final InputStream stream) {
    return new AttachmentDelivery(
        false, "W/\"abc\"", mimeType, PDF_BYTES.length, "Bericht.pdf", stream, Duration.ofDays(1));
  }

  /** Strom, der das Schliessen merkt und optional dabei scheitert. */
  private static final class TrackingStream extends ByteArrayInputStream {

    private final boolean failOnClose;
    private final IOException closeFailure = new IOException("Schliessen gescheitert");
    private boolean closed;

    TrackingStream(final byte[] bytes, final boolean failOnClose) {
      super(bytes);
      this.failOnClose = failOnClose;
    }

    @Override
    public void close() throws IOException {
      closed = true;
      if (failOnClose) {
        throw closeFailure;
      }
    }
  }
}
