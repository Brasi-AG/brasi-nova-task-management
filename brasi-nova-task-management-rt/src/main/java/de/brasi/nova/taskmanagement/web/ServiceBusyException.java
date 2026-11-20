package de.brasi.nova.taskmanagement.web;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signalisiert, dass der Dienst eine Anfrage wegen Auslastung nicht annehmen kann (HTTP 503) und
 * der Client es nach einer Wartezeit erneut versuchen soll. Sie traegt die Wartezeit als Header
 * {@code Retry-After} (ganze Sekunden, aufgerundet).
 *
 * <p><b>Bewusst {@link ResponseStatusException}-Subklasse</b>, wie {@code
 * SnapshotExpiredException}: Der KSF-{@code GlobalApiExceptionHandler} liefert sie statusgetreu im
 * {@code ApiError}-Format aus und uebernimmt den Header {@code Retry-After} aus {@link
 * #getHeaders()} (seine Allowlist nennt ausdruecklich 429/503). KSF kennt fuer 503 keinen eigenen
 * {@code errorKey}; der Handler vergibt fuer Status 5xx {@code NOVA_INTERNAL_ERROR}.
 */
public class ServiceBusyException extends ResponseStatusException {

  private static final long serialVersionUID = 1L;

  private final Duration retryAfter;

  /**
   * Erzeugt die Exception mit HTTP 503.
   *
   * @param reason Begruendung (erscheint in {@code details}, wenn KSF Details ausgibt)
   * @param retryAfter Wartezeit bis zum naechsten Versuch; positiv
   * @param cause Ausloeser, etwa die Ablehnung durch den Executor
   */
  public ServiceBusyException(
      final String reason, final Duration retryAfter, final Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    this.retryAfter = retryAfter;
  }

  /**
   * Der Header {@code Retry-After} in ganzen Sekunden; ein Bruchteil einer Sekunde wird
   * aufgerundet.
   *
   * @return Header mit der Wartezeit
   */
  @Override
  public HttpHeaders getHeaders() {
    final HttpHeaders headers = new HttpHeaders();
    final long seconds = retryAfter.toSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
    headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    return headers;
  }
}
