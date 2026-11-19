package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentEtags.contentEtag;
import static de.brasi.nova.taskmanagement.attachment.AttachmentEtags.matches;
import static de.brasi.nova.taskmanagement.attachment.AttachmentEtags.thumbnailEtag;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link AttachmentEtags} (Intake #152, Task A8): schwache ETags aus der
 * gespeicherten Pruefsumme ({@code SHA256}), Vorschau mit Breite; {@code If-None-Match} als
 * kommagetrennte Liste, mit oder ohne {@code W/}, und {@code *}.
 */
class AttachmentEtagsTest {

  @Test
  void i152_etags() {
    assertThat(contentEtag("abc")).isEqualTo("W/\"abc\"");
    assertThat(thumbnailEtag("abc", 600)).isEqualTo("W/\"abc-t600\"");
    assertThat(matches("\"abc\"", "W/\"abc\"")).isTrue();
    assertThat(matches("W/\"x\", W/\"abc\"", "W/\"abc\"")).isTrue();
    assertThat(matches("*", "W/\"abc\"")).isTrue();
    assertThat(matches(null, "W/\"abc\"")).isFalse();
    assertThat(matches("W/\"abd\"", "W/\"abc\"")).isFalse();
  }

  @Test
  void i152_etag_match_edge_cases() {
    // Leerer Header und Leerraum um die Eintraege
    assertThat(matches("", "W/\"abc\"")).isFalse();
    assertThat(matches("  W/\"abc\"  ", "W/\"abc\"")).isTrue();
    assertThat(matches("\"x\",\"abc\"", "W/\"abc\"")).isTrue();
    // Vorschau-ETag passt nicht zum Inhalts-ETag derselben Datei
    assertThat(matches(contentEtag("abc"), thumbnailEtag("abc", 600))).isFalse();
    assertThat(matches(thumbnailEtag("abc", 600), thumbnailEtag("abc", 600))).isTrue();
    // Andere Vorschaubreite = anderer ETag
    assertThat(matches(thumbnailEtag("abc", 300), thumbnailEtag("abc", 600))).isFalse();
  }
}
