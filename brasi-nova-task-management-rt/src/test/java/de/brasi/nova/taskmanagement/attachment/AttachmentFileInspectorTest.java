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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
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
 *
 * <p>Intake #184: MP4 gilt, wenn die Bytes 4 bis 7 {@code ftyp} sind und die Bytes 8 bis 11 (Major
 * Brand) eine der Marken {@code isom}, {@code iso2} bis {@code iso6}, {@code mp41}, {@code mp42},
 * {@code avc1}, {@code "M4V "} oder {@code MSNV} tragen; die Box-Groesse (Bytes 0 bis 3) wird nicht
 * geprueft. CSV wird wie TXT geprueft. Die Typen MP4 und CSV werden ueber {@link
 * AttachmentFileType#fromExtension(String)} gesucht (nur in den Testmethoden, nie in statischen
 * Feldern), damit die Klasse auch ohne die Konstanten kompiliert und die i152-Tests unberuehrt
 * bleiben.
 */
class AttachmentFileInspectorTest {

  /** Laenge des gelesenen Kopfes fuer die Textpruefung (8 KiB). */
  private static final int HEAD_BYTES = 8 * 1024;

  /** Genannte Major Brands, die als MP4 gelten (Intake #184). */
  private static final List<String> MP4_BRANDS =
      List.of(
          "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV");

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

  /**
   * Runde 2: Die Obergrenze der ZIP-Eintraege liegt bei 10 000. Mit genau 10 000 Eintraegen
   * (Eintraege der Pflichtnamen eingerechnet) passt die Datei, mit 10 001 nicht.
   */
  @Test
  void i152_open_xml_entry_limit_is_ten_thousand() {
    assertThat(inspector.contentMatches(DOCX, zipWithEntryCount(10_000))).isTrue();
    assertThat(inspector.contentMatches(DOCX, zipWithEntryCount(10_001))).isFalse();
  }

  /**
   * Intake #184: Jede genannte Major Brand ergibt mit einer minimalen ftyp-Box ({@code 00 00 00 18
   * ftyp <Marke> 00 00 02 00 isom mp41}) {@code true}. Die Box-Groesse (Bytes 0 bis 3) zaehlt
   * nicht, und 12 Bytes (bis einschliesslich der Major Brand) genuegen.
   */
  @Test
  void i184_mp4_ftyp_accepted() {
    final AttachmentFileType mp4 = mp4Type();

    for (final String brand : MP4_BRANDS) {
      assertThat(inspector.contentMatches(mp4, ftypBox("00000018", brand)))
          .as("Major Brand \"%s\"", brand)
          .isTrue();
    }
    for (final String size : List.of("00000000", "00000001", "00000020", "FFFFFFFF")) {
      assertThat(inspector.contentMatches(mp4, ftypBox(size, "isom")))
          .as("Box-Groesse %s", size)
          .isTrue();
    }
    final InputStreamSource twelveBytes =
        new ByteArrayResource(Arrays.copyOf(ftypBytes("00000018", "mp42"), 12));
    assertThat(inspector.contentMatches(mp4, twelveBytes)).isTrue();
  }

  /**
   * Intake #184: Kein MP4 sind fremder Inhalt, andere Major Brands (QuickTime {@code "qt "}, 3GPP
   * {@code 3gp4}, nicht genannte Marken; die kompatiblen Marken {@code isom} und {@code mp41} der
   * Box zaehlen nicht), Dateien unter 12 Bytes und eine ftyp-Box an Byte 0 statt an Byte 4.
   */
  @Test
  void i184_mp4_rejected() {
    final AttachmentFileType mp4 = mp4Type();

    assertThat(inspector.contentMatches(mp4, bytes("%PDF-1.7\n%âãÏÓ\n1 0 obj"))).isFalse();
    assertThat(inspector.contentMatches(mp4, utf8("Das ist kein Video, sondern Text."))).isFalse();
    assertThat(inspector.contentMatches(mp4, PNG_HEADER_PLUS_IHDR)).isFalse();

    for (final String brand : List.of("qt  ", "3gp4", "iso7", "mp43")) {
      assertThat(inspector.contentMatches(mp4, ftypBox("00000018", brand)))
          .as("Major Brand \"%s\"", brand)
          .isFalse();
    }

    final byte[] box = ftypBytes("00000018", "isom");
    for (final int length : new int[] {0, 4, 8, 11}) {
      assertThat(inspector.contentMatches(mp4, new ByteArrayResource(Arrays.copyOf(box, length))))
          .as("Laenge %d", length)
          .isFalse();
    }

    final InputStreamSource shifted = bytes("ftypisommp41\u0000\u0000\u0002\u0000isom");
    assertThat(inspector.contentMatches(mp4, shifted)).isFalse();

    // IOException beim Lesen ergibt false
    assertThat(inspector.contentMatches(mp4, failingSource())).isFalse();
  }

