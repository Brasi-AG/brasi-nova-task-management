package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.entity.TaskEventReceipt;
import de.brasi.nova.taskmanagement.entity.enums.EventReceiptStatus;
import de.brasi.nova.taskmanagement.repository.TaskEventReceiptQueryRepository;
import io.keel.framework.api.event.NovaEventIdempotencyStore;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox der verarbeiteten Ereignisse ({@code TASK_EREIGNIS_QUITTUNG}, Intake #190) nach dem Vertrag
 * von {@link NovaEventIdempotencyStore}.
 *
 * <p>{@code executeOnce} laeuft in der Transaktion des Aufrufers ({@code MANDATORY}) und unter dem
 * Kontext-Mandanten, dessen UUID der Scope sein muss: erst die Quittung ({@code PROCESSED})
 * einfuegen, dann den Handler ausfuehren. Ist das Ereignis schon quittiert oder verliert der Insert
 * gegen einen gleichzeitigen Claim (Unique {@code (MANDANT_UUID, EREIGNIS_ID)}), liefert er {@code
 * false} ohne Handler; der Aufrufer setzt seine Transaktion dann auf rollback-only. Eine Ausnahme
 * des Handlers geht unveraendert weiter, der Rollback nimmt die Quittung mit.
 *
 * <p>{@link #markDead} schreibt in eigener Transaktion ({@code REQUIRES_NEW}), damit die Verwerfung
 * den Rollback der Verarbeitung ueberlebt. Eine Quittung in jedem Status zaehlt als verarbeitet.
 */
@Component
public class TaskEventReceiptStore implements NovaEventIdempotencyStore {

  /** Hoechstlaenge von {@code FEHLER_MELDUNG}. */
  static final int MAX_ERROR_LENGTH = 900;

  private static final Logger log = LoggerFactory.getLogger(TaskEventReceiptStore.class);

  private final TaskEventReceiptQueryRepository repository;

  /**
   * Konstruktor-Injektion.
   *
   * @param repository Repository der Quittungen
   */
  public TaskEventReceiptStore(final TaskEventReceiptQueryRepository repository) {
    this.repository = repository;
  }

  /**
   * SPI-Pfad ohne Abteilungsbezug ({@code ABTEILUNG_REF} bleibt leer).
   *
   * @param scope Mandanten-UUID des Ereignisses; muss dem Kontext-Mandanten gleichen
   * @param eventId ID des Ereignisses
   * @param handler fachliche Verarbeitung
   * @return {@code true}, wenn der Handler lief; {@code false} bei Duplikat
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean executeOnce(final UUID scope, final UUID eventId, final Runnable handler) {
    return claimAndRun(scope, eventId, null, handler);
  }

  /**
   * Quittiert ein Abteilungs-Signal und fuehrt danach den Handler aus.
   *
   * @param signal geprueftes Signal
   * @param handler fachliche Verarbeitung
   * @return {@code true}, wenn der Handler lief; {@code false} bei Duplikat
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean executeOnce(final DivisionSignal signal, final Runnable handler) {
    Objects.requireNonNull(signal, "signal");
    return claimAndRun(signal.tenantUuid(), signal.eventId(), signal.divisionRef(), handler);
  }

  /**
   * Ob das Signal im Kontext-Mandanten schon quittiert ist ({@code PROCESSED} oder {@code DEAD}).
   *
   * @param signal geprueftes Signal
   * @return {@code true}, wenn eine Quittung besteht
   */
  public boolean isProcessed(final DivisionSignal signal) {
    return repository.existsByEvent(signal.tenantUuid(), signal.eventId());
  }

  /**
   * Verwirft das Signal endgueltig: Quittung {@code DEAD} mit Grund (auf {@value #MAX_ERROR_LENGTH}
   * Zeichen gekuerzt), eine vorhandene Quittung wird auf {@code DEAD} gesetzt. Laeuft in eigener
   * Transaktion und unter dem Kontext-Mandanten.
   *
   * @param signal geprueftes Signal
   * @param reason Grund ohne Token oder Secret
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markDead(final DivisionSignal signal, final String reason) {
    Objects.requireNonNull(signal, "signal");
    final TenantInfo tenant = requireScope(signal.tenantUuid());
    final TaskEventReceipt receipt =
        repository
            .findByEvent(signal.tenantUuid(), signal.eventId())
            .orElseGet(
                () ->
                    newReceipt(
                        tenant.id(), signal.tenantUuid(), signal.eventId(), signal.divisionRef()));
    receipt.setStatus(EventReceiptStatus.DEAD);
    receipt.setErrorMessage(truncate(reason));
    repository.save(receipt);
    log.debug("Ereignis {} (Mandant {}): Quittung DEAD.", signal.eventId(), signal.tenantUuid());
  }

  private boolean claimAndRun(
      final UUID tenantUuid, final UUID eventId, final UUID divisionRef, final Runnable handler) {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(handler, "handler");
    final TenantInfo tenant = requireScope(tenantUuid);
    if (repository.existsByEvent(tenantUuid, eventId)) {
      log.debug("Ereignis {} (Mandant {}): bereits quittiert.", eventId, tenantUuid);
      return false;
    }
    final TaskEventReceipt receipt = newReceipt(tenant.id(), tenantUuid, eventId, divisionRef);
    receipt.setStatus(EventReceiptStatus.PROCESSED);
    try {
      repository.save(receipt);
    } catch (final BusinessRuleException | DataIntegrityViolationException conflict) {
      if (!isDuplicateKey(conflict)) {
        throw conflict;
      }
      log.info(
          "Ereignis {} (Mandant {}): gleichzeitig von anderer Stelle quittiert.",
          eventId,
          tenantUuid);
      return false;
    }
    handler.run();
    return true;
  }

  private static TaskEventReceipt newReceipt(
      final long tenantId, final UUID tenantUuid, final UUID eventId, final UUID divisionRef) {
    final TaskEventReceipt receipt = new TaskEventReceipt();
    receipt.setTenantId(tenantId);
    receipt.setTenantUuid(tenantUuid);
    receipt.setEventId(eventId);
    receipt.setDivisionRef(divisionRef);
    return receipt;
  }

  /** Der Scope muss der Kontext-Mandant sein; sonst landete die Quittung im falschen Mandanten. */
  private static TenantInfo requireScope(final UUID tenantUuid) {
    Objects.requireNonNull(tenantUuid, "tenantUuid");
    final TenantInfo tenant =
        TenantContextHolder.getTenant()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "TaskEventReceiptStore: kein Mandanten-Kontext im aktuellen Thread."));
    if (!tenantUuid.equals(tenant.uuid())) {
      throw new IllegalStateException(
          "TaskEventReceiptStore: Scope "
              + tenantUuid
              + " passt nicht zum Kontext-Mandanten "
              + tenant.uuid()
              + ".");
    }
    return tenant;
  }

  /** Unique-Verstoss beim Insert: {@link DuplicateKeyException} oder MSSQL 2601/2627. */
  static boolean isDuplicateKey(final Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof DuplicateKeyException) {
        return true;
      }
      if (cause instanceof SQLException sql
          && (sql.getErrorCode() == 2601 || sql.getErrorCode() == 2627)) {
        return true;
      }
    }
    return false;
  }

  private static String truncate(final String reason) {
    if (reason == null) {
      return null;
    }
    return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
  }
}
