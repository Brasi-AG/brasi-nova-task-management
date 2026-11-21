package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskDivision;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * KSF-Repository der Abteilungs-Projektion ({@code TASK_ABTEILUNG}, Intake #190). Alle Queries sind
 * ueber {@code :tenantId} an den Kontext-Mandanten gebunden.
 */
public interface TaskDivisionQueryRepository extends BaseTaskDivisionRepository {

  /**
   * Liest und sperrt den Kopf einer Abteilung bis zum Ende der umgebenden Transaktion, in jedem
   * Status. Auf MSSQL nimmt {@code UPDLOCK, HOLDLOCK} eine Update- und Bereichssperre auf dem
   * Schluessel {@code (TENANT_ID, ABTEILUNG_REF)}: auch die Erstanlage derselben Abteilung durch
   * einen zweiten Abruf wartet bis zum Commit.
   *
   * @param divisionRef UUID der Abteilung in application
   * @return der gesperrte Kopf, leer, wenn die Abteilung im Mandanten nie gesehen wurde
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_ABTEILUNG WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_REF = :divisionRef",
      mssql =
          "SELECT * FROM [dbo].[TASK_ABTEILUNG] WITH (UPDLOCK, HOLDLOCK)"
              + " WHERE TENANT_ID = :tenantId AND ABTEILUNG_REF = :divisionRef")
  Optional<TaskDivision> findByRefForUpdate(@Param("divisionRef") UUID divisionRef);

  /**
   * Revision des uebernommenen Stands einer aktiven Abteilung.
   *
   * @param divisionRef UUID der Abteilung in application
   * @return die Revision, leer, wenn die Abteilung unbekannt oder deaktiviert ist
   */
  @NovaQuery(
      value =
          "SELECT REVISION FROM TASK_ABTEILUNG WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_REF = :divisionRef AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT REVISION FROM [dbo].[TASK_ABTEILUNG] WHERE TENANT_ID = :tenantId"
              + " AND ABTEILUNG_REF = :divisionRef AND ENTITY_STATUS = 'ACTIVE'")
  Optional<String> findRevision(@Param("divisionRef") UUID divisionRef);
}
