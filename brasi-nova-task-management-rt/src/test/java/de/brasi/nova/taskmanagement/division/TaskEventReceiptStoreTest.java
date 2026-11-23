package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskEventReceipt;
import de.brasi.nova.taskmanagement.repository.TaskEventReceiptQueryRepository;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Prueft den Duplikat-Zweig von {@link TaskEventReceiptStore} (Intake #190) mit gemocktem
 * Repository, ohne Datenbank: Das Insert der Quittung scheitert am Unique-Verstoss; ob das Signal
 * als gleichzeitig quittiert uebersprungen wird, entscheidet allein die gelesene Gewinnerzeile.
 *
 * <p>Der Wettlauf mit zwei Threads in {@code TaskEventReceiptStoreIT} belegt diesen Zweig nicht:
 * der zweite Insert kann auch an der Sequenzvergabe scheitern oder an der Vorpruefung vorbeilaufen.
 * Hier loest das gemockte {@code save} den Konflikt gezielt aus. Die Transaktionsklammer ({@code
 * MANDATORY}) liegt am Bean-Proxy und ist ohne Spring nicht im Spiel.
 */
class TaskEventReceiptStoreTest {

  private static final UUID EVT = UUID.fromString("00000056-0000-0056-eeee-000000000191");
  private static final UUID TEN = UUID.fromString("00000056-0000-0056-0000-000000000565");
  private static final long TENANT_ID = 565L;
  private static final UUID DIV = UUID.fromString("00000056-0000-0056-dddd-000000000565");

  private final TaskEventReceiptQueryRepository repository =
      mock(TaskEventReceiptQueryRepository.class);
  private final DivisionSignal signal = new DivisionSignal(EVT, TEN, DIV);
  private final AtomicInteger handlerRuns = new AtomicInteger();

  private TaskEventReceiptStore store;

  @BeforeEach
  void setUp() {
    TenantContextHolder.setTenant(new TenantInfo(TEN, TENANT_ID));
    store = new TaskEventReceiptStore(repository);
  }

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  @Test
  @DisplayName("Duplikat mit Gewinnerzeile: false, Handler nicht aufgerufen")
  void i190_duplicate_with_winner_row_returns_false() {
    // Vorpruefung: noch keine Quittung; nach dem Unique-Verstoss: die Quittung des Gewinners.
    when(repository.existsByEvent(TEN, EVT)).thenReturn(false, true);
    doThrow(new BusinessRuleException("Konflikt", new DuplicateKeyException("UQ_EREIGNIS")))
        .when(repository)
        .save(any(TaskEventReceipt.class));

    final boolean claimed = store.executeOnce(signal, handlerRuns::incrementAndGet);

    assertThat(claimed).isFalse();
    assertThat(handlerRuns).as("Handler nicht aufgerufen").hasValue(0);
    verify(repository, times(1)).save(any(TaskEventReceipt.class));
    verify(repository, times(2)).existsByEvent(TEN, EVT);
  }

  @Test
  @DisplayName("MSSQL 2627 in der Ursachenkette mit Gewinnerzeile: false, Handler nicht aufgerufen")
  void i190_sql_2627_with_winner_row_returns_false() {
    when(repository.existsByEvent(TEN, EVT)).thenReturn(false, true);
    doThrow(
            new DataIntegrityViolationException(
                "Insert verletzt Unique-Schluessel",
                new SQLException("Violation of UNIQUE KEY constraint", "23000", 2627)))
        .when(repository)
        .save(any(TaskEventReceipt.class));

    final boolean claimed = store.executeOnce(signal, handlerRuns::incrementAndGet);

    assertThat(claimed).isFalse();
    assertThat(handlerRuns).as("Handler nicht aufgerufen").hasValue(0);
  }

  @Test
  @DisplayName("Duplikat ohne Gewinnerzeile (etwa Sequenz-Primaerschluessel): dieselbe Ausnahme")
  void i190_duplicate_without_winner_row_rethrows_original() {
    // Auch nach dem Verstoss keine Quittung: der Schluessel gehoert nicht zu (MANDANT_UUID,
    // EREIGNIS_ID), ein Ueberspringen verlore das Signal still.
    when(repository.existsByEvent(TEN, EVT)).thenReturn(false);
    final BusinessRuleException conflict =
        new BusinessRuleException(
            "Konflikt", new DuplicateKeyException("PK_TASK_EREIGNIS_QUITTUNG"));
    doThrow(conflict).when(repository).save(any(TaskEventReceipt.class));

    assertThatThrownBy(() -> store.executeOnce(signal, handlerRuns::incrementAndGet))
        .isSameAs(conflict);

    assertThat(handlerRuns).as("Handler nicht aufgerufen").hasValue(0);
    verify(repository, times(2)).existsByEvent(TEN, EVT);
  }

  @Test
  @DisplayName("Sequenz-Konflikt ohne Datenbank-Ursache: dieselbe Ausnahme, kein erneutes Lesen")
  void i190_sequence_conflict_rethrows_without_reread() {
    when(repository.existsByEvent(TEN, EVT)).thenReturn(false);
    final BusinessRuleException conflict =
        new BusinessRuleException("Sequenz TaskEventReceipt gleichzeitig geaendert");
    doThrow(conflict).when(repository).save(any(TaskEventReceipt.class));

    assertThatThrownBy(() -> store.executeOnce(signal, handlerRuns::incrementAndGet))
        .isSameAs(conflict);

    assertThat(handlerRuns).as("Handler nicht aufgerufen").hasValue(0);
    verify(repository, times(1)).existsByEvent(TEN, EVT);
  }
}
