package de.brasi.nova.taskmanagement.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.brasi.nova.taskmanagement.paging.KeysetCursor;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link KeysetCursor}: Encode/Decode-Roundtrip und Fehlerbehandlung.
 */
class KeysetCursorTest {

  // ---- Roundtrip ------------------------------------------------------------------

  @Test
  @DisplayName("encode_decode_roundtrip_zweitokens: zwei Token enkodiert -> dekodiert korrekt")
  void encode_decode_roundtrip_zweitokens() {
    final List<String> tokens = List.of("2026-10-17T10:00:00", "abc-def-uuid-1234");
    final String cursor = KeysetCursor.encode(tokens);

    assertThat(cursor).as("Cursor darf nicht leer sein").isNotBlank();
    // Base64url ohne Padding: darf kein '=' enthalten
    assertThat(cursor).as("Kein Padding in base64url").doesNotContain("=");

    final List<String> decoded = KeysetCursor.decode(cursor, 2);
    assertThat(decoded).containsExactly("2026-10-17T10:00:00", "abc-def-uuid-1234");
  }

  @Test
  @DisplayName("encode_decode_eintoken: ein Token ohne Unit-Separator enkodiert -> dekodiert korrekt")
  void encode_decode_eintoken() {
    final List<String> tokens = List.of("nur-ein-token");
    final String cursor = KeysetCursor.encode(tokens);
    final List<String> decoded = KeysetCursor.decode(cursor, 1);
    assertThat(decoded).containsExactly("nur-ein-token");
  }

  @Test
  @DisplayName("encode_decode_dreitokens: drei Token enkodiert -> dekodiert korrekt")
  void encode_decode_dreitokens() {
    final List<String> tokens = List.of("a", "b", "c");
    final String cursor = KeysetCursor.encode(tokens);
    final List<String> decoded = KeysetCursor.decode(cursor, 3);
    assertThat(decoded).containsExactly("a", "b", "c");
  }

  // ---- Manipulation: nicht-base64 -> 400 ------------------------------------------

  @Test
  @DisplayName("decode_nicht_base64_wirft_EntityValidationException")
  void decode_nicht_base64_wirft_EntityValidationException() {
    assertThatThrownBy(() -> KeysetCursor.decode("!!!nicht-base64!!!", 2))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("cursor");
  }

  // ---- Manipulation: falsche Token-Anzahl -> 400 ----------------------------------

  @Test
  @DisplayName("decode_falsche_token_anzahl_wirft_EntityValidationException")
  void decode_falsche_token_anzahl_wirft_EntityValidationException() {
    // Encode mit 1 Token, decode erwartet 2 -> soll 400 werfen
    final String cursor = KeysetCursor.encode(List.of("nur-einer"));
    assertThatThrownBy(() -> KeysetCursor.decode(cursor, 2))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("cursor");
  }

  @Test
  @DisplayName("decode_zu_viele_tokens_wirft_EntityValidationException")
  void decode_zu_viele_tokens_wirft_EntityValidationException() {
    final String cursor = KeysetCursor.encode(List.of("a", "b", "c"));
    assertThatThrownBy(() -> KeysetCursor.decode(cursor, 2))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("cursor");
  }

  // ---- Leer/Blank -> Aufrufer behandelt First-Page separat, kein Wurf hier --------

  @Test
  @DisplayName("decode_mit_leerem_token_innerhalb_der_liste_bleibt_erhalten")
  void decode_mit_leerem_token_innerhalb_der_liste_bleibt_erhalten() {
    // Ein Token das leer ist, ist ein valider Wert in der Liste (kein Trennzeichen-Konflikt)
    final List<String> tokens = List.of("value1", "");
    final String cursor = KeysetCursor.encode(tokens);
    final List<String> decoded = KeysetCursor.decode(cursor, 2);
    assertThat(decoded).containsExactly("value1", "");
  }

  @Test
  @DisplayName("encode_erzeugt_gueltiges_base64url_ohne_padding")
  void encode_erzeugt_gueltiges_base64url_ohne_padding() {
    final String cursor = KeysetCursor.encode(List.of("ts", "id"));
    // Base64url-Zeichensatz: A-Z, a-z, 0-9, -, _
    assertThat(cursor).matches("[A-Za-z0-9_\\-]+");
  }
}
