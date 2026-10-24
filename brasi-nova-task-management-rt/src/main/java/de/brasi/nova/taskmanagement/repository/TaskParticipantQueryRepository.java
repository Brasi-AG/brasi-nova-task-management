package de.brasi.nova.taskmanagement.repository;

import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import io.keel.framework.api.annotation.db.NovaQuery;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Erweiterung des generierten {@link BaseTaskParticipantRepository} mit Task-spezifischen Abfragen.
 *
 * <p>Kein {@code tenantId}-Parameter: {@code :tenantId} wird automatisch vom
 * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
 */
public interface TaskParticipantQueryRepository extends BaseTaskParticipantRepository {

  /**
   * Sucht den aktiven PERSON-{@link TaskParticipant} fuer eine Aufgabe und einen bestimmten
   * Teilnehmer im aktuellen (Task-)Mandanten.
   *
   * <p>Gibt maximal einen Eintrag zurueck.
   *
   * <p><b>Tenant-Scope-Sweep (Auflage 8/B2):</b> zusaetzlich zur Nova-Basisspalte {@code TENANT_ID}
   * wird die fachliche Teilnehmer-Mandantenspalte {@code PARTICIPANT_TENANT_ID = :tenantId} und
   * {@code PARTICIPANT_KIND = 'PERSON'} als Konjunkt geprueft. Der V3-Unique-Index
   * {@code (TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF)} erlaubt zwei Zeilen pro (Task, Ref)
   * mit unterschiedlichem {@code PARTICIPANT_TENANT_ID}; mit diesem Konjunkt faellt die
   * Anomalie-Zeile mit fremdem Teilnehmer-Mandanten heraus und es verbleibt genau die eine
   * task-mandanten-/PERSON-Zeile. {@code :tenantId} wird automatisch vom
   * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden.
   *
   * @param taskId           interne Aufgaben-ID
   * @param participantRef   UUID des Teilnehmers (PROD_BENUTZER.EXTERNALID)
   * @return Liste mit 0 oder 1 Eintraegen
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskParticipant> findByTaskIdAndParticipantRef(
      @Param("taskId") Long taskId, @Param("participantRef") UUID participantRef);

  /**
   * WRITE-Member-Existenzpruefung (Membership-Guard): gibt genau dann {@code true} zurueck, wenn
   * eine aktive, nicht ausgetretene PERSON-Teilnehmerzeile fuer {@code participantRef} an der
   * Aufgabe im Task-Mandanten existiert.
   *
   * <p>Volles WRITE-Member-Praedikat (SPEC, Membership-Praedikate): {@code PARTICIPANT_KIND='PERSON'
   * AND ENTITY_STATUS='ACTIVE' AND EXIT_KIND IS NULL AND TENANT_ID=:tenantId AND
   * PARTICIPANT_TENANT_ID=:tenantId AND TASK_ID=:taskId AND PARTICIPANT_REF=:participantRef}. Nutzt
   * den TASK_ID-praefixierten V3-Unique-Index (ein {@code EXISTS} pro Request, kein N+1).
   * {@code :tenantId} wird automatisch vom Framework gebunden.
   *
   * @param taskId         interne Aufgaben-ID
   * @param participantRef UUID des Teilnehmers (PROD_BENUTZER.EXTERNALID)
   * @return {@code true}, wenn der Teilnehmer aktiver WRITE-Member der Aufgabe ist
   */
  @NovaQuery(
      value =
          "SELECT CASE WHEN EXISTS ("
              + "SELECT 1 FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "AND EXIT_KIND IS NULL"
              + ") THEN 1 ELSE 0 END",
      mssql =
          "SELECT CASE WHEN EXISTS ("
              + "SELECT 1 FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE' "
              + "AND EXIT_KIND IS NULL"
              + ") THEN 1 ELSE 0 END")
  boolean existsWriteMember(
      @Param("taskId") Long taskId, @Param("participantRef") UUID participantRef);

