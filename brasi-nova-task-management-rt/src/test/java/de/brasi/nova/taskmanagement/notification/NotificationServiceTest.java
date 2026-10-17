package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.push.NotificationPushEnvelope;
import de.brasi.nova.taskmanagement.push.NotificationPushPort;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit-Tests fuer {@link NotificationService}.
 *
 * <p>Testet den After-Commit-Seam mit echtem {@link NotificationService} und Mock-Abhaengigkeiten:
 * <ul>
 *   <li>(a) {@code emit} registriert innerhalb einer aktiven Tx den {@link NotificationPushPort}
 *       als After-Commit-Hook (kein sofortiger Push).
 *   <li>(b) Nach Rollback: kein Push (kein {@code afterCommit} gefeuert).
 * </ul>
 *
 * <p>Kein DB-Zugriff noetig: Repository-{@code save()} wird gemockt.
 */
class NotificationServiceTest {

  private static final long TENANT_ID = 500L;
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID RECIPIENT_REF = UUID.randomUUID();
  private static final UUID ACTOR_REF = UUID.randomUUID();
  private static final String TYPE_CODE = NotificationTypeCodes.ASSIGN;
  private static final String SEVERITY_CODE = NotificationSeverityCodes.INFO;
  private static final long TYPE_ID = 401L;
  private static final long SEVERITY_ID = 501L;

  private NotificationPushPort pushPort;
  private BaseTaskNotificationRepository notificationRepository;
  private DictionaryCodeResolver resolver;
  private NotificationService service;

  @BeforeEach
  void setUp() {
    pushPort = mock(NotificationPushPort.class);
    notificationRepository = mock(BaseTaskNotificationRepository.class);
    resolver = mock(DictionaryCodeResolver.class);
    service = new NotificationService(notificationRepository, resolver, pushPort);

    // Resolver-Stubs
    when(resolver.resolveNotificationType(TYPE_CODE)).thenReturn(TYPE_ID);
    when(resolver.resolveNotificationSeverity(SEVERITY_CODE)).thenReturn(SEVERITY_ID);

    // Repository-Stub: save gibt die Entity mit gesetzter UUID zurueck
    when(notificationRepository.save(any(TaskNotification.class)))
        .thenAnswer(invocation -> {
          final TaskNotification n = invocation.getArgument(0);
          if (n.getUuid() == null) {
            n.setUuid(UUID.randomUUID());
          }
          n.setId(99L);
          return n;
        });

    // TenantContext setzen
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- (a) emit registriert afterCommit-Push; kein Push vor Commit ---------------------------

  @Test
  @DisplayName(
      "emit_tx_registriert_afterCommit_push: innerhalb aktiver Tx kein sofortiger Push;"
          + " genau EIN Push nach afterCommit")
  void emit_tx_registriert_afterCommit_push() {
    TransactionSynchronizationManager.initSynchronization();

    // Act: emit aufrufen
    final UUID notificationId =
        service.emit(RECIPIENT_REF, null, TYPE_CODE, SEVERITY_CODE, "Testnachricht", "Vorschau",
            ACTOR_REF);

    // Kein Push vor Commit
    verify(pushPort, never()).push(anyLong(), any(), any());
    assertThat(notificationId).as("emit muss eine UUID zurueckgeben").isNotNull();

    // afterCommit ausfuehren (simuliert Commit-Abschluss)
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCommit());

    // Genau EIN Push nach Commit, mit Routing-Metadaten + korrektem Envelope-Kontrakt
    final ArgumentCaptor<NotificationPushEnvelope> envelopeCaptor =
        ArgumentCaptor.forClass(NotificationPushEnvelope.class);
    verify(pushPort, times(1))
        .push(eq(TENANT_ID), eq(RECIPIENT_REF), envelopeCaptor.capture());

    final NotificationPushEnvelope envelope = envelopeCaptor.getValue();
    assertThat(envelope.type())
        .as("Envelope-Diskriminator MUSS fest 'notification' sein (nicht der Typ-CODE)")
        .isEqualTo("notification");
    assertThat(envelope.version()).isEqualTo("1");
    assertThat(envelope.category())
        .as("Benachrichtigungs-KATEGORIE = typeCode")
        .isEqualTo(TYPE_CODE);
    assertThat(envelope.severity()).isEqualTo(SEVERITY_CODE);
    assertThat(envelope.notificationId()).isEqualTo(notificationId);
    assertThat(envelope.payload().text()).isEqualTo("Testnachricht");
    assertThat(envelope.payload().preview()).isEqualTo("Vorschau");
  }

  // ---- (b) kein Push bei Rollback --------------------------------------------------------------

  @Test
  @DisplayName(
      "kein_push_bei_rollback: bei Rollback wird NotificationPushPort nicht aufgerufen")
  void kein_push_bei_rollback() {
    TransactionSynchronizationManager.initSynchronization();

    service.emit(RECIPIENT_REF, null, TYPE_CODE, SEVERITY_CODE, "Rollback-Test", null, ACTOR_REF);

    // afterCompletion mit STATUS_ROLLED_BACK ausfuehren (kein afterCommit)
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(
            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

    // Port darf NICHT aufgerufen worden sein
    verify(pushPort, never()).push(anyLong(), any(), any());
  }

  // ---- (c) ohne aktive Tx: direkter Push mit Routing-Metadaten ---------------------------------

  @Test
  @DisplayName(
      "emit_ohne_tx_pusht_direkt: ohne aktive Transaktion wird sofort mit Routing-Metadaten gepusht")
  void emit_ohne_tx_pusht_direkt() {
    // KEINE Synchronisation aktiv (kein initSynchronization)
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

    final UUID notificationId =
        service.emit(RECIPIENT_REF, null, TYPE_CODE, SEVERITY_CODE, "Direkt", "P", ACTOR_REF);

    final ArgumentCaptor<NotificationPushEnvelope> envelopeCaptor =
        ArgumentCaptor.forClass(NotificationPushEnvelope.class);
    verify(pushPort, times(1))
        .push(eq(TENANT_ID), eq(RECIPIENT_REF), envelopeCaptor.capture());
    assertThat(envelopeCaptor.getValue().type()).isEqualTo("notification");
    assertThat(envelopeCaptor.getValue().category()).isEqualTo(TYPE_CODE);
    assertThat(envelopeCaptor.getValue().notificationId()).isEqualTo(notificationId);
  }
}
