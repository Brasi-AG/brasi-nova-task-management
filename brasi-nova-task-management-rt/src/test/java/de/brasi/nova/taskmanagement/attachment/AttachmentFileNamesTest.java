package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentFileNames.extensionOf;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileNames.sanitize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.keel.framework.api.validation.EntityValidationException;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link AttachmentFileNames} (Intake #152, Task A3; Review Focus 4).
 *
 * <p>Regel: letzter Pfadanteil nach {@code /} oder {@code \}, Steuerzeichen entfernt, getrimmt,
 * hoechstens 255 Zeichen unter Erhalt der Endung; leeres Ergebnis ergibt 400 ({@link
 * EntityValidationException} „Der Dateiname fehlt.“). Endung klein, ohne Punkt, leer ohne Endung.
 */
class AttachmentFileNamesTest {

  @Test
  void i152_file_name_sanitized() {
    assertThat(sanitize("C:\\Users\\x\\Bericht.pdf")).isEqualTo("Bericht.pdf");
    assertThat(sanitize("../../etc/passwd.txt")).isEqualTo("passwd.txt");
    assertThat(sanitize("..\\..\\x.pdf")).isEqualTo("x.pdf");
    assertThat(sanitize("Re\u0000port\r\n.pdf")).isEqualTo("Report.pdf");
    assertThat(sanitize("Übersicht \"final\".pdf")).isEqualTo("Übersicht \"final\".pdf");
    assertThat(sanitize("a".repeat(300) + ".pdf")).hasSize(255).endsWith(".pdf");
    assertThatThrownBy(() -> sanitize("  \t "))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Der Dateiname fehlt.");
    assertThat(extensionOf("archiv.tar.GZ")).isEqualTo("gz");
    assertThat(extensionOf("ohne-endung")).isEmpty();
  }
}