  /**
   * READ-Member-Existenzpruefung (Membership-Guard): wie {@link #existsWriteMember}, aber
   * <b>ohne</b> {@code EXIT_KIND IS NULL}. Ein ausgetretener Nutzer (Zeile bleibt
   * {@code ENTITY_STATUS='ACTIVE'}, nur {@code EXIT_KIND} gesetzt) ist weiterhin READ-Member und
   * darf sein Archiv-Detail lesen.
   *
   * <p>Volles READ-Member-Praedikat: {@code PARTICIPANT_KIND='PERSON' AND ENTITY_STATUS='ACTIVE' AND
   * TENANT_ID=:tenantId AND PARTICIPANT_TENANT_ID=:tenantId AND TASK_ID=:taskId AND
   * PARTICIPANT_REF=:participantRef}. {@code :tenantId} wird automatisch gebunden.
   *
   * @param taskId         interne Aufgaben-ID
   * @param participantRef UUID des Teilnehmers (PROD_BENUTZER.EXTERNALID)
   * @return {@code true}, wenn der Teilnehmer READ-Member der Aufgabe ist (inkl. ausgetreten)
   */
  @NovaQuery(
      value =
          "SELECT CASE WHEN EXISTS ("
              + "SELECT 1 FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE'"
              + ") THEN 1 ELSE 0 END",
      mssql =
          "SELECT CASE WHEN EXISTS ("
              + "SELECT 1 FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef "
              + "AND ENTITY_STATUS = 'ACTIVE'"
              + ") THEN 1 ELSE 0 END")
  boolean existsReadMember(
      @Param("taskId") Long taskId, @Param("participantRef") UUID participantRef);

  /**
   * Unique-Key-exakter Lookup der einen Teilnehmerzeile einer Aufgabe, OHNE
   * Status-/{@code EXIT_KIND}-/{@code PARTICIPANT_KIND}-Filter (ensureAssignee-Auswahlvertrag,
   * SPEC/Auflage 8). Gefiltert wird GENAU auf den V3-Unique-Key
   * {@code (TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF)}; die Nova-Basisspalte
   * {@code TENANT_ID} ist bewusst NICHT Teil des Praedikats.
   *
   * <p><b>Warum kein {@code TENANT_ID}-Konjunkt (BLOCKER-Fix):</b> der V3-Unique-Key ist
   * {@code (TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF)} - die Basis-{@code TENANT_ID} gehoert
   * NICHT dazu. Mit {@code PARTICIPANT_TENANT_ID = :tenantId} (= Task-Mandant) liefert die Query
   * bereits genau {@code <=1} Zeile. Ein zusaetzlicher {@code TENANT_ID = :tenantId}-Konjunkt wuerde
   * eine Zeile mit passendem {@code PARTICIPANT_TENANT_ID}, aber abweichendem Basis-{@code TENANT_ID}
   * faelschlich "nicht gefunden" machen; dann koennte der Aufrufer (ensureAssignee, Slice 4) den
   * Fall "fremdes Basis-{@code TENANT_ID}" nicht als fail-closed 409 klassifizieren. Der Lookup
   * liefert die Unique-Key-Zeile daher UNABHAENGIG vom Basis-{@code TENANT_ID}; die Klassifikation
   * (PERSON reaktivieren vs. TEAM/fremdes Basis-{@code TENANT_ID} -> 409) erfolgt im Aufrufer.
   *
   * <p>{@code :tenantId} (an {@code PARTICIPANT_TENANT_ID} gebunden) wird automatisch vom
   * KSF-{@code NovaQueryTenantValidator} aus dem {@code TenantContextHolder} gebunden; der
   * Platzhalter erfuellt zugleich die Pflicht-Tenant-Filterung der Startup-Validierung.
   *
   * @param taskId         interne Aufgaben-ID
   * @param participantRef UUID des Teilnehmers (PROD_BENUTZER.EXTERNALID)
   * @return Liste mit 0 oder 1 Eintraegen (Unique-Key)
   */
  @NovaQuery(
      value =
          "SELECT * FROM TASK_PARTICIPANT "
              + "WHERE PARTICIPANT_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef",
      mssql =
          "SELECT * FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE PARTICIPANT_TENANT_ID = :tenantId "
              + "AND TASK_ID = :taskId "
              + "AND PARTICIPANT_REF = :participantRef")
  List<TaskParticipant> findRowByUniqueKey(
      @Param("taskId") Long taskId, @Param("participantRef") UUID participantRef);

