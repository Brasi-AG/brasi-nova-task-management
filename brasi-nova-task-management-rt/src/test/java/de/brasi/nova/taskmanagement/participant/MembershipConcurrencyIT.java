package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.participant.model.ParticipantAddInput;
import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import de.brasi.nova.taskmanagement.push.NotificationPushEnvelope;
import de.brasi.nova.taskmanagement.push.NotificationPushPort;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskInboxGenerationQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskUserStateQueryRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationTypeRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TagSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskDirectionRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskPriorityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real-MSSQL-Nebenlaeufigkeits-IT fuer parallele Mitgliedschaftsaenderungen VERSCHIEDENER Personen
 * derselben Aufgabe (Intake #167, zusammen mit #166): {@code AssigneeService#updateAssignees} (PUT
 * /assignees), {@code #addParticipants} (POST /participants) und {@code #exit} (POST /exit,
 * UNSUBSCRIBED).
 *
 * <p><b>Befund:</b> die drei Pfade lesen die States anderer Personen ({@code
 * TaskUserStateQueryRepository#findActiveUserRefsByTaskId}, beim Austritt auch {@code
 * TaskParticipantQueryRepository#findArchiveReadersByTaskId}) erst NACH ihren eigenen Teilnehmer-
 * und State-Schreibungen. Unter sperrendem READ COMMITTED wartet diese Lesung auf die X-gesperrte
 * State-Zeile der anderen Transaktion und umgekehrt: Deadlock 1205 (HTTP 500), bevor der
 * TASK-Versionskonflikt (409) greift. Vorbild der Korrektur ist {@code #removeParticipant} (Intake
 * #145, {@link ParticipantRemoveConcurrencyIT}): alle Reads vor der ersten Schreibung.
 *
 * <p><b>Erzwungener Ablauf</b> je Szenario (zwei Transaktionen, Rollen Gewinnerin und Verliererin;
 * Sync-Punkte an den Produktiv-Seams, kein Zeitablauf):
 *
 * <ol>
 *   <li><b>Sync 1</b> am Seam {@code onBeforeMembershipWritesForTest} (nach den Vorab-Reads, vor
 *       der ersten Schreibung): beide haben die Aufgabe mit derselben VERSION gelesen und halten
 *       keine Schreibsperren. Jede merkt sich ihre Sitzung ({@code @@SPID}).
 *   <li>Die Gewinnerin schreibt ihre Teilnehmer- und State-Zeilen und erreicht <b>Sync 2</b> am
 *       Seam {@code onAfterMembershipWritesForTest} (vor Historie, Benachrichtigung, TASK-save und
 *       Bump). Erst dann schreibt die Verliererin ihre Zeilen, waehrend die Gewinnerin ihre
 *       X-Sperren haelt.
 *   <li>An Sync 2 halten beide ihre X-Sperren. Die Gewinnerin laeuft weiter (Historie,
 *       Benachrichtigung, TASK-save, Bump, Commit); die Verliererin wartet, bis die Gewinnerin
 *       fertig ist.
 *   <li>Die Verliererin laeuft weiter: PUT und Austritt treffen beim versionierten TASK-save die
 *       veraltete VERSION ({@link BusinessRuleException} mit {@link
 *       OptimisticLockingFailureException}, 409, voller Rollback). Das Hinzufuegen speichert die
 *       Aufgabe heute nicht; dort sind beide Ausgaenge zulaessig (beide erfolgreich oder genau eine
 *       409 mit vollem Rollback; die fachliche Entscheidung steht aus).
 * </ol>
 *
 * <p><b>Warten wird beobachtet, nicht abgewartet:</b> wer an einem Sync-Punkt auf die andere
 * wartet, fragt ueber eine unabhaengige master-Verbindung {@code sys.dm_exec_requests} ab, ob die
 * andere Sitzung auf eine Sperre der eigenen wartet ({@code blocking_session_id}). Dann ist der
 * Ablauf entschieden: der Wartegrund (Art, Ressource, Anfang der SQL-Anweisung) wird vermerkt und
 * der Sync-Punkt sofort verlassen. Mit dem heutigen Code wartet die Gewinnerin nach Sync 2 in der
 * spaeten Leser-Lesung auf die State-Zeile der Verliererin; die Verliererin laeuft dann in dieselbe
 * Lesung, SQL Server erkennt den Zyklus und bricht eine als Deadlock-Opfer ab (1205). Der Test wird
 * dadurch rot mit 1205 im Fehlerbild, nicht an einem Zeitablauf. Ohne VIEW SERVER STATE sieht die
 * Sonde fremde Sitzungen nicht; dann laeuft der Sync-Punkt nach {@link #GATE_TIMEOUT_MS} ab, der
 * Deadlock folgt genauso.
 *
 * <p><b>Ausfuehrungsplaene wie im Betrieb:</b> die Teilnehmer- und State-Tabellen werden mit
 * Fremdzeilen anderer Aufgaben aufgefuellt und die Statistiken aktualisiert. In einer Tabelle mit
 * einer Handvoll Zeilen waehlt SQL Server sonst Scans, und schon die Einzel-Lookups je Person
 * stiessen auf die X-Sperren der anderen Transaktion (Nebenpunkt in #167, nur bei Scan-Plaenen
 * riskant). Wartet trotzdem eine Lesung der Verliererin vor Sync 2 auf die Gewinnerin, ist auch das
 * rot und im Fehlerbild benannt.
 *
 * <p>Voraussetzung: die Wegwerf-DB laeuft mit sperrendem READ COMMITTED (kein RCSI), sonst warten
 * Lesungen nicht auf Schreibsperren und der Test bewiese nichts; das prueft jedes Szenario zuerst.
 * Jedes Szenario hat eine eigene Aufgabe.
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = MembershipConcurrencyIT.ItConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.banner-mode=off",
      "spring.cloud.config.enabled=false",
      "spring.cloud.bootstrap.enabled=false",
      "spring.cloud.config.import-check.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.flyway.enabled=true",
      "spring.flyway.baseline-on-migrate=true",
      "spring.flyway.baseline-version=0",
      "spring.flyway.locations=classpath:db/migration",
      "ksf.persistence.dialect=MSSQL",
      "ksf.persistence.ddl-auto=none",
      // Zwei offene Tx-Verbindungen plus je eine fuer die Sequenz-Vergabe (REQUIRES_NEW).
      "spring.datasource.hikari.maximum-pool-size=6",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class MembershipConcurrencyIT {

  private static final long TENANT_ID = 597L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000597");

  /** Ersteller aller Aufgaben (Creator und Assignee). */
  private static final UUID CREATOR_REF = UUID.fromString("00000059-00a0-0059-0000-000000000597");

  /** Weiterer aktiver Assignee; Akteur der Verlierer-Transaktion bei PUT und Hinzufuegen. */
  private static final UUID ACTOR_A_REF = UUID.fromString("00000059-00a1-0059-0000-000000000597");

  /** Person B: Ziel bzw. Akteur der Gewinner-Transaktion. */
  private static final UUID PERSON_B = UUID.fromString("00000059-00b0-0059-0000-000000000597");

  /** Person C: Ziel bzw. Akteur der Verlierer-Transaktion. */
  private static final UUID PERSON_C = UUID.fromString("00000059-00c0-0059-0000-000000000597");

  /** Aufzunehmende Person der Gewinner-Transaktion (ausgetreten, wird reaktiviert). */
  private static final UUID NEW_P1 = UUID.fromString("00000059-00d1-0059-0000-000000000597");

  /** Aufzunehmende Person der Verlierer-Transaktion (ausgetreten, wird reaktiviert). */
  private static final UUID NEW_P2 = UUID.fromString("00000059-00d2-0059-0000-000000000597");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  private static final long TASK_PUT_ID = 597101L;
  private static final UUID TASK_PUT_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000597101");

  private static final long TASK_EXIT_ID = 597102L;
  private static final UUID TASK_EXIT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000597102");

  private static final long TASK_ADD_ID = 597103L;
  private static final UUID TASK_ADD_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000597103");

  private static final long TASK_MIXED_ID = 597104L;
  private static final UUID TASK_MIXED_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000597104");

  /** Fremdzeilen je Tabelle (siehe Klassen-Javadoc, Ausfuehrungsplaene). */
  private static final int PADDING_ROWS = 2000;

  /** Fehlercode des SQL-Server-Deadlock-Opfers. */
  private static final int DEADLOCK_ERROR = 1205;

  /** Sicherung gegen Haengen an den Sync-Punkten; ein Ablauf macht den Test rot. */
  private static final long GATE_TIMEOUT_MS = 30_000L;

  /** Abfrage-Takt der Sperr-Sonde an den Sync-Punkten. */
  private static final long POLL_MS = 25L;

  private static final long JOIN_TIMEOUT_MS = 120_000L;

  private static final String WINNER = "winner";
  private static final String LOSER = "loser";

  /** Thread-lokaler Akteur (der UserAccessor liest ihn). */
  private static final ThreadLocal<UUID> ACTOR_PER_THREAD =
      ThreadLocal.withInitial(() -> CREATOR_REF);

  /** Thread-lokale Rolle an den Sync-Punkten (Gewinnerin oder Verliererin). */
  private static final ThreadLocal<String> ROLE_PER_THREAD = new ThreadLocal<>();

  /** Sync-Punkte des laufenden Szenarios; von den Seams gelesen. */
  private static final AtomicReference<Gates> GATES = new AtomicReference<>();

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestAssigneeService service;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecordingInboxSignalPort inboxSignalPort;
  @Autowired private RecordingNotificationPushPort notificationPushPort;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    master = RefSchemaItDb.masterDataSource();
    dbName = RefSchemaItDb.createThrowawayDb(master);
    registry.add("spring.datasource.url", () -> RefSchemaItDb.throwawayJdbcUrl(dbName));
    registry.add("spring.datasource.username", RefSchemaItEnv::user);
    registry.add("spring.datasource.password", RefSchemaItEnv::password);
    registry.add(
        "spring.datasource.driver-class-name",
        () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
  }

  @AfterAll
  static void teardown() {
    try {
      if (master != null && dbName != null) {
        RefSchemaItDb.dropThrowawayDb(master, dbName);
      }
    } finally {
      if (master != null) {
        master.close();
      }
    }
  }

  @BeforeAll
  static void seed(@Autowired final JdbcTemplate jt) {
    seedFirma(jt, TENANT_ID, TENANT_UUID);
    seedBenutzer(jt, 597501L, CREATOR_REF, "creator@brasi.de");
    seedBenutzer(jt, 597502L, ACTOR_A_REF, "actor-a@brasi.de");
    seedBenutzer(jt, 597503L, PERSON_B, "person-b@brasi.de");
    seedBenutzer(jt, 597504L, PERSON_C, "person-c@brasi.de");
    seedBenutzer(jt, 597505L, NEW_P1, "new-p1@brasi.de");
    seedBenutzer(jt, 597506L, NEW_P2, "new-p2@brasi.de");

    // PUT x PUT: Ersteller, A, B und C sind aktive Assignees.
    insertTask(jt, TASK_PUT_ID, TASK_PUT_UUID, "I167-PUT-parallel");
    insertParticipant(jt, 597211L, TASK_PUT_ID, CREATOR_REF, true, true, false);
    insertParticipant(jt, 597212L, TASK_PUT_ID, ACTOR_A_REF, false, true, false);
    insertParticipant(jt, 597213L, TASK_PUT_ID, PERSON_B, false, true, false);
    insertParticipant(jt, 597214L, TASK_PUT_ID, PERSON_C, false, true, false);
    insertUserState(jt, 597311L, TASK_PUT_ID, CREATOR_REF, false);
    insertUserState(jt, 597312L, TASK_PUT_ID, ACTOR_A_REF, false);
    insertUserState(jt, 597313L, TASK_PUT_ID, PERSON_B, false);
    insertUserState(jt, 597314L, TASK_PUT_ID, PERSON_C, false);

    // exit x exit: Ersteller, B und C (reine Beteiligte).
    insertTask(jt, TASK_EXIT_ID, TASK_EXIT_UUID, "I167-Austritt-parallel");
    insertParticipant(jt, 597221L, TASK_EXIT_ID, CREATOR_REF, true, true, false);
    insertParticipant(jt, 597222L, TASK_EXIT_ID, PERSON_B, false, false, false);
    insertParticipant(jt, 597223L, TASK_EXIT_ID, PERSON_C, false, false, false);
    insertUserState(jt, 597321L, TASK_EXIT_ID, CREATOR_REF, false);
    insertUserState(jt, 597322L, TASK_EXIT_ID, PERSON_B, false);
    insertUserState(jt, 597323L, TASK_EXIT_ID, PERSON_C, false);

    // add x add: Ersteller und A aktiv; P1 und P2 ausgetreten (Archivleser), werden reaktiviert.
    // Reaktivierung statt Erst-Insert: sie allokiert keine Sequenz und legt keine neuen
    // Index-Schluessel an (Lesson concurrency-test-seams); der spaete Read ist derselbe.
    insertTask(jt, TASK_ADD_ID, TASK_ADD_UUID, "I167-Hinzufuegen-parallel");
    insertParticipant(jt, 597231L, TASK_ADD_ID, CREATOR_REF, true, true, false);
    insertParticipant(jt, 597232L, TASK_ADD_ID, ACTOR_A_REF, false, true, false);
    insertParticipant(jt, 597233L, TASK_ADD_ID, NEW_P1, false, false, true);
    insertParticipant(jt, 597234L, TASK_ADD_ID, NEW_P2, false, false, true);
    insertUserState(jt, 597331L, TASK_ADD_ID, CREATOR_REF, false);
    insertUserState(jt, 597332L, TASK_ADD_ID, ACTOR_A_REF, false);
    insertUserState(jt, 597333L, TASK_ADD_ID, NEW_P1, true);
    insertUserState(jt, 597334L, TASK_ADD_ID, NEW_P2, true);

    // PUT x exit: Ersteller und B Assignees, C reiner Beteiligter.
    insertTask(jt, TASK_MIXED_ID, TASK_MIXED_UUID, "I167-PUT-gegen-Austritt");
    insertParticipant(jt, 597241L, TASK_MIXED_ID, CREATOR_REF, true, true, false);
    insertParticipant(jt, 597242L, TASK_MIXED_ID, PERSON_B, false, true, false);
    insertParticipant(jt, 597243L, TASK_MIXED_ID, PERSON_C, false, false, false);
    insertUserState(jt, 597341L, TASK_MIXED_ID, CREATOR_REF, false);
    insertUserState(jt, 597342L, TASK_MIXED_ID, PERSON_B, false);
    insertUserState(jt, 597343L, TASK_MIXED_ID, PERSON_C, false);

    padMembershipTables(jt);
  }

  // ---- Szenarien
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "i167_put_x_put: Nutzer 1 laesst B weg, Nutzer 2 laesst C weg; beide halten ihre X-Sperren"
          + " -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_put_omitting_different_persons_204_and_409_never_deadlock() {
    assertLockingReadCommitted();
    final int taskVersionBefore = taskVersion(TASK_PUT_ID);
    final int loseParticipantVersionBefore = participantVersion(TASK_PUT_ID, PERSON_C);
    final int loseStateVersionBefore = userStateVersion(TASK_PUT_ID, PERSON_C);

    final Outcome outcome =
        runPair(
            "i167-put-put",
            CREATOR_REF,
            () ->
                service.updateAssignees(
                    TASK_PUT_UUID,
                    new AssigneeUpdateInput(Set.of(CREATOR_REF, ACTOR_A_REF, PERSON_C))),
            ACTOR_A_REF,
            () ->
                service.updateAssignees(
                    TASK_PUT_UUID,
                    new AssigneeUpdateInput(Set.of(CREATOR_REF, ACTOR_A_REF, PERSON_B))));

    assertNoDeadlockAndBothHeldLocks(outcome);
    assertWinnerCommittedLoserVersionConflict(outcome);

    assertThat(taskVersion(TASK_PUT_ID))
        .as("TASK-VERSION genau +1")
        .isEqualTo(taskVersionBefore + 1);
    assertThat(exitedUnsubscribed(TASK_PUT_ID, PERSON_B)).as("B ausgetreten").isEqualTo(1);
    assertThat(userStateUnsubscribed(TASK_PUT_ID, PERSON_B)).as("State B abgemeldet").isEqualTo(1);
    assertUntouched(
        TASK_PUT_ID, PERSON_C, true, loseParticipantVersionBefore, loseStateVersionBefore);
    assertSingleSignalContaining(PERSON_B);
  }

  @Test
  @DisplayName(
      "i167_exit_x_exit: B und C treten gleichzeitig aus (UNSUBSCRIBED); beide halten ihre"
          + " X-Sperren -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_exit_of_different_persons_204_and_409_never_deadlock() {
    assertLockingReadCommitted();
    final int taskVersionBefore = taskVersion(TASK_EXIT_ID);
    final int loseParticipantVersionBefore = participantVersion(TASK_EXIT_ID, PERSON_C);
    final int loseStateVersionBefore = userStateVersion(TASK_EXIT_ID, PERSON_C);

    final Outcome outcome =
        runPair(
            "i167-exit-exit",
            PERSON_B,
            () -> service.exit(TASK_EXIT_UUID, new ExitInput(ExitKind.UNSUBSCRIBED)),
            PERSON_C,
            () -> service.exit(TASK_EXIT_UUID, new ExitInput(ExitKind.UNSUBSCRIBED)));

    assertNoDeadlockAndBothHeldLocks(outcome);
    assertWinnerCommittedLoserVersionConflict(outcome);

    assertThat(taskVersion(TASK_EXIT_ID))
        .as("TASK-VERSION genau +1")
        .isEqualTo(taskVersionBefore + 1);
    assertThat(exitedUnsubscribed(TASK_EXIT_ID, PERSON_B)).as("B ausgetreten").isEqualTo(1);
    assertThat(userStateUnsubscribed(TASK_EXIT_ID, PERSON_B)).as("State B abgemeldet").isEqualTo(1);
    assertUntouched(
        TASK_EXIT_ID, PERSON_C, false, loseParticipantVersionBefore, loseStateVersionBefore);
    assertThat(historyCount(TASK_EXIT_ID, PERSON_B)).as("Historie der Gewinnerin").isEqualTo(1);
    assertThat(historyCount(TASK_EXIT_ID, PERSON_C)).as("keine Historie der Verliererin").isZero();
    assertSingleSignalContaining(PERSON_B);
  }

  @Test
  @DisplayName(
      "i167_add_x_add: zwei POST /participants mit verschiedenen Personen; beide halten ihre"
          + " X-Sperren -> beide 204 oder genau eine 409 mit vollem Rollback, nie 1205/500")
  void i167_parallel_add_of_different_persons_never_deadlock() {
    assertLockingReadCommitted();
    final int p1ParticipantVersionBefore = participantVersion(TASK_ADD_ID, NEW_P1);
    final int p1StateVersionBefore = userStateVersion(TASK_ADD_ID, NEW_P1);
    final int p2ParticipantVersionBefore = participantVersion(TASK_ADD_ID, NEW_P2);
    final int p2StateVersionBefore = userStateVersion(TASK_ADD_ID, NEW_P2);

    final Outcome outcome =
        runPair(
            "i167-add-add",
            CREATOR_REF,
            () -> service.addParticipants(TASK_ADD_UUID, new ParticipantAddInput(List.of(NEW_P1))),
            ACTOR_A_REF,
            () -> service.addParticipants(TASK_ADD_UUID, new ParticipantAddInput(List.of(NEW_P2))));

    assertNoDeadlockAndBothHeldLocks(outcome);

    // Fachlich offen (Stefan): beide gelingen ODER genau eine bekommt 409. Nie beide abgelehnt, nie
    // ein anderer Fehler als die 409.
    final boolean winnerAdded = outcome.winnerError() == null;
    final boolean loserAdded = outcome.loserError() == null;
    assertThat(winnerAdded || loserAdded).as("mindestens eine Aufnahme gelingt").isTrue();
    for (final Throwable error : outcome.errors()) {
      assertThat(error)
          .as("eine abgelehnte Aufnahme ist 409 (BusinessRuleException), nie 500")
          .isInstanceOf(BusinessRuleException.class);
    }
    assertAddEffect(NEW_P1, winnerAdded, p1ParticipantVersionBefore, p1StateVersionBefore);
    assertAddEffect(NEW_P2, loserAdded, p2ParticipantVersionBefore, p2StateVersionBefore);
    assertThat(inboxSignalPort.sends())
        .as("je gelungener Aufnahme genau ein Signal, keines aus einem Rollback")
        .hasSize((winnerAdded ? 1 : 0) + (loserAdded ? 1 : 0));
  }

  @Test
  @DisplayName(
      "i167_put_x_exit: der Ersteller laesst B weg, C tritt gleichzeitig aus; beide halten ihre"
          + " X-Sperren -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_put_and_exit_of_different_persons_204_and_409_never_deadlock() {
    assertLockingReadCommitted();
    final int taskVersionBefore = taskVersion(TASK_MIXED_ID);
    final int loseParticipantVersionBefore = participantVersion(TASK_MIXED_ID, PERSON_C);
    final int loseStateVersionBefore = userStateVersion(TASK_MIXED_ID, PERSON_C);

    final Outcome outcome =
        runPair(
            "i167-put-exit",
            CREATOR_REF,
            () ->
                service.updateAssignees(
                    TASK_MIXED_UUID, new AssigneeUpdateInput(Set.of(CREATOR_REF))),
            PERSON_C,
            () -> service.exit(TASK_MIXED_UUID, new ExitInput(ExitKind.UNSUBSCRIBED)));

    assertNoDeadlockAndBothHeldLocks(outcome);
    assertWinnerCommittedLoserVersionConflict(outcome);

    assertThat(taskVersion(TASK_MIXED_ID))
        .as("TASK-VERSION genau +1")
        .isEqualTo(taskVersionBefore + 1);
    assertThat(exitedUnsubscribed(TASK_MIXED_ID, PERSON_B)).as("B ausgetreten").isEqualTo(1);
    assertThat(userStateUnsubscribed(TASK_MIXED_ID, PERSON_B))
        .as("State B abgemeldet")
        .isEqualTo(1);
    assertUntouched(
        TASK_MIXED_ID, PERSON_C, false, loseParticipantVersionBefore, loseStateVersionBefore);
    assertThat(historyCount(TASK_MIXED_ID, PERSON_C)).as("keine Historie der Verliererin").isZero();
    assertSingleSignalContaining(PERSON_B);
  }

  // ---- Ablauf und gemeinsame Pruefungen
  // ----------------------------------------------------------

  /**
   * Ergebnis eines Szenarios: Fehler beider Transaktionen und die Beobachtungen der Sync-Punkte.
   */
  record Outcome(Throwable winnerError, Throwable loserError, Gates gates) {

    List<Throwable> errors() {
      final List<Throwable> errors = new ArrayList<>();
      if (winnerError != null) {
        errors.add(winnerError);
      }
      if (loserError != null) {
        errors.add(loserError);
      }
      return errors;
    }
  }

  private Outcome runPair(
      final String label,
      final UUID winnerActor,
      final Runnable winnerCall,
      final UUID loserActor,
      final Runnable loserCall) {
    inboxSignalPort.clear();
    notificationPushPort.clear();
    final Gates gates = new Gates(jdbcTemplate, new JdbcTemplate(master));
    GATES.set(gates);
    final AtomicReference<Throwable> winnerError = new AtomicReference<>();
    final AtomicReference<Throwable> loserError = new AtomicReference<>();
    final Thread winner =
        pairThread(label + "-winner", winnerActor, WINNER, winnerCall, winnerError, gates);
    final Thread loser =
        pairThread(label + "-loser", loserActor, LOSER, loserCall, loserError, gates);
    try {
      winner.start();
      loser.start();
      joinOrFail(winner);
      joinOrFail(loser);
    } finally {
      GATES.set(null);
    }
    assertThat(winner.isAlive() || loser.isAlive()).as("beide Threads beendet").isFalse();
    return new Outcome(winnerError.get(), loserError.get(), gates);
  }

  private static Thread pairThread(
      final String name,
      final UUID actorRef,
      final String role,
      final Runnable call,
      final AtomicReference<Throwable> error,
      final Gates gates) {
    return new Thread(
        () -> {
          TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
          ACTOR_PER_THREAD.set(actorRef);
          ROLE_PER_THREAD.set(role);
          try {
            call.run();
          } catch (final Throwable t) {
            error.set(t);
          } finally {
            gates.finished(role);
            ROLE_PER_THREAD.remove();
            ACTOR_PER_THREAD.remove();
            TenantContextHolder.clear();
          }
        },
        name);
  }

  private static void joinOrFail(final Thread thread) {
    try {
      thread.join(JOIN_TIMEOUT_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Warten auf " + thread.getName() + " unterbrochen", e);
    }
    if (thread.isAlive()) {
      thread.interrupt();
    }
  }

  private void assertLockingReadCommitted() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()",
                Boolean.class))
        .as("Voraussetzung: sperrendes READ COMMITTED (kein RCSI), sonst warten Lesungen nicht")
        .isFalse();
  }

  /**
   * Kein Deadlock und der erzwungene Ablauf wurde wirklich erreicht. Die 1205-Pruefung steht vorn,
   * damit das Fehlerbild den Deadlock nennt; die Wartegruende der Sonde stehen in ihrer Meldung.
   */
  private static void assertNoDeadlockAndBothHeldLocks(final Outcome outcome) {
    final Gates gates = outcome.gates();
    assertThat(deadlockVictims(outcome))
        .as(
            "kein Deadlock-Opfer %d (500 statt 409). Gewinnerin wartete nach Sync 2 auf die"
                + " Verliererin: %s. Verliererin wartete vor Sync 2 auf die Gewinnerin: %s",
            DEADLOCK_ERROR, gates.winnerBlockedByLoser.get(), gates.loserBlockedByWinner.get())
        .isEmpty();
    assertThat(gates.winnerBlockedByLoser.get())
        .as("nach Sync 2 wartet keine Lesung der Gewinnerin auf eine X-Sperre der Verliererin")
        .isNull();
    assertThat(gates.loserBlockedByWinner.get())
        .as("vor Sync 2 wartet keine Lesung der Verliererin auf eine X-Sperre der Gewinnerin")
        .isNull();
    assertThat(gates.gateTimedOut.get()).as("kein Ablauf an den Sync-Punkten").isFalse();
    assertThat(gates.membershipWritesReached.get())
        .as("beide hielten ihre Teilnehmer- und State-Sperren gleichzeitig (Sync 2)")
        .isEqualTo(2);
  }

  private static void assertWinnerCommittedLoserVersionConflict(final Outcome outcome) {
    assertThat(outcome.winnerError()).as("Gewinnerin: 204 ohne Fehler").isNull();
    assertThat(outcome.loserError())
        .as("Verliererin: 409 aus dem TASK-Versionskonflikt")
        .isInstanceOf(BusinessRuleException.class)
        .hasCauseInstanceOf(OptimisticLockingFailureException.class);
  }

  private static List<String> deadlockVictims(final Outcome outcome) {
    final List<String> victims = new ArrayList<>();
    if (isDeadlock(outcome.winnerError())) {
      victims.add("Gewinnerin: " + outcome.winnerError());
    }
    if (isDeadlock(outcome.loserError())) {
      victims.add("Verliererin: " + outcome.loserError());
    }
    return victims;
  }

  /** Deadlock-Opfer: Spring-Sperrfehler oder SQL-Fehler 1205 irgendwo in der Ursachenkette. */
  private static boolean isDeadlock(final Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof PessimisticLockingFailureException) {
        return true;
      }
      if (current instanceof SQLException sql && sql.getErrorCode() == DEADLOCK_ERROR) {
        return true;
      }
      if (String.valueOf(current.getMessage()).contains("deadlock victim")) {
        return true;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return false;
  }

  /** Die Verlierer-Person ist unveraendert: Teilnehmerzeile, State und Rolle wie vorher. */
  private void assertUntouched(
      final long taskId,
      final UUID ref,
      final boolean assignee,
      final int participantVersionBefore,
      final int stateVersionBefore) {
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND"
                    + " PARTICIPANT_REF=? AND EXIT_KIND IS NULL AND EXIT_AT IS NULL"
                    + " AND ASSIGNEE_FLAG=? AND ENTITY_STATUS='ACTIVE'",
                taskId,
                ref,
                assignee ? 1 : 0))
        .as("%s weiter aktives Mitglied mit unveraenderter Rolle", ref)
        .isEqualTo(1);
    assertThat(participantVersion(taskId, ref))
        .as("Teilnehmerzeile %s unveraendert (voller Rollback)", ref)
        .isEqualTo(participantVersionBefore);
    assertThat(userStateUnsubscribed(taskId, ref)).as("State %s aktiv", ref).isZero();
    assertThat(userStateVersion(taskId, ref))
        .as("State %s unveraendert (voller Rollback)", ref)
        .isEqualTo(stateVersionBefore);
  }

  /** Aufnahme wirksam (reiner Teilnehmer, State aktiv, PARTICIPANT gemeldet) oder voll zurueck. */
  private void assertAddEffect(
      final UUID ref,
      final boolean added,
      final int participantVersionBefore,
      final int stateVersionBefore) {
    if (added) {
      assertThat(
              count(
                  "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND"
                      + " PARTICIPANT_REF=? AND EXIT_KIND IS NULL AND PARTICIPANT_FLAG=1"
                      + " AND ASSIGNEE_FLAG=0 AND ENTITY_STATUS='ACTIVE'",
                  TASK_ADD_ID,
                  ref))
          .as("%s als reiner Teilnehmer aufgenommen", ref)
          .isEqualTo(1);
      assertThat(userStateUnsubscribed(TASK_ADD_ID, ref)).as("State %s aktiv", ref).isZero();
      assertThat(notificationCount(TASK_ADD_ID, ref)).as("PARTICIPANT an %s", ref).isEqualTo(1);
    } else {
      assertThat(exitedUnsubscribed(TASK_ADD_ID, ref))
          .as("%s weiter ausgetreten", ref)
          .isEqualTo(1);
      assertThat(participantVersion(TASK_ADD_ID, ref))
          .as("Teilnehmerzeile %s unveraendert (voller Rollback)", ref)
          .isEqualTo(participantVersionBefore);
      assertThat(userStateUnsubscribed(TASK_ADD_ID, ref))
          .as("State %s abgemeldet", ref)
          .isEqualTo(1);
      assertThat(userStateVersion(TASK_ADD_ID, ref))
          .as("State %s unveraendert (voller Rollback)", ref)
          .isEqualTo(stateVersionBefore);
      assertThat(notificationCount(TASK_ADD_ID, ref))
          .as("keine Benachrichtigung an %s", ref)
          .isZero();
    }
  }

  /** Genau ein After-Commit-Signal (nur die Gewinnerin committet), das die Person enthaelt. */
  private void assertSingleSignalContaining(final UUID ref) {
    assertThat(inboxSignalPort.sends()).as("genau ein Signal (Gewinnerin)").hasSize(1);
    assertThat(inboxSignalPort.sends().get(0)).as("Signal enthaelt %s", ref).contains(ref);
  }

  // ---- Sync-Punkte
  // -------------------------------------------------------------------------------

  /**
   * Sync-Punkte eines Szenarios mit Sperr-Sonde. Ablaeufe und beobachtete Sperr-Wartezustaende
   * werden vermerkt und machen den Test rot.
   */
  static final class Gates {
    private final JdbcTemplate txJdbc;
    private final JdbcTemplate probe;
    private final CyclicBarrier beforeWrites = new CyclicBarrier(2);
    private final CountDownLatch winnerWritesDone = new CountDownLatch(1);
    private final CountDownLatch loserWritesDone = new CountDownLatch(1);
    private final CountDownLatch winnerDone = new CountDownLatch(1);
    private final AtomicInteger winnerSession = new AtomicInteger();
    private final AtomicInteger loserSession = new AtomicInteger();
    private final AtomicInteger membershipWritesReached = new AtomicInteger();
    private final AtomicBoolean gateTimedOut = new AtomicBoolean(false);
    private final AtomicReference<String> winnerBlockedByLoser = new AtomicReference<>();
    private final AtomicReference<String> loserBlockedByWinner = new AtomicReference<>();

    /**
     * @param txJdbc JdbcTemplate der Anwendung; im Thread einer Transaktion laeuft er auf deren
     *     Verbindung (fuer {@code @@SPID})
     * @param probe unabhaengige master-Verbindung fuer {@code sys.dm_exec_requests}
     */
    Gates(final JdbcTemplate txJdbc, final JdbcTemplate probe) {
      this.txJdbc = txJdbc;
      this.probe = probe;
    }

    /** Sync 1: Sitzung merken, beide treffen sich; die Verliererin schreibt erst nach Sync 2. */
    void beforeWrites() {
      final String role = ROLE_PER_THREAD.get();
      if (role == null) {
        return;
      }
      final Integer session = txJdbc.queryForObject("SELECT @@SPID", Integer.class);
      (WINNER.equals(role) ? winnerSession : loserSession).set(session == null ? 0 : session);
      try {
        beforeWrites.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (final TimeoutException | BrokenBarrierException e) {
        gateTimedOut.set(true);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        gateTimedOut.set(true);
      }
      if (LOSER.equals(role)) {
        // Die Gewinnerin haelt keine Sperre, auf die die Verliererin hier warten koennte.
        try {
          if (!winnerWritesDone.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            gateTimedOut.set(true);
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          gateTimedOut.set(true);
        }
      }
    }

    /**
     * Sync 2: beide halten ihre X-Sperren. Die Gewinnerin wartet auf die Schreibungen der
     * Verliererin, die Verliererin auf das Ende der Gewinnerin; wartet die jeweils andere dabei auf
     * eine Sperre der eigenen Sitzung, wird das vermerkt und der Sync-Punkt verlassen.
     */
    void afterMembershipWrites() {
      final String role = ROLE_PER_THREAD.get();
      if (role == null) {
        return;
      }
      membershipWritesReached.incrementAndGet();
      if (WINNER.equals(role)) {
        winnerWritesDone.countDown();
        awaitOrObserveBlock(
            loserWritesDone, loserSession.get(), winnerSession.get(), loserBlockedByWinner);
      } else {
        loserWritesDone.countDown();
        awaitOrObserveBlock(
            winnerDone, winnerSession.get(), loserSession.get(), winnerBlockedByLoser);
      }
    }

    /** Ende einer Transaktion (Commit oder Rollback): wartende Partnerin freigeben. */
    void finished(final String role) {
      if (WINNER.equals(role)) {
        winnerWritesDone.countDown();
        winnerDone.countDown();
      } else {
        loserWritesDone.countDown();
      }
      // Brach eine Transaktion vor Sync 1 ab, haengt die andere nicht bis zum Ablauf.
      beforeWrites.reset();
    }

    private void awaitOrObserveBlock(
        final CountDownLatch latch,
        final int waiterSession,
        final int holderSession,
        final AtomicReference<String> observed) {
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GATE_TIMEOUT_MS);
      try {
        while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
          final String lockWait = lockWait(waiterSession, holderSession);
          if (lockWait != null) {
            observed.set(lockWait);
            return;
          }
          if (System.nanoTime() > deadline) {
            gateTimedOut.set(true);
            return;
          }
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        gateTimedOut.set(true);
      }
    }

    /**
     * Wartet {@code waiterSession} gerade auf eine Sperre von {@code holderSession}? Liefert Art,
     * Ressource und den Anfang der wartenden Anweisung, sonst {@code null}.
     */
    private String lockWait(final int waiterSession, final int holderSession) {
      if (waiterSession == 0 || holderSession == 0) {
        return null;
      }
      final List<String> waits =
          probe.query(
              "SELECT r.wait_type, r.wait_resource, LEFT(t.text, 300)"
                  + " FROM sys.dm_exec_requests r"
                  + " OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t"
                  + " WHERE r.session_id = ? AND r.blocking_session_id = ?",
              (rs, rowNum) ->
                  rs.getString(1) + " auf " + rs.getString(2) + " in [" + rs.getString(3) + "]",
              waiterSession,
              holderSession);
      return waits.isEmpty() ? null : waits.get(0);
    }
  }

  // ---- Sonden
  // ------------------------------------------------------------------------------------

  private Integer count(final String sql, final Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private int taskVersion(final long taskId) {
    return count("SELECT VERSION FROM [dbo].[TASK] WHERE ID=?", taskId);
  }

  private int participantVersion(final long taskId, final UUID ref) {
    return count(
        "SELECT VERSION FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?",
        taskId,
        ref);
  }

  private int exitedUnsubscribed(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
            + " AND EXIT_KIND='UNSUBSCRIBED' AND EXIT_AT IS NOT NULL AND ENTITY_STATUS='ACTIVE'",
        taskId,
        ref);
  }

  private int userStateVersion(final long taskId, final UUID ref) {
    return count(
        "SELECT VERSION FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?", taskId, ref);
  }

  private int userStateUnsubscribed(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?"
            + " AND UNSUBSCRIBED_AT IS NOT NULL",
        taskId,
        ref);
  }

  private int historyCount(final long taskId, final UUID actorRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] WHERE TASK_ID=? AND TENANT_ID=?"
            + " AND ACTOR_REF=?",
        taskId,
        TENANT_ID,
        actorRef);
  }

  private int notificationCount(final long taskId, final UUID recipientRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] WHERE TASK_ID=? AND TENANT_ID=?"
            + " AND RECIPIENT_REF=?",
        taskId,
        TENANT_ID,
        recipientRef);
  }

  // ---- Seed-Helfer
  // -------------------------------------------------------------------------------

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id,
        id,
        externalId,
        id);
  }

  private static void seedBenutzer(
      final JdbcTemplate jt, final long id, final UUID externalId, final String loginName) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " LOGIN_NAME, VOLLER_NAME) VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id,
        id,
        externalId,
        TENANT_ID,
        loginName);
  }

  private static void insertTask(
      final JdbcTemplate jt, final long id, final UUID externalId, final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE,"
            + " TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT,"
            + " COMMENT_COUNT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id,
        id,
        externalId,
        TENANT_ID,
        title,
        STATUS_OPEN,
        PRIORITY_NORMAL,
        DIRECTION,
        TENANT_ID);
  }

  private static void insertParticipant(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID ref,
      final boolean creator,
      final boolean assignee,
      final boolean exited) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, EXIT_KIND, EXIT_AT, ANGELEGT_VON,"
            + " ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, ?, ?, 1,"
            + " CASE WHEN ? = 1 THEN 'UNSUBSCRIBED' END, CASE WHEN ? = 1 THEN GETDATE() END,"
            + " 'it-seed', GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID,
        creator ? 1 : 0,
        assignee ? 1 : 0,
        exited ? 1 : 0,
        exited ? 1 : 0);
  }

  private static void insertUserState(
      final JdbcTemplate jt,
      final long id,
      final long taskId,
      final UUID ref,
      final boolean unsubscribed) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED,"
            + " UNSUBSCRIBED_AT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 0, 0, 0,"
            + " CASE WHEN ? = 1 THEN GETDATE() END, 'it-seed', GETDATE())",
        id,
        id,
        TENANT_ID,
        taskId,
        ref,
        TENANT_ID,
        unsubscribed ? 1 : 0);
  }

  /**
   * Fuellt Teilnehmer- und State-Tabelle mit Zeilen fremder Aufgaben desselben Mandanten (100
   * Aufgaben-IDs ohne TASK-Zeile, je 20 Personen) und aktualisiert die Statistiken, damit die
   * Lookups wie im Betrieb per Index-Seek statt per Scan laufen (Klassen-Javadoc).
   */
  private static void padMembershipTables(final JdbcTemplate jt) {
    final String numbers =
        ";WITH n AS (SELECT TOP ("
            + PADDING_ROWS
            + ") ROW_NUMBER() OVER (ORDER BY (SELECT NULL))"
            + " AS i FROM sys.all_objects a CROSS JOIN sys.all_objects b) ";
    jt.update(
        numbers
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " SELECT 9000000 + i, 'ACTIVE', NEWID(), 1, ?, 8000000 + (i % 100), NEWID(),"
            + " 'PERSON', ?, 0, 0, 1, 'it-padding', GETDATE() FROM n",
        TENANT_ID,
        TENANT_ID);
    jt.update(
        numbers
            + "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED,"
            + " ANGELEGT_VON, ANGELEGT_AM)"
            + " SELECT 9100000 + i, 'ACTIVE', NEWID(), 1, ?, 8000000 + (i % 100), NEWID(), ?,"
            + " 0, 0, 0, 'it-padding', GETDATE() FROM n",
        TENANT_ID,
        TENANT_ID);
    jt.execute("UPDATE STATISTICS [dbo].[TASK_PARTICIPANT] WITH FULLSCAN");
    jt.execute("UPDATE STATISTICS [dbo].[TASK_USER_STATE] WITH FULLSCAN");
  }

  // ---- Aufzeichnende Ports
  // -----------------------------------------------------------------------

  /** Zeichnet die After-Commit-Signal-Sends auf. */
  static final class RecordingInboxSignalPort implements InboxSignalPort {
    private final List<Set<UUID>> sends = new CopyOnWriteArrayList<>();

    @Override
    public void send(final long tenantId, final Collection<UUID> userRefs) {
      sends.add(new HashSet<>(userRefs));
    }

    List<Set<UUID>> sends() {
      return sends;
    }

    void clear() {
      sends.clear();
    }
  }

  /** Zeichnet die After-Commit-Pushes je Empfaenger auf. */
  static final class RecordingNotificationPushPort implements NotificationPushPort {
    private final List<UUID> pushedRecipients = new CopyOnWriteArrayList<>();

    @Override
    public void push(
        final long tenantId, final UUID recipientRef, final NotificationPushEnvelope envelope) {
      pushedRecipients.add(recipientRef);
    }

    void clear() {
      pushedRecipients.clear();
    }
  }

  // ---- Test-Service: Sync-Punkte an den Produktiv-Seams
  // ------------------------------------------

  /** Sync 1 und Sync 2 an den Produktiv-Seams des AssigneeService. */
  static class TestAssigneeService extends AssigneeService {

    TestAssigneeService(
        final BaseTaskRepository taskRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final TaskHandoverQueryRepository handoverRepository,
        final BaseTaskHandoverTargetRepository handoverTargetRepository,
        final TaskHistoryService taskHistoryService,
        final TaskMembershipGuard membershipGuard,
        final PermissionEvaluator permissionEvaluator) {
      super(
          taskRepository,
          participantQueryRepository,
          userStateQueryRepository,
          userStateService,
          notificationService,
          inboxSignalService,
          userReadRepository,
          userAccessor,
          inboxGenerationService,
          handoverRepository,
          handoverTargetRepository,
          taskHistoryService,
          membershipGuard,
          permissionEvaluator);
    }

    @Override
    protected void onBeforeMembershipWritesForTest(final Long taskId) {
      final Gates gates = GATES.get();
      if (gates != null) {
        gates.beforeWrites();
      }
    }

    @Override
    protected void onAfterMembershipWritesForTest(final Long taskId) {
      final Gates gates = GATES.get();
      if (gates != null) {
        gates.afterMembershipWrites();
      }
    }
  }

  @SpringBootConfiguration
  @AutoConfigurationPackage(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @EnableTransactionManagement
  @EnableNovaRepositories(
      basePackages = {
        "de.brasi.nova.taskmanagement.entity",
        "de.brasi.nova.taskmanagement.repository",
        "de.brasi.nova.taskmanagement.refentity",
      })
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class,
    KsfPersistenceAutoConfiguration.class,
    KsfIntegrationAutoConfiguration.class,
    AopAutoConfiguration.class,
  })
  static class ItConfig {

    @Bean
    AuditorProvider testAuditorProvider() {
      return () -> "membership-concurrency-it";
    }

    @Bean
    UserStateService userStateService(final TaskUserStateQueryRepository repo) {
      return new UserStateService(repo);
    }

    @Bean
    InboxGenerationService inboxGenerationService(final TaskInboxGenerationQueryRepository repo) {
      return new InboxGenerationService(repo);
    }

    @Bean
    TaskHistoryService taskHistoryService(final TaskHistoryEntryQueryRepository repo) {
      return new TaskHistoryService(repo);
    }

    @Bean
    DictionaryCodeResolver dictionaryCodeResolver(
        final TaskStatusRepository taskStatusRepository,
        final TaskPriorityRepository taskPriorityRepository,
        final TaskDirectionRepository taskDirectionRepository,
        final NotificationTypeRepository notificationTypeRepository,
        final NotificationSeverityRepository notificationSeverityRepository,
        final TagSeverityRepository tagSeverityRepository) {
      return new DictionaryCodeResolver(
          taskStatusRepository,
          taskPriorityRepository,
          taskDirectionRepository,
          notificationTypeRepository,
          notificationSeverityRepository,
          tagSeverityRepository);
    }

    @Bean
    RecordingNotificationPushPort notificationPushPort() {
      return new RecordingNotificationPushPort();
    }

    @Bean
    RecordingInboxSignalPort inboxSignalPort() {
      return new RecordingInboxSignalPort();
    }

    // Echte Services: der Rollback persistierter Benachrichtigungen und der After-Commit-Signale
    // der Verliererin ist so real belegbar.
    @Bean
    NotificationService notificationService(
        final BaseTaskNotificationRepository notificationRepository,
        final DictionaryCodeResolver resolver,
        final RecordingNotificationPushPort pushPort) {
      return new NotificationService(notificationRepository, resolver, pushPort);
    }

    @Bean
    InboxSignalService inboxSignalService(final RecordingInboxSignalPort port) {
      return new InboxSignalService(port);
    }

    @Bean
    UserAccessor userAccessor() {
      return () -> {
        final UUID ref = ACTOR_PER_THREAD.get();
        return new AuthUser(ref, ref + "@brasi.de");
      };
    }

    @Bean
    TaskMembershipGuard membershipGuard(
        final TaskParticipantQueryRepository participantQueryRepository) {
      return new TaskMembershipGuard(participantQueryRepository);
    }

    /** Stub: gewaehrt nur TASK_WRITE (PUT und Hinzufuegen pruefen es am Controller). */
    @Bean
    PermissionEvaluator permissionEvaluator() {
      return new PermissionEvaluator() {
        @Override
        public boolean hasAnyRole(final String... expectedRoles) {
          return false;
        }

        @Override
        public boolean hasRole(final String expectedRole) {
          return false;
        }

        @Override
        public List<String> getUserRoles() {
          return List.of();
        }

        @Override
        public boolean isOwner(final NovaEntity<?> entity) {
          return false;
        }

        @Override
        public boolean hasExactAuthority(final String authority) {
          return TaskManagementPermissions.TASK_WRITE.equals(authority);
        }
      };
    }

    @Bean
    TestAssigneeService testAssigneeService(
        final BaseTaskRepository taskRepository,
        final TaskParticipantQueryRepository participantQueryRepository,
        final TaskUserStateQueryRepository userStateQueryRepository,
        final UserStateService userStateService,
        final NotificationService notificationService,
        final InboxSignalService inboxSignalService,
        final UserReadRepository userReadRepository,
        final UserAccessor userAccessor,
        final InboxGenerationService inboxGenerationService,
        final TaskHandoverQueryRepository handoverRepository,
        final BaseTaskHandoverTargetRepository handoverTargetRepository,
        final TaskHistoryService taskHistoryService,
        final TaskMembershipGuard membershipGuard,
        final PermissionEvaluator permissionEvaluator) {
      return new TestAssigneeService(
          taskRepository,
          participantQueryRepository,
          userStateQueryRepository,
          userStateService,
          notificationService,
          inboxSignalService,
          userReadRepository,
          userAccessor,
          inboxGenerationService,
          handoverRepository,
          handoverTargetRepository,
          taskHistoryService,
          membershipGuard,
          permissionEvaluator);
    }
  }
}
