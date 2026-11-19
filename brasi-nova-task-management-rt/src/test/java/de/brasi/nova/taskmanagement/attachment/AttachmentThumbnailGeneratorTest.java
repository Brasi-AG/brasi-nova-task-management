package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.DOCX;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.JPEG;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PDF;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PNG;
import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.TXT;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.util.unit.DataSize;

/**
 * Unit-Tests fuer {@link AttachmentThumbnailGenerator} (Intake #152, Task A5; Review Focus 5).
 *
 * <p>Vertrag: JPEG mit konfigurierter Breite (600 px) von der ersten PDF-Seite bzw. vom
 * verkleinerten Bild (nie vergroessert, Seitenverhaeltnis bleibt); leer fuer nicht vorschaufaehige
 * Typen, bei jedem Fehler und bei Bildern ueber der Pixel-Obergrenze der Quelle (ohne Dekodieren).
 * Testdaten werden im Test erzeugt (PDF per PDFBox {@link PDDocument}, Bilder per {@link ImageIO});
 * PDFBox kommt erst mit A5 ins POM.
 */
class AttachmentThumbnailGeneratorTest {

  /** Pixelbudget des Generators bei Breite 600: 4 * 600 * 600. */
  private static final long BUDGET = 4L * 600 * 600;

  private final AttachmentThumbnailGenerator generator =
      new AttachmentThumbnailGenerator(properties());

