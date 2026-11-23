package de.brasi.nova.taskmanagement.division;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration der Abteilungserwaehnung im Kommentar (Intake #190), gebunden an {@code
 * task-management.division-mentions}. Die Vorgabe steht in {@code application.properties}.
 *
 * @param maxPersons hoechste Zahl verschiedener Personen ({@code MITARBEITER_REF}), die die
 *     erwaehnten Abteilungen eines Vorgangs zusammen umfassen duerfen; 1 bis {@value
 *     #MAX_PERSONS_LIMIT}, Vorgabe 200
 */
@ConfigurationProperties(DivisionMentionProperties.PREFIX)
public record DivisionMentionProperties(@DefaultValue("200") int maxPersons) {

  /** Praefix der Konfigurationsschluessel. */
  public static final String PREFIX = "task-management.division-mentions";

  /**
   * Hoechster erlaubter Wert von {@code maxPersons}. Die Personen der Abteilungen und bis zu 100
   * einzeln erwaehnte Personen laufen in dieselbe {@code IN}-Liste ({@code findActiveByUuids}); mit
   * dieser Obergrenze bleibt sie unter den 2100 Parametern, die SQL Server je Anweisung erlaubt.
   */
  public static final int MAX_PERSONS_LIMIT = 1000;

  /**
   * Prueft die Grenze beim Binden; ein Wert ausserhalb des Bereichs bricht den Start ab.
   *
   * @throws IllegalStateException wenn {@code maxPersons} kleiner als 1 oder groesser als {@value
   *     #MAX_PERSONS_LIMIT} ist
   */
  public DivisionMentionProperties {
    if (maxPersons < 1 || maxPersons > MAX_PERSONS_LIMIT) {
      throw new IllegalStateException(
          PREFIX
              + ".max-persons muss zwischen 1 und "
              + MAX_PERSONS_LIMIT
              + " liegen, ist aber "
              + maxPersons
              + ".");
    }
  }
}
