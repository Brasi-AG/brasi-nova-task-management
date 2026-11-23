package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskDivision;
import io.keel.framework.api.annotation.db.GlobalQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * KSF-Repository der Abteilungs-Projektion ({@code TASK_ABTEILUNG}, Intake #190). Alle Queries sind
 * ueber {@code :tenantId} an den Kontext-Mandanten gebunden, ausser der Resync-Liste {@link
 * #findAllForResync()} ({@code @GlobalQuery}, nur unter {@code runAsSystem}).
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

  /**
   * Alle Abteilungen aller Mandanten, die der Resync-Job nachladen soll, mit der UUID des aktiven
   * Mandanten. Nur unter {@code runAsSystem}; der Job arbeitet danach je Zeile unter {@code
   * runWithTenant}.
   *
   * <p>Zwei Quellen, per {@code UNION} vereinigt (je Mandant und Abteilung eine Zeile): jede
   * Abteilung mit Kopfzeile in {@code TASK_ABTEILUNG} in jedem Status (auch Grabsteine) und jede
   * Abteilung mit einer {@code DEAD}-Quittung (mit gesetztem {@code ABTEILUNG_REF}) in {@code
   * TASK_EREIGNIS_QUITTUNG}, fuer die es im selben Mandanten keine Kopfzeile gibt, egal in welchem
   * Status. So erreicht der Nachlauf auch eine Abteilung, deren Erstabruf bis zum Zustelllimit
   * scheiterte und die deshalb nur die {@code DEAD}-Spur hat. Mehrere {@code DEAD}-Quittungen
   * derselben Abteilung ergeben eine Zeile. Quittungen ohne Abteilungsbezug (SPI-Pfad) und {@code
   * PROCESSED}-Quittungen zaehlen nicht. Die {@code DEAD}-Spur gilt bis zur Aufbewahrung der
   * Quittungen ({@code receipt-retention}); danach erfasst diese Query die Abteilung nicht mehr.
   *
   * @return eine Zeile je Mandant und Abteilung, nach Mandant und Abteilung sortiert
   */
  @GlobalQuery(
      reason =
          "Resync-Job laedt den aktuellen Stand jeder bekannten Abteilung aller Mandanten nach"
              + " (Kopfzeile oder DEAD-Quittung ohne Kopfzeile); liefert nur Mandanten- und"
              + " Abteilungs-Schluessel, danach runWithTenant je Zeile.")
  @NovaQuery(
      value =
          "SELECT a.TENANT_ID AS tenantId, f.EXTERNALID AS tenantUuid,"
              + " a.ABTEILUNG_REF AS divisionRef FROM TASK_ABTEILUNG a"
              + " JOIN PROD_FIRMA f ON f.ID = a.TENANT_ID AND f.ENTITY_STATUS = 'ACTIVE'"
              + " UNION"
              + " SELECT q.TENANT_ID AS tenantId, f.EXTERNALID AS tenantUuid,"
              + " q.ABTEILUNG_REF AS divisionRef FROM TASK_EREIGNIS_QUITTUNG q"
              + " JOIN PROD_FIRMA f ON f.ID = q.TENANT_ID AND f.ENTITY_STATUS = 'ACTIVE'"
              + " WHERE q.STATUS = 'DEAD' AND q.ABTEILUNG_REF IS NOT NULL"
              + " AND NOT EXISTS (SELECT 1 FROM TASK_ABTEILUNG h"
              + " WHERE h.TENANT_ID = q.TENANT_ID AND h.ABTEILUNG_REF = q.ABTEILUNG_REF)"
              + " ORDER BY tenantId, divisionRef",
      mssql =
          "SELECT a.TENANT_ID AS tenantId, f.EXTERNALID AS tenantUuid,"
              + " a.ABTEILUNG_REF AS divisionRef FROM [dbo].[TASK_ABTEILUNG] a"
              + " JOIN [dbo].[PROD_FIRMA] f ON f.ID = a.TENANT_ID AND f.ENTITY_STATUS = 'ACTIVE'"
              + " UNION"
              + " SELECT q.TENANT_ID AS tenantId, f.EXTERNALID AS tenantUuid,"
              + " q.ABTEILUNG_REF AS divisionRef FROM [dbo].[TASK_EREIGNIS_QUITTUNG] q"
              + " JOIN [dbo].[PROD_FIRMA] f ON f.ID = q.TENANT_ID AND f.ENTITY_STATUS = 'ACTIVE'"
              + " WHERE q.STATUS = 'DEAD' AND q.ABTEILUNG_REF IS NOT NULL"
              + " AND NOT EXISTS (SELECT 1 FROM [dbo].[TASK_ABTEILUNG] h"
              + " WHERE h.TENANT_ID = q.TENANT_ID AND h.ABTEILUNG_REF = q.ABTEILUNG_REF)"
              + " ORDER BY tenantId, divisionRef")
  List<DivisionResyncRow> findAllForResync();
}
