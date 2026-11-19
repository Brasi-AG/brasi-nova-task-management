package de.brasi.nova.taskmanagement.attachment;

import io.keel.framework.api.validation.EntityValidationException;
import java.util.Locale;

/**
 * Bereinigt Dateinamen hochgeladener Anhaenge (Intake #152) und ermittelt ihre Endung.
 *
 * <p>Der vom Client gelieferte Name wird nie ungeprueft gespeichert oder in einen Header
 * uebernommen: nur der letzte Pfadanteil (nach {@code /} oder {@code \}), ohne Steuerzeichen (z. B.
 * NUL, CR, LF) und ohne Bidi- und unsichtbare Formatzeichen (siehe {@link #sanitize(String)}),
 * getrimmt und auf hoechstens {@value #MAX_LENGTH} Zeichen gekuerzt, wobei die Endung erhalten
 * bleibt.
 */
public final class AttachmentFileNames {

  /** Hoechste Laenge eines gespeicherten Dateinamens in Zeichen. */
  public static final int MAX_LENGTH = 255;

  private static final String MISSING_NAME_MESSAGE = "Der Dateiname fehlt.";

  private AttachmentFileNames() {}

  /**
   * Bereinigt einen Dateinamen: letzter Pfadanteil nach {@code /} oder {@code \}, Steuerzeichen
   * (ISO-Control, z. B. NUL, CR, LF) sowie Bidi- und unsichtbare Formatzeichen entfernt, getrimmt,
   * auf hoechstens {@value #MAX_LENGTH} Zeichen gekuerzt (Endung bleibt erhalten).
   *
   * <p>Entfernt werden zusaetzlich zu den ISO-Steuerzeichen: U+200E und U+200F (Links-nach-rechts-
   * und Rechts-nach-links-Markierung), U+202A bis U+202E (Bidi-Einbettungen und -Ueberschreibungen,
   * u. a. RTL-Override), U+2066 bis U+2069 (Bidi-Isolierungen), U+061C (arabische
   * Buchstabenmarkierung), U+200B bis U+200D (Zero-Width-Zeichen) und U+FEFF (Byte-Order-Mark bzw.
   * Zero Width No-Break Space). So kann ein Name wie {@code rechnung<RLO>fdp.exe} nicht als {@code
   * rechnungexe.pdf} erscheinen; die Endung wird nach der Bereinigung bestimmt ({@link
   * #extensionOf(String)}) und gegen die Erlaubtliste geprueft.
   *
   * @param originalName Dateiname, wie ihn der Client geliefert hat; darf {@code null} sein
   * @return bereinigter, nicht leerer Dateiname
   * @throws EntityValidationException wenn nach der Bereinigung nichts bleibt (HTTP 400)
   */
  public static String sanitize(final String originalName) {
    if (originalName == null) {
      throw new EntityValidationException(MISSING_NAME_MESSAGE);
    }
    final int lastSeparator =
        Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
    final String lastPart = originalName.substring(lastSeparator + 1);
    final String name = removeUnwantedCharacters(lastPart).strip();
    if (name.isEmpty()) {
      throw new EntityValidationException(MISSING_NAME_MESSAGE);
    }
    return limitLength(name);
  }

  /**
   * Ermittelt die Endung eines bereinigten Dateinamens.
   *
   * @param sanitizedName Name, wie ihn {@link #sanitize(String)} liefert
   * @return Endung in Kleinbuchstaben ohne Punkt (bei {@code archiv.tar.GZ} also {@code gz}); leer
   *     ohne Endung
   */
  public static String extensionOf(final String sanitizedName) {
    if (sanitizedName == null) {
      return "";
    }
    final int dot = sanitizedName.lastIndexOf('.');
    if (dot < 0) {
      return "";
    }
    return sanitizedName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String removeUnwantedCharacters(final String value) {
    final StringBuilder cleaned = new StringBuilder(value.length());
    value
        .codePoints()
        .filter(codePoint -> !isRemovable(codePoint))
        .forEach(cleaned::appendCodePoint);
    return cleaned.toString();
  }

  /** ISO-Steuerzeichen sowie Bidi- und unsichtbare Formatzeichen (siehe {@link #sanitize}). */
  private static boolean isRemovable(final int codePoint) {
    return Character.isISOControl(codePoint)
        || codePoint == 0x061C
        || (codePoint >= 0x200B && codePoint <= 0x200F)
        || (codePoint >= 0x202A && codePoint <= 0x202E)
        || (codePoint >= 0x2066 && codePoint <= 0x2069)
        || codePoint == 0xFEFF;
  }

  /** Kuerzt auf {@link #MAX_LENGTH} Zeichen und erhaelt dabei die Endung (mit Punkt). */
  private static String limitLength(final String name) {
    if (name.length() <= MAX_LENGTH) {
      return name;
    }
    final int dot = name.lastIndexOf('.');
    final String extension = dot >= 0 ? name.substring(dot) : "";
    if (extension.length() >= MAX_LENGTH) {
      // Endung selbst zu lang (keine sinnvolle Endung): einfach vorne behalten
      return name.substring(0, cutIndex(name, MAX_LENGTH));
    }
    final String base = dot >= 0 ? name.substring(0, dot) : name;
    return base.substring(0, cutIndex(base, MAX_LENGTH - extension.length())) + extension;
  }

  /** Schnittpunkt, der kein Ersatzzeichenpaar (Surrogate) zerreisst. */
  private static int cutIndex(final String value, final int maxIndex) {
    if (maxIndex > 0 && Character.isHighSurrogate(value.charAt(maxIndex - 1))) {
      return maxIndex - 1;
    }
    return maxIndex;
  }
}
