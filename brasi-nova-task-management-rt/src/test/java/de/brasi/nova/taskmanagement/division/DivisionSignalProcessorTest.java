package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.division.DivisionFetchResult.Members;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.NotFound;
import de.brasi.nova.taskmanagement.division.model.DivisionMemberPayload;
import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;
import de.brasi.nova.taskmanagement.refentity.FirmReadRepository;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Prueft {@link DivisionSignalProcessor} (Intake #190, Task 5) mit Mockito und einer echten {@code
 * TransactionTemplate} ueber einen aufzeichnenden Fake-Transaktionsmanager.
 *
 * <p>Ablauf nach dem Ruling vom 2026-11-22 (immer {@code self}): Mandant aufloesen, Quittung
 * vorhanden, Kurzschluss bei gleicher revision, Abrufstart, {@code fetchCurrent}, eine Transaktion
 * mit {@code executeOnce(signal, () -> projection.apply(...))}. Laufzeitfehler unterhalb von {@code
 * maxDeliveryAttempts} werden weitergeworfen, am Limit wird die Quittung {@code DEAD}.
 *
 * <p>Die Uhr tickt bei jedem Lesen eine Sekunde weiter: so zeigt der Vergleich mit der Zeit des
 * Client-Aufrufs, ob der Abrufstart vor dem HTTP-Aufruf genommen wurde. Ihre Zone ist die
 * Persistenz-Zone {@code Europe/Berlin}.
 *
 * <p>Angenommene Signaturen: paketprivater Test-Konstruktor {@code
 * DivisionSignalProcessor(DivisionMembersClient, DivisionProjectionService, TaskEventReceiptStore,
 * FirmReadRepository, PlatformTransactionManager, DivisionSyncProperties, Clock)}; {@code void
 * process(DivisionSignal, int)}; {@code FirmReadRepository#findIdByUuid(UUID): Optional<Long>}.
 */
class DivisionSignalProcessorTest {

  private static final UUID EVT = UUID.fromString("00000056-0000-0056-eeee-000000000190");
  private static final UUID TEN = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final long TENANT_ID = 561L;
  private static final UUID DIV = UUID.fromString("00000056-0000-0056-dddd-000000000001");
  private static final UUID EMPLOYEE = UUID.fromString("00000056-0000-0056-eeee-00000000000a");
  private static final String REV = "b".repeat(64);
  private static final String OLD_REV = "a".repeat(64);

  private static final ZoneId PERSISTENCE_ZONE = ZoneId.of("Europe/Berlin");
  private static final Instant START = Instant.parse("2026-11-20T09:00:00Z");

  private final DivisionMembersClient client = mock(DivisionMembersClient.class);
  private final DivisionProjectionService projection = mock(DivisionProjectionService.class);
  private final TaskEventReceiptStore store = mock(TaskEventReceiptStore.class);
  private final FirmReadRepository firmRepository = mock(FirmReadRepository.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final TickingClock clock = new TickingClock(START, PERSISTENCE_ZONE);
  private final DivisionSignal signal = new DivisionSignal(EVT, TEN, DIV, REV);

  private DivisionSignalProcessor processor;

  @BeforeEach
  void setUp() {
    when(firmRepository.findIdByUuid(TEN)).thenReturn(Optional.of(TENANT_ID));
    when(store.isProcessed(signal)).thenReturn(false);
    when(store.executeOnce(eq(signal), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return true;
            });
    when(projection.currentRevision(DIV)).thenReturn(Optional.of(OLD_REV));
    when(projection.apply(eq(DIV), any(), any())).thenReturn(DivisionApplyOutcome.UPDATED);
    when(client.fetchCurrent(TEN, DIV)).thenReturn(members());
    processor =
        new DivisionSignalProcessor(
            client, projection, store, firmRepository, transactionManager, properties(), clock);
  }

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName("Signal laedt den aktuellen Stand und wendet ihn in einer Transaktion an")
  void i190_signal_loads_current_and_applies() {
    final AtomicBoolean transactionalApply = new AtomicBoolean();
    when(projection.apply(eq(DIV), any(), any()))
        .thenAnswer(
            invocation -> {
              transactionalApply.set(TransactionSynchronizationManager.isActualTransactionActive());
              return DivisionApplyOutcome.UPDATED;
            });

    processor.process(signal, 1);

    verify(client).fetchCurrent(TEN, DIV);
    verify(store).executeOnce(eq(signal), any());
    verify(projection).apply(eq(DIV), isA(Members.class), any());
    assertThat(transactionalApply).as("apply laeuft in der Transaktion").isTrue();
    assertThat(transactionManager.commits).hasValue(1);
    assertThat(transactionManager.rollbacks).hasValue(0);
  }

  @Test
  @DisplayName("gleiche revision: nur Quittung in einer Transaktion, kein HTTP, kein apply")
  void i190_same_revision_skips_http() {
    when(projection.currentRevision(DIV)).thenReturn(Optional.of(REV));
    final AtomicBoolean transactionalClaim = new AtomicBoolean();
    when(store.executeOnce(eq(signal), any()))
        .thenAnswer(
            invocation -> {
              transactionalClaim.set(TransactionSynchronizationManager.isActualTransactionActive());
              invocation.<Runnable>getArgument(1).run();
              return true;
            });

    processor.process(signal, 1);

    verifyNoInteractions(client);
    verify(store).executeOnce(eq(signal), any());
    verify(projection, never()).apply(any(), any(), any());
    assertThat(transactionalClaim).as("executeOnce ist MANDATORY").isTrue();
  }

  @Test
  @DisplayName("vorhandene Quittung: weder HTTP noch Projektion noch neue Quittung")
  void i190_duplicate_event_skips_everything() {
    when(store.isProcessed(signal)).thenReturn(true);

    processor.process(signal, 1);

    verifyNoInteractions(client, projection);
    verify(store, never()).executeOnce(any(DivisionSignal.class), any());
    verify(store, never()).markDead(any(), any());
  }

  @Test
  @DisplayName("404 von application wird als NotFound angewendet")
  void i190_not_found_is_applied() {
    when(client.fetchCurrent(TEN, DIV)).thenReturn(new NotFound());

    processor.process(signal, 1);

    verify(projection).apply(eq(DIV), isA(NotFound.class), any());
    verify(store).executeOnce(eq(signal), any());
  }

  @Test
  @DisplayName("unbekannter Mandant: verworfen ohne HTTP, Quittung oder Projektion")
  void i190_unknown_tenant_is_dropped() {
    when(firmRepository.findIdByUuid(TEN)).thenReturn(Optional.empty());

    processor.process(signal, 1);

    verifyNoInteractions(client, store, projection);
    assertThat(transactionManager.commits).hasValue(0);
  }

  @Test
  @DisplayName("403: unter dem Limit weiterwerfen, am Limit DEAD-Quittung ohne Wurf")
  void i190_forbidden_retries_then_dead_letter() {
    when(client.fetchCurrent(TEN, DIV))
        .thenThrow(
            new DivisionFetchException(
                "Abruf der Mitglieder von Abteilung " + DIV + " fehlgeschlagen: HTTP 403"));

    assertThatThrownBy(() -> processor.process(signal, 4))
        .isInstanceOf(DivisionFetchException.class);
    verify(store, never()).markDead(any(), any());

    processor.process(signal, 5); // kein Wurf

    verify(store).markDead(eq(signal), contains("403"));
    verify(projection, never()).apply(any(), any(), any());
  }

  @Test
  @DisplayName("Quittung und Projektion laufen unter dem Mandanten des Signals")
  void i190_writes_happen_under_signal_tenant() {
    final AtomicReference<Optional<TenantInfo>> tenantInClaim = new AtomicReference<>();
    final AtomicReference<Optional<TenantInfo>> tenantInApply = new AtomicReference<>();
    when(store.executeOnce(eq(signal), any()))
        .thenAnswer(
            invocation -> {
              tenantInClaim.set(TenantContextHolder.getTenant());
              invocation.<Runnable>getArgument(1).run();
              return true;
            });
    when(projection.apply(eq(DIV), any(), any()))
        .thenAnswer(
            invocation -> {
              tenantInApply.set(TenantContextHolder.getTenant());
              return DivisionApplyOutcome.UPDATED;
            });

    processor.process(signal, 1);

    final TenantInfo expected = new TenantInfo(TEN, TENANT_ID);
    assertThat(tenantInClaim.get()).contains(expected);
    assertThat(tenantInApply.get()).contains(expected);
  }

  @Test
  @DisplayName("Abrufstart wird vor dem HTTP-Aufruf in der Persistenz-Zone genommen")
  void i190_fetch_start_taken_before_http() {
    final AtomicReference<LocalDateTime> clientCalledAt = new AtomicReference<>();
    when(client.fetchCurrent(TEN, DIV))
        .thenAnswer(
            invocation -> {
              clientCalledAt.set(LocalDateTime.now(clock));
              return members();
            });

    processor.process(signal, 1);

    final ArgumentCaptor<LocalDateTime> fetchStartedAt =
        ArgumentCaptor.forClass(LocalDateTime.class);
    verify(projection).apply(eq(DIV), isA(Members.class), fetchStartedAt.capture());
    assertThat(clientCalledAt.get()).isNotNull();
    assertThat(fetchStartedAt.getValue())
        .as("Abrufstart vor dem Client-Aufruf")
        .isBefore(clientCalledAt.get())
        .as("Wanduhr der Persistenz-Zone (09:00Z = 10:00 Berlin)")
        .isAfterOrEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static DivisionFetchResult members() {
    return new Members(
        new DivisionMembersPayload(
            DIV,
            "Vertrieb",
            REV,
            List.of(
                new DivisionMemberPayload(
                    EMPLOYEE, "Anna Alpha", "Anna", "Alpha", "aalpha", true))));
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
        null,
        Duration.ofDays(30));
  }

  /** Uhr, die bei jedem Lesen eine Sekunde weitertickt. */
  private static final class TickingClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    TickingClock(final Instant start, final ZoneId zone) {
      this.now = new AtomicReference<>(start);
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(final ZoneId newZone) {
      return new TickingClock(now.get(), newZone);
    }

    @Override
    public Instant instant() {
      return now.getAndUpdate(current -> current.plusSeconds(1));
    }
  }

  /** Fake-Transaktionsmanager: echte Transaktionsgrenzen, zaehlt Commits und Rollbacks. */
  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(final Object transaction, final TransactionDefinition definition) {
      // keine Ressource
    }

    @Override
    protected void doCommit(final DefaultTransactionStatus status) {
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(final DefaultTransactionStatus status) {
      rollbacks.incrementAndGet();
    }
  }
}
