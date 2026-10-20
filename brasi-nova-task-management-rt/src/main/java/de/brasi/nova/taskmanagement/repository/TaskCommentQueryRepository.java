package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskComment;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import org.springframework.data.repository.query.Param;

/**
 * Handgeschriebene Erweiterung des generierten {@link BaseTaskCommentRepository} mit
 * Kommentar-spezifischen Lese-Abfragen (BE-2). Das scaffold-{@code api}-Modul bleibt unangetastet.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskCommentQueryRepository extends BaseTaskCommentRepository {

  /**
   * Liefert alle aktiven Kommentare einer Aufgabe chronologisch (flache Liste; der Client baut den
   * Antwort-Baum ueber {@code parentCommentId}).
   *
   * <p>Ordnung {@code ANGELEGT_AM ASC, EXTERNALID ASC} (deterministischer Tiebreak bei gleichem
   * Zeitstempel). {@code ENTITY_STATUS='ACTIVE'} ist Pflicht (KSF filtert nicht automatisch);
   * {@code TASK_ID} bindet die Kommentare an genau eine Aufgabe.
   *
   * @param taskId interne Aufgaben-ID
   * @return aktive Kommentare der Aufgabe, chronologisch aufsteigend
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_COMMENT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM ASC, EXTERNALID ASC",
      mssql =
          "SELECT * FROM [dbo].[TASK_COMMENT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM ASC, EXTERNALID ASC")
  List<TaskComment> findActiveByTaskIdOrdered(@Param("taskId") Long taskId);
}
