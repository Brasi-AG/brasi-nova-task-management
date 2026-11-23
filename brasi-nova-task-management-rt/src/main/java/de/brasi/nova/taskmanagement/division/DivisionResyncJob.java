package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.repository.DivisionResyncRow;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskEventReceiptQueryRepository;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Selbstheilung des Abteilungs-Abgleichs (Intake #190), nur bei {@code
 * task-management.division-sync.enabled=true}: laedt nach {@code resync.cron} den aktuellen Stand
 * jeder bekannten Abteilung aller Mandanten nach (auch deaktivierte) und loescht Quittungen, die
 * aelter als {@code receipt-retention} sind.
 *
 * <p>Erfasst werden Abteilungen mit Kopfzeile in {@code TASK_ABTEILUNG} (aktiver Stand oder
 * Grabstein nach 404) und Abteilungen, deren Abrufe seit dem ersten Signal alle scheiterten und die
 * deshalb nur eine {@code DEAD}-Quittung ohne Kopfzeile haben ({@code
 * TaskDivisionQueryRepository#findAllForResync}); der Abruf legt dann die Kopfzeile an. Grenze: Die
 * {@code DEAD}-Spur verfaellt nach {@code receipt-retention}; eine bis dahin nicht geheilte
 * Abteilung ohne Kopfzeile erfasst der Lauf danach nicht mehr, dann hilft nur ein neues Signal von
 * application oder dessen Start-Resync. Im Lauf kommt der Resync vor der Aufbewahrung.
 *
 * <p>Gelesen und geloescht wird mandantenuebergreifend unter {@code runAsSystem}; jeder Abruf
 * laeuft ueber {@link DivisionSignalProcessor#resync} unter seinem Mandanten. Ein Fehler bei einer
 * Abteilung wird als WARN gemeldet und unterbricht den Lauf nicht.
 */
@Component
@ConditionalOnProperty(
    prefix = DivisionSyncProperties.PREFIX,
    name = "enabled",
    havingValue = "true")
public class DivisionResyncJob {

  private static final Logger log = LoggerFactory.getLogger(DivisionResyncJob.class);

  private final TaskDivisionQueryRepository divisionRepository;
  private final TaskEventReceiptQueryRepository receiptRepository;
  private final DivisionSignalProcessor processor;
  private final DivisionSyncProperties properties;
  private final Clock clock;

  /**
   * Spring-Konstruktor: Uhr der Persistenz-Zone.
   *
   * @param divisionRepository Repository der Abteilungskoepfe
   * @param receiptRepository Repository der Quittungen
   * @param processor Nachladen je Abteilung
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param persistenceZoneId {@code app.time.persistence-zone}
   */
  @Autowired
  public DivisionResyncJob(
      final TaskDivisionQueryRepository divisionRepository,
      final TaskEventReceiptQueryRepository receiptRepository,
      final DivisionSignalProcessor processor,
      final DivisionSyncProperties properties,
      @Value("${app.time.persistence-zone:Europe/Berlin}") final String persistenceZoneId) {
    this(
        divisionRepository,
        receiptRepository,
        processor,
        properties,
        Clock.system(ZoneId.of(persistenceZoneId)));
  }

  /**
   * Konstruktor fuer Tests mit eigener Uhr.
   *
   * @param divisionRepository Repository der Abteilungskoepfe
   * @param receiptRepository Repository der Quittungen
   * @param processor Nachladen je Abteilung
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param clock Uhr in der Persistenz-Zone (Stichtag der Aufbewahrung)
   */
  DivisionResyncJob(
      final TaskDivisionQueryRepository divisionRepository,
      final TaskEventReceiptQueryRepository receiptRepository,
      final DivisionSignalProcessor processor,
      final DivisionSyncProperties properties,
      final Clock clock) {
    this.divisionRepository = divisionRepository;
    this.receiptRepository = receiptRepository;
    this.processor = processor;
    this.properties = properties;
    this.clock = clock;
  }

  /** Geplanter Lauf: erst Resync, dann Aufbewahrung. */
  @Scheduled(cron = "${task-management.division-sync.resync.cron}")
  public void run() {
    resyncAll();
    purgeReceipts();
  }

  /**
   * Laedt den aktuellen Stand aller bekannten Abteilungen nach.
   *
   * @return Zahl der erfolgreich nachgeladenen Abteilungen
   */
  public int resyncAll() {
    final List<DivisionResyncRow> rows =
        TenantContextHolder.runAsSystem(divisionRepository::findAllForResync);
    int succeeded = 0;
    for (final DivisionResyncRow row : rows) {
      try {
        processor.resync(row.tenantId(), row.tenantUuid(), row.divisionRef());
        succeeded++;
      } catch (final RuntimeException e) {
        log.warn(
            "Resync Abteilung {} (Mandant {}) fehlgeschlagen, weiter mit der naechsten. Grund: {}",
            row.divisionRef(),
            row.tenantUuid(),
            e.getMessage());
      }
    }
    log.info(
        "Resync der Abteilungen: {} von {} nachgeladen, {} fehlgeschlagen.",
        succeeded,
        rows.size(),
        rows.size() - succeeded);
    return succeeded;
  }

  /**
   * Loescht Quittungen aller Mandanten, die aelter als {@code receipt-retention} sind.
   *
   * <p>Ohne umgebende Transaktion: das einzelne {@code DELETE} laeuft im Autocommit, ein
   * modifizierendes {@code @NovaQuery} verlangt in KSF keine Transaktion. Belegt durch {@code
   * TaskEventReceiptStoreIT#i190_purge_receipts_without_surrounding_transaction}.
   *
   * @return Zahl der geloeschten Quittungen
   */
  public int purgeReceipts() {
    final Duration retention = properties.receiptRetention();
    if (retention == null || retention.isNegative() || retention.isZero()) {
      log.warn(
          "Aufbewahrung der Quittungen uebersprungen: {}.receipt-retention fehlt oder ist nicht"
              + " positiv.",
          DivisionSyncProperties.PREFIX);
      return 0;
    }
    final LocalDateTime cutoff = LocalDateTime.now(clock).minus(retention);
    final int deleted =
        TenantContextHolder.runAsSystem(() -> receiptRepository.deleteOlderThan(cutoff));
    log.info("Aufbewahrung: {} Quittungen vor {} geloescht.", deleted, cutoff);
    return deleted;
  }
}
