package de.brasi.nova.taskmanagement.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.comment.model.CommentCreateInput;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Liest den Teil {@code comment} von {@code POST /api/v1/tasks/{id}/comments/with-attachments} als
 * JSON, gleich mit welchem Content-Type er ankommt (Intake #152, Nachbesserung P415).
 *
 * <p>Warum: Ein Browser, der {@code FormData.append('comment', JSON.stringify(...))} aufruft,
 * sendet den Teil ohne Content-Type oder als {@code text/plain}. Den Teil als {@link
 * CommentCreateInput} zu binden, scheitert dann mit 415, weil der JSON-Konverter nur {@code
 * application/json} liest. Der Controller nimmt den Teil deshalb als Rohinhalt an; dieser Leser
 * macht daraus mit dem {@link ObjectMapper} der Anwendung dieselbe {@link CommentCreateInput} wie
 * der JSON-Weg ({@code POST /api/v1/tasks/{id}/comments}): gleiche Module, gleiche Regeln, und die
 * Pflichtpruefung des Textes im kompakten Konstruktor von {@link CommentCreateInput}.
 *
 * <p>Der Inhalt wird als Bytes gelesen, nicht als {@code String}: Jackson erkennt die Kodierung
 * selbst (UTF-8 nach RFC 8259), unabhaengig vom Standardzeichensatz des String-Konverters.
 *
 * <p>Fehlerformat unveraendert: Ist der Inhalt kein gueltiges JSON, fehlt der Text oder verletzt
 * die Eingabe eine Regel von {@link CommentCreateInput}, wirft der Leser eine {@link
 * HttpMessageNotReadableException} mit der urspruenglichen Ursache. Das ist dieselbe Ausnahme, die
 * Spring beim Lesen eines JSON-Teils wirft; die KSF-Fehlerbehandlung antwortet daher wie bisher mit
 * 400 und dem {@code errorKey} {@code NOVA_MESSAGE_NOT_READABLE}, die Regelverletzung steht in
 * {@code details}. Der Aufruf liegt im Handler vor jedem Service, also vor der Pruefung der
 * Mitgliedschaft.
 */
@Component
public class CommentPartReader {

  private final ObjectMapper objectMapper;

  /**
   * Konstruktor-Injektion.
   *
   * @param objectMapper der ObjectMapper der Anwendung (derselbe wie fuer die JSON-Antworten und
   *     den JSON-Weg des Kommentars)
   */
  public CommentPartReader(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Liest den Teil {@code comment} aus der Multipart-Anfrage als {@link CommentCreateInput}.
   *
   * <p>Warum {@link Part} und nicht {@code byte[]} oder {@code String} (Intake #152 Teil B): Ein
   * als {@code byte[]} oder {@code String} gebundener Teil laeuft ueber einen MessageConverter und
   * danach durch jede {@code RequestBodyAdvice}. Die KSF-Advice {@code
   * NovaTenantBindingRequestBodyAdvice} (ksf-web) behandelt jedes Array als {@code Object[]} und
   * bricht bei Primitiv-Arrays wie {@code byte[]} mit {@code ClassCastException} ab (500 {@code
   * NOVA_INTERNAL_ERROR}). Ein {@link Part} loest Spring ohne MessageConverter und ohne Advice auf;
   * die Bytes liest dieser Leser selbst aus {@link Part#getInputStream()}.
   *
   * <p>Verhalten wie bisher: Ein leerer Teil gilt als fehlend ({@link
   * MissingServletRequestPartException}, 400 {@code NOVA_VALIDATION_FAILED}), wie es Spring beim
   * Lesen ueber einen MessageConverter hielt. Ein Lesefehler wird wie dort zu einer {@link
   * HttpMessageNotReadableException} (400 {@code NOVA_MESSAGE_NOT_READABLE}); der Inhalt geht an
   * {@link #read(byte[])}.
   *
   * @param part der Teil {@code comment}; von Spring nie {@code null} (ein fehlender Teil endet
   *     vorher mit {@link MissingServletRequestPartException})
   * @return die geprueften Eingabedaten (Text nicht leer, Erwaehnungen normalisiert)
   * @throws MissingServletRequestPartException wenn der Teil leer ist (HTTP 400)
   * @throws HttpMessageNotReadableException wenn der Teil nicht gelesen werden kann oder der Inhalt
   *     nicht zu {@link CommentCreateInput} passt (HTTP 400)
   */
  public CommentCreateInput read(final Part part) throws MissingServletRequestPartException {
    final byte[] content;
    try (InputStream body = part.getInputStream()) {
      content = body.readAllBytes();
    } catch (IOException e) {
      throw new HttpMessageNotReadableException(
          "Der Teil comment konnte nicht gelesen werden: " + e.getMessage(),
          e,
          new PartMessage(new byte[0]));
    }
    if (content.length == 0) {
      throw new MissingServletRequestPartException(part.getName());
    }
    return read(content);
  }

  /**
   * Liest den Rohinhalt des Teils {@code comment} als {@link CommentCreateInput}.
   *
   * @param part Rohinhalt des Teils (JSON, UTF-8); nie {@code null} und nie leer (ein leerer Teil
   *     gilt als fehlend, siehe {@link #read(Part)})
   * @return die geprueften Eingabedaten (Text nicht leer, Erwaehnungen normalisiert)
   * @throws HttpMessageNotReadableException wenn der Inhalt kein JSON-Objekt ist, nicht zu {@link
   *     CommentCreateInput} passt oder eine Regel verletzt (HTTP 400); die urspruengliche Ursache
   *     bleibt als Ursache erhalten
   */
  public CommentCreateInput read(final byte[] part) {
    final CommentCreateInput input;
    try {
      input = objectMapper.readValue(part, CommentCreateInput.class);
    } catch (IOException e) {
      throw new HttpMessageNotReadableException(
          "Der Teil comment ist kein gültiges JSON: " + e.getMessage(), e, new PartMessage(part));
    }
    if (input == null) {
      throw new HttpMessageNotReadableException(
          "Der Teil comment enthält kein JSON-Objekt.", new PartMessage(part));
    }
    return input;
  }

  /**
   * Der Rohinhalt des Teils als Eingabenachricht, wie sie {@link HttpMessageNotReadableException}
   * verlangt.
   */
  private record PartMessage(byte[] content) implements HttpInputMessage {

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public HttpHeaders getHeaders() {
      return new HttpHeaders();
    }
  }
}
