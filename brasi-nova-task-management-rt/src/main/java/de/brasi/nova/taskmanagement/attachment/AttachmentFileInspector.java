package de.brasi.nova.taskmanagement.attachment;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

/**
 * Prueft, ob der Inhalt einer Datei zu ihrem Dateityp passt (Intake #152, Magic Bytes).
 *
 * <p>Regeln je Typ:
 *
 * <ul>
 *   <li>PDF: beginnt mit {@code %PDF-}
 *   <li>PNG: beginnt mit {@code 89 50 4E 47 0D 0A 1A 0A}
 *   <li>JPEG: beginnt mit {@code FF D8 FF}
 *   <li>DOC, XLS, PPT: beginnt mit der OLE2-Signatur {@code D0 CF 11 E0 A1 B1 1A E1}
 *   <li>DOCX, XLSX, PPTX: beginnt mit {@code 50 4B 03 04} (ZIP) und enthaelt die Eintraege {@code
 *       [Content_Types].xml} sowie einen Eintrag unter {@code word/}, {@code xl/} bzw. {@code ppt/}
 *   <li>TXT: in den ersten 8 KiB kein NUL-Byte, Beginn ohne eine der genannten Signaturen und ohne
 *       {@code MZ} (ausfuehrbare Windows-Dateien)
 * </ul>
 *
 * <p>Es wird nie mehr als der Kopf (8 KiB) gelesen; bei Office-Open-XML-Dateien werden nur die
 * Eintragsnamen des ZIP gelesen (hoechstens 1000 Eintraege), nie entpackt oder ausgewertet. Ein
 * Lesefehler oder ein beschaedigtes ZIP ergibt {@code false}.
 */
@Component
public class AttachmentFileInspector {

  private static final Logger log = LoggerFactory.getLogger(AttachmentFileInspector.class);

  /** Laenge des gelesenen Kopfes fuer die Textpruefung (8 KiB). */
  private static final int HEAD_BYTES = 8 * 1024;

  /** Hoechste Zahl gelesener ZIP-Eintraege (Schutz vor ZIP-Archiven mit sehr vielen Eintraegen). */
  private static final int MAX_ZIP_ENTRIES = 1000;

  private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private static final byte[] PNG_SIGNATURE = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
  };

  private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

  private static final byte[] OLE2_SIGNATURE = {
    (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
  };

  private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

  /** Beginn einer ausfuehrbaren Windows-Datei ({@code MZ}). */
  private static final byte[] EXECUTABLE_SIGNATURE = {0x4D, 0x5A};

  /** Signaturen, mit denen eine Textdatei nicht beginnen darf. */
  private static final List<byte[]> FORBIDDEN_TEXT_PREFIXES =
      List.of(
          PDF_SIGNATURE,
          PNG_SIGNATURE,
          JPEG_SIGNATURE,
          OLE2_SIGNATURE,
          ZIP_SIGNATURE,
          EXECUTABLE_SIGNATURE);

  private static final String OPEN_XML_CONTENT_TYPES = "[Content_Types].xml";

  /**
   * Prueft, ob der Inhalt zum Dateityp passt.
   *
   * @param type erwarteter Dateityp (aus der Endung)
   * @param source Quelle des Dateiinhalts; wird hoechstens einmal geoeffnet und wieder geschlossen
   * @return {@code true}, wenn der Inhalt zum Typ passt; {@code false}, wenn nicht oder wenn beim
   *     Lesen ein Fehler auftritt
   */
  public boolean contentMatches(final AttachmentFileType type, final InputStreamSource source) {
    try (InputStream in = source.getInputStream()) {
      return switch (type) {
        case PDF -> startsWith(in, PDF_SIGNATURE);
        case PNG -> startsWith(in, PNG_SIGNATURE);
        case JPEG -> startsWith(in, JPEG_SIGNATURE);
        case DOC, XLS, PPT -> startsWith(in, OLE2_SIGNATURE);
        case DOCX -> isOpenXml(in, "word/");
        case XLSX -> isOpenXml(in, "xl/");
        case PPTX -> isOpenXml(in, "ppt/");
        case TXT -> isPlainText(in);
      };
    } catch (IOException | IllegalArgumentException e) {
      // Lesefehler oder beschaedigtes ZIP (z. B. ungueltiger Eintragsname): Inhalt ungueltig
      log.debug("Inhaltspruefung fuer Dateityp {} nicht moeglich: {}", type, e.toString());
      return false;
    }
  }

  private static boolean startsWith(final InputStream in, final byte[] signature)
      throws IOException {
    return hasPrefix(in.readNBytes(signature.length), signature);
  }

  private static boolean hasPrefix(final byte[] head, final byte[] prefix) {
    return head.length >= prefix.length
        && Arrays.equals(head, 0, prefix.length, prefix, 0, prefix.length);
  }

  /**
   * Office Open XML: ZIP-Signatur, dazu der Eintrag {@code [Content_Types].xml} und mindestens ein
   * Eintrag unter dem Ordner des Formats. Gelesen werden nur die Eintragsnamen.
   */
  private static boolean isOpenXml(final InputStream in, final String formatFolder)
      throws IOException {
    final BufferedInputStream buffered = new BufferedInputStream(in);
    buffered.mark(ZIP_SIGNATURE.length);
    if (!startsWith(buffered, ZIP_SIGNATURE)) {
      return false;
    }
    buffered.reset();
    boolean hasContentTypes = false;
    boolean hasFormatFolder = false;
    try (ZipInputStream zip = new ZipInputStream(buffered)) {
      ZipEntry entry;
      int entries = 0;
      while (entries < MAX_ZIP_ENTRIES && (entry = zip.getNextEntry()) != null) {
        entries++;
        final String name = entry.getName();
        if (OPEN_XML_CONTENT_TYPES.equals(name)) {
          hasContentTypes = true;
        } else if (name.startsWith(formatFolder)) {
          hasFormatFolder = true;
        }
        if (hasContentTypes && hasFormatFolder) {
          return true;
        }
      }
    }
    return false;
  }

  /** Text: kein NUL-Byte im Kopf und kein Beginn mit der Signatur eines anderen Dateityps. */
  private static boolean isPlainText(final InputStream in) throws IOException {
    final byte[] head = in.readNBytes(HEAD_BYTES);
    for (final byte value : head) {
      if (value == 0) {
        return false;
      }
    }
    for (final byte[] prefix : FORBIDDEN_TEXT_PREFIXES) {
      if (hasPrefix(head, prefix)) {
        return false;
      }
    }
    return true;
  }
}
