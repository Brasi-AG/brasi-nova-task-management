package de.brasi.nova.taskmanagement.notification;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.push.NotificationPushEnvelope;
import de.brasi.nova.taskmanagement.push.NotificationPushPort;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Erzeugt und persistiert Benachrichtigungen fuer Benutzer.
 *
 * <p>Der {@link NotificationPushPort}-Aufruf wird IMMER ueber
 * {@link TransactionSynchronizationManager#registerSynchronization(TransactionSynchronization)}
 * als {@code afterCommit}-Hook registriert. Kein Push innerhalb der DB-Transaktion; das verhindert
 * Phantom-Pushes bei Rollback.
 *
 * <p>typeCode und severityCode sind system-interne Konstanten (Aufrufer liefern Konstanten aus
 * {@code NotificationTypeCodes}/{@code NotificationSeverityCodes}).
 * Bei unbekanntem Code schlaegt der {@link DictionaryCodeResolver} laut fehl
 * ({@code DictionaryResolverException}) — das ist ein interner Programmierfehler, kein Client-400.
 *
 * <p>TENANT_ID wird aus dem aktuellen {@link TenantContextHolder} ermittelt und in der Entity gesetzt.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final BaseTaskNotificationRepository notificationRepository;
  private final DictionaryCodeResolver resolver;
  private final NotificationPushPort pushPort;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public NotificationService(
      final BaseTaskNotificationRepository notificationRepository,
      final DictionaryCodeResolver resolver,
      final NotificationPushPort pushPort) {
    this.notificationRepository = notificationRepository;
    this.resolver = resolver;
    this.pushPort = pushPort;
  }

  /**
   * Persistiert eine {@link TaskNotification} und registriert den WS-Push fuer {@code afterCommit}.
   *
   * <p>Ablauf:
   * <ol>
   *   <li>typeId und severityId werden ueber den {@link DictionaryCodeResolver} aufgeloest.
   *   <li>Eine neue {@link TaskNotification} wird gespeichert (KSF {@code save()}).
   *   <li>Ein {@link TransactionSynchronization#afterCommit()}-Hook registriert den
   *       {@link NotificationPushPort#push(NotificationPushEnvelope)}-Aufruf.
   * </ol>
   *
   * @param recipientRef  UUID des Empfaengers (PROD_BENUTZER.EXTERNALID), nie null
   * @param taskId        optionale interne Task-ID (null = System-Benachrichtigung)
   * @param typeCode      Benachrichtigungstyp-CODE (aus {@code NotificationTypeCodes}), nie null
   * @param severityCode  Schweregrad-CODE (aus {@code NotificationSeverityCodes}), nullable
   * @param text          Anzeigetext, nie null
   * @param preview       optionale Vorschau (z.B. Aufgabentitel)
   * @param actorRef      optionale UUID des Akteurs, der die Benachrichtigung ausgeloest hat
   * @return externe UUID (EXTERNALID) der persistierten {@link TaskNotification}
   */
  @Transactional
  public UUID emit(
      final UUID recipientRef,
      final Long taskId,
      final String typeCode,
      final String severityCode,
      final String text,
      final String preview,
      final UUID actorRef) {

    final TenantInfo tenant =
        TenantContextHolder.getTenant()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "NotificationService.emit: kein Mandanten-Kontext im aktuellen Thread."));

    // Code-Aufloesung: fail-loud bei unbekanntem Code (interner Programmierfehler)
    final Long typeId = resolver.resolveNotificationType(typeCode);
    final Long severityId =
        (severityCode != null) ? resolver.resolveNotificationSeverity(severityCode) : null;

    // TaskNotification persistieren
    final TaskNotification notification = new TaskNotification();
    notification.setTenantId(tenant.id());
    notification.setRecipientRef(recipientRef);
    notification.setTaskId(taskId);
    notification.setTypeId(typeId);
    notification.setRead(false);
    notification.setActorRef(actorRef);
    notification.setText(text);
    notification.setPreview(preview);
    notification.setSeverityId(severityId);

    final TaskNotification saved = notificationRepository.save(notification);
    final UUID notificationExternalId = saved.getUuid();

    log.info(
        "NotificationService.emit: Benachrichtigung {} (Typ={}, Empfaenger={}) persistiert"
            + " in Mandant {}.",
        notificationExternalId,
        typeCode,
        recipientRef,
        tenant.id());

    // After-Commit-Seam: Push erst nach erfolgreichem Commit senden
    final String severityCodeForPush = severityCode;
    final NotificationPushEnvelope envelope =
        new NotificationPushEnvelope(
            typeCode,
            "1",
            notificationExternalId,
            Instant.now(),
            severityCodeForPush,
            new NotificationPushEnvelope.Payload(text, preview));

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              log.debug(
                  "NotificationService.afterCommit: Push fuer Benachrichtigung {} (Typ={}).",
                  notificationExternalId,
                  typeCode);
              pushPort.push(envelope);
            }
          });
    } else {
      // Kein Transaktionskontext: direkt senden (Best-Effort, z.B. in Tests ohne Tx-Aktivierung)
      log.debug(
          "NotificationService (kein Tx-Kontext): Push fuer Benachrichtigung {} direkt gesendet.",
          notificationExternalId);
      pushPort.push(envelope);
    }

    return notificationExternalId;
  }
}
