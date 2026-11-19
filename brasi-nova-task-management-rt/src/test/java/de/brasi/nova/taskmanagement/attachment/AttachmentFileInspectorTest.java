package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.DOC;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.DOCX;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.JPEG;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PDF;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PNG;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PPT;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PPTX;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.TXT;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.XLS;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.XLSX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;

/**
 * Unit-Tests fuer {@link AttachmentFileInspector} (Intake #152, Task A3): Inhalt (Magic Bytes) muss
 * zur Endung passen.
 *
 * <p>Regeln: PDF {@code %PDF-}; PNG {@code 89 50 4E 47 0D 0A 1A 0A}; JPEG {@code FF D8 FF};
 * doc/xls/ppt OLE2 {@code D0 CF 11 E0 A1 B1 1A E1}; docx/xlsx/pptx ZIP {@code 50 4B 03 04} plus
 * Eintraege {@code [Content_Types].xml} und {@code word/} bzw. {@code xl/} bzw. {@code ppt/}; TXT:
 * kein NUL-Byte in den ersten 8 KiB, keine der Signaturen, kein {@code MZ}. IOException ergibt
 * {@code false}. Testdaten werden im Test erzeugt (ZIP per {@link ZipOutputStream}, PNG per {@link
 * ImageIO}).
 */
class AttachmentFileInspectorTest {

  private static final InputStreamSource PNG_HEADER_PLUS_IHDR = new ByteArrayResource(png(4, 3));

  private final AttachmentFileInspector inspector = new AttachmentFileInspector();

  @Test
  void i152_magic_bytes_accept() {
    assertThat(inspector.contentMatches(PDF, bytes("%PDF-1.7\n%âãÏÓ\n1 0 obj"))).isTrue();
    assertThat(inspector.contentMatches(PNG, PNG_HEADER_PLUS_IHDR)).isTrue();
    assertThat(inspector.contentMatches(JPEG, hex("FFD8FFE0"))).isTrue();
    assertThat(inspector.contentMatches(DOCX, zip("[Content_Types].xml", "word/document.xml")))
        .isTrue();
    assertThat(inspector.contentMatches(XLSX, zip("[Content_Types].xml", "xl/workbook.xml")))
        .isTrue();
    assertThat(inspector.contentMatches(PPTX, zip("[Content_Types].xml", "ppt/presentation.xml")))
        .isTrue();
    assertThat(inspector.contentMatches(DOC, hex("D0CF11E0A1B11AE1"))).isTrue();
    assertThat(inspector.contentMatches(XLS, hex("D0CF11E0A1B11AE1"))).isTrue();
    assertThat(inspector.contentMatches(PPT, hex("D0CF11E0A1B11AE1"))).isTrue();
    assertThat(inspector.contentMatches(TXT, utf8("Grüße\r\nZeile 2"))).isTrue();
  }

  @Test
  void i152_magic_bytes_reject() {
    assertThat(inspector.contentMatches(PDF, PNG_HEADER_PLUS_IHDR)).isFalse();
    assertThat(inspector.contentMatches(DOCX, zip("[Content_Types].xml", "xl/workbook.xml")))
        .isFalse();
    assertThat(inspector.contentMatches(DOCX, zip("readme.txt"))).isFalse();
    assertThat(inspector.contentMatches(TXT, bytes("ab\u0000cd"))).isFalse();
    assertThat(inspector.contentMatches(TXT, bytes("%PDF-1.4"))).isFalse();
    assertThat(inspector.contentMatches(TXT, bytes("MZ\u0090\u0000"))).isFalse();
    assertThat(inspector.contentMatches(PNG, new ByteArrayResource(new byte[0]))).isFalse();
    // IOException beim Lesen ergibt false (Interfaces-Block)
    assertThat(inspector.contentMatches(PDF, failingSource())).isFalse();
  }