  @Test
  void i152_pdf_first_page_has_configured_width() throws IOException {
    final byte[] jpeg = generator.generate(PDF, source(pdfWithPages(2))).orElseThrow();

    assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8);
    assertThat(decode(jpeg).getWidth()).isEqualTo(600);
  }

  @Test
  void i152_png_scaled_keeps_aspect() throws IOException {
    final BufferedImage t = decode(generator.generate(PNG, source(png(1200, 800))).orElseThrow());

    assertThat(t.getWidth()).isEqualTo(600);
    assertThat(t.getHeight()).isEqualTo(400);
  }

  @Test
  void i152_small_image_not_upscaled() throws IOException {
    final byte[] jpeg = generator.generate(JPEG, source(jpeg(300, 200))).orElseThrow();

    assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8);
    assertThat(decode(jpeg).getWidth()).isEqualTo(300);
  }

  @Test
  void i152_corrupt_pdf_returns_empty() {
    assertThat(generator.generate(PDF, source(bytes("%PDF-1.7 kaputt")))).isEmpty();
  }

  @Test
  void i152_office_and_txt_return_empty() {
    assertThat(generator.generate(DOCX, source(anyBytes()))).isEmpty();
    assertThat(generator.generate(TXT, source(utf8("x")))).isEmpty();
  }

  @Test
  void i152_pixel_bomb_returns_empty_without_decoding() {
    // Review Focus 5: PNG-Signatur + IHDR 30000x30000 + abgeschnittenes IDAT (wenige Bytes);
    // Dekodieren wuerde rund 2,7 GB fuer das Bild anlegen
    assertThat(generator.generate(PNG, source(pngHeaderOnly(30_000, 30_000)))).isEmpty();
  }

  @Test
  void i152_tall_narrow_image_is_subsampled() throws IOException {
    // Pixelbudget 4 * 600 * 600: unter dem Budget Faktor 1, schmal und hoch wird unterabgetastet
    assertThat(generator.subsamplingFactor(1200, 800)).isEqualTo(1);
    assertThat(generator.subsamplingFactor(300, 200)).isEqualTo(1);
    assertThat(generator.subsamplingFactor(2400, 1600)).isEqualTo(2);
    // Deckel w / previewWidth: Dekodat mindestens so breit wie das Vorschaubild (169 px)
    assertThat(generator.subsamplingFactor(600, 30_000)).isEqualTo(3);
    assertThat(generator.subsamplingFactor(1000, 50_000)).isEqualTo(5);

    final BufferedImage t =
        decode(generator.generate(PNG, source(binaryPng(600, 30_000))).orElseThrow());

    assertThat(t.getWidth()).isLessThan(600);
    assertThat((long) t.getWidth() * t.getHeight()).isLessThanOrEqualTo(BUDGET);
  }

  @Test
  void i152_decoded_image_never_narrower_than_preview() throws IOException {
    assertThat(generator.subsamplingFactor(1080, 1920)).isEqualTo(1);
    final int[][] sizes = {
      {1080, 1920}, {1000, 1500}, {600, 2500}, {1200, 800}, {2400, 1600}, {600, 30_000},
      {1000, 50_000}, {7000, 7000}, {3, 4_000_000}
    };
    for (final int[] size : sizes) {
      final int factor = generator.subsamplingFactor(size[0], size[1]);
      final int decodedWidth = (size[0] + factor - 1) / factor; // wie ImageIO-Subsampling
      assertThat(decodedWidth)
          .as("Dekodatbreite fuer %d x %d", size[0], size[1])
          .isGreaterThanOrEqualTo(generator.previewWidth(size[0], size[1]));
    }

    final BufferedImage t =
        decode(generator.generate(PNG, source(binaryPng(1080, 1920))).orElseThrow());

    assertThat(t.getWidth()).isEqualTo(600);
    assertThat(t.getHeight()).isEqualTo(1067);
  }

  @Test
  void i152_extremely_narrow_source_returns_empty() throws IOException {
    // Schon ein Pixel Breite sprengt das Pixelbudget: kein Vorschaubild, kein Riesenbild
    assertThat(generator.previewWidth(1, 1_500_000)).isZero();
    assertThat(generator.generate(PDF, source(pdfWithPage(new PDRectangle(0.001f, 40_000)))))
        .isEmpty();
  }

  @Test
  void i152_tall_pdf_page_rendered_within_budget() throws IOException {
    final byte[] pdf = pdfWithPage(new PDRectangle(612, 14_400));

    final BufferedImage t = decode(generator.generate(PDF, source(pdf)).orElseThrow());

    assertThat(t.getWidth()).isLessThan(600);
    assertThat((long) t.getWidth() * t.getHeight()).isLessThanOrEqualTo(BUDGET);
  }

  private static InputStreamSource source(final byte[] content) {
    return new ByteArrayResource(content);
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] utf8(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static BufferedImage decode(final byte[] image) throws IOException {
    final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
    assertThat(decoded).isNotNull();
    return decoded;
  }

  /** PDF mit {@code pages} leeren A4-Seiten. */
  private static byte[] pdfWithPages(final int pages) throws IOException {
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        document.addPage(new PDPage(PDRectangle.A4));
      }
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** PDF mit einer Seite der angegebenen Groesse. */
  private static byte[] pdfWithPage(final PDRectangle size) throws IOException {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage(size));
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** Schwarz-weisses PNG (klein im Testspeicher auch bei grossen Abmessungen). */
  private static byte[] binaryPng(final int width, final int height) throws IOException {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThat(ImageIO.write(image, "png", out)).isTrue();
    return out.toByteArray();
  }

  private static byte[] png(final int width, final int height) throws IOException {
    return image(width, height, "png");
  }

  private static byte[] jpeg(final int width, final int height) throws IOException {
    return image(width, height, "jpg");
  }

  private static byte[] image(final int width, final int height, final String format)
      throws IOException {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThat(ImageIO.write(image, format, out)).isTrue();
    return out.toByteArray();
  }

  /** Ein gueltiges docx-artiges ZIP (Inhalt fuer den Generator egal). */
  private static byte[] anyBytes() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * PNG-Signatur, gueltiger IHDR-Chunk mit den Abmessungen (8 Bit RGB) und ein abgeschnittener
   * IDAT-Chunk (angekuendigt 1000 Bytes, geliefert 8) – Abmessungen lesbar, Bild nicht dekodierbar.
   */
  private static byte[] pngHeaderOnly(final int width, final int height) {
    final ByteBuffer ihdr = ByteBuffer.allocate(13);
    ihdr.putInt(width).putInt(height);
    ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(
        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}); // PNG-Signatur
    writeChunk(out, "IHDR", ihdr.array());
    out.writeBytes(ByteBuffer.allocate(4).putInt(1000).array());
    out.writeBytes("IDAT".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(new byte[] {0x78, (byte) 0x9C, 0, 0, 0, 0, 0, 0});
    return out.toByteArray();
  }

  private static void writeChunk(
      final ByteArrayOutputStream out, final String type, final byte[] data) {
    final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
    final CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
    out.writeBytes(typeBytes);
    out.writeBytes(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
  }

  /** Properties mit den Vorgaben aus application.properties (Breite 600, Qualitaet 0,85). */
  private static AttachmentProperties properties() {
    return new AttachmentProperties(
        new AttachmentProperties.Storage(null),
        List.of(),
        20,
        new AttachmentProperties.Limits(
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(15),
            DataSize.ofMegabytes(15)),
        new AttachmentProperties.Thumbnail(600, 0.85f, 50_000_000L),
        new AttachmentProperties.Cache(Duration.ofDays(1), Duration.ofDays(10)));
  }
}
