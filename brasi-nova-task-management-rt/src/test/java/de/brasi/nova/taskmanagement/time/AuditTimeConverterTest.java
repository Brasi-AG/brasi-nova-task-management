package de.brasi.nova.taskmanagement.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link AuditTimeConverter}.
 *
 * <p>Beweist korrekte DST-sensible Konvertierung von lokalen Audit-Zeitstempeln (ANGELEGT_AM)
 * in {@link Instant}-Werte sowie null-Sicherheit und Konfigurierbarkeit.
 */
class AuditTimeConverterTest {

  private final AuditTimeConverter converter = new AuditTimeConverter("Europe/Berlin");

  // ---- Sommer (CEST, +02:00) -----------------------------------------------------------------

  @Test
  @DisplayName("sommer_cest_plus2h: 2026-07-01 12:00 Berlin -> 2026-07-01T10:00:00Z")
  void sommer_cest_plus2h() {
    final LocalDateTime ldt = LocalDateTime.of(2026, 7, 1, 12, 0, 0);
    final Instant expected = Instant.parse("2026-07-01T10:00:00Z");
    assertThat(converter.toInstant(ldt)).isEqualTo(expected);
  }

  // ---- Winter (CET, +01:00) ------------------------------------------------------------------

  @Test
  @DisplayName("winter_cet_plus1h: 2026-01-15 12:00 Berlin -> 2026-01-15T11:00:00Z")
  void winter_cet_plus1h() {
    final LocalDateTime ldt = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
    final Instant expected = Instant.parse("2026-01-15T11:00:00Z");
    assertThat(converter.toInstant(ldt)).isEqualTo(expected);
  }

  // ---- null -> null --------------------------------------------------------------------------

  @Test
  @DisplayName("null_ergibt_null: kein Fallback auf Instant.now()")
  void null_ergibt_null() {
    assertThat(converter.toInstant(null)).isNull();
  }

  // ---- DST-Ueberlappung (Overlap): Berlin 2026-10-25 02:30 ist mehrdeutig -------------------

  @Test
  @DisplayName("dst_overlap_frueher_offset: Berlin 2026-10-25 02:30 -> frueher Offset (+02:00) = 00:30Z")
  void dst_overlap_frueher_offset() {
    final LocalDateTime ldt = LocalDateTime.of(2026, 10, 25, 2, 30);
    final Instant result = converter.toInstant(ldt);

    // Referenz: frueher Offset (CEST +02:00)
    final Instant expected = ldt.atZone(ZoneId.of("Europe/Berlin")).toInstant();
    assertThat(result).isEqualTo(expected);
    // Konkreter Nachweis: frueher Offset = 00:30Z
    assertThat(result).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
  }

  // ---- DST-Luecke (Gap): Berlin 2026-03-29 02:30 existiert nicht --------------------------

  @Test
  @DisplayName("dst_gap_kein_fehler_vorwaerts_verschoben: Berlin 2026-03-29 02:30 -> keine Exception, vorwaerts")
  void dst_gap_kein_fehler_vorwaerts_verschoben() {
    final LocalDateTime ldt = LocalDateTime.of(2026, 3, 29, 2, 30);

    // Keine Exception erwartet
    assertThatCode(() -> converter.toInstant(ldt)).doesNotThrowAnyException();

    final Instant result = converter.toInstant(ldt);
    // Referenz-Konvertierung (ZoneId-Standard: vorwaerts verschoben)
    final Instant expected = ldt.atZone(ZoneId.of("Europe/Berlin")).toInstant();
    assertThat(result).isEqualTo(expected);
  }

  // ---- Nicht-Standard-Zone -------------------------------------------------------------------

  @Test
  @DisplayName("utc_zone_kein_offset: 2026-07-01 12:00 UTC -> 2026-07-01T12:00:00Z")
  void utc_zone_kein_offset() {
    final AuditTimeConverter utcConverter = new AuditTimeConverter("UTC");
    final LocalDateTime ldt = LocalDateTime.of(2026, 7, 1, 12, 0, 0);
    assertThat(utcConverter.toInstant(ldt)).isEqualTo(Instant.parse("2026-07-01T12:00:00Z"));
  }

  // ---- zone()-Getter -------------------------------------------------------------------------

  @Test
  @DisplayName("zone_getter_liefert_konfigurierte_zone")
  void zone_getter_liefert_konfigurierte_zone() {
    assertThat(converter.zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
  }
}
