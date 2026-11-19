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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

  private static InputStreamSource failingSource() {
    return () -> {
      throw new IOException("Lesefehler (Test)");
    };
  }
}
