package de.brasi.nova.taskmanagement.scheduler;

import de.brasi.nova.taskmanagement.entity.TaskReminderReceipt;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.repository.BaseTaskReminderReceiptRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emittiert genau einmal pro (Mandant, Aufgabe, Empfaenger, Faelligkeitstag) eine DUE-Erinnerung
 * (arch-v2 §5).
 *
 * <p><b>Atomizitaet:</b> Receipt-INSERT und DUE-Notification laufen in EINER eigenen Transaktion
 * ({@link Propagation#REQUIRES_NEW}). Der Receipt wird ZUERST gespeichert - ein UNIQUE-Konflikt
 * (die Erinnerung existiert bereits) bricht die Transaktion mit einer {@link
 * org.springframework.dao.DuplicateKeyException} ab, bevor eine zweite Notification entsteht.
 * Scheitert die Notification, rollt der Receipt mit zurueck -> ein Retry ist moeglich. Der
 * UNIQUE-Konflikt wird bewusst AUSSERHALB dieser Methode (im {@link DueReminderWorker}) als
 * "bereits erledigt" behandelt. Push und Snapshot-Invalidierung laufen ueber den After-Commit-Seam
 * von {@link NotificationService}/{@link InboxSignalService} erst nach erfolgreichem Commit.
 */
@Service
public class DueReminderEmitter {

  private static final Logger log = LoggerFactory.getLogger(DueReminderEmitter.class);

  /** Spaltenlaenge von {@code TASK_NOTIFICATION.TEXT}/{@code PREVIEW} ({@code nvarchar(500)}). */
  private static final int MAX_NOTIFICATION_TEXT = 500;

  private final BaseTaskReminderReceiptRepository receiptRepository;
  private final NotificationService notificationService;
  private final InboxSignalService inboxSignalService;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public DueReminderEmitter(
      final BaseTaskReminderReceiptRepository receiptRepository,
      final NotificationService notificationService,
      final InboxSignalService inboxSignalService) {
    this.receiptRepository = receiptRepository;
    this.notificationService = notificationService;
    this.inboxSignalService = inboxSignalService;
  }

  /**
   * Schreibt Idempotenz-Quittung + DUE-Notification atomar und invalidiert nach Commit den
   * Empfaenger-Snapshot. Kurzform fuer einen nicht stummgeschalteten Empfaenger (siehe {@link
   * #emitOnce(long, Long, UUID, LocalDate, String, boolean)}).
   *
   * @param tenantId interne Mandanten-Id (wird in der Receipt-Entity gesetzt)
   * @param taskId interne Aufgaben-Id
   * @param recipientRef UUID des Empfaengers (PROD_BENUTZER.EXTERNALID)
   * @param occurrenceDate Faelligkeitstag
   * @param taskTitle Aufgabentitel (Vorschau/Anzeigetext)
   * @throws org.springframework.dao.DuplicateKeyException wenn die Erinnerung bereits existiert
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void emitOnce(
      final long tenantId,
      final Long taskId,
      final UUID recipientRef,
      final LocalDate occurrenceDate,
      final String taskTitle) {
    emitOnce(tenantId, taskId, recipientRef, occurrenceDate, taskTitle, false);
  }

  /**
   * Schreibt die Idempotenz-Quittung, die DUE-Notification (ausser bei stummgeschaltetem
   * Empfaenger) und invalidiert nach Commit den Empfaenger-Snapshot.
   *
   * <p>Intake #158: Hat der Empfaenger die Aufgabe stummgeschaltet (vom {@link DueReminderWorker}
   * gebuendelt ueber den {@code NotificationMuteFilter} ermittelt), entfaellt nur die
   * DUE-Benachrichtigung. Quittung und {@code snapshot-invalidated}-Signal bleiben unveraendert:
   * die Quittung markiert den Tag als erledigt (kein erneuter Versuch je Scheduler-Lauf), das
   * Signal ist keine Benachrichtigung.
   *
   * @param tenantId interne Mandanten-Id (wird in der Receipt-Entity gesetzt)
   * @param taskId interne Aufgaben-Id
   * @param recipientRef UUID des Empfaengers (PROD_BENUTZER.EXTERNALID)
   * @param occurrenceDate Faelligkeitstag
   * @param taskTitle Aufgabentitel (Vorschau/Anzeigetext)
   * @param recipientMuted {@code true}, wenn der Empfaenger die Aufgabe stummgeschaltet hat
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void emitOnce(
      final long tenantId,
      final Long taskId,
      final UUID recipientRef,
      final LocalDate occurrenceDate,
      final String taskTitle,
      final boolean recipientMuted) {

    // Receipt ZUERST: UNIQUE-Konflikt bricht ab, bevor eine zweite DUE-Notification entsteht.
    final TaskReminderReceipt receipt = new TaskReminderReceipt();
    receipt.setTenantId(tenantId);
    receipt.setTaskId(taskId);
    receipt.setRecipientRef(recipientRef);
    receipt.setOccurrenceDate(occurrenceDate);
    receiptRepository.save(receipt);

    // TASK_NOTIFICATION.TEXT/PREVIEW sind nvarchar(500); TITLE darf bis 500 Zeichen lang sein.
    // Mit dem Praefix wuerde TEXT sonst ueberlaufen -> Insert-Fehler -> Receipt-Rollback -> kein
    // DUE.
    if (recipientMuted) {
      log.info(
          "DueReminderEmitter: Aufgabe {} von Empfaenger {} stummgeschaltet -"
              + " DUE-Benachrichtigung unterdrueckt (Tag {}, Mandant {}).",
          taskId,
          recipientRef,
          occurrenceDate,
          tenantId);
    } else {
      notificationService.emit(
          recipientRef,
          taskId,
          NotificationTypeCodes.DUE,
          NotificationSeverityCodes.WARN,
          truncate("Aufgabe faellig: " + taskTitle, MAX_NOTIFICATION_TEXT),
          truncate(taskTitle, MAX_NOTIFICATION_TEXT),
          null);
    }

    // Snapshot-Invalidierung nach Commit (arch-v2 §3: Due aendert die Inbox-Liste).
    inboxSignalService.invalidate(tenantId, List.of(recipientRef));

    log.info(
        "DueReminderEmitter: DUE-Erinnerung fuer Aufgabe {} / Empfaenger {} (Tag {}, Mandant {}).",
        taskId,
        recipientRef,
        occurrenceDate,
        tenantId);
  }

  /**
   * Kuerzt {@code value} auf hoechstens {@code maxLength} UTF-16-Code-Units (null-sicher) und
   * schneidet dabei NICHT mitten in ein Surrogatpaar (z.B. Emoji) - sonst entstuende ein
   * ungepaartes Surrogat (korrupter Unicode / Insert-Fehler). Paketsichtbar fuer Unit-Tests.
   */
  static String truncate(final String value, final int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    int end = maxLength;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      // Letztes eingeschlossenes Zeichen ist das High-Surrogate eines Paares, dessen
      // Low-Surrogate gerade abgeschnitten wuerde -> das High-Surrogate ebenfalls weglassen.
      end--;
    }
    return value.substring(0, end);
  }
}
