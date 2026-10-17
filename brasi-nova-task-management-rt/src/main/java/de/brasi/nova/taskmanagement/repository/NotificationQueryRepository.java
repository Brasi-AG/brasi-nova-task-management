package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hand-geschriebenes Repository fuer benutzer-scoped Keyset-Paginierungsabfragen auf
 * TASK_NOTIFICATION (arch-v2 §2.1).
 * Ergaenzt {@link BaseTaskNotificationRepository} um eine user-scoped Keyset-Methode.
 *
 * <p>Notifications sind benutzer-scoped: {@code RECIPIENT_REF = :userRef}.
 * Keyset-Ordnung: {@code ANGELEGT_AM DESC, EXTERNALID DESC}.
 * {@code :tenantId} wird automatisch vom Framework gebunden (kein tenantId-Parameter).
 */
public interface NotificationQueryRepository extends BaseTaskNotificationRepository {

  /**
   * Liefert eine Keyset-Seite von Benachrichtigungen fuer den angegebenen Benutzer.
   * Keyset: {@code ANGELEGT_AM DESC, EXTERNALID DESC}.
   *
   * @param userRef       UUID des Empfaengers (aus {@code UserAccessor})
   * @param afterCreatedAt Keyset-Cursor: letzte ANGELEGT_AM (exklusive Grenze)
   * @param afterExternalId Keyset-Cursor: letzte EXTERNALID (Tie-Break)
   * @param limit         maximale Anzahl zurueckgegebener Eintraege (limit+1 fuer hasMore)
   * @return Liste der Benachrichtigungen, sortiert nach {@code ANGELEGT_AM DESC, EXTERNALID DESC}
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) n.* FROM TASK_NOTIFICATION n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND (n.ANGELEGT_AM < :afterCreatedAt"
              + "   OR (n.ANGELEGT_AM = :afterCreatedAt AND n.EXTERNALID < :afterExternalId))"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) n.* FROM [dbo].[TASK_NOTIFICATION] n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND (n.ANGELEGT_AM < :afterCreatedAt"
              + "   OR (n.ANGELEGT_AM = :afterCreatedAt AND n.EXTERNALID < :afterExternalId))"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC")
  List<TaskNotification> findPage(
      UUID userRef,
      LocalDateTime afterCreatedAt,
      UUID afterExternalId,
      int limit);
}
