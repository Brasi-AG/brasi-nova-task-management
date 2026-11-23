package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.repository.DivisionResyncRow;
import de.brasi.nova.taskmanagement.repository.TaskDivisionQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskEventReceiptQueryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Prueft {@link DivisionResyncJob} (Intake #190, Task 7) mit Mockito: Resync aller bekannten
 * Abteilungen, der bei einem Fehler je Abteilung weiterlaeuft, und die Aufbewahrungsbereinigung der
 * Quittungen mit dem Stichtag aus Uhr und {@code receipt-retention}.
 *
 * <p>Angenommene Signaturen: paketprivater Test-Konstruktor {@code
 * DivisionResyncJob(TaskDivisionQueryRepository, TaskEventReceiptQueryRepository,
 * DivisionSignalProcessor, DivisionSyncProperties, Clock)}; {@code int resyncAll()} (Zahl der
 * erfolgreich nachgeladenen Abteilungen), {@code int purgeReceipts()} (Zahl der geloeschten
 * Quittungen); {@code DivisionSignalProcessor#resync(long, UUID, UUID)}; {@code
 * TaskDivisionQueryRepository#findAllForResync()}; {@code
 * TaskEventReceiptQueryRepository#deleteOlderThan(LocalDateTime)}.
 */
class DivisionResyncJobTest {

  private static final ZoneId PERSISTENCE_ZONE = ZoneId.of("Europe/Berlin");
  private static final UUID TEN_A = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final UUID TEN_B = UUID.fromString("00000056-0000-0056-0000-000000000562");
  private static final UUID DIV_1 = UUID.fromString("00000056-0000-0056-dddd-000000000001");
  private static final UUID DIV_2 = UUID.fromString("00000056-0000-0056-dddd-000000000002");
  private static final UUID DIV_3 = UUID.fromString("00000056-0000-0056-dddd-000000000003");

  private final TaskDivisionQueryRepository divisionRepository =
      mock(TaskDivisionQueryRepository.class);
  private final TaskEventReceiptQueryRepository receiptRepository =
      mock(TaskEventReceiptQueryRepository.class);
  private final DivisionSignalProcessor processor = mock(DivisionSignalProcessor.class);

  @Test
  @DisplayName("Resync laeuft nach einem Fehler weiter: drei Aufrufe, zwei erfolgreich")
  void i190_resync_continues_after_single_failure() {
    when(divisionRepository.findAllForResync())
        .thenReturn(
            List.of(
                new DivisionResyncRow(561L, TEN_A, DIV_1),
                new DivisionResyncRow(561L, TEN_A, DIV_2),
                new DivisionResyncRow(562L, TEN_B, DIV_3)));
    when(processor.resync(561L, TEN_A, DIV_1)).thenReturn(DivisionApplyOutcome.UPDATED);
    when(processor.resync(561L, TEN_A, DIV_2))
        .thenThrow(
            new DivisionFetchException(
                "Abruf der Mitglieder von Abteilung " + DIV_2 + " fehlgeschlagen: HTTP 503"));
    when(processor.resync(562L, TEN_B, DIV_3)).thenReturn(DivisionApplyOutcome.DEACTIVATED);

    final int resynced = job(clockAt(2026, 11, 20, 3, 30)).resyncAll();

    assertThat(resynced).isEqualTo(2);
    final InOrder order = inOrder(processor);
    order.verify(processor).resync(561L, TEN_A, DIV_1);
    order.verify(processor).resync(561L, TEN_A, DIV_2);
    order.verify(processor).resync(562L, TEN_B, DIV_3);
  }

  @Test
  @DisplayName("Bereinigung: Uhr 2026-11-20, P30D ergibt Stichtag 2026-10-21")
  void i190_purge_uses_retention() {
    when(receiptRepository.deleteOlderThan(any())).thenReturn(4);

    final int purged = job(clockAt(2026, 11, 20, 12, 0)).purgeReceipts();

    assertThat(purged).isEqualTo(4);
    final ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(receiptRepository).deleteOlderThan(cutoff.capture());
    // Nur das Datum: ueber das Ende der Sommerzeit (25.10.) darf die Uhrzeit um eine Stunde
    // abweichen, je nachdem ob in Wanduhr- oder Instant-Zeit gerechnet wird.
    assertThat(cutoff.getValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 21));
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private DivisionResyncJob job(final Clock clock) {
    return new DivisionResyncJob(
        divisionRepository, receiptRepository, processor, properties(), clock);
  }

  private static Clock clockAt(
      final int year, final int month, final int day, final int hour, final int minute) {
    return Clock.fixed(
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, PERSISTENCE_ZONE).toInstant(),
        PERSISTENCE_ZONE);
  }

  private static DivisionSyncProperties properties() {
    return new DivisionSyncProperties(
        true,
        "jms.topic.brasi-nova.tm-test.divisions.DivisionMembershipChanged",
        "task-management.division-membership",
        "https://application.example.test",
        new DivisionSyncProperties.OAuth2(
            "https://identity.example.test/oauth2/token",
            "tm-test-client",
            "test-secret-value",
            "masterdata.sync.read"),
        5,
        null,
        new DivisionSyncProperties.Resync("0 30 3 * * *"),
        Duration.ofDays(30));
  }
}
