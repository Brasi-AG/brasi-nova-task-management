package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Erweiterung des generierten {@link BaseTaskParticipantRepository} mit Task-spezifischen Abfragen.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskParticipantQueryRepository extends BaseTaskParticipantRepository {

  /**
   * Sucht den {@link TaskParticipant} fuer eine Aufgabe und einen bestimmten Teilnehmer.
   *
   * <p>Gibt maximal einen Eintrag zurueck.
   *
   * @param taskId           interne Aufgaben-ID
   * @param participantRef   UUID des Teilnehmers (PROD_BENUTZER.EXTERNALID)
   * @return Liste mit 0 oder 1 Eintraegen
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskParticipant> findByTaskIdAndParticipantRef(
      @Param("taskId") Long taskId, @Param("participantRef") UUID participantRef);

  /**
   * Liefert alle aktiven Assignees (ASSIGNEE_FLAG=1) einer Aufgabe.
   *
   * <p>Wird von {@link de.brasi.nova.taskmanagement.participant.AssigneeService#updateAssignees}
   * verwendet, um die aktuellen Assignees mit der gewuenschten Menge zu vergleichen.
   *
   * @param taskId interne Aufgaben-ID
   * @return alle aktiven Assignee-Teilnehmer
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ASSIGNEE_FLAG = 1 "
              + "AND EXIT_KIND IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ASSIGNEE_FLAG = 1 "
              + "AND EXIT_KIND IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskParticipant> findActiveAssigneesByTaskId(@Param("taskId") Long taskId);
}
