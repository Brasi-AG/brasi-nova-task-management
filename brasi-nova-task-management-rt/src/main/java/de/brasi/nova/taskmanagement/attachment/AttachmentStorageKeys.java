package de.brasi.nova.taskmanagement.attachment;

import java.util.Locale;

/**
 * Bildet die Speicherschluessel der Anhang-Dateien (Intake #152, Ordnerregel).
 *
 * <p>Ein Schluessel ist ein relativer Pfad mit {@code /} als Trenner, unabhaengig vom
 * Betriebssystem; aufgeloest wird er erst vom {@link AttachmentContentProvider} unter dem
 * konfigurierten Basispfad. Die Ordnerregel lautet {@code {TENANT_ID mindestens
 * dreistellig}/{Anhang-ID ohne die letzten 4 Stellen}/{Anhang-ID}.{endung}}; Anhang-IDs unter 10000
 * liegen im Ordner {@code 0}. Das Vorschaubild liegt daneben als {@code {Anhang-ID}_thumb.jpeg}.
 *
 * <p>Beispiel: Mandant 5, Anhang-ID 10000000095, Endung {@code pdf} ergibt {@code
 * 005/1000000/10000000095.pdf}.
 */
public final class AttachmentStorageKeys {

  /** Trenner der Schluesselteile; Schluessel sind immer relativ und verwenden {@code /}. */
  private static final char SEPARATOR = '/';

  /** Formatierung der Mandanten-ID: mindestens dreistellig, mit fuehrenden Nullen aufgefuellt. */
  private static final String TENANT_FORMAT = "%03d";

  /** Teiler fuer den Ordner der Anhang-ID: die letzten vier Dezimalstellen entfallen. */
  private static final long FOLDER_DIVISOR = 10_000L;

  /** Namensanhang des Vorschaubilds, das neben der Inhaltsdatei liegt. */
  private static final String THUMBNAIL_SUFFIX = "_thumb.jpeg";

  private AttachmentStorageKeys() {}

  /**
   * Bildet den Speicherschluessel der Inhaltsdatei eines Anhangs.
   *
   * @param tenantId Mandanten-ID ({@code TENANT_ID}, nicht negativ)
   * @param attachmentId interne Anhang-ID (nicht negativ)
   * @param extension Endung der Datei in Kleinbuchstaben ohne Punkt, z. B. {@code pdf}
   * @return relativer Schluessel, z. B. {@code 005/1000000/10000000095.pdf}
   * @throws IllegalArgumentException bei negativer ID oder einer Endung, die leer ist oder Punkt
   *     bzw. Pfadtrenner enthaelt
   */
  public static String contentKey(
      final long tenantId, final long attachmentId, final String extension) {
    if (tenantId < 0 || attachmentId < 0) {
      throw new IllegalArgumentException("Mandanten- und Anhang-ID dürfen nicht negativ sein.");
    }
    if (extension == null
        || extension.isBlank()
        || extension.indexOf('.') >= 0
        || extension.indexOf('/') >= 0
        || extension.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Ungültige Dateiendung für den Speicherschlüssel.");
    }
    return String.format(Locale.ROOT, TENANT_FORMAT, tenantId)
        + SEPARATOR
        + attachmentId / FOLDER_DIVISOR
        + SEPARATOR
        + attachmentId
        + '.'
        + extension;
  }

  /**
   * Bildet den Speicherschluessel des Vorschaubilds neben der Inhaltsdatei: die Endung des
   * Dateinamens wird durch {@code _thumb.jpeg} ersetzt.
   *
   * @param contentKey Schluessel der Inhaltsdatei, wie ihn {@link #contentKey(long, long, String)}
   *     liefert
   * @return Schluessel des Vorschaubilds, z. B. {@code 005/1000000/10000000095_thumb.jpeg}
   */
  public static String thumbnailKey(final String contentKey) {
    final int nameStart = contentKey.lastIndexOf(SEPARATOR) + 1;
    final int dot = contentKey.lastIndexOf('.');
    final String stem = dot > nameStart ? contentKey.substring(0, dot) : contentKey;
    return stem + THUMBNAIL_SUFFIX;
  }
}
