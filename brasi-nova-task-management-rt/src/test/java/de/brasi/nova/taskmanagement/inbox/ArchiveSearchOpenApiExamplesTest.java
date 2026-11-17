package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Haelt die OpenAPI-Beispiele der Archivsuche stimmig mit dem tatsaechlichen Verhalten: das
 * 200-Beispiel mit seinem Cursor (Zeitbasis), das 410-Beispiel mit der {@link
 * SnapshotExpiredException}. Das echte Fehlerformat sichert {@code InboxArchiveSearchIT} gegen die
 * Live-Antwort.
 */
class ArchiveSearchOpenApiExamplesTest {

  /** Persistenz-Zone der Vorgabe (Lesson audit-time-zone: Audit-Zeiten sind lokale Wanduhr). */
  private static final AuditTimeConverter CONVERTER = new AuditTimeConverter("Europe/Berlin");

  @Test
  @DisplayName("beispiel_200_zeiten_und_id_passen_zum_cursor_in_persistenz_zone")
  void beispiel_200_zeiten_und_id_passen_zum_cursor_in_persistenz_zone() {
    final JsonNode page = ArchiveSearchOpenApiExamples.forResponse("200");
    final JsonNode item = page.get("_embedded").get("tasks").get(0);
    final List<String> tokens = KeysetCursor.decode(page.get("nextCursor").asText(), 5);

    // Cursor traegt lokale Wanduhr (asOf, lastActivityAt); die Wire-Form liefert UTC-Instants.
    assertThat(Instant.parse(item.get("lastActivityAt").asText()))
        .as("lastActivityAt des Treffers muss dem Zeit-Token des Cursors entsprechen")
        .isEqualTo(CONVERTER.toInstant(LocalDateTime.parse(tokens.get(1))));
    assertThat(Instant.parse(page.get("asOf").asText()))
        .as("asOf der Seite muss dem asOf-Token des Cursors entsprechen")
        .isEqualTo(CONVERTER.toInstant(LocalDateTime.parse(tokens.get(0))));
    // Der Cursor zeigt auf den letzten Treffer der Seite.
    assertThat(item.get("id").asText()).isEqualTo(tokens.get(2));
  }

  @Test
  @DisplayName("beispiel_410_entspricht_snapshot_expired_exception")
  void beispiel_410_entspricht_snapshot_expired_exception() {
    final SnapshotExpiredException expired = new SnapshotExpiredException();
    final JsonNode example = ArchiveSearchOpenApiExamples.forResponse("410");

    assertThat(example.get("status").asInt()).isEqualTo(expired.getStatusCode().value());
    assertThat(example.get("error").asText()).isEqualTo(HttpStatus.GONE.getReasonPhrase());
    // Der KSF-Handler legt den Grundtext der Exception in details ab, message ist neutral.
    assertThat(example.get("details").get(0).asText()).isEqualTo(expired.getReason());
    assertThat(example.get("path").asText()).isEqualTo("/api/v1/tasks/archive/search");
  }
}
