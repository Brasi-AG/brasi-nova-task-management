package de.brasi.nova.taskmanagement.repository;

import io.keel.framework.api.annotation.db.NovaQuery;
import org.springframework.data.repository.query.Param;

/**
 * KSF-Repository der Mitglieder der Abteilungs-Projektion ({@code TASK_ABTEILUNG_MITGLIED}, Intake
 * #190). Alle Queries sind ueber {@code :tenantId} an den Kontext-Mandanten gebunden.
 */
public interface TaskDivisionMemberQueryRepository extends BaseTaskDivisionMemberRepository {

  /**
   * Loescht die Mitglieder einer Abteilung physisch. Die Zeilen sind abgeleitete Daten ohne
   * fachliche Historie und werden bei jedem neuen Stand ersetzt (V15).
   *
   * @param divisionId interne ID des Abteilungskopfs ({@code TASK_ABTEILUNG.ID})
   * @return Zahl der geloeschten Zeilen
   */
  @NovaQuery(
      modifying = true,
      value =
          "DELETE FROM TASK_ABTEILUNG_MITGLIED WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_ID = :divisionId",
      mssql =
          "DELETE FROM [dbo].[TASK_ABTEILUNG_MITGLIED] WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_ID = :divisionId")
  int deleteByDivisionId(@Param("divisionId") Long divisionId);
}
