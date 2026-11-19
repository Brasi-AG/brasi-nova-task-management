package de.brasi.nova.taskmanagement.attachment;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

/**
 * Erzeugt Vorschaubilder fuer Anhaenge (Intake #152): JPEG der ersten PDF-Seite bzw. des
 * verkleinerten Bilds (PNG, JPEG) mit der konfigurierten Breite.
 *
 * <p>Bilder werden nie vergroessert; das Seitenverhaeltnis bleibt erhalten, transparente Flaechen
 * werden weiss. Die Abmessungen eines Bilds werden vor dem Dekodieren aus dem Dateikopf gelesen;
 * ueberschreitet die Pixelzahl die konfigurierte Obergrenze der Quelle, wird nichts dekodiert und
 * kein Vorschaubild erzeugt (Schutz vor Dekompressionsbomben). Grosse Bilder werden schon beim
 * Lesen unterabgetastet.
 *
 * <p>Die Erzeugung bricht den Upload nie ab: Fuer nicht vorschaufaehige Typen und bei jedem Fehler
 * der Erzeugung ist das Ergebnis leer, Fehler werden mit Typ und Ursache (ohne Dateiinhalt) als
 * Warnung geloggt. Alle Werte (Breite, JPEG-Qualitaet, Pixel-Obergrenze) kommen aus {@link
 * AttachmentProperties#thumbnail()}.
 */
@Component
public class AttachmentThumbnailGenerator {

  private static final Logger log = LoggerFactory.getLogger(AttachmentThumbnailGenerator.class);

  private static final String JPEG_FORMAT = "jpeg";
  private static final int FIRST_PAGE = 0;
  private static final int FIRST_IMAGE = 0;
  private static final int QUARTER_TURN = 90;
  private static final int HALF_TURN = 180;

  /**
   * Pixelbudget als Vielfaches von Breite mal Breite: Ein dekodiertes bzw. gerendertes Bild und das
   * Vorschaubild haben hoechstens {@code 4 * width * width} Pixel (bei 600 px rund 1,4 Mio. Pixel,
   * knapp 6 MB als RGB). Das entspricht einem quadratischen Bild doppelter Breite und Hoehe, genug
   * Reserve fuer eine saubere Verkleinerung.
   */
  private static final long PIXEL_BUDGET_FACTOR = 4L;

  /** Gelesene Breite mindestens doppelt so gross wie die Zielbreite (Qualitaetsreserve). */
  private static final int OVERSAMPLING = 2;

  private final AttachmentProperties.Thumbnail settings;

  /**
   * Erzeugt den Generator.
   *
   * @param properties Anhang-Konfiguration; verwendet wird {@link AttachmentProperties#thumbnail()}
   */
  public AttachmentThumbnailGenerator(final AttachmentProperties properties) {
    this.settings = properties.thumbnail();
  }

  /**
   * Erzeugt das Vorschaubild eines Anhangs.
   *
   * @param type gepruefter Dateityp des Anhangs
   * @param source Inhalt des Anhangs
   * @return JPEG-Bytes des Vorschaubilds; leer fuer nicht vorschaufaehige Typen, fuer Bilder ueber
   *     der Pixel-Obergrenze und bei jedem Fehler der Erzeugung
   */
  public Optional<byte[]> generate(final AttachmentFileType type, final InputStreamSource source) {
    if (type == null || !type.previewable()) {
      return Optional.empty();
    }
    try {
      final Optional<BufferedImage> preview =
          switch (type) {
            case PDF -> renderFirstPdfPage(source);
            case PNG, JPEG -> readScaledImage(type, source);
            default -> Optional.empty();
          };
      return preview.map(this::toJpeg);
    } catch (Exception | OutOfMemoryError | StackOverflowError | LinkageError e) {
      // Bewusst breit: defekte oder boesartige Dateien loesen in PDFBox/ImageIO neben IOException
      // auch Laufzeitfehler, Speicherueberlauf einzelner Allokationen oder tiefe Rekursion aus;
      // fehlende native Grafik-/Schriftbibliotheken melden sich als LinkageError. Keiner dieser
      // Fehler darf den Upload abbrechen.
      // Ausnahmetexte von PDFBox/ImageIO koennen Dateifragmente enthalten: auf WARN nur die Art
      log.warn(
          "Vorschaubild fuer Anhang vom Typ {} nicht erzeugt: {}",
          type,
          e.getClass().getSimpleName());
      log.debug("Ursache der fehlgeschlagenen Vorschaubild-Erzeugung (Typ {})", type, e);
      return Optional.empty();
    }
  }

