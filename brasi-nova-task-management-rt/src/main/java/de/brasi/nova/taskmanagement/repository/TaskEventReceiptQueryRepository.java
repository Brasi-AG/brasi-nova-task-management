package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskEventReceipt;
import io.keel.framework.api.annotation.db.GlobalQuery;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * KSF-Repository der Ereignis-Quittungen ({@code TASK_EREIGNIS_QUITTUNG}, Intake #190). Lesende
 * Queries sind ueber {@code :tenantId} an den Kontext-Mandanten gebunden; nur die
 * Aufbewahrungsbereinigung laeuft mandantenuebergreifend.
 *
 * <p>Die Existenzpruefung filtert {@code ENTITY_STATUS} bewusst nicht: sie spiegelt den
 * Unique-Schluessel {@code (MANDANT_UUID, EREIGNIS_ID)}, der unabhaengig vom Status gilt.
 */
public interface TaskEventReceiptQueryRepository extends BaseTaskEventReceiptRepository {

  /**
   * Zahl der Quittungen eines Ereignisses im Kontext-Mandanten (0 oder 1).
   *
   * @param tenantUuid Scope der Quittung (tenantId des Ereignisses)
   * @param eventId ID des Ereignisses
   * @return Zahl der Zeilen
   */
  @NovaQuery(
      value =
          "SELECT COUNT(*) FROM TASK_EREIGNIS_QUITTUNG WHERE TENANT_ID = :tenantId"
              + " AND MANDANT_UUID = :tenantUuid AND EREIGNIS_ID = :eventId",
      mssql =
          "SELECT COUNT(*) FROM [dbo].[TASK_EREIGNIS_QUITTUNG] WHERE TENANT_ID = :tenantId"
              + " AND MANDANT_UUID = :tenantUuid AND EREIGNIS_ID = :eventId")
  long countByEvent(@Param("tenantUuid") UUID tenantUuid, @Param("eventId") UUID eventId);

  /**
   * Ob das Ereignis im Kontext-Mandanten quittiert ist, in jedem Status ({@code PROCESSED} oder
   * {@code DEAD}).
   *
   * @param tenantUuid Scope der Quittung (tenantId des Ereignisses)
   * @param eventId ID des Ereignisses
   * @return {@code true}, wenn eine Quittung besteht
   */
  default boolean existsByEvent(final UUID tenantUuid, final UUID eventId) {
    return countByEvent(tenantUuid, eventId) > 0;
  }

  /**
   * Quittung eines Ereignisses im Kontext-Mandanten.
   *
   * @param tenantUuid Scope der Quittung (tenantId des Ereignisses)
   * @param eventId ID des Ereignisses
   * @return die Quittung, leer, wenn das Ereignis nicht quittiert ist
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_EREIGNIS_QUITTUNG WHERE TENANT_ID = :tenantId"
              + " AND MANDANT_UUID = :tenantUuid AND EREIGNIS_ID = :eventId",
      mssql =
          "SELECT * FROM [dbo].[TASK_EREIGNIS_QUITTUNG] WHERE TENANT_ID = :tenantId"
              + " AND MANDANT_UUID = :tenantUuid AND EREIGNIS_ID = :eventId")
  Optional<TaskEventReceipt> findByEvent(
      @Param("tenantUuid") UUID tenantUuid, @Param("eventId") UUID eventId);

  /**
   * Loescht Quittungen aller Mandanten, die vor dem Stichtag angelegt wurden (Aufbewahrung). Nur
   * unter {@code runAsSystem}.
   *
   * @param cutoff Stichtag als Wanduhr der Persistenz-Zone ({@code ANGELEGT_AM})
   * @return Zahl der geloeschten Zeilen
   */
  @GlobalQuery(
      reason =
          "Aufbewahrung: der Resync-Job loescht alte Ereignis-Quittungen aller Mandanten unter"
              + " runAsSystem; betrifft nur die Inbox-Zeilen, keine fachlichen Daten.")
  @NovaQuery(
      modifying = true,
      value = "DELETE FROM TASK_EREIGNIS_QUITTUNG WHERE ANGELEGT_AM < :cutoff",
      mssql = "DELETE FROM [dbo].[TASK_EREIGNIS_QUITTUNG] WHERE ANGELEGT_AM < :cutoff")
  int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
