package de.brasi.nova.taskmanagement.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer die laengen-/surrogat-sichere Text-Kappung in {@link DueReminderEmitter}.
 */
class DueReminderEmitterTest {

  @Test
  @DisplayName("truncate_null_und_kurz_bleiben_unveraendert")
  void truncate_null_und_kurz_bleiben_unveraendert() {
    assertThat(DueReminderEmitter.truncate(null, 500)).isNull();
    assertThat(DueReminderEmitter.truncate("kurz", 500)).isEqualTo("kurz");
    assertThat(DueReminderEmitter.truncate("genau", 5)).isEqualTo("genau");
  }

  @Test
  @DisplayName("truncate_kappt_ueberlange_strings_auf_maxLength")
  void truncate_kappt_ueberlange_strings_auf_maxLength() {
    final String value = "a".repeat(600);
    final String result = DueReminderEmitter.truncate(value, 500);
    assertThat(result).hasSize(500);
  }

  @Test
  @DisplayName("truncate_zerschneidet_kein_surrogatpaar")
  void truncate_zerschneidet_kein_surrogatpaar() {
    // Ein Emoji (U+1F600) ist ein Surrogatpaar (2 UTF-16-Code-Units). Es sitzt genau auf der
    // Grenze: 499 Fueller-Zeichen + Emoji -> Grenze bei 500 wuerde das Paar (499/500) trennen.
    final String emoji = "😀"; // GRINNING FACE
    final String value = "a".repeat(499) + emoji + "b".repeat(50);

    final String result = DueReminderEmitter.truncate(value, 500);

    // Das halbe Surrogat darf NICHT uebrig bleiben -> auf 499 gekappt, kein ungepaartes Surrogat.
    assertThat(result).hasSize(499);
    assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1)))
        .as("kein ungepaartes High-Surrogate am Ende")
        .isFalse();
    assertThat(result.codePoints().anyMatch(cp -> cp == 0xD83D || cp == 0xDE00))
        .as("kein halbes Surrogat im Ergebnis")
        .isFalse();
  }

  @Test
  @DisplayName("truncate_behaelt_vollstaendiges_surrogatpaar_vor_der_grenze")
  void truncate_behaelt_vollstaendiges_surrogatpaar_vor_der_grenze() {
    // Emoji endet bei Index 499/500 nicht auf der Grenze -> vollstaendiges Paar bleibt erhalten.
    final String emoji = "😀";
    final String value = "a".repeat(498) + emoji + "b".repeat(50);

    final String result = DueReminderEmitter.truncate(value, 500);

    assertThat(result).hasSize(500);
    assertThat(result).endsWith(emoji);
  }
}
