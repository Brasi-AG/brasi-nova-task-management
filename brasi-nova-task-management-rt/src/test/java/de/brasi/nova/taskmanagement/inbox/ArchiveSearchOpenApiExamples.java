package de.brasi.nova.taskmanagement.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Liest die OpenAPI-Beispielantworten von {@code GET /api/v1/tasks/archive/search} aus den
 * Annotationen des {@link InboxSnapshotController}, damit Tests sie gegen das echte Verhalten
 * pruefen koennen.
 */
final class ArchiveSearchOpenApiExamples {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ArchiveSearchOpenApiExamples() {}

  /**
   * Liefert das erste Beispiel der dokumentierten Antwort zum Statuscode.
   *
   * @param responseCode HTTP-Statuscode als Text, etwa {@code "410"}
   * @return Beispiel als JSON-Baum
   * @throws IllegalStateException wenn der Statuscode nicht dokumentiert ist oder das Beispiel kein
   *     gueltiges JSON ist
   */
  static JsonNode forResponse(final String responseCode) {
    final ApiResponses responses;
    try {
      responses =
          InboxSnapshotController.class
              .getMethod("searchArchive", String.class, String.class, String.class, int.class)
              .getAnnotation(ApiResponses.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Methode searchArchive nicht gefunden", e);
    }
    for (final ApiResponse response : responses.value()) {
      if (response.responseCode().equals(responseCode)) {
        try {
          return MAPPER.readTree(response.content()[0].examples()[0].value());
        } catch (JsonProcessingException e) {
          throw new IllegalStateException("Beispiel zu " + responseCode + " ist kein JSON", e);
        }
      }
    }
    throw new IllegalStateException("Antwort " + responseCode + " ist nicht dokumentiert");
  }
}