  /** Rendert die erste PDF-Seite in der konfigurierten Breite (RGB). */
  private Optional<BufferedImage> renderFirstPdfPage(final InputStreamSource source)
      throws IOException {
    try (InputStream in = source.getInputStream();
        RandomAccessReadBuffer buffer = new RandomAccessReadBuffer(in);
        PDDocument document = Loader.loadPDF(buffer, IOUtils.createTempFileOnlyStreamCache())) {
      if (document.getNumberOfPages() == 0) {
        log.warn("Vorschaubild fuer PDF nicht erzeugt: Das Dokument hat keine Seiten.");
        return Optional.empty();
      }
      final PDPage page = document.getPage(FIRST_PAGE);
      final PDRectangle box = page.getCropBox();
      final boolean turned = Math.floorMod(page.getRotation(), HALF_TURN) == QUARTER_TURN;
      final float pageWidth = turned ? box.getHeight() : box.getWidth();
      final float pageHeight = turned ? box.getWidth() : box.getHeight();
      if (!(pageWidth > 0 && pageHeight > 0)) {
        log.warn("Vorschaubild fuer PDF nicht erzeugt: Die erste Seite hat keine Flaeche.");
        return Optional.empty();
      }
      // Extrem hohe Seiten werden als Ganzes schmaler gerendert (Pixelbudget), nie riesig
      final int targetWidth =
          (int) Math.max(1, Math.min(settings.width(), budgetWidth(pageWidth, pageHeight)));
      final float scale = targetWidth / pageWidth;
      final double renderedPixels = (double) targetWidth * (pageHeight * scale);
      if (renderedPixels > settings.maxSourcePixels()) {
        log.warn(
            "Vorschaubild fuer PDF nicht erzeugt: Erste Seite ergaebe {} Pixel (Obergrenze {}).",
            Math.round(renderedPixels),
            settings.maxSourcePixels());
        return Optional.empty();
      }
      final PDFRenderer renderer = new PDFRenderer(document);
      renderer.setSubsamplingAllowed(true);
      final BufferedImage rendered = renderer.renderImage(FIRST_PAGE, scale, ImageType.RGB);
      // PDFRenderer rundet die Pixelbreite ab (z. B. 599 statt 600): auf exakte Breite bringen
      return Optional.of(scaleToWidth(rendered, targetWidth));
    }
  }

  /**
   * Liest ein Bild unterabgetastet und verkleinert es auf die konfigurierte Breite (nie
   * vergroessert). Die Abmessungen kommen aus dem Dateikopf; ueber der Pixel-Obergrenze wird nicht
   * dekodiert.
   */
  private Optional<BufferedImage> readScaledImage(
      final AttachmentFileType type, final InputStreamSource source) throws IOException {
    try (InputStream in = source.getInputStream();
        ImageInputStream imageInput = new MemoryCacheImageInputStream(in)) {
      final Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
      if (!readers.hasNext()) {
        log.warn("Vorschaubild fuer {} nicht erzeugt: Kein passender Bildleser gefunden.", type);
        return Optional.empty();
      }
      final ImageReader reader = readers.next();
      try {
        reader.setInput(imageInput, true, true);
        final int sourceWidth = reader.getWidth(FIRST_IMAGE);
        final int sourceHeight = reader.getHeight(FIRST_IMAGE);
        if (sourceWidth <= 0 || sourceHeight <= 0) {
          log.warn("Vorschaubild fuer {} nicht erzeugt: Das Bild hat keine Flaeche.", type);
          return Optional.empty();
        }
        final long sourcePixels = (long) sourceWidth * sourceHeight;
        if (sourcePixels > settings.maxSourcePixels()) {
          log.warn(
              "Vorschaubild fuer {} nicht erzeugt: {} x {} Pixel ueberschreiten die Obergrenze {}.",
              type,
              sourceWidth,
              sourceHeight,
              settings.maxSourcePixels());
          return Optional.empty();
        }
        final int factor = subsamplingFactor(sourceWidth, sourceHeight);
        final ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(factor, factor, 0, 0);
        final BufferedImage decoded = reader.read(FIRST_IMAGE, param);

        final long targetWidth =
            Math.max(
                1,
                Math.min(
                    Math.min(settings.width(), sourceWidth),
                    budgetWidth(sourceWidth, sourceHeight)));
        return Optional.of(scaleToWidth(decoded, (int) targetWidth));
      } finally {
        reader.dispose();
      }
    }
  }