  /**
   * Fix-Runde 1: Die Eintragsnamen kommen aus dem Central Directory; ein sehr stark komprimierter
   * Eintrag (ZIP-Bombe, rund 50 MB Nullen) wird nicht entpackt. Die Datei wird vor der Messung
   * erzeugt, geprueft wird nur {@code contentMatches}.
   */
  @Test
  void i152_zip_bomb_entry_is_not_inflated() {
    final InputStreamSource bomb = zipWithZeroBombBetween();

    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> assertThat(inspector.contentMatches(DOCX, bomb)).isTrue());
  }

  /**
   * Fix-Runde 1 (deterministischer Beleg): Die Daten eines Eintrags werden nie gelesen. Ein Eintrag
   * mit absichtlich ungueltigen Deflate-Daten stoert die Pruefung nicht (beim Entpacken ergaebe das
   * einen Fehler und damit {@code false}).
   */
  @Test
  void i152_entry_data_is_never_read() {
    final InputStreamSource corrupted =
        zipWithCorruptedMiddleEntry("[Content_Types].xml", "middle.bin", "word/document.xml");

    assertThat(inspector.contentMatches(DOCX, corrupted)).isTrue();
  }

  /** Fix-Runde 1: {@code [Content_Types].xml} darf auch am Ende stehen (z. B. openpyxl). */
  @Test
  void i152_content_types_at_end_accepted() {
    assertThat(inspector.contentMatches(DOCX, zip("word/document.xml", "[Content_Types].xml")))
        .isTrue();
    assertThat(inspector.contentMatches(XLSX, zip("xl/workbook.xml", "[Content_Types].xml")))
        .isTrue();
    assertThat(inspector.contentMatches(PPTX, zip("ppt/slides/slide1.xml", "[Content_Types].xml")))
        .isTrue();
  }

  /** Bytes eines Textes in ISO-8859-1 (jedes Zeichen bis U+00FF wird genau ein Byte). */
  private static InputStreamSource bytes(final String text) {
    return new ByteArrayResource(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static InputStreamSource utf8(final String text) {
    return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
  }

  private static InputStreamSource hex(final String hex) {
    return new ByteArrayResource(HexFormat.of().parseHex(hex));
  }

  /** ZIP mit den genannten Eintragsnamen (Inhalte jeweils ein kurzer Platzhalter). */
  private static InputStreamSource zip(final String... entryNames) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (final String name : entryNames) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new ByteArrayResource(out.toByteArray());
  }

  /** Echtes kleines PNG (Signatur, IHDR, IDAT, IEND) per ImageIO. */
  private static byte[] png(final int width, final int height) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * ZIP mit {@code [Content_Types].xml}, einem DEFLATED-Eintrag aus rund 50 MB Nullen (komprimiert
   * wenige Zehntausend Bytes) und {@code word/document.xml} am Ende.
   */
  private static InputStreamSource zipWithZeroBombBetween() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] zeros = new byte[1024 * 1024];
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("bomb.bin"));
      for (int megabyte = 0; megabyte < 50; megabyte++) {
        zip.write(zeros);
      }
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new ByteArrayResource(out.toByteArray());
  }

  /**
   * ZIP mit den genannten Eintraegen (alle DEFLATED); beim Eintrag {@code corruptedName} werden die
   * ersten Bytes der komprimierten Daten durch einen ungueltigen Deflate-Blockkopf ersetzt (BFINAL
   * und BTYPE 3 = reserviert). Das Central Directory bleibt unberuehrt.
   */
  private static InputStreamSource zipWithCorruptedMiddleEntry(
      final String first, final String corruptedName, final String last) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] payload =
        "<x>Beispieltext fuer die Kompression</x>".repeat(20).getBytes(StandardCharsets.UTF_8);
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (final String name : new String[] {first, corruptedName, last}) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(payload);
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    final byte[] bytes = out.toByteArray();
    final byte[] name = corruptedName.getBytes(StandardCharsets.UTF_8);
    // Erster Treffer des Namens liegt im lokalen Dateikopf: Name ab Offset 30, danach Extra-Feld
    final int nameOffset = indexOf(bytes, name);
    final int extraLength = (bytes[nameOffset - 2] & 0xFF) | ((bytes[nameOffset - 1] & 0xFF) << 8);
    final int dataOffset = nameOffset + name.length + extraLength;
    for (int i = 0; i < 4; i++) {
      bytes[dataOffset + i] = (byte) 0xFF;
    }
    return new ByteArrayResource(bytes);
  }

  private static int indexOf(final byte[] haystack, final byte[] needle) {
    for (int start = 0; start <= haystack.length - needle.length; start++) {
      int length = 0;
      while (length < needle.length && haystack[start + length] == needle[length]) {
        length++;
      }
      if (length == needle.length) {
        return start;
      }
    }
    throw new IllegalStateException("Name nicht im ZIP gefunden");
  }

  private static InputStreamSource failingSource() {
    return () -> {
      throw new IOException("Lesefehler (Test)");
    };
  }
}
