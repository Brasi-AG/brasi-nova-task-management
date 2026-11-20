package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskAttachment;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Handgeschriebene Erweiterung des generierten {@link BaseTaskAttachmentRepository} mit den
 * Lese-Abfragen der Anhaenge (Intake #152). Das scaffold-{@code api}-Modul bleibt unangetastet.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom KSF-{@code
 * NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden. {@code ENTITY_STATUS =
 * 'ACTIVE'} steht in jeder Abfrage ausdruecklich, denn {@code @NovaQuery} filtert den Status nicht
 * von selbst; entfernte Anhaenge (DEACTIVE) zaehlen und erscheinen nirgends.
 */
public interface TaskAttachmentQueryRepository extends BaseTaskAttachmentRepository {

  /**
   * Zaehlt die aktiven Anhaenge direkt an der Aufgabe (ohne Kommentar-Anhaenge).
   *
   * @param taskId interne Aufgaben-ID
   * @return Zahl der aktiven Aufgaben-Anhaenge
   */
  @NovaQuery(
      value =
          "SELECT COUNT(*) FROM TASK_ANHANG "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND KOMMENTAR_ID IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT COUNT(*) FROM [dbo].[TASK_ANHANG] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND KOMMENTAR_ID IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  long countActiveTaskAttachments(@Param("taskId") Long taskId);

  /**
   * Liefert die aktiven Anhaenge direkt an der Aufgabe (ohne Kommentar-Anhaenge) in
   * Hochlade-Reihenfolge.
   *
   * <p>Ordnung {@code ANGELEGT_AM ASC, ID ASC}: Bei gleichem Zeitstempel entscheidet die interne
   * {@code ID} (Sequenz, also die Reihenfolge des Hochladens), nicht die zufaellige {@code
   * EXTERNALID}.
   *
   * @param taskId interne Aufgaben-ID
   * @return aktive Aufgaben-Anhaenge, chronologisch aufsteigend
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_ANHANG "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND KOMMENTAR_ID IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM ASC, ID ASC",
      mssql =
          "SELECT * FROM [dbo].[TASK_ANHANG] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND KOMMENTAR_ID IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM ASC, ID ASC")
  List<TaskAttachment> findActiveTaskAttachments(@Param("taskId") Long taskId);

  /**
   * Liefert die aktiven Anhaenge aller Kommentare der Aufgabe in Hochlade-Reihenfolge, in einer
   * Abfrage fuer die ganze Kommentarliste (Intake #152 B1, kein N+1, keine {@code IN}-Liste).
   *
   * <p>Ob der Kommentar selbst noch aktiv ist, prueft die Abfrage nicht: der Aufrufer ordnet die
   * Anhaenge den aktiven Kommentaren zu, Anhaenge anderer Kommentare fallen dabei weg. Ordnung
   * {@code ANGELEGT_AM ASC, ID ASC}: Bei gleichem Zeitstempel entscheidet die interne {@code ID}
   * (Sequenz, also die Reihenfolge des Hochladens), nicht die zufaellige {@code EXTERNALID}.
   *
   * @param taskId interne Aufgaben-ID
   * @return aktive Kommentar-Anhaenge der Aufgabe, chronologisch aufsteigend
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_ANHANG "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND KOMMENTAR_ID IS NOT NULL "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM ASC, ID ASC",
      mssql =
          "SELECT * FROM [dbo].[TASK_ANHANG] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND KOMMENTAR_ID IS NOT NULL "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "ORDER BY ANGELEGT_AM ASC, ID ASC")
  List<TaskAttachment> findActiveCommentAttachmentsByTaskId(@Param("taskId") Long taskId);

  /**
   * Sucht einen aktiven Anhang der Aufgabe anhand seiner externen UUID, gleich ob er direkt an der
   * Aufgabe oder an einem ihrer Kommentare haengt. Der Aufrufer unterscheidet ueber {@code
   * commentId}.
   *
   * @param taskId interne Aufgaben-ID
   * @param attachmentUuid externe UUID des Anhangs
   * @return Liste mit 0 oder 1 Anhaengen (EXTERNALID ist eindeutig)
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_ANHANG "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND EXTERNALID = :attachmentUuid "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "SELECT * FROM [dbo].[TASK_ANHANG] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND EXTERNALID = :attachmentUuid "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskAttachment> findActiveByTaskIdAndUuid(
      @Param("taskId") Long taskId, @Param("attachmentUuid") UUID attachmentUuid);
}
