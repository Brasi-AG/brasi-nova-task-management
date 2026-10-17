package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskTagAssignment;
import io.keel.framework.api.annotation.db.NovaQuery;
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
}
