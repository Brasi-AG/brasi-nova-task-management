package de.brasi.nova.taskmanagement.attachment;

/**
 * Schwache ETags der Anhang-Auslieferung (Intake #152).
 *
 * <p>Der ETag des Inhalts ist die beim Hochladen gespeicherte Pruefsumme ({@code SHA256}) als
 * schwacher ETag {@code W/"<sha256>"}; das Vorschaubild traegt zusaetzlich die tatsaechliche Breite
 * des ausgelieferten Bilds ({@code W/"<sha256>-t<breite>"}), damit ein Vorschaubild anderer Breite
 * nicht als unveraendert gilt und ein bereits gespeichertes seinen ETag behaelt.
 * {@link #matches(String, String)} vergleicht schwach (RFC 9110, {@code If-None-Match}): mit oder
 * ohne {@code W/}, kommagetrennte Liste, {@code *} passt immer.
 */
final class AttachmentEtags {

  private static final String WEAK_PREFIX = "W/";
  private static final String QUOTE = "\"";
  private static final String THUMBNAIL_MARKER = "-t";
  private static final String ANY = "*";
  private static final String LIST_SEPARATOR = ",";

  private AttachmentEtags() {}

  /**
   * ETag des Dateiinhalts.
   *
   * @param sha256 gespeicherte Pruefsumme des Inhalts (Hex)
   * @return schwacher ETag, z. B. {@code W/"abc"}
   */
  static String contentEtag(final String sha256) {
    return WEAK_PREFIX + QUOTE + sha256 + QUOTE;
  }

  /**
   * ETag des Vorschaubilds.
   *
   * @param sha256 gespeicherte Pruefsumme des Inhalts (Hex)
   * @param width tatsaechliche Breite des ausgelieferten Vorschaubilds in Pixeln
   * @return schwacher ETag, z. B. {@code W/"abc-t600"}
   */
  static String thumbnailEtag(final String sha256, final int width) {
    return WEAK_PREFIX + QUOTE + sha256 + THUMBNAIL_MARKER + width + QUOTE;
  }

  /**
   * Prueft, ob ein {@code If-None-Match}-Header den ETag nennt (schwacher Vergleich).
   *
   * @param ifNoneMatch Wert des Headers; {@code null} oder leer passt nie
   * @param etag aktueller ETag der Ressource
   * @return {@code true}, wenn ein Eintrag der Liste dem ETag ohne {@code W/} gleicht oder {@code
   *     *} ist
   */
  static boolean matches(final String ifNoneMatch, final String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null) {
      return false;
    }
    final String expected = opaqueTag(etag);
    for (final String candidate : ifNoneMatch.split(LIST_SEPARATOR)) {
      final String trimmed = candidate.trim();
      if (ANY.equals(trimmed)) {
        return true;
      }
      if (!trimmed.isEmpty() && opaqueTag(trimmed).equals(expected)) {
        return true;
      }
    }
    return false;
  }

  /** Entfernt das Schwach-Kennzeichen {@code W/}; die Anfuehrungszeichen bleiben Teil des Werts. */
  private static String opaqueTag(final String tag) {
    final String trimmed = tag.trim();
    return trimmed.startsWith(WEAK_PREFIX) ? trimmed.substring(WEAK_PREFIX.length()) : trimmed;
  }
}