  /**
   * Intake #184: CSV wird wie TXT geprueft: kein NUL-Byte in den ersten 8 KiB und kein Beginn mit
   * einer Signatur (PDF, PNG, JPEG, OLE2, ZIP, {@code MZ}). Die Pruefungen ohne NUL-Byte
   * (Signaturen ohne NUL) zeigen, dass die Signaturregel gilt und nicht nur die NUL-Regel.
   */
  @Test
  void i184_csv_like_txt() {
    final AttachmentFileType csv = csvType();

    // Akzeptiert: UTF-8 mit Umlauten, Semikolon und Komma, auch mit BOM (Excel-Export)
    final InputStreamSource semicolons =
        utf8("Name;Straße;Ort\r\nMüller;Hauptstraße 1;Köln\r\nÖzdemir;Weg 2;Ülm\r\n");
    assertThat(inspector.contentMatches(csv, semicolons)).isTrue();
    assertThat(inspector.contentMatches(csv, utf8("Name,Betrag\n\"Müller, Hans\",12.50\n")))
        .isTrue();
    final InputStreamSource withBom = utf8((char) 0xFEFF + "Name;Wert\r\nÄ;1\r\n");
    assertThat(inspector.contentMatches(csv, withBom)).isTrue();

    // Das NUL-Byte zaehlt nur innerhalb der ersten 8 KiB (Indizes 0 bis 8191)
    assertThat(inspector.contentMatches(csv, textWithNulAt(HEAD_BYTES - 1))).isFalse();
    assertThat(inspector.contentMatches(csv, textWithNulAt(HEAD_BYTES))).isTrue();

    // Abgelehnt
    assertThat(inspector.contentMatches(csv, bytes("a;b\u0000c"))).isFalse();
    assertThat(inspector.contentMatches(csv, bytes("%PDF-1.4"))).isFalse();
    assertThat(inspector.contentMatches(csv, bytes("MZ,1,2"))).isFalse();
    assertThat(inspector.contentMatches(csv, bytes("PK\u0003\u0004;1;2"))).isFalse();
    assertThat(inspector.contentMatches(csv, hex("89504E470D0A1A0A"))).isFalse();
    assertThat(inspector.contentMatches(csv, hex("FFD8FFE0"))).isFalse();
    assertThat(inspector.contentMatches(csv, hex("D0CF11E0A1B11AE1"))).isFalse();
    assertThat(inspector.contentMatches(csv, zip("daten.csv"))).isFalse();
    assertThat(inspector.contentMatches(csv, ftypBox("00000018", "isom"))).isFalse();
    // UTF-16 enthaelt NUL-Bytes und gilt (wie bei TXT) nicht
    final byte[] utf16 = "Name;Wert\r\n1;2\r\n".getBytes(StandardCharsets.UTF_16LE);
    assertThat(inspector.contentMatches(csv, new ByteArrayResource(utf16))).isFalse();
  }

  private static AttachmentFileType mp4Type() {
    return AttachmentFileType.fromExtension("mp4").orElseThrow();
  }

  private static AttachmentFileType csvType() {
    return AttachmentFileType.fromExtension("csv").orElseThrow();
  }

  /** ftyp-Box: Groesse, {@code ftyp}, Major Brand, Version 512, kompatible Marken isom und mp41. */
  private static byte[] ftypBytes(final String sizeHex, final String majorBrand) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(HexFormat.of().parseHex(sizeHex));
    out.writeBytes("ftyp".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(majorBrand.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(HexFormat.of().parseHex("00000200"));
    out.writeBytes("isom".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes("mp41".getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  private static InputStreamSource ftypBox(final String sizeHex, final String majorBrand) {
    return new ByteArrayResource(ftypBytes(sizeHex, majorBrand));
  }

  /** Text aus {@code a} mit einem NUL-Byte an der Stelle {@code index}, 8 KiB plus 1 Byte lang. */
  private static InputStreamSource textWithNulAt(final int index) {
    final byte[] content = new byte[HEAD_BYTES + 1];
    Arrays.fill(content, (byte) 'a');
    content[index] = 0;
    return new ByteArrayResource(content);
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

  /**
   * ZIP mit genau {@code count} leeren Eintraegen: Fuelleintraege, am Ende {@code
   * [Content_Types].xml} und {@code word/document.xml}.
   */
  private static InputStreamSource zipWithEntryCount(final int count) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (int index = 0; index < count - 2; index++) {
        zip.putNextEntry(new ZipEntry("customXml/item" + index + ".xml"));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new ByteArrayResource(out.toByteArray());
  }

  private static InputStreamSource failingSource() {
    return () -> {
      throw new IOException("Lesefehler (Test)");
    };
  }
}
