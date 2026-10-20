package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.query.Param;

/**
 * Handgeschriebene Erweiterung des generierten {@link BaseTaskTagAssignmentRepository}
 * mit Assignment-spezifischen Abfragen.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskTagAssignmentQueryRepository extends BaseTaskTagAssignmentRepository {

  /**
   * Liefert alle aktiven Tag-Zuordnungen fuer eine Aufgabe.
   *
   * <p>ENTITY_STATUS='ACTIVE' ist Pflicht (13a-Lektion: {@code @NovaQuery} filtert NICHT automatisch).
   *
   * @param taskId interne Aufgaben-ID
   * @return Liste aller aktiven Tag-Zuordnungen fuer die Aufgabe
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_TAG_ASSIGNMENT "
              + "WHERE ENTITY_STATUS = 'ACTIVE' "
              + "AND TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId",
      mssql =
          "SELECT * FROM [dbo].[TASK_TAG_ASSIGNMENT] "
              + "WHERE ENTITY_STATUS = 'ACTIVE' "
              + "AND TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId")
  List<TaskTagAssignment> findActiveByTaskId(@Param("taskId") Long taskId);

  /**
   * Sucht eine bestehende Zuordnung (aktiv oder inaktiv) fuer eine Aufgabe+Tag-Kombination.
   *
   * <p>Ohne ENTITY_STATUS-Filter, da bei der UNIQUE-Constraint ([TASK_ID], [TAG_ID]) ein
   * inaktiver Eintrag reaktiviert werden muss statt einen neuen anzulegen.
   *
   * @param taskId interne Aufgaben-ID
   * @param tagId  interne Tag-ID
   * @return Optional mit dem Eintrag (beliebiger Status) oder leer
   */
  @NovaQuery(
      value =
          "SELECT TOP 1 * FROM TASK_TAG_ASSIGNMENT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND TAG_ID = :tagId",
      mssql =
          "SELECT TOP 1 * FROM [dbo].[TASK_TAG_ASSIGNMENT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND TAG_ID = :tagId")
  java.util.Optional<TaskTagAssignment> findByTaskIdAndTagId(
      @Param("taskId") Long taskId,
      @Param("tagId") Long tagId);

  /**
   * FAITHFUL-Projektion der EXTERNALIDs aller aktiv zugeordneten Tags einer Aufgabe (per-Task
   * Read-Contract, {@code TaskDetailService}). JOIN TASK_TAG_ASSIGNMENT -> TASK_TAG ueber die interne
   * ID; liefert die Tag-UUID (als String) fuer JEDE aktive Zuordnung, UNABHAENGIG vom Status
   * ({@code ACTIVE}/{@code DEACTIVE}) oder der Katalog-Visibility des Tags. So geht KEIN zugeordneter
   * Tag im Detail verloren (kein stiller Verlust beim Read-modify-write); die Konfliktpruefung
   * (inaktiv/abgelaufen) bleibt beim PATCH ({@code findActiveByUuid}).
   *
   * <p>Bewusst KEINE Katalog-Visibility- und KEINE Status-/Gueltigkeitsfilterung auf {@code tag} -
   * die Autorisierung erfolgt auf Task-Ebene (TASK_READ + Tenant + aktive Aufgabe), nicht als
   * Katalog-Browsing. Nur die Assignment-Seite ist tenant-scoped ({@code a.TENANT_ID = :tenantId});
   * {@code tag.TENANT_ID = 0} ist der feste globale Dictionary-Scope. Genau EIN gebundener
   * {@code :taskId}-Parameter (plus auto-{@code :tenantId}) -> kein SQL-Parameterlimit-Risiko bei
   * vielen Zuordnungen.
   *
   * @param taskId interne Aufgaben-ID
   * @return EXTERNALIDs (UUID-Strings) der aktiv zugeordneten Tags (beliebige Reihenfolge)
   */
  @NovaQuery(
      value =
          "SELECT tag.EXTERNALID FROM TASK_TAG_ASSIGNMENT a"
              + " JOIN TASK_TAG tag ON a.TAG_ID = tag.ID AND tag.TENANT_ID = 0"
              + " WHERE a.ENTITY_STATUS = 'ACTIVE' AND a.TENANT_ID = :tenantId AND a.TASK_ID = :taskId",
      mssql =
          "SELECT tag.EXTERNALID FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
              + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID AND tag.TENANT_ID = 0"
              + " WHERE a.ENTITY_STATUS = 'ACTIVE' AND a.TENANT_ID = :tenantId AND a.TASK_ID = :taskId")
  List<String> findActiveTagUuidsByTaskId(@Param("taskId") Long taskId);

  /**
   * Batch-/FAITHFUL-Projektion der Inbox-Read-Anreicherung (BE-1): liefert je aktiver Zuordnung der
   * uebergebenen Aufgaben-Seite genau EIN {@code (taskId, tagUuid)}-Paar in EINER Abfrage (konstante
   * Query-Zahl, kein N+1). Batch-Gegenstueck zum per-Task {@link #findActiveTagUuidsByTaskId}.
   *
   * <p>Wie das per-Task-Pendant bewusst OHNE Katalog-Visibility- und OHNE Status-/Gueltigkeitsfilter
   * auf {@code tag}: jeder aktiv zugeordnete Tag erscheint, auch wenn der Katalog-Tag inzwischen
   * {@code DEACTIVE}/abgelaufen ist (kein stiller Verlust zugeordneter Tags; BE-1-PLAN #1). Nur die
   * Assignment-Seite ist tenant-scoped ({@code a.TENANT_ID = :tenantId}); {@code tag.TENANT_ID = 0}
   * ist der feste globale Dictionary-Scope. Die Paarung ist je Ergebniszeile gesichert (beide Spalten
   * aus derselben Zeile, auf {@link TaskTagUuidRow} gemappt), nie ueber Listenreihenfolge.
   *
   * <p>{@code taskIds} darf NIE leer sein (MSSQL {@code IN ()} -> Syntaxfehler); der Service guardet
   * die leere Seite. {@code :tenantId} wird automatisch vom KSF-NovaQueryTenantValidator gebunden.
   *
   * @param taskIds interne Aufgaben-IDs der Seite (nicht leer)
   * @return {@code (taskId, tagUuid)}-Paare aller aktiven Zuordnungen dieser Aufgaben
   */
  @NovaQuery(
      value =
          "SELECT a.TASK_ID AS taskId, tag.EXTERNALID AS tagUuid FROM TASK_TAG_ASSIGNMENT a"
              + " JOIN TASK_TAG tag ON a.TAG_ID = tag.ID AND tag.TENANT_ID = 0"
              + " WHERE a.ENTITY_STATUS = 'ACTIVE' AND a.TENANT_ID = :tenantId"
              + " AND a.TASK_ID IN (:taskIds)",
      mssql =
          "SELECT a.TASK_ID AS taskId, tag.EXTERNALID AS tagUuid FROM [dbo].[TASK_TAG_ASSIGNMENT] a"
              + " JOIN [dbo].[TASK_TAG] tag ON a.TAG_ID = tag.ID AND tag.TENANT_ID = 0"
              + " WHERE a.ENTITY_STATUS = 'ACTIVE' AND a.TENANT_ID = :tenantId"
              + " AND a.TASK_ID IN (:taskIds)")
  List<TaskTagUuidRow> findActiveTagUuidsByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
