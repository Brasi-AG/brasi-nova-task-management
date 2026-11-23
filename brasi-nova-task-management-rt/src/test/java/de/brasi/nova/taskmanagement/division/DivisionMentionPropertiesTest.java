package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Prueft {@link DivisionMentionProperties} (Intake #190, Task 8) ohne Spring-Kontext: die Grenze
 * {@code max-persons} gilt nur im Bereich 1 bis 1000, ein Wert ausserhalb bricht schon beim Binden
 * ab (Muster {@code DivisionSyncPropertiesTest}).
 *
 * <p>Grund der Obergrenze: Die Personen der Abteilungen und bis zu 100 einzeln erwaehnte Personen
 * laufen in dieselbe {@code IN}-Liste von {@code findActiveByUuids}; SQL Server erlaubt je
 * Anweisung hoechstens 2100 Parameter (Lesson {@code ksf-novaquery-pitfalls} Punkt 10).
 */
class DivisionMentionPropertiesTest {

  private static final String KEY = DivisionMentionProperties.PREFIX + ".max-persons";

  @Test
  @DisplayName("Grenzen 1 und 1000 sind erlaubt, die Vorgabe 200 liegt dazwischen")
  void i190_max_persons_accepts_the_range() {
    for (final int valid : new int[] {1, 200, 1000}) {
      assertThatCode(() -> new DivisionMentionProperties(valid))
          .as("max-persons %d", valid)
          .doesNotThrowAnyException();
    }
    assertThat(new DivisionMentionProperties(1000).maxPersons())
        .isEqualTo(DivisionMentionProperties.MAX_PERSONS_LIMIT);
  }

  @Test
  @DisplayName("0, negativ und mehr als 1000 -> IllegalStateException mit Schluessel und Bereich")
  void i190_max_persons_outside_the_range_fails() {
    for (final int invalid : new int[] {0, -1, 1001, 2000, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> new DivisionMentionProperties(invalid))
          .as("max-persons %d", invalid)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(KEY + " muss zwischen 1 und 1000 liegen, ist aber " + invalid + ".");
    }
  }

  @Test
  @DisplayName("Vorgabe 200 aus application.properties; ein Wert ueber 1000 bricht das Binden ab")
  void i190_binding_rejects_values_above_the_limit() throws IOException {
    final Properties raw =
        PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

    final DivisionMentionProperties bound =
        new Binder(new MapConfigurationPropertySource(raw))
            .bind(DivisionMentionProperties.PREFIX, DivisionMentionProperties.class)
            .get();
    assertThat(bound.maxPersons()).isEqualTo(200);

    final Properties tooLarge = new Properties();
    tooLarge.putAll(raw);
    tooLarge.setProperty(KEY, "2000");
    assertThatThrownBy(
            () ->
                new Binder(new MapConfigurationPropertySource(tooLarge))
                    .bind(DivisionMentionProperties.PREFIX, DivisionMentionProperties.class))
        .isInstanceOf(BindException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(KEY + " muss zwischen 1 und 1000 liegen, ist aber 2000.");
  }
}
