package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.Task;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hand-geschriebenes Repository fuer mandanten-scoped Keyset-Paginierungsabfragen auf TASK.
 * Ergaenzt {@link BaseTaskRepository} um benutzer-scoped Inbox-/Archiv-Queries (arch-v2 §2.1,
 * arch-v3 §3.1).
 *
 * <p>Alle Queries sind benutzer-scoped. Inbox-/Archiv-done-Queries verlangen einen aktiven
 * {@code TASK_USER_STATE} (UNSUBSCRIBED_AT IS NULL). Die Archiv-left-Query ist das Gegenstueck:
 * sie liefert Tasks, die der Benutzer verlassen hat - entweder ueber {@code TASK_USER_STATE.
 * UNSUBSCRIBED_AT} (persoenliches Abmelden) ODER ueber {@code TASK_PARTICIPANT.EXIT_KIND}
 * (Teilnehmer-Austritt, z.B. HANDOVER). EXIT_KIND liegt auf TASK_PARTICIPANT, nicht auf
 * TASK_USER_STATE; beide Dimensionen muessen in der Query (nicht service-seitig) OR-verknuepft
 * werden, sonst ist die Keyset-/TOP-Paginierung unvollstaendig.
 *
 * <p>Status-Filter laufen ueber aufgeloeste {@code Long}-IDs ({@code :openStatusId},
 * {@code :doneId}, {@code :cancelledId}), die der aufrufende Service einmalig via
 * {@link de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver} aufloest.
 * Das sind KEINE tenantId-Parameter — der {@code NovaQueryTenantValidator} bleibt erfuellt;
 * {@code :tenantId} wird weiterhin automatisch gebunden.
 *
 * <p><b>Startup-Falle:</b> Kein Parameter darf {@code tenantId} heissen (KSF
 * {@code NovaQueryTenantValidator} bricht sonst den Context-Start ab). Alle Status-Ids sind
 * bewusst anders benannt.
 */
public interface TaskKeysetQueryRepository extends BaseTaskRepository {

  // ---- Inbox: aktive Tasks (TASK_STATUS_ID = openStatusId) --------------------------------

  /**
   * Liefert eine Keyset-Seite aktiver Tasks fuer den angegebenen Benutzer.
   * Filtert auf {@code TASK_STATUS_ID = :openStatusId} (aufgeloeste Id, kein String-Literal).
   * Keyset: {@code LAST_ACTIVITY_AT DESC, EXTERNALID DESC} mit {@code <= :asOf} Wasserzeichen.
   *
   * @param userRef       UUID des Benutzers (aus {@code UserAccessor}, nie aus Clientdaten)
   * @param openStatusId  aufgeloeste ID fuer "OPEN" (via {@link de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver})
   * @param asOf          Wasserzeichen (Snapshot-Zeitpunkt, inklusiv)
   * @param lastActivityAt Keyset-Cursor: letzte LAST_ACTIVITY_AT (exklusive Tie-Break-Grenze)
   * @param externalId    Keyset-Cursor: letzte EXTERNALID (Tie-Break)
   * @param limit         maximale Anzahl zurueckgegebener Tasks (fuer hasMore: limit+1 uebergeben)
   * @return Liste der Tasks, sortiert nach {@code LAST_ACTIVITY_AT DESC, EXTERNALID DESC}
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) t.* FROM TASK t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.TASK_STATUS_ID = :openStatusId"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM TASK_USER_STATE s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.TASK_STATUS_ID = :openStatusId"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC")
  List<Task> findActivePage(
      UUID userRef,
      Long openStatusId,
      LocalDateTime asOf,
      LocalDateTime lastActivityAt,
      UUID externalId,
      int limit);

  // ---- Archiv: erledigte Tasks (done/cancelled) ------------------------------------------

  /**
   * Liefert eine Keyset-Seite archivierter Tasks (DONE oder CANCELLED) fuer den Benutzer.
   * Filtert auf {@code TASK_STATUS_ID IN (:doneId, :cancelledId)} (aufgeloeste Ids).
   * Keyset: {@code LAST_ACTIVITY_AT DESC, EXTERNALID DESC} mit {@code <= :asOf} Wasserzeichen.
   *
   * @param userRef      UUID des Benutzers
   * @param doneId       aufgeloeste ID fuer "DONE"
   * @param cancelledId  aufgeloeste ID fuer "CANCELLED"
   * @param asOf         Wasserzeichen (Snapshot-Zeitpunkt, inklusiv)
   * @param lastActivityAt Keyset-Cursor
   * @param externalId   Keyset-Cursor Tie-Break
   * @param limit        maximale Anzahl
   * @return Liste der Tasks
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) t.* FROM TASK t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM TASK_USER_STATE s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC")
  List<Task> findArchiveDonePage(
      UUID userRef,
      Long doneId,
      Long cancelledId,
      LocalDateTime asOf,
      LocalDateTime lastActivityAt,
      UUID externalId,
      int limit);

  // ---- Archiv: verlassene Tasks (UNSUBSCRIBED_AT/EXIT_KIND, statusunabhaengig) -----------

  /**
   * Liefert eine Keyset-Seite verlassener Tasks fuer den Benutzer (statusunabhaengig).
   * "Verlassen" = {@code TASK_USER_STATE.UNSUBSCRIBED_AT IS NOT NULL} (persoenliches Abmelden)
   * ODER {@code TASK_PARTICIPANT.EXIT_KIND IS NOT NULL} fuer {@code PARTICIPANT_REF = :userRef}
   * (Teilnehmer-Austritt, z.B. HANDOVER) - arch-v2 §2.1. Beide Alternativen sind in der Query
   * OR-verknuepft (nicht service-seitig), damit die Keyset-/TOP-Paginierung vollstaendig bleibt.
   * Keyset: {@code LAST_ACTIVITY_AT DESC, EXTERNALID DESC}.
   *
   * @param userRef        UUID des Benutzers
   * @param asOf           Wasserzeichen (Snapshot-Zeitpunkt, inklusiv)
   * @param lastActivityAt Keyset-Cursor
   * @param externalId     Keyset-Cursor Tie-Break
   * @param limit          maximale Anzahl
   * @return Liste der Tasks
   */
  @NovaQuery(
      value =
          "SELECT TOP (:limit) t.* FROM TASK t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   EXISTS ("
              + "     SELECT 1 FROM TASK_USER_STATE s"
              + "     WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "     AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NOT NULL"
              + "   )"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_PARTICIPANT p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "   )"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "     WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "     AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NOT NULL"
              + "   )"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_PARTICIPANT] p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "   )"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC")
  List<Task> findArchiveLeftPage(
      UUID userRef,
      LocalDateTime asOf,
      LocalDateTime lastActivityAt,
      UUID externalId,
      int limit);
}
