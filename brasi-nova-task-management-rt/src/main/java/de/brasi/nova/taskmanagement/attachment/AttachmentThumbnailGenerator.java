package de.brasi.nova.taskmanagement.attachment;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
 *
 * <p>Die Erzeugung laeuft auf einem eigenen, begrenzten Pool mit Zeitlimit ({@link
 * AttachmentThumbnailPoolProperties}); der Aufrufer (Upload bzw. Selbstheilung beim Abruf) wartet
 * synchron. Der Pool ist bewusst <b>keine Bean</b> und kein {@code Executor}-Bean: ein solches
 * wuerde wegen {@code @EnableAsync} den {@code applicationTaskExecutor} verdraengen (Muster {@link
 * AttachmentStreamingConfiguration}). Er gehoert diesem Generator und wird mit ihm beendet.
 */
@Component
public class AttachmentThumbnailGenerator implements DisposableBean {

  /** Erzeugungsfunktion auf dem Pool: Dateityp und vollstaendig gelesener Inhalt zu JPEG-Bytes. */
  @FunctionalInterface
  interface Renderer {

    /**
     * Erzeugt das Vorschaubild.
     *
     * @param type vorschaufaehiger Dateityp
     * @param content vollstaendiger Inhalt des Anhangs
     * @return JPEG-Bytes oder leer
     */
    Optional<byte[]> render(AttachmentFileType type, byte[] content);
  }

  private static final Logger log = LoggerFactory.getLogger(AttachmentThumbnailGenerator.class);

  private static final String JPEG_FORMAT = "jpeg";
  private static final int FIRST_PAGE = 0;
  private static final int FIRST_IMAGE = 0;
  private static final int QUARTER_TURN = 90;
  private static final int HALF_TURN = 180;

  /**
   * Pixelbudget als Vielfaches von Breite mal Breite: Die gerenderte PDF-Seite und das Vorschaubild
   * haben hoechstens {@code 4 * width * width} Pixel (bei 600 px rund 1,4 Mio. Pixel, knapp 6 MB
   * als RGB), ein dekodiertes Bild hoechstens rund das Vierfache davon (siehe {@link
   * #subsamplingFactor}). Das Budget entspricht einem Quadrat doppelter Breite und Hoehe.
   */
  private static final long PIXEL_BUDGET_FACTOR = 4L;

  /** Sehr breite Bilder werden bis etwa zur doppelten Zielbreite unterabgetastet gelesen. */
  private static final int OVERSAMPLING = 2;

  private final AttachmentProperties.Thumbnail settings;
  private final Duration timeout;
  private final Renderer renderer;
  private final ThreadPoolTaskExecutor executor;

  /**
   * Erzeugt den Generator und startet seinen Pool. {@code @Autowired} waehlt diesen Konstruktor fuer
   * Spring, weil es daneben die Test-Naht gibt.
   *
   * @param properties Anhang-Konfiguration; verwendet wird {@link AttachmentProperties#thumbnail()}
   * @param pool Groesse, Warteschlange, Zeitlimit und Thread-Namen des Erzeugungs-Pools
   */
  @Autowired
  public AttachmentThumbnailGenerator(
      final AttachmentProperties properties, final AttachmentThumbnailPoolProperties pool) {
    this.settings = properties.thumbnail();
    this.timeout = pool.timeout();
    this.renderer = this::render;
    this.executor = createExecutor(pool);
  }

  /**
   * Test-Naht: Generator mit eigener Erzeugungsfunktion (z. B. absichtlich langsam), um Zeitlimit
   * und vollen Pool ohne echte PDF-Last zu pruefen.
   *
   * @param properties Anhang-Konfiguration
   * @param pool Konfiguration des Erzeugungs-Pools
   * @param renderer Erzeugungsfunktion, die auf dem Pool laeuft
   */
  AttachmentThumbnailGenerator(
      final AttachmentProperties properties,
      final AttachmentThumbnailPoolProperties pool,
      final Renderer renderer) {
    this.settings = properties.thumbnail();
    this.timeout = pool.timeout();
    this.renderer = renderer;
    this.executor = createExecutor(pool);
  }

  /** Baut den begrenzten Pool (feste Groesse, Warteschlange aus der Konfiguration). */
  private static ThreadPoolTaskExecutor createExecutor(
      final AttachmentThumbnailPoolProperties pool) {
    final ThreadPoolTaskExecutor created = new ThreadPoolTaskExecutor();
    created.setCorePoolSize(pool.poolSize());
    created.setMaxPoolSize(pool.poolSize());
    created.setQueueCapacity(pool.queueCapacity());
    created.setThreadNamePrefix(pool.threadNamePrefix());
    created.initialize();
    return created;
  }

  /**
   * Beendet den Pool mit dem Kontext; laufende Erzeugungen werden unterbrochen (PDFBox reagiert
   * darauf nicht zuverlaessig, die Threads enden dann mit ihrer Erzeugung).
   */
  @Override
  public void destroy() {
    executor.shutdown();
  }

