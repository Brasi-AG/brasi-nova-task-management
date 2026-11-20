package de.brasi.nova.taskmanagement.attachment;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Erlaubte Dateitypen fuer Anhaenge (Intake #152, #184): PDF, Excel, Word, PowerPoint, Text, CSV,
 * die Bildformate PNG und JPEG sowie Video im Format MP4.
 *
 * <p>Ein Typ gilt nur, wenn die Endung in der Erlaubtliste steht <em>und</em> der Inhalt dazu passt
 * (Pruefung durch {@link AttachmentFileInspector}). Vorschaubilder gibt es nur fuer PDF und Bilder
 * ({@link #previewable()}); CSV und MP4 haben keine Vorschau.
 */
public enum AttachmentFileType {
  /** PDF-Dokument. */
  PDF("application/pdf", true, "pdf"),
  /** PNG-Bild. */
  PNG("image/png", true, "png"),
  /** JPEG-Bild (Endungen {@code jpg} und {@code jpeg}). */
  JPEG("image/jpeg", true, "jpg", "jpeg"),
  /** Reiner Text. */
  TXT("text/plain", false, "txt"),
  /** Tabelle als Text mit Trennzeichen (CSV); wie {@link #TXT} ohne Zeichensatz im MIME-Typ. */
  CSV("text/csv", false, "csv"),
  /** Word-Dokument (Binaerformat). */
  DOC("application/msword", false, "doc"),
  /** Word-Dokument (Office Open XML). */
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, "docx"),
  /** Excel-Arbeitsmappe (Binaerformat). */
  XLS("application/vnd.ms-excel", false, "xls"),
  /** Excel-Arbeitsmappe (Office Open XML). */
  XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, "xlsx"),
  /** PowerPoint-Praesentation (Binaerformat). */
  PPT("application/vnd.ms-powerpoint", false, "ppt"),
  /** PowerPoint-Praesentation (Office Open XML). */
  PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", false, "pptx"),
  /** Video im Format MP4. */
  MP4("video/mp4", false, "mp4");

  /** Reihenfolge der Typen im Text der Erlaubtliste (wie in der Fehlermeldung). */
  private static final List<AttachmentFileType> DISPLAY_ORDER =
      List.of(PDF, XLS, XLSX, DOC, DOCX, PPT, PPTX, TXT, CSV, PNG, JPEG, MP4);

  private static final String ALLOWED_EXTENSIONS_TEXT =
      DISPLAY_ORDER.stream()
          .flatMap(type -> type.extensions().stream())
          .collect(Collectors.joining(", "));

  private static final Map<String, AttachmentFileType> BY_EXTENSION = indexByExtension();

  private final String mimeType;
  private final boolean previewable;
  private final Set<String> extensions;

  AttachmentFileType(final String mimeType, final boolean previewable, final String... extensions) {
    this.mimeType = mimeType;
    this.previewable = previewable;
    this.extensions = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(extensions)));
  }

  /**
   * Endungen des Typs.
   *
   * @return unveraenderliche Menge, klein und ohne Punkt (z. B. {@code jpg}, {@code jpeg})
   */
  public Set<String> extensions() {
    return extensions;
  }

  /**
   * MIME-Typ, mit dem der Inhalt ausgeliefert wird.
   *
   * @return MIME-Typ, z. B. {@code application/pdf}
   */
  public String mimeType() {
    return mimeType;
  }

  /**
   * Ob fuer den Typ ein Vorschaubild erzeugt werden kann.
   *
   * @return {@code true} fuer PDF, PNG und JPEG; sonst {@code false}
   */
  public boolean previewable() {
    return previewable;
  }

  /**
   * Sucht den Typ zu einer Endung (Gross-/Kleinschreibung egal).
   *
   * @param extension Endung ohne Punkt; {@code null} und leer ergeben ein leeres Ergebnis
   * @return der Typ, oder leer, wenn die Endung nicht erlaubt ist
   */
  public static Optional<AttachmentFileType> fromExtension(final String extension) {
    if (extension == null || extension.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_EXTENSION.get(extension.toLowerCase(Locale.ROOT)));
  }

  /**
   * Text der Erlaubtliste fuer Fehlermeldungen.
   *
   * @return alle erlaubten Endungen, kommagetrennt: {@code pdf, xls, xlsx, doc, docx, ppt, pptx,
   *     txt, csv, png, jpg, jpeg, mp4}
   */
  public static String allowedExtensionsText() {
    return ALLOWED_EXTENSIONS_TEXT;
  }

  private static Map<String, AttachmentFileType> indexByExtension() {
    final Map<String, AttachmentFileType> index = new HashMap<>();
    for (final AttachmentFileType type : values()) {
      for (final String extension : type.extensions) {
        index.put(extension, type);
      }
    }
    return Collections.unmodifiableMap(index);
  }
}
