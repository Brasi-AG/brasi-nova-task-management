package de.brasi.nova.taskmanagement.inbox;

import io.keel.framework.api.validation.EntityValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Validierter Suchbegriff der Archivsuche ({@code GET /api/v1/tasks/archive/search}, Intake #149).
 *
 * <p>Der Begriff wird getrimmt; leer oder nur Leerzeichen sowie mehr als {@link #MAX_LENGTH}
 * Zeichen (nach dem Trimmen) sind ein Eingabefehler (HTTP 400). Das LIKE-Muster ist klein
 * geschrieben (die Query vergleicht gegen {@code LOWER(...)}, damit die Suche unabhaengig von der
 * Datenbank-Collation case-insensitiv ist) und escaped die LIKE-Sonderzeichen {@code %}, {@code _}
 * und {@code [} sowie das Escape-Zeichen {@value #ESCAPE} selbst; die Query traegt dazu {@code
 * ESCAPE '!'}. So wird jeder Begriff woertlich als Teilstring gesucht.
 *
 * @param value getrimmter Suchbegriff
 * @param likePattern LIKE-Muster {@code %<escaped, klein>%}
 */
record ArchiveSearchTerm(String value, String likePattern) {

  /** Maximale Laenge des getrimmten Suchbegriffs. */
  static final int MAX_LENGTH = 200;

  /** Escape-Zeichen des LIKE-Musters (muss zum {@code ESCAPE '!'} der Query passen). */
  static final char ESCAPE = '!';

  /**
   * Validiert und normalisiert den rohen Suchbegriff.
   *
   * @param raw Suchbegriff aus dem Request (darf null sein)
   * @return validierter Suchbegriff mit LIKE-Muster
   * @throws EntityValidationException wenn der Begriff fehlt, leer ist oder zu lang ist (HTTP 400)
   */
  static ArchiveSearchTerm of(final String raw) {
    final String trimmed = raw == null ? "" : raw.strip();
    if (trimmed.isEmpty()) {
      throw new EntityValidationException("Der Parameter 'query' darf nicht leer sein.");
    }
    if (trimmed.length() > MAX_LENGTH) {
      throw new EntityValidationException(
          "Der Parameter 'query' darf hoechstens " + MAX_LENGTH + " Zeichen lang sein.");
    }
    return new ArchiveSearchTerm(trimmed, "%" + escape(trimmed.toLowerCase(Locale.ROOT)) + "%");
  }

  /**
   * Scope-Token des Such-Cursors: die ersten 16 Hex-Zeichen von SHA-256 ueber den aufgeloesten
   * Filter und den normalisierten (getrimmten, klein geschriebenen) Suchbegriff. Gleiche Suche
   * (auch mit anderer Gross-/Kleinschreibung oder Leerzeichen aussen) ergibt denselben Token, ein
   * anderer Begriff oder Filter einen anderen; der Service lehnt dann den Cursor mit 400 ab.
   *
   * @param resolvedFilter aufgeloester Filter ({@code all|done|left})
   * @return Scope-Token (16 Hex-Zeichen)
   */
  String cursorScope(final String resolvedFilter) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (resolvedFilter + '\u001f' + value.toLowerCase(Locale.ROOT))
                      .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 nicht verfuegbar.", e);
    }
  }

  /** Stellt Escape-Zeichen, {@code %}, {@code _} und {@code [} das Escape-Zeichen voran. */
  private static String escape(final String value) {
    final StringBuilder escaped = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c == ESCAPE || c == '%' || c == '_' || c == '[') {
        escaped.append(ESCAPE);
      }
      escaped.append(c);
    }
    return escaped.toString();
  }
}
