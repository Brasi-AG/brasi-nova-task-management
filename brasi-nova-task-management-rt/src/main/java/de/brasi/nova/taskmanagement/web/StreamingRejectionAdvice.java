package de.brasi.nova.taskmanagement.web;

import de.brasi.nova.taskmanagement.attachment.AttachmentController;
import de.brasi.nova.taskmanagement.attachment.AttachmentStreamingProperties;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Meldet eine abgelehnte Auslieferung eines Anhangs als 503 mit {@code Retry-After} im
 * KSF-Fehlerformat (Intake #152).
 *
 * <p>Der {@code StreamingResponseBody} laeuft auf dem begrenzten Pool aus {@code
 * AttachmentStreamingConfiguration}. Ist er voll, wirft der Executor beim Start der asynchronen
 * Verarbeitung ein {@link RejectedExecutionException} (Spring: {@code TaskRejectedException}).
 * {@code WebAsyncManager#startCallableProcessing} faengt es, macht es zum Ergebnis der Anfrage und
 * dispatcht erneut; die Ausnahme erreicht damit die Ausnahmebehandlung des Controllers. Ohne diese
 * Advice wuerde der KSF-Catch-all daraus 500 machen, das das Gateway als Fehler zaehlt. Die Antwort
 * baut {@link GlobalApiExceptionHandler#handleResponseStatus} aus einer {@link
 * ServiceBusyException}: dasselbe Format wie jede andere Fehlerantwort, ohne eigenen {@code
 * errorKey} und ohne KSF-Aenderung. Die Reihenfolge {@link Ordered#HIGHEST_PRECEDENCE} stellt sie
 * vor die KSF-Advice.
 *
 * <p>Zwei Aufraeumarbeiten, weil die Ablehnung nach dem Controller-Aufruf passiert:
 *
 * <ul>
 *   <li>Der Strom, den der Service fuer die Auslieferung schon geoeffnet hat, wird geschlossen
 *       (Request-Attribut {@link AttachmentController#PENDING_STREAM_ATTRIBUTE}); der Body, der ihn
 *       sonst schliesst, laeuft nie.
 *   <li>Spring hat die Header der 200-Antwort (Inhaltsart, Laenge, ETag, Cache-Control,
 *       Content-Disposition) schon auf die Servlet-Antwort geschrieben und raeumt sie bei einem
 *       Fehler nicht ab. Sie wuerden den Fehler verfaelschen (Laenge der Datei, Cache-Dauer, Name
 *       des Downloads) und werden deshalb entfernt, alle anderen Header bleiben.
 * </ul>
 *
 * <p>Die Wartezeit fuer {@code Retry-After} kommt aus {@link
 * AttachmentStreamingProperties#retryAfter()}, nie als Literal.
 */
@RestControllerAdvice(assignableTypes = AttachmentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StreamingRejectionAdvice {

  /**
   * Header, die {@link AttachmentController} fuer die 200-Antwort einer Auslieferung setzt
   * (kleingeschrieben). Ein Test haelt die Menge gegen den Controller fest.
   */
  public static final Set<String> DELIVERY_HEADERS =
      Set.of(
          "content-type",
          "content-length",
          "etag",
          "cache-control",
          "content-disposition",
          "x-content-type-options");

  /** Begruendung der 503; steht in {@code details}, wenn KSF Details ausgibt. */
  public static final String BUSY_REASON =
      "Der Dienst ist ausgelastet, bitte später erneut versuchen.";

  private static final Logger log = LoggerFactory.getLogger(StreamingRejectionAdvice.class);

  private final GlobalApiExceptionHandler apiExceptionHandler;
  private final AttachmentStreamingProperties properties;

  /**
   * Konstruktor-Injektion.
   *
   * @param apiExceptionHandler KSF-Fehlerbehandlung, die die Antwort im {@link ApiError}-Format
   *     baut
   * @param properties Streaming-Konfiguration (Wartezeit fuer {@code Retry-After})
   */
  public StreamingRejectionAdvice(
      final GlobalApiExceptionHandler apiExceptionHandler,
      final AttachmentStreamingProperties properties) {
    this.apiExceptionHandler = apiExceptionHandler;
    this.properties = properties;
  }

  /**
   * Der Pool der Auslieferung hat die Anfrage abgelehnt.
   *
   * @param ex Ablehnung durch den Executor
   * @param request aktueller Request
   * @param response aktuelle Antwort (noch nicht abgeschickt)
   * @return 503 im KSF-Fehlerformat mit {@code Retry-After}
   */
  @ExceptionHandler(RejectedExecutionException.class)
  public ResponseEntity<ApiError> handleRejected(
      final RejectedExecutionException ex,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    log.warn(
        "Auslieferung {} abgelehnt, Pool ausgelastet: {}",
        request.getRequestURI(),
        ex.getMessage());
    closePendingStream(request);
    dropDeliveryHeaders(response);
    return apiExceptionHandler.handleResponseStatus(
        new ServiceBusyException(BUSY_REASON, properties.retryAfter(), ex), request);
  }

  /** Schliesst den fuer die Auslieferung geoeffneten Strom, falls der Body nie gestartet ist. */
  private static void closePendingStream(final HttpServletRequest request) {
    final Object pending = request.getAttribute(AttachmentController.PENDING_STREAM_ATTRIBUTE);
    request.removeAttribute(AttachmentController.PENDING_STREAM_ATTRIBUTE);
    if (pending instanceof InputStream stream) {
      try {
        stream.close();
      } catch (IOException e) {
        log.debug("Strom der abgelehnten Auslieferung nicht sauber geschlossen: {}", e.toString());
      }
    }
  }

  /**
   * Entfernt die Header der 200-Antwort; alle uebrigen Header bleiben. Ist die Antwort schon
   * abgeschickt, laesst sich nichts mehr aendern.
   */
  private static void dropDeliveryHeaders(final HttpServletResponse response) {
    if (response.isCommitted()) {
      return;
    }
    final Map<String, List<String>> kept = new LinkedHashMap<>();
    for (final String name : response.getHeaderNames()) {
      if (!DELIVERY_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        kept.put(name, List.copyOf(response.getHeaders(name)));
      }
    }
    try {
      response.reset();
    } catch (IllegalStateException e) {
      log.debug("Antwort bereits abgeschickt, Header bleiben: {}", e.toString());
      return;
    }
    kept.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
  }
}
