package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hand-geschriebenes Repository fuer benutzer-scoped Keyset-Paginierungsabfragen auf
 * TASK_NOTIFICATION (arch-v2 §2.1).
 * Ergaenzt {@link BaseTaskNotificationRepository} um user-scoped Keyset-Methoden.
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
   * @param userRef         UUID des Empfaengers (aus {@code UserAccessor})
   * @param afterCreatedAt  Keyset-Cursor: letzte ANGELEGT_AM (exklusive Grenze)
   * @param afterExternalId Keyset-Cursor: letzte EXTERNALID (Tie-Break)
   * @param limit           maximale Anzahl zurueckgegebener Eintraege (limit+1 fuer hasMore)
   * @return Liste der Benachrichtigungen, sortiert nach {@code ANGELEGT_AM DESC, EXTERNALID DESC}
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) n.* FROM TASK_NOTIFICATION n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND (n.ANGELEGT_AM < :afterCreatedAt"
              + "   OR (n.ANGELEGT_AM = :afterCreatedAt AND n.EXTERNALID < :afterExternalId))"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) n.* FROM [dbo].[TASK_NOTIFICATION] n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND (n.ANGELEGT_AM < :afterCreatedAt"
              + "   OR (n.ANGELEGT_AM = :afterCreatedAt AND n.EXTERNALID < :afterExternalId))"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC")
  List<TaskNotification> findPage(
      UUID userRef,
      LocalDateTime afterCreatedAt,
      UUID afterExternalId,
      int limit);

  /**
   * Erste Seite aller Benachrichtigungen fuer den Benutzer (kein Keyset-Praedikat).
   * Keyset-Ordnung: {@code ANGELEGT_AM DESC, EXTERNALID DESC}.
   *
   * @param userRef UUID des Empfaengers
   * @param limit   maximale Anzahl (limit+1 fuer hasMore-Logik)
   * @return Liste der Benachrichtigungen (neueste zuerst)
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) n.* FROM TASK_NOTIFICATION n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) n.* FROM [dbo].[TASK_NOTIFICATION] n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC")
  List<TaskNotification> findFirstPage(UUID userRef, int limit);

  /**
   * Erste Seite der ungelesenen Benachrichtigungen fuer den Benutzer (kein Keyset-Praedikat).
   * Keyset-Ordnung: {@code ANGELEGT_AM DESC, EXTERNALID DESC}.
   *
   * @param userRef UUID des Empfaengers
   * @param limit   maximale Anzahl (limit+1 fuer hasMore-Logik)
   * @return Liste der ungelesenen Benachrichtigungen (neueste zuerst)
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) n.* FROM TASK_NOTIFICATION n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND n.IS_READ = 0"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) n.* FROM [dbo].[TASK_NOTIFICATION] n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND n.IS_READ = 0"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC")
  List<TaskNotification> findUnreadFirstPage(UUID userRef, int limit);

  /**
   * Keyset-Fortsetzung der ungelesenen Benachrichtigungen fuer den Benutzer.
   * Keyset-Ordnung: {@code ANGELEGT_AM DESC, EXTERNALID DESC}.
   *
   * @param userRef         UUID des Empfaengers
   * @param afterCreatedAt  Keyset-Cursor: letzte ANGELEGT_AM (exklusive Grenze)
   * @param afterExternalId Keyset-Cursor: letzte EXTERNALID (Tie-Break)
   * @param limit           maximale Anzahl (limit+1 fuer hasMore-Logik)
   * @return Liste der ungelesenen Benachrichtigungen (neueste zuerst)
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) n.* FROM TASK_NOTIFICATION n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND n.IS_READ = 0"
              + " AND (n.ANGELEGT_AM < :afterCreatedAt"
              + "   OR (n.ANGELEGT_AM = :afterCreatedAt AND n.EXTERNALID < :afterExternalId))"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) n.* FROM [dbo].[TASK_NOTIFICATION] n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND n.IS_READ = 0"
              + " AND (n.ANGELEGT_AM < :afterCreatedAt"
              + "   OR (n.ANGELEGT_AM = :afterCreatedAt AND n.EXTERNALID < :afterExternalId))"
              + " ORDER BY n.ANGELEGT_AM DESC, n.EXTERNALID DESC")
  List<TaskNotification> findUnreadPage(
      UUID userRef,
      LocalDateTime afterCreatedAt,
      UUID afterExternalId,
      int limit);

  /**
   * Alle ungelesenen Benachrichtigungen eines Benutzers (ohne Limit, fuer markAllRead).
   * Strikt gescoped auf {@code RECIPIENT_REF = :userRef} + {@code TENANT_ID = :tenantId}.
   *
   * @param userRef UUID des Empfaengers
   * @return Liste aller ungelesenen Benachrichtigungen des Benutzers
   */
  @NovaQuery(
      value =
          "SELECT n.* FROM TASK_NOTIFICATION n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND n.IS_READ = 0",
      mssql =
          "SELECT n.* FROM [dbo].[TASK_NOTIFICATION] n"
              + " WHERE n.TENANT_ID = :tenantId"
              + " AND n.ENTITY_STATUS = 'ACTIVE'"
              + " AND n.RECIPIENT_REF = :userRef"
              + " AND n.IS_READ = 0")
  List<TaskNotification> findUnreadAllForUser(UUID userRef);
}
