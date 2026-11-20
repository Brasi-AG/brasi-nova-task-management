package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentStorageKeys.contentKey;
import static de.brasi.nova.taskmanagement.attachment.AttachmentStorageKeys.thumbnailKey;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link AttachmentStorageKeys} (Intake #152, Task A4).
 *
 * <p>Ordnerregel: {@code {TENANT_ID mindestens dreistellig}/{Anhang-ID ohne die letzten 4
 * Stellen}/{Anhang-ID}.{endung}}, Anhang-IDs unter 10000 im Ordner {@code 0}; Vorschaubild daneben
 * als {@code {Anhang-ID}_thumb.jpeg}. Schluessel relativ mit {@code /}.
 */
class AttachmentStorageKeysTest {

  @Test
  void i152_folder_rule() {
    assertThat(contentKey(5L, 10_000_000_095L, "pdf")).isEqualTo("005/1000000/10000000095.pdf");
    assertThat(contentKey(1_000_000_001L, 42L, "png")).isEqualTo("1000000001/0/42.png");
    assertThat(contentKey(7L, 9_999L, "txt")).isEqualTo("007/0/9999.txt");
    assertThat(contentKey(7L, 10_000L, "txt")).isEqualTo("007/1/10000.txt");
    assertThat(thumbnailKey("005/1000000/10000000095.pdf"))
        .isEqualTo("005/1000000/10000000095_thumb.jpeg");
  }
}
