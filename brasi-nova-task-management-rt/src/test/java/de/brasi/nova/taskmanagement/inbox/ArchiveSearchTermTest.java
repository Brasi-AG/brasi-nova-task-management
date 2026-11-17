package de.brasi.nova.taskmanagement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.brasi.nova.taskmanagement.inbox.model.ArchiveSearchPageSchema;
import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit-Tests fuer {@link ArchiveSearchTerm} (Validierung und LIKE-Muster der Archivsuche). */
class ArchiveSearchTermTest {

  @Test
  @DisplayName("query_null_wirft_400")
  void query_null_wirft_400() {
    assertThatThrownBy(() -> ArchiveSearchTerm.of(null))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("query_leer_oder_nur_leerzeichen_wirft_400")
  void query_leer_oder_nur_leerzeichen_wirft_400() {
    assertThatThrownBy(() -> ArchiveSearchTerm.of(""))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> ArchiveSearchTerm.of("   \t "))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("query_wird_getrimmt")
  void query_wird_getrimmt() {
    assertThat(ArchiveSearchTerm.of("  Bericht  ").value()).isEqualTo("Bericht");
  }

  @Test
  @DisplayName("query_maximallaenge_200_erlaubt_201_wirft_400")
  void query_maximallaenge_200_erlaubt_201_wirft_400() {
    assertThat(ArchiveSearchTerm.of("a".repeat(200)).value()).hasSize(200);
    // Gemessen wird nach dem Trimmen.
    assertThat(ArchiveSearchTerm.of(" " + "a".repeat(200) + " ").value()).hasSize(200);
    assertThatThrownBy(() -> ArchiveSearchTerm.of("a".repeat(201)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("like_muster_klein_geschrieben_mit_wildcards_aussen")
  void like_muster_klein_geschrieben_mit_wildcards_aussen() {
    assertThat(ArchiveSearchTerm.of("Jahres Abschluss").likePattern())
        .isEqualTo("%jahres abschluss%");
  }

  @Test
  @DisplayName("like_muster_escaped_prozent_unterstrich_klammer_und_escape_zeichen")
  void like_muster_escaped_prozent_unterstrich_klammer_und_escape_zeichen() {
    assertThat(ArchiveSearchTerm.of("50%_[a]!").likePattern()).isEqualTo("%50!%!_![a]!!%");
  }

  @Test
  @DisplayName("cursor_scope_normalisiert_begriff_und_unterscheidet_filter")
  void cursor_scope_normalisiert_begriff_und_unterscheidet_filter() {
    final String scope = ArchiveSearchTerm.of("Quartal").cursorScope("all");
    assertThat(scope).hasSize(16).matches("[0-9a-f]{16}");
    assertThat(ArchiveSearchTerm.of("  qUARTAL ").cursorScope("all")).isEqualTo(scope);
    assertThat(ArchiveSearchTerm.of("Quartale").cursorScope("all")).isNotEqualTo(scope);
    assertThat(ArchiveSearchTerm.of("Quartal").cursorScope("done")).isNotEqualTo(scope);
  }

  @Test
  @DisplayName("openapi_beispielcursor_ist_gueltig_kodiert_und_passt_zum_scope")
  void openapi_beispielcursor_ist_gueltig_kodiert_und_passt_zum_scope() {
    final List<String> tokens = KeysetCursor.decode(ArchiveSearchPageSchema.EXAMPLE_CURSOR, 5);
    LocalDateTime.parse(tokens.get(0));
    LocalDateTime.parse(tokens.get(1));
    UUID.fromString(tokens.get(2));
    Long.parseLong(tokens.get(3));
    assertThat(tokens.get(4)).isEqualTo(ArchiveSearchTerm.of("Quartalsbericht").cursorScope("all"));
  }
}
