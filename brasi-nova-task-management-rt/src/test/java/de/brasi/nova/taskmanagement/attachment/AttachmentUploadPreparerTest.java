package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.attachment.AttachmentFileType.PDF;
import static de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff.STANDARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.keel.framework.api.validation.EntityValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Unit-Tests fuer {@link AttachmentUploadPreparer} (Intake #152, Task A6): echter {@link
 * AttachmentFileInspector}, gemockte {@link AttachmentLimitPolicy} (STANDARD 5 MiB) und gemockter
 * {@link AttachmentThumbnailGenerator}.
 *
 * <p>Vertrag: alles oder nichts – erst alle Dateien pruefen, dann erst Hash und Vorschau (kein
 * Generator-Aufruf bei einer ungueltigen Datei). Pruefreihenfolge und Meldungstexte ({@link
 * EntityValidationException}, 400) wie im Interfaces-Block; die Texte sind hier wortgleich
 * festgelegt. Scheitert die Vorschau, bleibt der Anhang gueltig (ohne Vorschaubild).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttachmentUploadPreparerTest {

  private static final long TENANT = 560L;
  private static final long STANDARD_LIMIT = 5_242_880L;
  private static final byte[] PDF_BYTES = pdfOfSize(64);
  private static final byte[] PNG_BYTES = png();
  private static final byte[] MZ = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
  private static final byte[] TXT = "Notiz\r\nZeile 2".getBytes(StandardCharsets.UTF_8);
  private static final byte[] THUMBNAIL = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1};

  /** Minimale MP4-ftyp-Box: Groesse 24, {@code ftyp}, Major Brand {@code isom}, Version, Marken. */
  private static final byte[] MP4 = {
    0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p',
    '4', '1'
  };

  private static final byte[] CSV =
      "Name;Straße\r\nMüller;Hauptstraße 1\r\n".getBytes(StandardCharsets.UTF_8);

  @Mock private AttachmentLimitPolicy limitPolicy;
  @Mock private AttachmentThumbnailGenerator thumbnailGenerator;

  private AttachmentUploadPreparer preparer;

  @BeforeEach
  void setUp() {
    preparer =
        new AttachmentUploadPreparer(
            limitPolicy, new AttachmentFileInspector(), thumbnailGenerator, properties());
    when(limitPolicy.limitFor(anyLong()))
        .thenReturn(new AttachmentFileLimit(STANDARD, STANDARD_LIMIT));
    when(thumbnailGenerator.generate(any(), any())).thenReturn(Optional.of(THUMBNAIL));
  }

  @Test
  void i152_valid_pdf_prepared() throws Exception {
    final MultipartFile upload = file("..\\Bericht.pdf", PDF_BYTES);

    final List<PreparedAttachment> prepared = preparer.prepare(TENANT, 0, List.of(upload));

    assertThat(prepared).hasSize(1);
    final PreparedAttachment p = prepared.get(0);
    assertThat(p.fileName()).isEqualTo("Bericht.pdf");
    assertThat(p.type()).isEqualTo(PDF);
    assertThat(p.extension()).isEqualTo("pdf");
    assertThat(p.sizeBytes()).isEqualTo(PDF_BYTES.length);
    assertThat(p.sha256Hex())
        .isEqualTo(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF_BYTES)));
    assertThat(p.hasThumbnail()).isTrue();
    assertThat(p.thumbnailJpeg()).isEqualTo(THUMBNAIL);
    try (InputStream in = p.source().getInputStream()) {
      assertThat(in.readAllBytes()).isEqualTo(PDF_BYTES);
    }
  }

  @Test
  void i152_second_invalid_file_rejects_whole_upload() {
    assertThatThrownBy(
            () -> preparer.prepare(TENANT, 0, List.of(file("a.pdf", PDF_BYTES), file("b.exe", MZ))))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("„.exe“")
        .hasMessage(
            "Der Dateityp „.exe“ ist nicht erlaubt. Erlaubt sind: "
                + "pdf, xls, xlsx, doc, docx, ppt, pptx, txt, csv, png, jpg, jpeg, mp4.");
    verifyNoInteractions(thumbnailGenerator);
  }

  /**
   * Intake #184: MP4 und CSV werden angenommen (die Schreibweise der Endung ist egal), ohne
   * Vorschaubild; der Vorschaubild-Generator wird nicht gefragt.
   */
  @Test
  void i184_mp4_and_csv_prepared_without_thumbnail() {
    final List<PreparedAttachment> prepared =
        preparer.prepare(TENANT, 0, List.of(file("Clip.MP4", MP4), file("Daten.csv", CSV)));

    assertThat(prepared).hasSize(2);
    assertThat(prepared.get(0).fileName()).isEqualTo("Clip.MP4");
    assertThat(prepared.get(0).extension()).isEqualTo("mp4");
    assertThat(prepared.get(0).type().name()).isEqualTo("MP4");
    assertThat(prepared.get(0).type().mimeType()).isEqualTo("video/mp4");
    assertThat(prepared.get(0).sizeBytes()).isEqualTo(MP4.length);
    assertThat(prepared.get(1).fileName()).isEqualTo("Daten.csv");
    assertThat(prepared.get(1).extension()).isEqualTo("csv");
    assertThat(prepared.get(1).type().name()).isEqualTo("CSV");
    assertThat(prepared.get(1).type().mimeType()).isEqualTo("text/csv");
    assertThat(prepared.get(1).sizeBytes()).isEqualTo(CSV.length);
    assertThat(prepared).allSatisfy(p -> assertThat(p.hasThumbnail()).isFalse());
    assertThat(prepared).allSatisfy(p -> assertThat(p.thumbnailJpeg()).isNull());
    verifyNoInteractions(thumbnailGenerator);
  }

  /** Intake #184: Falscher Inhalt unter der Endung mp4 bzw. csv ergibt die 400 mit dem Dateityp. */
  @Test
  void i184_mp4_and_csv_content_mismatch_rejected() {
    assertThatThrownBy(preparing("video.mp4", PDF_BYTES))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Der Inhalt der Datei „video.mp4“ passt nicht zum Dateityp MP4.");
    assertThatThrownBy(preparing("daten.csv", MP4))
        .isInstanceOf(EntityValidationException.class)
        .hasMessage("Der Inhalt der Datei „daten.csv“ passt nicht zum Dateityp CSV.");
    verifyNoInteractions(thumbnailGenerator);
  }

  @Test
  void i152_empty_file_rejected() {
    assertThatThrownBy(preparing("leer.txt", new byte[0]))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("ist leer")
        .hasMessage("Die Datei „leer.txt“ ist leer.");
  }

  @Test
  void i152_over_tariff_limit_names_limit_and_tariff() {
    // Grenze STANDARD 5 MiB
    assertThatThrownBy(preparing("gross.pdf", pdfOfSize(5_242_881)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("5 MB")
        .hasMessageContaining("STANDARD")
        .hasMessage(
            "Die Datei „gross.pdf“ ist größer als die Grenze von 5 MB des Tarifs STANDARD.");
  }

  @Test
  void i152_size_exactly_at_limit_accepted() {
    assertThat(prepareOne("genau.pdf", pdfOfSize(5_242_880))).hasSize(1);
  }

  @Test
  void i152_magic_mismatch_rejected() {
    assertThatThrownBy(preparing("bild.pdf", PNG_BYTES))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("passt nicht")
        .hasMessage("Der Inhalt der Datei „bild.pdf“ passt nicht zum Dateityp PDF.");
    verifyNoInteractions(thumbnailGenerator);
  }

  @Test
  void i152_count_over_max_rejected() {
    assertThatThrownBy(
            () -> preparer.prepare(TENANT, 19, List.of(file("a.txt", TXT), file("b.txt", TXT))))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("20")
        .hasMessage("Höchstens 20 aktive Anhänge je Aufgabe bzw. je Kommentar.");
  }

  @Test
  void i152_no_files_rejected() {
    assertThatThrownBy(() -> preparer.prepare(TENANT, 0, List.of()))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("keine Datei")
        .hasMessage("Es wurde keine Datei übergeben.");
  }

  @Test
  void i152_thumbnail_failure_keeps_attachment() {
    when(thumbnailGenerator.generate(any(), any())).thenReturn(Optional.empty());

    final PreparedAttachment p = prepareOne("x.pdf", PDF_BYTES).get(0);

    assertThat(p.hasThumbnail()).isFalse();
    assertThat(p.thumbnailJpeg()).isNull();
  }

  private List<PreparedAttachment> prepareOne(final String name, final byte[] content) {
    return preparer.prepare(TENANT, 0, List.of(file(name, content)));
  }

  private ThrowingCallable preparing(final String name, final byte[] content) {
    return () -> prepareOne(name, content);
  }

  private static MultipartFile file(final String name, final byte[] content) {
    return new MockMultipartFile("files", name, null, content);
  }

  /** PDF-Kopf {@code %PDF-1.7}, mit Leerzeichen auf genau {@code size} Bytes aufgefuellt. */
  private static byte[] pdfOfSize(final int size) {
    final byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1);
    final byte[] content = new byte[size];
    Arrays.fill(content, (byte) ' ');
    System.arraycopy(head, 0, content, 0, head.length);
    return content;
  }

  private static byte[] png() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** Properties mit den Vorgaben aus application.properties (hoechstens 20 je Ziel). */
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