  /**
   * Erzeugt das Vorschaubild eines Anhangs auf dem begrenzten Pool und wartet hoechstens das
   * konfigurierte Zeitlimit darauf.
   *
   * <p>Der Inhalt wird vorher im aufrufenden Thread vollstaendig gelesen; die Quelle (z. B. eine
   * {@code MultipartFile}) wird also nur innerhalb des Requests benutzt. Ist der Pool voll (alle
   * Threads belegt, Warteschlange voll) oder laeuft das Zeitlimit ab, entsteht kein Vorschaubild
   * (Warnung ohne Inhalt); beim Zeitlimit wird die Erzeugung mit {@link Future#cancel(boolean)}
   * unterbrochen. PDFBox reagiert nicht zuverlaessig auf die Unterbrechung, eine abgebrochene
   * Erzeugung kann also weiterrechnen; deshalb begrenzt die Poolgroesse die Zahl gleichzeitig
   * laufender Erzeugungen und damit Rechenzeit und Speicher.
   *
   * @param type gepruefter Dateityp des Anhangs
   * @param source Inhalt des Anhangs
   * @return JPEG-Bytes des Vorschaubilds; leer fuer nicht vorschaufaehige Typen, fuer Bilder ueber
   *     der Pixel-Obergrenze, bei vollem Pool, nach Ablauf des Zeitlimits und bei jedem Fehler der
   *     Erzeugung
   */
  public Optional<byte[]> generate(final AttachmentFileType type, final InputStreamSource source) {
    if (type == null || !type.previewable()) {
      return Optional.empty();
    }
    final byte[] content;
    try (InputStream in = source.getInputStream()) {
      content = in.readAllBytes();
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Vorschaubild fuer Anhang vom Typ {} nicht erzeugt: Inhalt nicht lesbar ({}).",
          type,
          e.getClass().getSimpleName());
      return Optional.empty();
    }
    final Future<Optional<byte[]>> future;
    try {
      future = executor.submit(() -> renderer.render(type, content));
    } catch (TaskRejectedException e) {
      log.warn(
          "Vorschaubild fuer Anhang vom Typ {} nicht erzeugt: Erzeugungs-Pool ausgelastet.", type);
      return Optional.empty();
    }
    try {
      final Optional<byte[]> result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return result == null ? Optional.empty() : result;
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn(
          "Vorschaubild fuer Anhang vom Typ {} nicht erzeugt: Zeitlimit von {} ueberschritten.",
          type,
          timeout);
      return Optional.empty();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      log.warn("Vorschaubild fuer Anhang vom Typ {} nicht erzeugt: Warten unterbrochen.", type);
      return Optional.empty();
    } catch (ExecutionException e) {
      final Throwable cause = e.getCause() == null ? e : e.getCause();
      log.warn(
          "Vorschaubild fuer Anhang vom Typ {} nicht erzeugt: {}",
          type,
          cause.getClass().getSimpleName());
      log.debug("Ursache der fehlgeschlagenen Vorschaubild-Erzeugung (Typ {})", type, cause);
      return Optional.empty();
    }
  }

  /**
   * Erzeugt das Vorschaubild aus dem gelesenen Inhalt (laeuft auf dem Pool).
   *
   * @param type vorschaufaehiger Dateityp
   * @param content vollstaendiger Inhalt des Anhangs
   * @return JPEG-Bytes; leer fuer Bilder ueber der Pixel-Obergrenze und bei jedem Fehler
   */
  private Optional<byte[]> render(final AttachmentFileType type, final byte[] content) {
    try {
      final Optional<BufferedImage> preview =
          switch (type) {
            case PDF -> renderFirstPdfPage(content);
            case PNG, JPEG -> readScaledImage(type, content);
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
  private Optional<BufferedImage> renderFirstPdfPage(final byte[] content) throws IOException {
    try (RandomAccessReadBuffer buffer = new RandomAccessReadBuffer(content);
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
      final long targetWidth = Math.min(settings.width(), budgetWidth(pageWidth, pageHeight));
      if (targetWidth < 1) {
        log.warn("Vorschaubild fuer PDF nicht erzeugt: Seitenverhaeltnis sprengt das Pixelbudget.");
        return Optional.empty();
      }
      final float scale = targetWidth / pageWidth;
      // Pixelzahl wie in PDFRenderer berechnet (Hoehe abgerundet, mindestens 1)
      final double renderedPixels = targetWidth * Math.max(1, Math.floor(pageHeight * scale));
      final long pixelLimit = Math.min(pixelBudget(), settings.maxSourcePixels());
      if (renderedPixels > pixelLimit) {
        log.warn(
            "Vorschaubild fuer PDF nicht erzeugt: Erste Seite ergaebe {} Pixel (Obergrenze {}).",
            Math.round(renderedPixels),
            pixelLimit);
        return Optional.empty();
      }
      final PDFRenderer renderer = new PDFRenderer(document);
      renderer.setSubsamplingAllowed(true);
      final BufferedImage rendered = renderer.renderImage(FIRST_PAGE, scale, ImageType.RGB);
      // PDFRenderer rundet die Pixelbreite ab (z. B. 599 statt 600): auf exakte Breite bringen
      return Optional.of(scaleToWidth(rendered, (int) targetWidth));
    }
  }

  /**
   * Liest ein Bild unterabgetastet und verkleinert es auf die konfigurierte Breite (nie
   * vergroessert). Die Abmessungen kommen aus dem Dateikopf; ueber der Pixel-Obergrenze wird nicht
   * dekodiert.
   */
  private Optional<BufferedImage> readScaledImage(
      final AttachmentFileType type, final byte[] content) throws IOException {
    try (ImageInputStream imageInput =
        new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
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
        final int previewWidth = previewWidth(sourceWidth, sourceHeight);
        if (previewWidth < 1) {
          log.warn(
              "Vorschaubild fuer {} nicht erzeugt: {} x {} Pixel sprengen das Pixelbudget.",
              type,
              sourceWidth,
              sourceHeight);
          return Optional.empty();
        }
        final int factor = subsamplingFactor(sourceWidth, sourceHeight);
        final ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(factor, factor, 0, 0);
        final BufferedImage decoded = reader.read(FIRST_IMAGE, param);
        // Der Faktor haelt das Dekodat mindestens so breit wie das Vorschaubild; die zweite Grenze
        // sichert zu, dass scaleToWidth in keinem Fall vergroessert
        return Optional.of(scaleToWidth(decoded, Math.min(previewWidth, decoded.getWidth())));
      } finally {
        reader.dispose();
      }
    }
  }

  /**
   * Breite des Vorschaubilds fuer ein Quellbild: {@code min(width, w, budgetWidth)}. Nie breiter
   * als die Quelle (nie vergroessern) und so schmal, dass das Vorschaubild hoechstens das
   * Pixelbudget {@code 4 * width * width} hat. Erst Bilder, die mehr als viermal so hoch wie breit
   * sind, werden schmaler als {@code width}.
   *
   * @param sourceWidth Breite des Quellbilds in Pixeln (positiv)
   * @param sourceHeight Hoehe des Quellbilds in Pixeln (positiv)
   * @return Breite in Pixeln; {@code 0}, wenn selbst ein Pixel Breite das Budget sprengt (extrem
   *     schmales, hohes Bild, dann kein Vorschaubild)
   */
  int previewWidth(final int sourceWidth, final int sourceHeight) {
    return (int)
        Math.min(Math.min(settings.width(), sourceWidth), budgetWidth(sourceWidth, sourceHeight));
  }

  /**
   * Unterabtast-Faktor beim Lesen eines Bilds (je Richtung jede n-te Zeile und Spalte).
   *
   * <p>{@code max(1, min(max(w / (2 * width), ceil(sqrt(w * h / budget))), w / T))} mit {@code T =
   * previewWidth(w, h)} und {@code budget = 4 * width * width}:
   *
   * <ul>
   *   <li>{@code w / (2 * width)}: sehr breite Bilder werden bis etwa zur doppelten Zielbreite
   *       verkleinert gelesen.
   *   <li>{@code ceil(sqrt(w * h / budget))}: schmale, hohe Bilder (z. B. 1000 x 50000) haetten
   *       nach der Breite allein den Faktor 1 und wuerden voll dekodiert; mit Faktor {@code f}
   *       sinkt die Pixelzahl auf {@code w * h / (f * f)}.
   *   <li>{@code w / T} (abgerundet) deckelt den Faktor: Das Dekodat ist dann mindestens {@code T}
   *       breit, das Vorschaubild entsteht also immer durch Verkleinern, nie durch Hochziehen eines
   *       zu schmal gelesenen Bilds (z. B. 1080 x 1920 bleibt bei Faktor 1).
   * </ul>
   *
   * <p>Speicher: Ohne Deckel liegt das Dekodat beim Budget (bis auf das Aufrunden um eine Zeile und
   * Spalte). Greift der Deckel, ist es weniger als doppelt so breit wie {@code T} und weniger als
   * doppelt so hoch wie das Vorschaubild, also unter dem Vierfachen des Budgets (bei 600 px unter
   * 5,8 Mio. Pixeln, ebenfalls bis auf eine Zeile und Spalte). Bilder, fuer die schon ein Pixel
   * Breite das Budget sprengt, werden gar nicht gelesen ({@link #previewWidth} = 0).
   *
   * @param sourceWidth Breite des Quellbilds in Pixeln (positiv)
   * @param sourceHeight Hoehe des Quellbilds in Pixeln (positiv)
   * @return Faktor ab 1
   */
  int subsamplingFactor(final int sourceWidth, final int sourceHeight) {
    final int byWidth = sourceWidth / (OVERSAMPLING * settings.width());
    final int byBudget =
        (int) Math.ceil(Math.sqrt((double) sourceWidth * sourceHeight / pixelBudget()));
    final int keepPreviewWidth = sourceWidth / Math.max(1, previewWidth(sourceWidth, sourceHeight));
    return Math.max(1, Math.min(Math.max(byWidth, byBudget), keepPreviewWidth));
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
