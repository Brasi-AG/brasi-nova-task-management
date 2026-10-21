package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskUserState;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Erweiterung des generierten {@link BaseTaskUserStateRepository} mit benutzerscoped Abfragen.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskUserStateQueryRepository extends BaseTaskUserStateRepository {

  /**
   * Sucht den {@link TaskUserState} fuer eine spezifische Aufgabe und einen Benutzer.
   *
   * <p>Gibt maximal einen Eintrag zurueck (UNIQUE-Constraint auf TASK_ID + USER_REF).
   *
   * @param taskId  interne Aufgaben-ID
   * @param userRef UUID des Benutzers
   * @return Liste mit 0 oder 1 Eintraegen
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findByTaskIdAndUserRef(
      @Param("taskId") Long taskId, @Param("userRef") UUID userRef);

  /**
   * Sucht alle {@link TaskUserState}-Eintraege fuer eine Aufgabe.
   *
   * @param taskId interne Aufgaben-ID
   * @return alle aktiven Eintraege fuer die Aufgabe
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findByTaskId(@Param("taskId") Long taskId);

  /**
   * Liefert alle aktiven Teilnehmer einer Aufgabe (UNSUBSCRIBED_AT IS NULL).
   *
   * <p>Wird von {@link de.brasi.nova.taskmanagement.task.TaskCommandService#patch} verwendet,
   * um nach einem Commit alle betroffenen Benutzer-Inboxen zu invalidieren.
   * Kein {@code tenantId}-Parameter: automatisch vom KSF-NovaQueryTenantValidator gebunden.
   *
   * @param taskId interne Aufgaben-ID
   * @return alle aktiven (nicht abgemeldeten) Teilnehmer-Zustandseintraege
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND UNSUBSCRIBED_AT IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND UNSUBSCRIBED_AT IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findActiveUserRefsByTaskId(@Param("taskId") Long taskId);

  /**
   * Batch-Variante fuer die Inbox-Read-Anreicherung (BE-1): liefert die aktiven
   * {@link TaskUserState}-Rows des ANFRAGERS ({@code userRef}) fuer die uebergebene Aufgaben-Seite in
   * EINER Abfrage (konstante Query-Zahl, kein N+1). Pro (Aufgabe, Benutzer) existiert maximal eine
   * aktive Row (UNIQUE TASK_ID + USER_REF), daher je Aufgabe hoechstens ein Eintrag.
   *
   * <p>Liefert die persoenlichen Marker ({@code IS_PINNED/IS_FLAGGED/IS_MUTED}) sowie
   * {@code UNSUBSCRIBED_AT} (+ geerbtes {@code ANGELEGT_AM}) fuer die asOf-gebundene
   * Marker-/{@code exit}-Ableitung des Anfragers.
   *
   * <p>{@code taskIds} darf NIE leer sein (MSSQL {@code IN ()} -> Syntaxfehler); der Service guardet
   * die leere Seite. {@code :tenantId} wird automatisch vom KSF-NovaQueryTenantValidator gebunden.
   *
   * @param taskIds interne Aufgaben-IDs der Seite (nicht leer)
   * @param userRef UUID des Anfragers (Actor)
   * @return aktive UserState-Rows des Anfragers fuer diese Aufgaben (hoechstens eine je Aufgabe)
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID IN (:taskIds) "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND TASK_ID IN (:taskIds) "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findByTaskIdsAndUserRef(
      @Param("taskIds") Collection<Long> taskIds, @Param("userRef") UUID userRef);
}
