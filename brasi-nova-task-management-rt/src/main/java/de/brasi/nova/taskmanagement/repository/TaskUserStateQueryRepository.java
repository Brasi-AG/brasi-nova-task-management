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
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom KSF-{@code
 * NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 *
 * <p>{@code USER_TENANT_ID} wird wie {@code TENANT_ID} aus dem Kontext gebunden (Intake #170),
 * damit jede Lesung den vollen Unique-Schluessel (TASK_ID, USER_TENANT_ID, USER_REF) nutzt und
 * nicht die State-Zeilen anderer Personen der Aufgabe ueberstreicht (Deadlock-Flaeche unter
 * sperrendem READ COMMITTED). Vorbild: {@code PARTICIPANT_TENANT_ID} in {@link
 * TaskParticipantQueryRepository}.
 */
public interface TaskUserStateQueryRepository extends BaseTaskUserStateRepository {

  /**
   * Sucht den {@link TaskUserState} fuer eine spezifische Aufgabe und einen Benutzer.
   *
   * <p>Gibt maximal einen Eintrag zurueck (UNIQUE-Constraint auf TASK_ID, USER_TENANT_ID, USER_REF;
   * {@code USER_TENANT_ID} ist an den Kontext-Mandanten gebunden).
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers
   * @return Liste mit 0 oder 1 Eintraegen
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findByTaskIdAndUserRef(
      @Param("taskId") Long taskId, @Param("userRef") UUID userRef);

  /**
   * Wie {@link #findByTaskIdAndUserRef}, sperrt die Zeile aber bis zum Ende der umgebenden
   * Transaktion (Intake #150). Auf MSSQL nimmt {@code UPDLOCK, ROWLOCK} eine Update-Sperre:
   * konkurrierende Schreiber derselben Zeile warten bis zum Commit, reine Leser nicht. Dient dazu,
   * die eigene State-Zeile VOR der Aufgabenzeile zu sperren (Sperrfolge State vor Aufgabe, Karte
   * participants-actions), wenn sie erst nach der Aufgabensperre geschrieben wird.
   *
   * @param taskId interne Aufgaben-ID
   * @param userRef UUID des Benutzers
   * @return Liste mit 0 oder 1 Eintraegen
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] WITH (UPDLOCK, ROWLOCK) "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> lockByTaskIdAndUserRef(
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
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findByTaskId(@Param("taskId") Long taskId);

  /**
   * Liefert alle aktiven Teilnehmer einer Aufgabe (UNSUBSCRIBED_AT IS NULL).
   *
   * <p>Wird von {@link de.brasi.nova.taskmanagement.task.TaskCommandService#patch} verwendet, um
   * nach einem Commit alle betroffenen Benutzer-Inboxen zu invalidieren. Kein {@code
   * tenantId}-Parameter: automatisch vom KSF-NovaQueryTenantValidator gebunden.
   *
   * @param taskId interne Aufgaben-ID
   * @return alle aktiven (nicht abgemeldeten) Teilnehmer-Zustandseintraege
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND UNSUBSCRIBED_AT IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND UNSUBSCRIBED_AT IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findActiveUserRefsByTaskId(@Param("taskId") Long taskId);

  /**
   * Batch-Variante fuer die Inbox-Read-Anreicherung (BE-1): liefert die aktiven {@link
   * TaskUserState}-Rows des ANFRAGERS ({@code userRef}) fuer die uebergebene Aufgaben-Seite in
   * EINER Abfrage (konstante Query-Zahl, kein N+1). Pro (Aufgabe, Benutzer) existiert maximal eine
   * aktive Row (UNIQUE TASK_ID, USER_TENANT_ID, USER_REF; {@code USER_TENANT_ID} ist an den
   * Kontext-Mandanten gebunden), daher je Aufgabe hoechstens ein Eintrag.
   *
   * <p>Liefert die persoenlichen Marker ({@code IS_PINNED/IS_FLAGGED/IS_MUTED}) sowie {@code
   * UNSUBSCRIBED_AT} (+ geerbtes {@code ANGELEGT_AM}) fuer die asOf-gebundene Marker-/{@code
   * exit}-Ableitung des Anfragers.
   *
   * <p>{@code taskIds} darf NIE leer sein (MSSQL {@code IN ()} -> Syntaxfehler); der Service
   * guardet die leere Seite. {@code :tenantId} wird automatisch vom KSF-NovaQueryTenantValidator
   * gebunden.
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
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID IN (:taskIds) "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID IN (:taskIds) "
              + "AND USER_REF = :userRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findByTaskIdsAndUserRef(
      @Param("taskIds") Collection<Long> taskIds, @Param("userRef") UUID userRef);

  /**
   * Lesung fuer die Stummschalt-Regel der Benachrichtigungen (Intake #158): liefert die aktiven
   * {@link TaskUserState}-Rows einer Aufgabe, die stummgeschaltet sind ({@code IS_MUTED = 1}). Eine
   * Abfrage je Ereignis, kein N+1.
   *
   * <p>Bewusst OHNE Empfaengerliste: eine {@code IN}-Liste ueber alle Empfaenger koennte bei sehr
   * vielen Beteiligten das SQL-Server-Limit von 2100 Parametern ueberschreiten. Die Parameterzahl
   * bleibt hier konstant; die Menge stummer Personen je Aufgabe ist klein. Die Schnittmenge mit den
   * Empfaenger-Kandidaten bildet der Aufrufer ({@code NotificationMuteFilter}) in Java.
   *
   * <p>Benutzer ohne aktive Row fehlen im Ergebnis und gelten damit als nicht stumm. {@code
   * :tenantId} wird automatisch vom KSF-NovaQueryTenantValidator gebunden.
   *
   * @param taskId interne Aufgaben-ID
   * @return die stummgeschalteten aktiven Rows dieser Aufgabe (hoechstens eine je Benutzer)
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_USER_STATE "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND IS_MUTED = 1 "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_USER_STATE] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND USER_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND IS_MUTED = 1 "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskUserState> findMutedByTaskId(@Param("taskId") Long taskId);
}
