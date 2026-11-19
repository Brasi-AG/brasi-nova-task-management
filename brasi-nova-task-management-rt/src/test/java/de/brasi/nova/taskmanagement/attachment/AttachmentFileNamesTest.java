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

  /**
   * Runde 2: Bidi- und unsichtbare Formatzeichen werden entfernt (Namens-Spoofing: ein RTL-Override
   * macht aus {@code rechnung<RLO>fdp.exe} sichtbar {@code rechnungexe.pdf}). Die Endung wird
   * danach bestimmt und bleibt damit gegen die Erlaubtliste pruefbar.
   */
  @Test
  void i152_bidi_and_invisible_characters_removed() {
    // RTL-Override (U+202E): Name wird bereinigt, Endung bleibt "exe" (nicht erlaubt)
    final String spoofed = sanitize("rechnung\u202Efdp.exe");
    assertThat(spoofed).isEqualTo("rechnungfdp.exe");
    assertThat(extensionOf(spoofed)).isEqualTo("exe");
    assertThat(AttachmentFileType.fromExtension(extensionOf(spoofed))).isEmpty();

    // Zero-Width-Zeichen und Byte-Order-Mark (U+200B, U+200C, U+200D, U+FEFF)
    assertThat(sanitize("\uFEFFrech\u200Bnung\u200C\u200D.pdf")).isEqualTo("rechnung.pdf");
    // Zeichen mitten in der Endung: erst entfernen, dann Endung bestimmen
    assertThat(extensionOf(sanitize("bericht.p\u200Bdf"))).isEqualTo("pdf");

    // Alle uebrigen Bereiche: U+200E/F, U+202A-E, U+2066-9, U+061C
    assertThat(
            sanitize(
                "a\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u061Cb.txt"))
        .isEqualTo("ab.txt");

    // Pfadanteil und Steuerzeichen bleiben wie bisher wirksam
    assertThat(sanitize("..\\x\u202E\\Re\u0000port\u200B.pdf")).isEqualTo("Report.pdf");

    // Normale Zeichen (Umlaute, Anfuehrungszeichen, Leerzeichen innen) bleiben erhalten
    assertThat(sanitize("Übersicht \u202D\"final\"\u200B.pdf"))
        .isEqualTo("Übersicht \"final\".pdf");

    // Name nur aus unsichtbaren Zeichen: fehlt
    assertThatThrownBy(() -> sanitize("\u200B\uFEFF\u202E\u2066"))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Der Dateiname fehlt.");
  }
}