  /**
   * Unterabtast-Faktor beim Lesen eines Bilds (je Richtung jede n-te Zeile und Spalte).
   *
   * <p>{@code max(1, w / (2 * width), ceil(sqrt(w * h / (4 * width * width))))}: Der zweite Term
   * haelt die gelesene Breite bei mindestens doppelter Zielbreite (Qualitaet). Der dritte Term
   * begrenzt die gelesene Pixelzahl auf das Pixelbudget {@code 4 * width * width}, denn ein
   * schmales, hohes Bild (z. B. 1000 x 50000) haette nach der Breite allein den Faktor 1 und wuerde
   * voll dekodiert; mit Faktor {@code f} sinkt die Pixelzahl auf {@code w * h / (f * f)}. Bilder
   * bis zum Budget (z. B. 1200 x 800 oder 300 x 200 bei Breite 600) bleiben bei Faktor 1.
   *
   * @param sourceWidth Breite des Quellbilds in Pixeln (positiv)
   * @param sourceHeight Hoehe des Quellbilds in Pixeln (positiv)
   * @return Faktor ab 1
   */
  int subsamplingFactor(final int sourceWidth, final int sourceHeight) {
    final int byWidth = sourceWidth / (OVERSAMPLING * settings.width());
    final int byBudget =
        (int) Math.ceil(Math.sqrt((double) sourceWidth * sourceHeight / pixelBudget()));
    return Math.max(1, Math.max(byWidth, byBudget));
  }

  /** Pixelbudget fuer gelesene/gerenderte Bilder und das Vorschaubild. */
  private long pixelBudget() {
    return PIXEL_BUDGET_FACTOR * settings.width() * settings.width();
  }

  /**
   * Groesste Breite, bei der ein Bild mit dem Seitenverhaeltnis {@code width : height} das
   * Pixelbudget einhaelt; fuer uebliche Formate liegt sie ueber der Zielbreite, nur Bilder, die
   * mehr als viermal so hoch wie breit sind, ergeben ein schmaleres Vorschaubild.
   */
  private long budgetWidth(final double width, final double height) {
    return (long) Math.floor(Math.sqrt(pixelBudget() * width / height));
  }

  /**
   * Bringt ein Bild auf die Zielbreite (Hoehe proportional) als RGB-Bild mit weissem Hintergrund.
   * Starke Verkleinerung erfolgt in Halbierungsschritten (bessere Qualitaet als ein einziger
   * bilinearer Schritt). Ist das Bild bereits RGB in Zielgroesse, entfaellt die Kopie.
   */
  private static BufferedImage scaleToWidth(final BufferedImage image, final int targetWidth) {
    final int targetHeight =
        Math.max(1, Math.round((float) image.getHeight() * targetWidth / image.getWidth()));
    BufferedImage current = image;
    while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= 1) {
      current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
    }
    final boolean done =
        current.getType() == BufferedImage.TYPE_INT_RGB
            && current.getWidth() == targetWidth
            && current.getHeight() == targetHeight;
    return done ? current : draw(current, targetWidth, targetHeight);
  }

  /** Zeichnet ein Bild bilinear in ein neues RGB-Bild der Zielgroesse (Hintergrund weiss). */
  private static BufferedImage draw(final BufferedImage image, final int width, final int height) {
    final BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.drawImage(image, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** Kodiert ein RGB-Bild als JPEG mit der konfigurierten Qualitaet. */
  private byte[] toJpeg(final BufferedImage image) {
    final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(JPEG_FORMAT);
    if (!writers.hasNext()) {
      throw new IllegalStateException("Kein JPEG-Schreiber verfuegbar.");
    }
    final ImageWriter writer = writers.next();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      final ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(settings.jpegQuality());
      try (ImageOutputStream imageOutput = new MemoryCacheImageOutputStream(out)) {
        writer.setOutput(imageOutput);
        writer.write(null, new IIOImage(image, null, null), param);
      }
    } catch (IOException e) {
      throw new IllegalStateException("JPEG-Kodierung des Vorschaubilds fehlgeschlagen.", e);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }
}
