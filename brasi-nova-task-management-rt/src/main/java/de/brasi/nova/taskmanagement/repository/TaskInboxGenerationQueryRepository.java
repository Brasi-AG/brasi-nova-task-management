package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskInboxGeneration;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Erweiterung des generierten {@link BaseTaskInboxGenerationRepository} um benutzerscoped Lese- und
 * eine atomare Increment-Operation fuer den Inbox-Generationszaehler (Finding 2 / Option B).
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden — auch fuer die
 * {@code modifying}-Query (KSF bindet den Tenant-Kontext fuer tenant-faehige Entities vor der
 * Ausfuehrung).
 */
public interface TaskInboxGenerationQueryRepository extends BaseTaskInboxGenerationRepository {

  /**
   * Liest die Generationszeile des Benutzers im aktuellen Mandanten (0 oder 1 Treffer, UNIQUE auf
   * TENANT_ID + USER_REF).
   *
   * @param userRef UUID des Benutzers
   * @return Liste mit 0 oder 1 Eintraegen
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_INBOX_GENERATION "
              + "WHERE TENANT_ID = :tenantId AND USER_REF = :userRef AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT * FROM [dbo].[TASK_INBOX_GENERATION] "
              + "WHERE TENANT_ID = :tenantId AND USER_REF = :userRef AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskInboxGeneration> findByUserRef(@Param("userRef") UUID userRef);

  /**
   * Erhoeht die Generation des Benutzers atomar um 1 ({@code SET GENERATION = GENERATION + 1}, kein
   * Read-modify-save). Erwartet eine vorhandene Zeile; liefert die Anzahl betroffener Zeilen (muss 1
   * sein, sonst behandelt der Aufrufer dies als Fehler).
   *
   * @param userRef UUID des Benutzers
   * @return Anzahl betroffener Zeilen
   */
  @NovaQuery(
      modifying = true,
      value =
          "UPDATE TASK_INBOX_GENERATION SET GENERATION = GENERATION + 1 "
              + "WHERE TENANT_ID = :tenantId AND USER_REF = :userRef AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "UPDATE [dbo].[TASK_INBOX_GENERATION] SET GENERATION = GENERATION + 1 "
              + "WHERE TENANT_ID = :tenantId AND USER_REF = :userRef AND ENTITY_STATUS = 'ACTIVE'")
  int incrementGeneration(@Param("userRef") UUID userRef);
}
