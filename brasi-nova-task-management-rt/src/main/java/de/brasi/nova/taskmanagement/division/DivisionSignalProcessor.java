package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.refentity.FirmReadRepository;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verarbeitet ein geprueftes Abteilungs-Signal (Intake #190): aktuellen Stand bei application laden
 * und mit Quittung in die Projektion uebernehmen.
 *
 * <p>Ablauf (Ruling 2026-11-22, immer {@code self}):
 *
 * <ol>
 *   <li>Mandant ueber {@code PROD_FIRMA.EXTERNALID} aufloesen ({@code runAsSystem}); unbekannt:
 *       INFO und verwerfen. Alles Weitere unter {@code runWithTenant}.
 *   <li>Quittung vorhanden ({@code PROCESSED} oder {@code DEAD}): nichts tun.
 *   <li>Lokale revision gleich Signal-revision: nur Quittung {@code PROCESSED}, kein HTTP-Aufruf.
 *   <li>Abrufstart (Wanduhr der Persistenz-Zone) vor dem HTTP-Aufruf nehmen, {@code fetchCurrent}.
 *   <li>Eine Transaktion: Quittung, dann Projektion ({@link TaskEventReceiptStore#executeOnce} mit
 *       {@link DivisionProjectionService#apply}). Verliert der Claim, wird die Transaktion
 *       rollback-only.
 * </ol>
 *
 * <p>Laufzeitfehler (HTTP 401/403/5xx, Zeitueberschreitung, Datenbank): unterhalb von {@code
 * max-delivery-attempts} (mindestens 1) WARN und erneut werfen, damit der Broker die Nachricht neu
 * zustellt; am Limit ERROR, Quittung {@code DEAD} in eigener Transaktion, kein Wurf (Nachricht wird
 * bestaetigt).
 *
 * <p>Der Nachtlauf des Resync ({@link DivisionResyncJob}) heilt nur Abteilungen mit Kopfzeile in
 * {@code TASK_ABTEILUNG}, also mit aktivem Stand oder Grabstein. Eine Abteilung, deren Abrufe alle
 * scheiterten und die deshalb nur eine {@code DEAD}-Quittung und keine Kopfzeile hat, sieht der
 * Resync nicht; sie heilt erst ein neues Signal von application oder dessen Start-Resync.
 */
@Service
public class DivisionSignalProcessor {

  private static final Logger log = LoggerFactory.getLogger(DivisionSignalProcessor.class);

  private final DivisionMembersClient client;
  private final DivisionProjectionService projection;
  private final TaskEventReceiptStore store;
  private final FirmReadRepository firmRepository;
  private final TransactionTemplate transactionTemplate;
  private final DivisionSyncProperties properties;
  private final Clock clock;

  /**
   * Spring-Konstruktor: Uhr der Persistenz-Zone.
   *
   * @param client Abruf bei application
   * @param projection lokale Projektion
   * @param store Ereignis-Quittungen
   * @param firmRepository Aufloesung der Mandanten-UUID
   * @param transactionManager Transaktionen fuer Quittung und Projektion
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param persistenceZoneId {@code app.time.persistence-zone}
   */
  @Autowired
  public DivisionSignalProcessor(
      final DivisionMembersClient client,
      final DivisionProjectionService projection,
      final TaskEventReceiptStore store,
      final FirmReadRepository firmRepository,
      final PlatformTransactionManager transactionManager,
      final DivisionSyncProperties properties,
      @Value("${app.time.persistence-zone:Europe/Berlin}") final String persistenceZoneId) {
    this(
        client,
        projection,
        store,
        firmRepository,
        transactionManager,
        properties,
        Clock.system(ZoneId.of(persistenceZoneId)));
  }

  /**
   * Konstruktor fuer Tests mit eigener Uhr.
   *
   * @param client Abruf bei application
   * @param projection lokale Projektion
   * @param store Ereignis-Quittungen
   * @param firmRepository Aufloesung der Mandanten-UUID
   * @param transactionManager Transaktionen fuer Quittung und Projektion
   * @param properties Konfiguration des Abteilungs-Abgleichs
   * @param clock Uhr in der Persistenz-Zone (Abrufstart)
   */
  DivisionSignalProcessor(
      final DivisionMembersClient client,
      final DivisionProjectionService projection,
      final TaskEventReceiptStore store,
      final FirmReadRepository firmRepository,
      final PlatformTransactionManager transactionManager,
      final DivisionSyncProperties properties,
      final Clock clock) {
    this.client = client;
    this.projection = projection;
    this.store = store;
    this.firmRepository = firmRepository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Verarbeitet ein Signal.
   *
   * @param signal geprueftes Signal
   * @param deliveryCount Zustellversuch laut Broker ({@code JMSXDeliveryCount}, ab 1)
   * @throws RuntimeException unterhalb des Zustelllimits, damit der Broker neu zustellt
   */
  public void process(final DivisionSignal signal, final int deliveryCount) {
    Objects.requireNonNull(signal, "signal");
    TenantInfo tenant = null;
    try {
      final Optional<Long> tenantId =
          TenantContextHolder.runAsSystem(
              () -> firmRepository.findTenantIdByUuid(signal.tenantUuid()));
      if (tenantId.isEmpty()) {
        log.info(
            "Abteilungs-Signal {}: Mandant {} unbekannt oder nicht aktiv, verworfen.",
            signal.eventId(),
            signal.tenantUuid());
        return;
      }
      tenant = new TenantInfo(signal.tenantUuid(), tenantId.get());
      TenantContextHolder.runWithTenant(
          tenant,
          () -> {
            handle(signal);
            return null;
          });
    } catch (final RuntimeException e) {
      final int maxAttempts = Math.max(1, properties.maxDeliveryAttempts());
      if (deliveryCount < maxAttempts) {
        log.warn(
            "Abteilungs-Signal {} (Mandant {}, Abteilung {}): Zustellversuch {} von {}"
                + " fehlgeschlagen, erneute Zustellung. Grund: {}",
            signal.eventId(),
            signal.tenantUuid(),
            signal.divisionRef(),
            deliveryCount,
            maxAttempts,
            e.getMessage());
        throw e;
      }
      log.error(
          "Abteilungs-Signal {} (Mandant {}, Abteilung {}): Zustellversuch {} von {}"
              + " fehlgeschlagen, endgueltig verworfen (DEAD).",
          signal.eventId(),
          signal.tenantUuid(),
          signal.divisionRef(),
          deliveryCount,
          maxAttempts,
          e);
      if (tenant != null) {
        final TenantInfo deadTenant = tenant;
        TenantContextHolder.runWithTenant(
            deadTenant,
            () -> {
              store.markDead(signal, reason(e));
              return null;
            });
      }
    }
  }

  /**
   * Laedt den aktuellen Stand einer Abteilung nach und uebernimmt ihn, ohne Quittung (Resync).
   *
   * @param tenantId interne Mandanten-ID
   * @param tenantUuid Mandanten-UUID
   * @param divisionRef UUID der Abteilung in application
   * @return Ergebnis der Projektion
   * @throws RuntimeException bei technischem Fehler des Abrufs oder der Datenbank
   */
  public DivisionApplyOutcome resync(
      final long tenantId, final UUID tenantUuid, final UUID divisionRef) {
    return TenantContextHolder.runWithTenant(
        new TenantInfo(tenantUuid, tenantId),
        () -> {
          final LocalDateTime fetchStartedAt = LocalDateTime.now(clock);
          final DivisionFetchResult result = client.fetchCurrent(tenantUuid, divisionRef);
          return transactionTemplate.execute(
              status -> projection.apply(divisionRef, result, fetchStartedAt));
        });
  }

  private void handle(final DivisionSignal signal) {
    if (store.isProcessed(signal)) {
      log.debug("Abteilungs-Signal {}: bereits quittiert.", signal.eventId());
      return;
    }
    final Optional<String> currentRevision =
        projection.currentRevision(signal.divisionRef()).map(String::trim);
    if (currentRevision.isPresent() && currentRevision.get().equals(signal.revision())) {
      log.debug(
          "Abteilung {}: revision unveraendert, nur Quittung fuer Signal {}.",
          signal.divisionRef(),
          signal.eventId());
      claimInTransaction(signal, () -> {});
      return;
    }
    final LocalDateTime fetchStartedAt = LocalDateTime.now(clock);
    final DivisionFetchResult result =
        client.fetchCurrent(signal.tenantUuid(), signal.divisionRef());
    claimInTransaction(
        signal, () -> projection.apply(signal.divisionRef(), result, fetchStartedAt));
  }

  /** Quittung und Handler in einer Transaktion; verliert der Claim, wird sie rollback-only. */
  private void claimInTransaction(final DivisionSignal signal, final Runnable handler) {
    transactionTemplate.executeWithoutResult(
        status -> {
          if (!store.executeOnce(signal, handler)) {
            log.info(
                "Abteilungs-Signal {}: bereits von anderer Stelle quittiert, nichts geschrieben.",
                signal.eventId());
            status.setRollbackOnly();
          }
        });
  }

  private static String reason(final RuntimeException e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
