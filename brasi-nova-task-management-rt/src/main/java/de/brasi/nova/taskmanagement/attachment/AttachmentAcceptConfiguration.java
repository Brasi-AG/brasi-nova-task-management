package de.brasi.nova.taskmanagement.attachment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sorgt dafuer, dass die mit {@link IgnoresAcceptHeader} markierten Methoden (Inhalt, Download und
 * Vorschaubild der Anhaenge) den {@code Accept}-Header auch bei Fehlern ignorieren (Intake #152,
 * Nachbesserung).
 *
 * <p>Ursache des Problems: Der Erfolg ist unkritisch, denn der {@code StreamingResponseBody} setzt
 * seinen {@code Content-Type} selbst und wird nicht ausgehandelt. Ein Fehler (404, 400, 503) wird
 * dagegen von der KSF-Fehlerbehandlung als {@code ResponseEntity<ApiError>} zurueckgegeben, und
 * Spring handelt dessen Typ gegen {@code Accept} aus. Schliesst {@code Accept} JSON aus (etwa
 * {@code image/png}), wirft das Schreiben eine {@code HttpMediaTypeNotAcceptableException} aus der
 * Fehlerbehandlung selbst; die Ausnahme bleibt unbehandelt und wird zu 500.
 *
 * <p>Loesung: Ein {@link HandlerExceptionResolver} steht vor den uebrigen Resolvern. Nur fuer
 * markierte Methoden und nur, wenn {@code Accept} weder JSON noch HAL-JSON zulaesst, reicht er die
 * Ausnahme mit einer Anfrage ohne {@code Accept} an dieselben Resolver weiter; die KSF-Advices
 * antworten dann wie bei jedem Client ohne {@code Accept} (JSON im Fehlerformat, unveraendert
 * Status und {@code errorKey}). Alle anderen Methoden und alle Anfragen, die JSON annehmen, bleiben
 * unberuehrt; es gibt keine globale Aenderung der Inhaltsaushandlung.
 */
@Configuration
public class AttachmentAcceptConfiguration implements WebMvcConfigurer {

  /** HAL-JSON: neben JSON der einzige Typ, in dem KSF Fehlerkoerper schreibt. */
  private static final MediaType HAL_JSON = MediaType.parseMediaType("application/hal+json");

  @Override
  public void extendHandlerExceptionResolvers(final List<HandlerExceptionResolver> resolvers) {
    resolvers.add(0, new AcceptIgnoringExceptionResolver(List.copyOf(resolvers)));
  }

  /**
   * Ist ein Fehlerkoerper als JSON fuer den Client annehmbar? Ohne {@code Accept} oder mit einem
   * Platzhalter ja; ein nicht lesbarer {@code Accept} gilt als nicht annehmbar (er wuerde die
   * Aushandlung sonst mit 500 scheitern lassen).
   */
  static boolean acceptsJson(final HttpServletRequest request) {
    final List<String> values = Collections.list(request.getHeaders(HttpHeaders.ACCEPT));
    if (values.isEmpty()) {
      return true;
    }
    final List<MediaType> accepted;
    try {
      accepted = MediaType.parseMediaTypes(values);
    } catch (InvalidMediaTypeException e) {
      return false;
    }
    return accepted.stream()
        .anyMatch(
            type ->
                type.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || type.isCompatibleWith(HAL_JSON));
  }

  /** Resolver, der markierte Methoden ohne {@code Accept} an die uebrigen Resolver weitergibt. */
  static final class AcceptIgnoringExceptionResolver implements HandlerExceptionResolver {

    private final List<HandlerExceptionResolver> delegates;

    AcceptIgnoringExceptionResolver(final List<HandlerExceptionResolver> delegates) {
      this.delegates = delegates;
    }

    @Override
    @Nullable public ModelAndView resolveException(
        final HttpServletRequest request,
        final HttpServletResponse response,
        @Nullable final Object handler,
        final Exception ex) {
      if (!(handler instanceof HandlerMethod method)
          || !method.hasMethodAnnotation(IgnoresAcceptHeader.class)
          || acceptsJson(request)) {
        return null;
      }
      final HttpServletRequest withoutAccept = new RequestWithoutAccept(request);
      for (final HandlerExceptionResolver delegate : delegates) {
        final ModelAndView resolved =
            delegate.resolveException(withoutAccept, response, handler, ex);
        if (resolved != null) {
          return resolved;
        }
      }
      return null;
    }
  }

  /**
   * Anfrage, die den {@code Accept}-Header nicht kennt; alles andere gibt sie unveraendert weiter.
   */
  static final class RequestWithoutAccept extends HttpServletRequestWrapper {

    RequestWithoutAccept(final HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(final String name) {
      return isAccept(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(final String name) {
      return isAccept(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      return Collections.enumeration(
          Collections.list(super.getHeaderNames()).stream()
              .filter(name -> !isAccept(name))
              .toList());
    }

    private static boolean isAccept(final String name) {
      return HttpHeaders.ACCEPT.equalsIgnoreCase(name);
    }
  }
}
