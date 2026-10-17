package de.brasi.nova.taskmanagement.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Konvertiert Nova/KSF-Audit-Zeitstempel (ANGELEGT_AM etc.) vom lokalen Wand-Uhr-Format
 * in den korrekten {@link Instant}.
 *
 * <p>Nova/KSF speichert Audit-Zeitstempel als lokale Wand-Uhr in der Deployment-Zone
 * ({@code KSF NovaEntity.createdAt = LocalDateTime.now()}, DB-Default {@code getdate()}).
 * Dieser Converter dekodiert sie zum korrekten {@code Instant}.
 *
 * <p><b>Invariante:</b> SQL-Server-OS-Zone == JVM-Zone == konfigurierte
 * {@code app.time.persistence-zone}. DST wird von {@link ZoneId} aufgeloest
 * (Gap -> vorwaerts verschoben; Overlap -> frueher Offset). {@code null} -> {@code null}
 * (NIE eine Erstellungszeit erfinden).
 *
 * <p>Die Deployment-Zeitzone wird ueber {@code app.time.persistence-zone} konfiguriert
 * (Default: {@code Europe/Berlin}); muss der OS-Zone von JVM UND SQL-Server entsprechen.
 */
@Component
public class AuditTimeConverter {

  private final ZoneId persistenceZone;

  /** Konstruktor-Injektion der Persistenz-Zone. */
  public AuditTimeConverter(
      @Value("${app.time.persistence-zone:Europe/Berlin}") final String persistenceZoneId) {
    this.persistenceZone = ZoneId.of(persistenceZoneId);
  }

  /**
   * Konvertiert einen lokalen Audit-Zeitstempel in einen {@link Instant}.
   *
   * @param ldt lokaler Zeitstempel aus der Datenbank (ANGELEGT_AM etc.); darf {@code null} sein
   * @return entsprechender {@code Instant} in der konfigurierten Persistenz-Zone,
   *         oder {@code null} wenn {@code ldt == null}
   */
  public Instant toInstant(final LocalDateTime ldt) {
    return ldt == null ? null : ldt.atZone(persistenceZone).toInstant();
  }

  /**
   * Gibt die konfigurierte Persistenz-Zone zurueck (nuetzlich fuer Tests und Diagnostik).
   *
   * @return konfigurierte {@link ZoneId}
   */
  public ZoneId zone() {
    return persistenceZone;
  }
}
