package de.brasi.nova.taskmanagement.support;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

/**
 * Testfilter fuer MockMvc: laesst den Request-Thread die Filterkette erst verlassen, wenn ein
 * gestarteter {@code StreamingResponseBody} fertig ist (Wackler i152).
 *
 * <p>Spring Securitys {@code HeaderWriterFilter} schreibt seine Header verzoegert: beim Commit der
 * Antwort oder im {@code finally} nach der Kette. Bei einer Auslieferung committet der Async-Thread
 * des Anhang-Pools die Antwort, waehrend der Request-Thread aus der Kette zurueckkehrt; die Sperre
 * dagegen ist ein einfaches {@code boolean} ohne Synchronisation. Ohne diesen Filter schreiben
 * beide Threads zugleich in dieselbe {@code MockHttpServletResponse} ({@code
 * ConcurrentModificationException} in {@code setHeader}).
 *
 * <p>Der Filter wird nach {@code springSecurity()} per {@code addFilters} registriert, sitzt also
 * innerhalb der Security-Kette, und wartet nach der Verarbeitung auf das Async-Ergebnis. Dessen
 * Ablage ({@code volatile}) folgt auf den Commit; danach sieht der {@code HeaderWriterFilter} den
 * Commit und schreibt nichts mehr. Gewartet wird hoechstens die Async-Frist des Requests ({@code
 * spring.mvc.async.request-timeout}), mit der auch {@code asyncDispatch} auf das Ergebnis wartet;
 * danach, oder ohne begrenzte Frist, scheitert der Abruf hart.
 *
 * <p>Genutzt von {@code AttachmentControllerIT} und {@code CommentAttachmentIT}.
 */
public final class AwaitStreamingBodyFilter implements Filter {

  @Override
  public void doFilter(
      final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    chain.doFilter(request, response);
    if (!request.isAsyncStarted()) {
      return;
    }
    final Duration timeout = Duration.ofMillis(request.getAsyncContext().getTimeout());
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalStateException(
          "Async-Frist von " + describe(request) + " ist nicht begrenzt (" + timeout + ").");
    }
    final WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (!asyncManager.hasConcurrentResult()) {
      if (System.nanoTime() - deadline > 0) {
        throw new IllegalStateException(
            "Async-Ergebnis der Auslieferung von "
                + describe(request)
                + " nicht innerhalb von "
                + timeout
                + " abgelegt.");
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
  }

  /** Methode und URI der Anfrage fuer die Fehlermeldung, damit der Abruf erkennbar ist. */
  private static String describe(final ServletRequest request) {
    if (request instanceof HttpServletRequest httpRequest) {
      return httpRequest.getMethod() + " " + httpRequest.getRequestURI();
    }
    return request.toString();
  }
}
