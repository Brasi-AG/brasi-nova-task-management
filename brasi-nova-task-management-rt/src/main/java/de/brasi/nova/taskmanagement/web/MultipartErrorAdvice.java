package de.brasi.nova.taskmanagement.web;

import de.brasi.nova.taskmanagement.attachment.AttachmentFileLimit;
import de.brasi.nova.taskmanagement.attachment.AttachmentProperties;
import io.keel.framework.api.validation.EntityValidationException;
import io.keel.framework.web.controller.GlobalApiExceptionHandler;
import io.keel.framework.web.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Meldet Fehler beim Lesen eines Multipart-Requests als 400 im KSF-Fehlerformat (Intake #152,
 * Review Focus 2).
 *
 * <p>Ohne diese Advice landen {@link MaxUploadSizeExceededException} und {@link MultipartException}
 * im Catch-all der KSF-Fehlerbehandlung (500). Die Advice baut deshalb eine {@link
 * EntityValidationException} mit deutschem Text und laesst die Antwort von {@link
 * GlobalApiExceptionHandler#handleEntityValidation} erzeugen: dasselbe Format wie jede andere 400
 * der Anhaenge ({@code errorKey} {@code NOVA_VALIDATION_FAILED}, Karte {@code ksf/error-format}),
 * ohne eigenen {@code errorKey} und ohne KSF-Aenderung. Die Reihenfolge {@link
 * Ordered#HIGHEST_PRECEDENCE} stellt sie vor die KSF-Advice.
 *
 * <p>Die harte Obergrenze im Text kommt aus {@link AttachmentProperties.Limits#hardMax()}, nie als
 * Literal.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartErrorAdvice {

  private static final Logger log = LoggerFactory.getLogger(MultipartErrorAdvice.class);

  private final GlobalApiExceptionHandler apiExceptionHandler;
  private final AttachmentProperties properties;

  /**
   * Konstruktor-Injektion.
   *
   * @param apiExceptionHandler KSF-Fehlerbehandlung, die die Antwort im {@link ApiError}-Format
   *     baut
   * @param properties Anhang-Konfiguration (harte Obergrenze je Datei)
   */
  public MultipartErrorAdvice(
      final GlobalApiExceptionHandler apiExceptionHandler, final AttachmentProperties properties) {
    this.apiExceptionHandler = apiExceptionHandler;
    this.properties = properties;
  }

  /**
   * Eine Datei oder der Request ist groesser als die Multipart-Grenze des Servers. Die Ausnahme
   * entsteht sowohl bei einer zu grossen Datei ({@code max-file-size}) als auch bei einem zu
   * grossen Request insgesamt ({@code max-request-size}); der Text nennt deshalb beides und die
   * harte Obergrenze je Datei.
   *
   * @param ex Ausnahme von Spring beim Lesen des Requests
   * @param request aktueller Request
   * @return 400 mit der harten Obergrenze je Datei im Text
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> handleMaxUploadSize(
      final MaxUploadSizeExceededException ex, final HttpServletRequest request) {
    final String limit = AttachmentFileLimit.displaySize(properties.limits().hardMax().toBytes());
    log.info(
        "Multipart-Request {} ueber der Groessengrenze abgelehnt: {}",
        request.getRequestURI(),
        ex.getMessage());
    return validationError(
        "Die Anfrage oder eine Datei ist zu groß. Je Datei sind höchstens " + limit + " zulässig.",
        request);
  }

  /**
   * Ein Pflichtteil des Multipart-Requests fehlt (etwa {@code files}).
   *
   * @param ex Ausnahme von Spring mit dem Namen des Teils
   * @param request aktueller Request
   * @return 400 mit dem Namen des fehlenden Teils im Text
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ApiError> handleMissingPart(
      final MissingServletRequestPartException ex, final HttpServletRequest request) {
    return validationError(
        "Der Teil „" + ex.getRequestPartName() + "“ fehlt in der Anfrage.", request);
  }

  /**
   * Der Request ist kein lesbarer Multipart-Request (abgebrochen, falscher Inhalt, zu viele Teile).
   *
   * @param ex Ausnahme von Spring beim Lesen des Requests
   * @param request aktueller Request
   * @return 400 mit neutralem Text
   */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ApiError> handleMultipart(
      final MultipartException ex, final HttpServletRequest request) {
    log.info("Multipart-Request {} nicht lesbar: {}", request.getRequestURI(), ex.getMessage());
    return validationError(
        "Die Anfrage konnte nicht als multipart/form-data gelesen werden.", request);
  }

  private ResponseEntity<ApiError> validationError(
      final String message, final HttpServletRequest request) {
    return apiExceptionHandler.handleEntityValidation(
        new EntityValidationException(message), request);
  }
}
