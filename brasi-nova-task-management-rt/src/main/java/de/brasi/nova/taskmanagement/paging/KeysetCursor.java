package de.brasi.nova.taskmanagement.paging;

import io.keel.framework.api.validation.EntityValidationException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Opaker, positionsbasierter Cursor-Codec fuer Keyset-Paginierung.
 *
 * <p><b>Kein HMAC / keine Signatur:</b> Der Cursor traegt KEINE Autorisierung. Jede Query ist
 * fest {@code RECIPIENT_REF = :actor} bzw. {@code TENANT_ID = :actorTenant} gescoped; ein
 * manipulierter, aber wohlgeformter Cursor verschiebt nur die Position INNERHALB der eigenen
 * Liste des Aufrufers, niemals ueber Benutzer-/Tenant-Grenzen. Malformed -&gt; 400.
 *
 * <p>Encoding: base64url-Codec ohne Padding ueber eine mit {@code } (ASCII Unit-Separator)
 * verbundene Token-Liste.
 */
public final class KeysetCursor {

  /** ASCII Unit-Separator (0x1F) als Trennzeichen innerhalb der Token-Liste. */
  private static final char SEPARATOR = '';

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private KeysetCursor() {}

  /**
   * Enkodiert eine Token-Liste in einen opaken base64url-Cursor ohne Padding.
   *
   * @param tokens die Positions-Token (mindestens eines erwartet)
   * @return opaker Cursor-String
   */
  public static String encode(final List<String> tokens) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      if (i > 0) {
        sb.append(SEPARATOR);
      }
      sb.append(tokens.get(i));
    }
    return ENCODER.encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Dekodiert einen opaken Cursor in die Token-Liste.
   *
   * <p>Wirft {@link EntityValidationException} ("Ungueltiger cursor.") wenn:
   * <ul>
   *   <li>Der String kein gueltiges base64url ist.</li>
   *   <li>Die Anzahl der enthaltenen Token nicht mit {@code expectedTokenCount} uebereinstimmt.</li>
   * </ul>
   *
   * <p>Null/Blank wird NICHT hier behandelt; der Aufrufer prueft auf First-Page separat
   * und ruft {@code decode} nur fuer einen tatsaechlich gesetzten Cursor auf.
   *
   * @param cursor             der opake Cursor-String
   * @param expectedTokenCount erwartete Anzahl Tokens
   * @return dekodierte Token-Liste
   * @throws EntityValidationException bei malformed Cursor
   */
  public static List<String> decode(final String cursor, final int expectedTokenCount) {
    final byte[] bytes;
    try {
      bytes = DECODER.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new EntityValidationException("Ungueltiger cursor.");
    }

    final String joined = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    final String[] parts = joined.split(String.valueOf(SEPARATOR), -1);

    if (parts.length != expectedTokenCount) {
      throw new EntityValidationException("Ungueltiger cursor.");
    }

    return Arrays.asList(parts);
  }
}
