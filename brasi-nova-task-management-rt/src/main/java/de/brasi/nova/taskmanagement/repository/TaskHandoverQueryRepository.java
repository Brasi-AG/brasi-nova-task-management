package de.brasi.nova.taskmanagement.repository;

import io.keel.framework.api.annotation.db.NovaQuery;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Handgeschriebene Erweiterung des generierten {@link BaseTaskHandoverRepository} mit dem
 * Read-Batch fuer die Handover-Anreicherung (BE-7, {@code exit.handedOverToIds}).
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskHandoverQueryRepository extends BaseTaskHandoverRepository {

  /**
   * Liefert je uebergebener Aufgabe die Ziele des JUENGSTEN sichtbaren Austritts-Handover-Ereignisses
   * des Anfragers (PA3). "Sichtbar" = {@code FROM_REF = :fromRef}, {@code SHOULD_STAY = 0}
   * (Stay-Handover hat keinen Austritt), {@code ANGELEGT_AM <= :asOf}, aktiv und tenant-scoped.
   * "Juengstes" = groesstes {@code (ANGELEGT_AM, ID)}; Tie-Break {@code ID} DESC (per NOT EXISTS:
   * kein anderes sichtbares Ereignis derselben Aufgabe ist spaeter).
   *
   * <p>Danach werden die Ziele per LEFT JOIN angehaengt: hat das juengste Ereignis KEINE Ziele
   * (Selbst-Handover ohne Ziele), liefert der LEFT JOIN genau eine Zeile mit {@code toRef = null} ->
   * der Service leitet daraus eine leere Ziel-Liste ab (kein Vermischen mit aelteren Ereignissen).
   * Stabile Zielreihenfolge via {@code ORDER BY ... TO_REF}.
   *
   * <p>{@code taskIds} darf NIE leer sein (MSSQL {@code IN ()} -> Syntaxfehler); der Service guardet
   * die leere Seite. {@code :tenantId} wird automatisch gebunden.
   *
   * @param taskIds interne Aufgaben-IDs der Seite (nicht leer)
   * @param fromRef UUID des Anfragers/Akteurs (Austritts-Perspektive)
   * @param asOf    Wasserzeichen (nur Ereignisse mit {@code ANGELEGT_AM <= asOf})
   * @return {@code (taskId, toRef)}-Paare; {@code toRef} null, wenn das juengste Ereignis keine Ziele
   *     hat
   */
  @NovaQuery(
      value =
          "SELECT h.TASK_ID AS taskId, tgt.TO_REF AS toRef"
              + " FROM TASK_HANDOVER h"
              + " LEFT JOIN TASK_HANDOVER_TARGET tgt"
              + "   ON tgt.HANDOVER_ID = h.ID"
              + "   AND tgt.TENANT_ID = :tenantId"
              + "   AND tgt.ENTITY_STATUS = 'ACTIVE'"
              + " WHERE h.TENANT_ID = :tenantId"
              + "   AND h.TASK_ID IN (:taskIds)"
              + "   AND h.FROM_REF = :fromRef"
              + "   AND h.SHOULD_STAY = 0"
              + "   AND h.ENTITY_STATUS = 'ACTIVE'"
              + "   AND h.ANGELEGT_AM <= :asOf"
              + "   AND NOT EXISTS ("
              + "     SELECT 1 FROM TASK_HANDOVER h2"
              + "     WHERE h2.TENANT_ID = :tenantId"
              + "       AND h2.TASK_ID = h.TASK_ID"
              + "       AND h2.FROM_REF = :fromRef"
              + "       AND h2.SHOULD_STAY = 0"
              + "       AND h2.ENTITY_STATUS = 'ACTIVE'"
              + "       AND h2.ANGELEGT_AM <= :asOf"
              + "       AND (h2.ANGELEGT_AM > h.ANGELEGT_AM"
              + "            OR (h2.ANGELEGT_AM = h.ANGELEGT_AM AND h2.ID > h.ID)))"
              + " ORDER BY h.TASK_ID, tgt.TO_REF",
      mssql =
          "SELECT h.TASK_ID AS taskId, tgt.TO_REF AS toRef"
              + " FROM [dbo].[TASK_HANDOVER] h"
              + " LEFT JOIN [dbo].[TASK_HANDOVER_TARGET] tgt"
              + "   ON tgt.HANDOVER_ID = h.ID"
              + "   AND tgt.TENANT_ID = :tenantId"
              + "   AND tgt.ENTITY_STATUS = 'ACTIVE'"
              + " WHERE h.TENANT_ID = :tenantId"
              + "   AND h.TASK_ID IN (:taskIds)"
              + "   AND h.FROM_REF = :fromRef"
              + "   AND h.SHOULD_STAY = 0"
              + "   AND h.ENTITY_STATUS = 'ACTIVE'"
              + "   AND h.ANGELEGT_AM <= :asOf"
              + "   AND NOT EXISTS ("
              + "     SELECT 1 FROM [dbo].[TASK_HANDOVER] h2"
              + "     WHERE h2.TENANT_ID = :tenantId"
              + "       AND h2.TASK_ID = h.TASK_ID"
              + "       AND h2.FROM_REF = :fromRef"
              + "       AND h2.SHOULD_STAY = 0"
              + "       AND h2.ENTITY_STATUS = 'ACTIVE'"
              + "       AND h2.ANGELEGT_AM <= :asOf"
              + "       AND (h2.ANGELEGT_AM > h.ANGELEGT_AM"
              + "            OR (h2.ANGELEGT_AM = h.ANGELEGT_AM AND h2.ID > h.ID)))"
              + " ORDER BY h.TASK_ID, tgt.TO_REF")
  List<HandoverTargetRow> findVisibleHandoverTargets(
      @Param("taskIds") Collection<Long> taskIds,
      @Param("fromRef") UUID fromRef,
      @Param("asOf") LocalDateTime asOf);
}
