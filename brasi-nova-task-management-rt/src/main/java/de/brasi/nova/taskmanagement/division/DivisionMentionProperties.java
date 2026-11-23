package de.brasi.nova.taskmanagement.division;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration der Abteilungserwaehnung im Kommentar (Intake #190), gebunden an {@code
 * task-management.division-mentions}. Die Vorgabe steht in {@code application.properties}.
 *
 * @param maxPersons hoechste Zahl verschiedener Personen ({@code MITARBEITER_REF}), die die
 *     erwaehnten Abteilungen eines Vorgangs zusammen umfassen duerfen; mindestens 1, Vorgabe 200
 */
@ConfigurationProperties(DivisionMentionProperties.PREFIX)
public record DivisionMentionProperties(@DefaultValue("200") int maxPersons) {

  /** Praefix der Konfigurationsschluessel. */
  public static final String PREFIX = "task-management.division-mentions";

  /**
   * Prueft die Grenze beim Binden.
   *
   * @throws IllegalStateException wenn {@code maxPersons} kleiner als 1 ist
   */
  public DivisionMentionProperties {
    if (maxPersons < 1) {
      throw new IllegalStateException(
          PREFIX + ".max-persons muss mindestens 1 sein, ist aber " + maxPersons + ".");
    }
  }
}
