package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskAttachmentLimit;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;

/**
 * Handgeschriebene Erweiterung des generierten {@link BaseTaskAttachmentLimitRepository} fuer die
 * Tarif-Grenze der Anhaenge (Intake #152).
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom KSF-{@code
 * NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskAttachmentLimitQueryRepository extends BaseTaskAttachmentLimitRepository {

  /**
   * Liefert die aktive Tarif-Grenze des Kontext-Mandanten.
   *
   * <p>Hoechstens eine aktive Zeile je Mandant (gefilterter Unique-Index in V14); deaktivierte
   * Zeilen bleiben als Verlauf stehen und werden nicht geliefert. {@code ENTITY_STATUS = 'ACTIVE'}
   * ist Pflicht, denn {@code @NovaQuery} filtert den Status nicht von selbst. Ohne Zeile gilt der
   * Tarif STANDARD (entscheidet der Aufrufer).
   *
   * @return Liste mit 0 oder 1 aktiven Tarif-Grenzen des Mandanten
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_ANHANG_GRENZE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT * FROM [dbo].[TASK_ANHANG_GRENZE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskAttachmentLimit> findActiveForTenant();
}
