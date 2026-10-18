package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.Task;
import io.keel.framework.api.annotation.db.GlobalQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.time.LocalDate;
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
 * <p><b>Entity-Status-Filter (Pflicht):</b> Alle Reads filtern {@code ENTITY_STATUS = 'ACTIVE'}
 * sowohl auf dem TASK-Row als auch in den {@code EXISTS}-Membership-Subqueries
 * ({@code TASK_USER_STATE}/{@code TASK_PARTICIPANT}). Sonst liefern/zaehlen Archiv- und
 * Snapshot-Reads logisch geloeschte (deaktivierte) Aufgaben bzw. Mitgliedschaften (modulweite
 * Konvention, konsistent mit den Notification-Queries).
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
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.TASK_STATUS_ID = :openStatusId"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM TASK_USER_STATE s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "   AND s.ENTITY_STATUS = 'ACTIVE'"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.TASK_STATUS_ID = :openStatusId"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "   AND s.ENTITY_STATUS = 'ACTIVE'"
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

  // ---- Due-Reminder-Scheduler: faellige OPEN-Tasks (mandantenuebergreifend) ----------------

  /**
   * Liefert alle aktiven OPEN-Aufgaben mit {@code DUE_DATE} am angegebenen Tag ueber ALLE Mandanten
   * (Kandidatensuche des Due-Reminder-Schedulers, arch-v2 §5).
   *
   * <p>Bewusst mandantenuebergreifend ({@link GlobalQuery}) und daher OHNE {@code :tenantId}; darf
   * nur im System-Scope ({@code TenantContextHolder.runAsSystem(...)}) laufen. Der aufrufende
   * {@code DueReminderWorker} setzt pro Treffer anschliessend {@code runWithTenant(tenantOf(row))},
   * bevor er Empfaenger aufloest und emittiert.
   *
   * @param today        Faelligkeitstag (vom Worker in der Persistenz-Zone bestimmt)
   * @param openStatusId aufgeloeste Id des Status {@code OPEN}
   * @return faellige, offene, aktive Aufgaben (mandantenuebergreifend)
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK"
              + " WHERE DUE_DATE = :today"
              + " AND TASK_STATUS_ID = :openStatusId"
              + " AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT * FROM [dbo].[TASK]"
              + " WHERE DUE_DATE = :today"
              + " AND TASK_STATUS_ID = :openStatusId"
              + " AND ENTITY_STATUS = 'ACTIVE'")
  @GlobalQuery(
      reason =
          "Due-Reminder-Scheduler sucht faellige OPEN-Aufgaben mandantenuebergreifend;"
              + " pro Treffer wird anschliessend runWithTenant gesetzt.")
  List<Task> findDueOpenTasks(LocalDate today, Long openStatusId);

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
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM TASK_USER_STATE s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "   AND s.ENTITY_STATUS = 'ACTIVE'"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "   AND s.ENTITY_STATUS = 'ACTIVE'"
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
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   EXISTS ("
              + "     SELECT 1 FROM TASK_USER_STATE s"
              + "     WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "     AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s.ENTITY_STATUS = 'ACTIVE'"
              + "   )"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_PARTICIPANT p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE'"
              + "   )"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "     WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "     AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s.ENTITY_STATUS = 'ACTIVE'"
              + "   )"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_PARTICIPANT] p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE'"
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

  // ---- Archiv: Vereinigung erledigt ODER verlassen ("all") -------------------------------

  /**
   * Liefert eine Keyset-Seite der Vereinigung aus erledigt ODER verlassen fuer den Benutzer
   * (Archiv-Filter {@code all}, arch-v2 §2.1). "all" ist exakt die Vereinigung von
   * {@link #findArchiveDonePage} (DONE/CANCELLED mit aktivem {@code TASK_USER_STATE}) und
   * {@link #findArchiveLeftPage} ({@code UNSUBSCRIBED_AT} ODER {@code EXIT_KIND}), in EINER
   * Query OR-verknuepft, damit die Keyset-/TOP-Paginierung vollstaendig bleibt (nicht
   * service-seitig zusammengefuehrt). Ein Task, der zugleich erledigt und verlassen ist, wird
   * durch das einzelne {@code EXISTS}-Praedikat genau einmal gezaehlt (keine Doppelung).
   * Keyset: {@code LAST_ACTIVITY_AT DESC, EXTERNALID DESC} mit {@code <= :asOf}.
   *
   * @param userRef        UUID des Benutzers
   * @param doneId         aufgeloeste ID fuer "DONE"
   * @param cancelledId    aufgeloeste ID fuer "CANCELLED"
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
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   (t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + "     AND EXISTS ("
              + "       SELECT 1 FROM TASK_USER_STATE s"
              + "       WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "       AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "       AND s.ENTITY_STATUS = 'ACTIVE'))"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_USER_STATE s2"
              + "     WHERE s2.TASK_ID = t.ID AND s2.TENANT_ID = :tenantId"
              + "     AND s2.USER_REF = :userRef AND s2.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s2.ENTITY_STATUS = 'ACTIVE')"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_PARTICIPANT p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE')"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC",
      mssql =
          "SELECT TOP (:limit) t.* FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   (t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + "     AND EXISTS ("
              + "       SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "       WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "       AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "       AND s.ENTITY_STATUS = 'ACTIVE'))"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_USER_STATE] s2"
              + "     WHERE s2.TASK_ID = t.ID AND s2.TENANT_ID = :tenantId"
              + "     AND s2.USER_REF = :userRef AND s2.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s2.ENTITY_STATUS = 'ACTIVE')"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_PARTICIPANT] p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE')"
              + " )"
              + " AND (t.LAST_ACTIVITY_AT < :lastActivityAt"
              + "   OR (t.LAST_ACTIVITY_AT = :lastActivityAt AND t.EXTERNALID < :externalId))"
              + " ORDER BY t.LAST_ACTIVITY_AT DESC, t.EXTERNALID DESC")
  List<Task> findArchiveAllPage(
      UUID userRef,
      Long doneId,
      Long cancelledId,
      LocalDateTime asOf,
      LocalDateTime lastActivityAt,
      UUID externalId,
      int limit);

  // ---- Archiv-Gesamtzahlen (asOf-gebunden, user-scoped) ----------------------------------

  /**
   * Zaehlt die Vereinigung aus erledigt ODER verlassen (Archiv-Filter {@code all}) fuer den
   * Benutzer bis zum Wasserzeichen. WHERE-Praedikat deckungsgleich mit {@link #findArchiveAllPage}
   * (ohne Keyset/TOP/ORDER) -> Zaehler == volle Kardinalitaet der {@code all}-Seitenmenge.
   *
   * @param userRef     UUID des Benutzers
   * @param doneId      aufgeloeste ID fuer "DONE"
   * @param cancelledId aufgeloeste ID fuer "CANCELLED"
   * @param asOf        Wasserzeichen (inklusiv)
   * @return Anzahl archivierter Aufgaben (erledigt ODER verlassen)
   */
  @NovaQuery(
      value =
          "SELECT COUNT(*) FROM TASK t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   (t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + "     AND EXISTS ("
              + "       SELECT 1 FROM TASK_USER_STATE s"
              + "       WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "       AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "       AND s.ENTITY_STATUS = 'ACTIVE'))"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_USER_STATE s2"
              + "     WHERE s2.TASK_ID = t.ID AND s2.TENANT_ID = :tenantId"
              + "     AND s2.USER_REF = :userRef AND s2.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s2.ENTITY_STATUS = 'ACTIVE')"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_PARTICIPANT p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE')"
              + " )",
      mssql =
          "SELECT COUNT(*) FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   (t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + "     AND EXISTS ("
              + "       SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "       WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "       AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "       AND s.ENTITY_STATUS = 'ACTIVE'))"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_USER_STATE] s2"
              + "     WHERE s2.TASK_ID = t.ID AND s2.TENANT_ID = :tenantId"
              + "     AND s2.USER_REF = :userRef AND s2.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s2.ENTITY_STATUS = 'ACTIVE')"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_PARTICIPANT] p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE')"
              + " )")
  long countArchiveAll(UUID userRef, Long doneId, Long cancelledId, LocalDateTime asOf);

  /**
   * Zaehlt die erledigten Aufgaben (DONE/CANCELLED mit aktivem {@code TASK_USER_STATE}) fuer den
   * Benutzer bis zum Wasserzeichen. WHERE deckungsgleich mit {@link #findArchiveDonePage}.
   *
   * @param userRef     UUID des Benutzers
   * @param doneId      aufgeloeste ID fuer "DONE"
   * @param cancelledId aufgeloeste ID fuer "CANCELLED"
   * @param asOf        Wasserzeichen (inklusiv)
   * @return Anzahl erledigter Aufgaben
   */
  @NovaQuery(
      value =
          "SELECT COUNT(*) FROM TASK t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM TASK_USER_STATE s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "   AND s.ENTITY_STATUS = 'ACTIVE')",
      mssql =
          "SELECT COUNT(*) FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.TASK_STATUS_ID IN (:doneId, :cancelledId)"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND EXISTS ("
              + "   SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "   WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "   AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NULL"
              + "   AND s.ENTITY_STATUS = 'ACTIVE')")
  long countArchiveDone(UUID userRef, Long doneId, Long cancelledId, LocalDateTime asOf);

  /**
   * Zaehlt die verlassenen Aufgaben ({@code UNSUBSCRIBED_AT} ODER {@code EXIT_KIND}) fuer den
   * Benutzer bis zum Wasserzeichen. WHERE deckungsgleich mit {@link #findArchiveLeftPage}.
   *
   * @param userRef UUID des Benutzers
   * @param asOf    Wasserzeichen (inklusiv)
   * @return Anzahl verlassener Aufgaben
   */
  @NovaQuery(
      value =
          "SELECT COUNT(*) FROM TASK t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   EXISTS ("
              + "     SELECT 1 FROM TASK_USER_STATE s"
              + "     WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "     AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s.ENTITY_STATUS = 'ACTIVE')"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM TASK_PARTICIPANT p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE')"
              + " )",
      mssql =
          "SELECT COUNT(*) FROM [dbo].[TASK] t"
              + " WHERE t.TENANT_ID = :tenantId"
              + " AND t.ENTITY_STATUS = 'ACTIVE'"
              + " AND t.LAST_ACTIVITY_AT <= :asOf"
              + " AND ("
              + "   EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_USER_STATE] s"
              + "     WHERE s.TASK_ID = t.ID AND s.TENANT_ID = :tenantId"
              + "     AND s.USER_REF = :userRef AND s.UNSUBSCRIBED_AT IS NOT NULL"
              + "     AND s.ENTITY_STATUS = 'ACTIVE')"
              + "   OR EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_PARTICIPANT] p"
              + "     WHERE p.TASK_ID = t.ID AND p.TENANT_ID = :tenantId"
              + "     AND p.PARTICIPANT_REF = :userRef AND p.EXIT_KIND IS NOT NULL"
              + "     AND p.ENTITY_STATUS = 'ACTIVE')"
              + " )")
  long countArchiveLeft(UUID userRef, LocalDateTime asOf);
}