  /**
   * Liefert alle aktiven Assignees (ASSIGNEE_FLAG=1) einer Aufgabe.
   *
   * <p>Wird von {@link de.brasi.nova.taskmanagement.participant.AssigneeService#updateAssignees}
   * verwendet, um die aktuellen Assignees mit der gewuenschten Menge zu vergleichen.
   *
   * @param taskId interne Aufgaben-ID
   * @return alle aktiven Assignee-Teilnehmer
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND ASSIGNEE_FLAG = 1 "
              + "AND EXIT_KIND IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID = :taskId "
              + "AND ASSIGNEE_FLAG = 1 "
              + "AND EXIT_KIND IS NULL "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskParticipant> findActiveAssigneesByTaskId(@Param("taskId") Long taskId);

  /**
   * Batch-Variante fuer die Inbox-Read-Anreicherung (BE-1): liefert ALLE aktiven
   * {@link TaskParticipant}-Rows der uebergebenen Aufgaben-Seite in EINER Abfrage (konstante
   * Query-Zahl, kein N+1).
   *
   * <p>Bewusst OHNE {@code EXIT_KIND}-Filter: der Anreicherungs-Assembler trennt selbst Membership
   * (Anzeige-Listen: {@code EXIT_KIND IS NULL}) von Actor-Austritt ({@code EXIT_KIND} gesetzt). So
   * stehen in derselben Ergebnismenge sowohl die Ersteller-/Assignee-/Teilnehmer-Rows als auch die
   * Austritts-Rows des Anfragers fuer die asOf-gebundene {@code exit}-Ableitung zur Verfuegung.
   *
   * <p>{@code taskIds} darf NIE leer sein (MSSQL {@code IN ()} -> Syntaxfehler); der Service guardet
   * die leere Seite vor dem Aufruf. {@code :tenantId} wird automatisch vom KSF-NovaQueryTenantValidator
   * aus dem {@code TenantContextHolder} gebunden (Mandanten-Isolation).
   *
   * @param taskIds interne Aufgaben-IDs der Seite (nicht leer)
   * @return alle aktiven Teilnehmer-Rows dieser Aufgaben (inkl. Rows mit gesetztem {@code EXIT_KIND})
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM TASK_PARTICIPANT "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID IN (:taskIds) "
              + "AND ENTITY_STATUS = 'ACTIVE'",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT * FROM [dbo].[TASK_PARTICIPANT] "
              + "WHERE TENANT_ID = :tenantId "
              + "AND PARTICIPANT_TENANT_ID = :tenantId "
              + "AND PARTICIPANT_KIND = 'PERSON' "
              + "AND TASK_ID IN (:taskIds) "
              + "AND ENTITY_STATUS = 'ACTIVE'")
  List<TaskParticipant> findActiveByTaskIds(@Param("taskIds") Collection<Long> taskIds);

  /**
   * Historische Archiv-Leser einer Aufgabe. Die aktive, tenantgleiche PERSON-Teilnehmerzeile ist
   * der READ-Membership-Anker; ein verwaister UserState darf keinen Generation-Bump ausloesen.
   * Deckt beide left-Wege ab: Participant-Austritt und persoenliches Abmelden im UserState.
   */
  @NovaQuery(
      value =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT p.* FROM TASK_PARTICIPANT p "
              + "WHERE p.TENANT_ID = :tenantId "
              + "AND p.PARTICIPANT_TENANT_ID = :tenantId "
              + "AND p.PARTICIPANT_KIND = 'PERSON' "
              + "AND p.TASK_ID = :taskId "
              + "AND p.ENTITY_STATUS = 'ACTIVE' "
              + "AND (p.EXIT_KIND IS NOT NULL OR EXISTS ("
              + "SELECT 1 FROM TASK_USER_STATE s "
              + "WHERE s.TENANT_ID = :tenantId AND s.TASK_ID = p.TASK_ID "
              + "AND s.USER_REF = p.PARTICIPANT_REF "
              + "AND s.ENTITY_STATUS = 'ACTIVE' AND s.UNSUBSCRIBED_AT IS NOT NULL))",
      mssql =
          "/* NOVA:DICTIONARY_VISIBILITY */ "
              + "SELECT p.* FROM [dbo].[TASK_PARTICIPANT] p "
              + "WHERE p.TENANT_ID = :tenantId "
              + "AND p.PARTICIPANT_TENANT_ID = :tenantId "
              + "AND p.PARTICIPANT_KIND = 'PERSON' "
              + "AND p.TASK_ID = :taskId "
              + "AND p.ENTITY_STATUS = 'ACTIVE' "
              + "AND (p.EXIT_KIND IS NOT NULL OR EXISTS ("
              + "SELECT 1 FROM [dbo].[TASK_USER_STATE] s "
              + "WHERE s.TENANT_ID = :tenantId AND s.TASK_ID = p.TASK_ID "
              + "AND s.USER_REF = p.PARTICIPANT_REF "
              + "AND s.ENTITY_STATUS = 'ACTIVE' AND s.UNSUBSCRIBED_AT IS NOT NULL))")
  List<TaskParticipant> findArchiveReadersByTaskId(@Param("taskId") Long taskId);
}
