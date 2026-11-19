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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * UNSUBSCRIBED), dazu das gleichzeitige Hinzufuegen DERSELBEN Person.
 *
 * <p><b>Befund (Rot-Nachweis, CI-Lauf 44389850011):</b> die drei Pfade lasen die States anderer
 * Personen ({@code TaskUserStateQueryRepository#findActiveUserRefsByTaskId}, beim Austritt auch
 * {@code TaskParticipantQueryRepository#findArchiveReadersByTaskId}) erst NACH ihren eigenen
 * Teilnehmer- und State-Schreibungen. Unter sperrendem READ COMMITTED wartete diese Lesung auf die
 * X-gesperrte State-Zeile der anderen Transaktion und umgekehrt: Deadlock 1205 (HTTP 500), bevor
 * der TASK-Versionskonflikt (409) griff. Seit der Korrektur stehen alle Reads vor der ersten
 * Schreibung (Vorbild {@code #removeParticipant}, {@link ParticipantRemoveConcurrencyIT}), und das
 * Hinzufuegen speichert die Aufgabe versioniert.
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
 *   <li>Die Verliererin laeuft weiter und trifft beim versionierten TASK-save die veraltete VERSION
 *       ({@link BusinessRuleException} mit {@link OptimisticLockingFailureException}, 409, voller
 *       Rollback). Beim Hinzufuegen derselben Person aendert die Verliererin nichts und bleibt 204.
 * </ol>
 *
 * <p><b>Warten wird beobachtet, nicht abgewartet:</b> wer an einem Sync-Punkt auf die andere
 * wartet, fragt ueber eine unabhaengige master-Verbindung {@code sys.dm_exec_requests} ab, ob die
 * andere Sitzung auf eine Sperre der eigenen wartet ({@code blocking_session_id}). Dann ist der
 * Ablauf entschieden: der Wartegrund (Art, Ressource, Anfang der SQL-Anweisung) wird vermerkt und
 * der Sync-Punkt sofort verlassen. Wartet die Gewinnerin nach Sync 2 auf die Verliererin, laeuft
 * diese in dieselbe Lage, und SQL Server bricht eine als Deadlock-Opfer ab (1205); genau das war
 * der Rot-Nachweis. Ohne VIEW SERVER STATE sieht die Sonde fremde Sitzungen nicht; dann laeuft der
 * Sync-Punkt nach {@link #GATE_TIMEOUT_MS} ab.
 *
 * <p><b>Zwei Phasen, Plan-Unabhaengigkeit (Prueffrage des Controllers):</b> jedes Szenario laeuft
 * zweimal auf eigener Aufgabe. Zuerst ohne Fremdzeilen: in Tabellen mit einer Handvoll Zeilen
 * waehlt SQL Server Scans, Einzel-Lookups lesen dann auch fremde Zeilen. Danach mit je 2000
 * Fremdzeilen in Teilnehmer- und State-Tabelle und frischen Statistiken (Index-Seeks wie im
 * Betrieb). Der Kern der Aussage gilt in beiden Phasen: kein Deadlock-Opfer, die Gewinnerin wartet
 * nach Sync 2 nie auf die Verliererin, eine 409 nur aus dem Versionskonflikt. Mit Fremdzeilen gilt
 * zusaetzlich, dass auch die Verliererin vor Sync 2 nicht auf die Gewinnerin wartet. Ohne
 * Fremdzeilen ist dieses Warten zulaessig und wird nur protokolliert: es ist ein Serialisieren (die
 * Gewinnerin haelt nichts, worauf sie selbst wartet), kein Zyklus; der gemeinsame Aufnahme-Kern
 * {@code addAsParticipants} liest seine Zeilen bewusst erst nach Sync 1.
 *
 * <p><b>Aufnahme zu zweit, verzahnt (Codex-Review):</b> der gemeinsame Kern {@code
 * addAsParticipants} las Teilnehmerzeile und State jeder Person erst nach der Schreibung der
 * vorigen; ohne Fremdzeilen verklemmte das (CI-Lauf 44407403653, 1205 in {@code
 * findByTaskIdAndUserRef}). Seit Fix-Runde 2 liest der Kern alle Zeilen vor der ersten Schreibung.
 * Das Szenario {@code ADD_PAIRS} laesst beide Transaktionen nach Sync 1 gleichzeitig schreiben und
 * trifft sie am Seam {@code onAfterParticipantAddForTest} nach ihrer ersten Person; danach schreibt
 * jede ihre zweite, waehrend die andere ihre erste X-gesperrt haelt.
 *
 * <p>Voraussetzung: die Wegwerf-DB laeuft mit sperrendem READ COMMITTED (kein RCSI), sonst warten
 * Lesungen nicht auf Schreibsperren und der Test bewiese nichts; das prueft jedes Szenario zuerst.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MembershipConcurrencyIT {

  private static final Logger log = LoggerFactory.getLogger(MembershipConcurrencyIT.class);

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

  /** Von beiden Transaktionen gleichzeitig aufzunehmende Person (ausgetreten). */
  private static final UUID SAME_P = UUID.fromString("00000059-00e0-0059-0000-000000000597");

  /**
   * Vier ausgetretene Personen fuer die Aufnahme zu zweit (Codex-Review #167), nach UUID sortiert
   * P1 &lt; P2 &lt; P3 &lt; P4: die Gewinnerin nimmt {P1, P3} auf, die Verliererin {P2, P4}.
   */
  private static final UUID PAIR_P1 = UUID.fromString("00000059-00f1-0059-0000-000000000597");

  private static final UUID PAIR_P2 = UUID.fromString("00000059-00f2-0059-0000-000000000597");
  private static final UUID PAIR_P3 = UUID.fromString("00000059-00f3-0059-0000-000000000597");
  private static final UUID PAIR_P4 = UUID.fromString("00000059-00f4-0059-0000-000000000597");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  /** Szenario-Nummern (Aufgabe je Phase und Szenario). */
  private static final int PUT_PUT = 1;

  private static final int EXIT_EXIT = 2;
  private static final int ADD_ADD = 3;
  private static final int PUT_EXIT = 4;
  private static final int ADD_SAME = 5;
  private static final int ADD_PAIRS = 6;

  /** Fremdzeilen je Tabelle (Phase mit Fremdzeilen, siehe Klassen-Javadoc). */
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

  /** Fremdzeilen schon eingespielt (Phase mit Fremdzeilen). */
  private static final AtomicBoolean PADDED = new AtomicBoolean(false);

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestAssigneeService service;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecordingInboxSignalPort inboxSignalPort;
  @Autowired private RecordingNotificationPushPort notificationPushPort;

  /** Phase eines Szenarios. */
  enum Phase {
    /** Ohne Fremdzeilen: kleine Tabellen, Scan-Plaene; die Verliererin darf serialisieren. */
    UNPADDED(0, false),
    /** Mit Fremdzeilen und frischen Statistiken: Index-Seeks; auch die Verliererin wartet nie. */
    PADDED(1, true);

    private final int offset;
    private final boolean loserMustNotWait;

    Phase(final int offset, final boolean loserMustNotWait) {
      this.offset = offset;
      this.loserMustNotWait = loserMustNotWait;
    }

    long taskId(final int scenario) {
      return 597100L + 100L * offset + scenario;
    }

    UUID taskUuid(final int scenario) {
      return UUID.fromString(String.format("00000059-0000-0059-aaaa-%012d", taskId(scenario)));
    }
  }

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
    seedBenutzer(jt, 597507L, SAME_P, "same-p@brasi.de");
    seedBenutzer(jt, 597508L, PAIR_P1, "pair-p1@brasi.de");
    seedBenutzer(jt, 597509L, PAIR_P2, "pair-p2@brasi.de");
    seedBenutzer(jt, 597510L, PAIR_P3, "pair-p3@brasi.de");
    seedBenutzer(jt, 597511L, PAIR_P4, "pair-p4@brasi.de");
    for (final Phase phase : Phase.values()) {
      // PUT x PUT: Ersteller, A, B und C sind aktive Assignees.
      seedMembers(
          jt,
          phase,
          PUT_PUT,
          member(CREATOR_REF, true, true, false),
          member(ACTOR_A_REF, false, true, false),
          member(PERSON_B, false, true, false),
          member(PERSON_C, false, true, false));
      // exit x exit: Ersteller, B und C (reine Beteiligte).
      seedMembers(
          jt,
          phase,
          EXIT_EXIT,
          member(CREATOR_REF, true, true, false),
          member(PERSON_B, false, false, false),
          member(PERSON_C, false, false, false));
      // add x add: Ersteller und A aktiv; P1 und P2 ausgetreten (Archivleser), werden reaktiviert.
      // Reaktivierung statt Erst-Insert: sie allokiert keine Sequenz und legt keine neuen
      // Index-Schluessel an (Lesson concurrency-test-seams); die Lesungen sind dieselben.
      seedMembers(
          jt,
          phase,
          ADD_ADD,
          member(CREATOR_REF, true, true, false),
          member(ACTOR_A_REF, false, true, false),
          member(NEW_P1, false, false, true),
          member(NEW_P2, false, false, true));
      // PUT x exit: Ersteller und B Assignees, C reiner Beteiligter.
      seedMembers(
          jt,
          phase,
          PUT_EXIT,
          member(CREATOR_REF, true, true, false),
          member(PERSON_B, false, true, false),
          member(PERSON_C, false, false, false));
      // add x add derselben Person: Ersteller und A aktiv, SAME_P ausgetreten.
      seedMembers(
          jt,
          phase,
          ADD_SAME,
          member(CREATOR_REF, true, true, false),
          member(ACTOR_A_REF, false, true, false),
          member(SAME_P, false, false, true));
      // add x add zu zweit: Ersteller und A aktiv, P1 bis P4 ausgetreten.
      seedMembers(
          jt,
          phase,
          ADD_PAIRS,
          member(CREATOR_REF, true, true, false),
          member(ACTOR_A_REF, false, true, false),
          member(PAIR_P1, false, false, true),
          member(PAIR_P2, false, false, true),
          member(PAIR_P3, false, false, true),
          member(PAIR_P4, false, false, true));
    }
  }

  // ---- Szenarien ohne Fremdzeilen (Scan-Plaene)
  // ----------------------------------------------------

  @Test
  @Order(1)
  @DisplayName("i167_ohne_fremdzeilen_put_x_put: eine 204, eine 409, nie 1205/500")
  void i167_unpadded_put_put() {
    putPut(Phase.UNPADDED);
  }

  @Test
  @Order(2)
  @DisplayName("i167_ohne_fremdzeilen_exit_x_exit: eine 204, eine 409, nie 1205/500")
  void i167_unpadded_exit_exit() {
    exitExit(Phase.UNPADDED);
  }

  @Test
  @Order(3)
  @DisplayName("i167_ohne_fremdzeilen_add_x_add: eine 204, eine 409, nie 1205/500")
  void i167_unpadded_add_add() {
    addAdd(Phase.UNPADDED);
  }

  @Test
  @Order(4)
  @DisplayName("i167_ohne_fremdzeilen_put_x_exit: eine 204, eine 409, nie 1205/500")
  void i167_unpadded_put_exit() {
    putExit(Phase.UNPADDED);
  }

  @Test
  @Order(5)
  @DisplayName(
      "i167_ohne_fremdzeilen_add_derselben_person: beide 204, eine Benachrichtigung, nie 1205")
  void i167_unpadded_add_same_person() {
    addSamePerson(Phase.UNPADDED);
  }

  @Test
  @Order(6)
  @DisplayName(
      "i167_ohne_fremdzeilen_add_x_add_zu_zweit: {P1,P3} gegen {P2,P4} verzahnt, eine 204, eine"
          + " 409, nie 1205/500")
  void i167_unpadded_add_pairs() {
    addPairs(Phase.UNPADDED);
  }

  // ---- Szenarien mit Fremdzeilen (Index-Seeks wie im Betrieb)
  // -------------------------------------

  @Test
  @Order(11)
  @DisplayName(
      "i167_put_x_put: Nutzer 1 laesst B weg, Nutzer 2 laesst C weg; beide halten ihre X-Sperren"
          + " -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_put_omitting_different_persons_204_and_409_never_deadlock() {
    ensurePadded();
    putPut(Phase.PADDED);
  }

  @Test
  @Order(12)
  @DisplayName(
      "i167_exit_x_exit: B und C treten gleichzeitig aus (UNSUBSCRIBED); beide halten ihre"
          + " X-Sperren -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_exit_of_different_persons_204_and_409_never_deadlock() {
    ensurePadded();
    exitExit(Phase.PADDED);
  }

  @Test
  @Order(13)
  @DisplayName(
      "i167_add_x_add: zwei POST /participants mit verschiedenen Personen; beide halten ihre"
          + " X-Sperren -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_add_of_different_persons_204_and_409_never_deadlock() {
    ensurePadded();
    addAdd(Phase.PADDED);
  }

  @Test
  @Order(14)
  @DisplayName(
      "i167_put_x_exit: der Ersteller laesst B weg, C tritt gleichzeitig aus; beide halten ihre"
          + " X-Sperren -> eine 204, eine 409 (Versionskonflikt, voller Rollback), nie 1205/500")
  void i167_parallel_put_and_exit_of_different_persons_204_and_409_never_deadlock() {
    ensurePadded();
    putExit(Phase.PADDED);
  }

  @Test
  @Order(15)
  @DisplayName(
      "i167_add_derselben_person: zwei POST /participants mit derselben Person -> beide 204, genau"
          + " eine Benachrichtigung, Aufgabe genau einmal gespeichert, nie 1205/500")
  void i167_parallel_add_of_same_person_both_204() {
    ensurePadded();
    addSamePerson(Phase.PADDED);
  }

  @Test
  @Order(16)
  @DisplayName(
      "i167_add_x_add_zu_zweit (Codex-Review): zwei POST /participants mit je zwei Personen,"
          + " {P1,P3} gegen {P2,P4}; jede liest ihre zweite Person, waehrend die andere ihre erste"
          + " X-gesperrt haelt -> eine 204, eine 409 (voller Rollback), nie 1205/500")
  void i167_parallel_add_of_two_persons_each_interleaved_never_deadlock() {
    ensurePadded();
    addPairs(Phase.PADDED);
  }

  // ---- Szenario-Ablaeufe
  // --------------------------------------------------------------------------

  private void putPut(final Phase phase) {
    assertLockingReadCommitted();
    final long taskId = phase.taskId(PUT_PUT);
    final UUID taskUuid = phase.taskUuid(PUT_PUT);
    final int taskVersionBefore = taskVersion(taskId);
    final int loseParticipantVersionBefore = participantVersion(taskId, PERSON_C);
    final int loseStateVersionBefore = userStateVersion(taskId, PERSON_C);

    final Outcome outcome =
        runPair(
            "i167-put-put-" + phase,
            CREATOR_REF,
            () ->
                service.updateAssignees(
                    taskUuid, new AssigneeUpdateInput(Set.of(CREATOR_REF, ACTOR_A_REF, PERSON_C))),
            ACTOR_A_REF,
            () ->
                service.updateAssignees(
                    taskUuid, new AssigneeUpdateInput(Set.of(CREATOR_REF, ACTOR_A_REF, PERSON_B))));

    assertNoDeadlock(outcome, phase);
    assertWinnerCommittedLoserVersionConflict(outcome);
    assertThat(taskVersion(taskId)).as("TASK-VERSION genau +1").isEqualTo(taskVersionBefore + 1);
    assertThat(exitedUnsubscribed(taskId, PERSON_B)).as("B ausgetreten").isEqualTo(1);
    assertThat(userStateUnsubscribed(taskId, PERSON_B)).as("State B abgemeldet").isEqualTo(1);
    assertUntouched(taskId, PERSON_C, true, loseParticipantVersionBefore, loseStateVersionBefore);
    assertSingleSignalContaining(PERSON_B);
  }

  private void exitExit(final Phase phase) {
    assertLockingReadCommitted();
    final long taskId = phase.taskId(EXIT_EXIT);
    final UUID taskUuid = phase.taskUuid(EXIT_EXIT);
    final int taskVersionBefore = taskVersion(taskId);
    final int loseParticipantVersionBefore = participantVersion(taskId, PERSON_C);
    final int loseStateVersionBefore = userStateVersion(taskId, PERSON_C);

    final Outcome outcome =
        runPair(
            "i167-exit-exit-" + phase,
            PERSON_B,
            () -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED)),
            PERSON_C,
            () -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED)));

    assertNoDeadlock(outcome, phase);
    assertWinnerCommittedLoserVersionConflict(outcome);
    assertThat(taskVersion(taskId)).as("TASK-VERSION genau +1").isEqualTo(taskVersionBefore + 1);
    assertThat(exitedUnsubscribed(taskId, PERSON_B)).as("B ausgetreten").isEqualTo(1);
    assertThat(userStateUnsubscribed(taskId, PERSON_B)).as("State B abgemeldet").isEqualTo(1);
    assertUntouched(taskId, PERSON_C, false, loseParticipantVersionBefore, loseStateVersionBefore);
    assertThat(historyCount(taskId, PERSON_B)).as("Historie der Gewinnerin").isEqualTo(1);
    assertThat(historyCount(taskId, PERSON_C)).as("keine Historie der Verliererin").isZero();
    assertSingleSignalContaining(PERSON_B);
  }

  private void addAdd(final Phase phase) {
    assertLockingReadCommitted();
    final long taskId = phase.taskId(ADD_ADD);
    final UUID taskUuid = phase.taskUuid(ADD_ADD);
    final int taskVersionBefore = taskVersion(taskId);
    final int p2ParticipantVersionBefore = participantVersion(taskId, NEW_P2);
    final int p2StateVersionBefore = userStateVersion(taskId, NEW_P2);

    final Outcome outcome =
        runPair(
            "i167-add-add-" + phase,
            CREATOR_REF,
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(NEW_P1))),
            ACTOR_A_REF,
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(NEW_P2))));

    // Stefan (2026-11-18): das Hinzufuegen speichert die Aufgabe versioniert; die Verliererin
    // bekommt 409 mit vollem Rollback.
    assertNoDeadlock(outcome, phase);
    assertWinnerCommittedLoserVersionConflict(outcome);
    assertThat(taskVersion(taskId)).as("TASK-VERSION genau +1").isEqualTo(taskVersionBefore + 1);
    assertAdded(taskId, NEW_P1);
    assertThat(exitedUnsubscribed(taskId, NEW_P2)).as("P2 weiter ausgetreten").isEqualTo(1);
    assertThat(participantVersion(taskId, NEW_P2))
        .as("Teilnehmerzeile P2 unveraendert (voller Rollback)")
        .isEqualTo(p2ParticipantVersionBefore);
    assertThat(userStateUnsubscribed(taskId, NEW_P2)).as("State P2 abgemeldet").isEqualTo(1);
    assertThat(userStateVersion(taskId, NEW_P2))
        .as("State P2 unveraendert (voller Rollback)")
        .isEqualTo(p2StateVersionBefore);
    assertThat(notificationCount(taskId, NEW_P2)).as("keine Benachrichtigung an P2").isZero();
    assertSingleSignalContaining(NEW_P1);
  }

  private void putExit(final Phase phase) {
    assertLockingReadCommitted();
    final long taskId = phase.taskId(PUT_EXIT);
    final UUID taskUuid = phase.taskUuid(PUT_EXIT);
    final int taskVersionBefore = taskVersion(taskId);
    final int loseParticipantVersionBefore = participantVersion(taskId, PERSON_C);
    final int loseStateVersionBefore = userStateVersion(taskId, PERSON_C);

    final Outcome outcome =
        runPair(
            "i167-put-exit-" + phase,
            CREATOR_REF,
            () -> service.updateAssignees(taskUuid, new AssigneeUpdateInput(Set.of(CREATOR_REF))),
            PERSON_C,
            () -> service.exit(taskUuid, new ExitInput(ExitKind.UNSUBSCRIBED)));

    assertNoDeadlock(outcome, phase);
    assertWinnerCommittedLoserVersionConflict(outcome);
    assertThat(taskVersion(taskId)).as("TASK-VERSION genau +1").isEqualTo(taskVersionBefore + 1);
    assertThat(exitedUnsubscribed(taskId, PERSON_B)).as("B ausgetreten").isEqualTo(1);
    assertThat(userStateUnsubscribed(taskId, PERSON_B)).as("State B abgemeldet").isEqualTo(1);
    assertUntouched(taskId, PERSON_C, false, loseParticipantVersionBefore, loseStateVersionBefore);
    assertThat(historyCount(taskId, PERSON_C)).as("keine Historie der Verliererin").isZero();
    assertSingleSignalContaining(PERSON_B);
  }

  private void addSamePerson(final Phase phase) {
    assertLockingReadCommitted();
    final long taskId = phase.taskId(ADD_SAME);
    final UUID taskUuid = phase.taskUuid(ADD_SAME);
    final int taskVersionBefore = taskVersion(taskId);

    final Outcome outcome =
        runPair(
            "i167-add-same-" + phase,
            CREATOR_REF,
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(SAME_P))),
            ACTOR_A_REF,
            () -> service.addParticipants(taskUuid, new ParticipantAddInput(List.of(SAME_P))));

    // Dieselbe Zeile: die Verliererin wartet beim Lesen der Zeile auf die Gewinnerin
    // (Serialisieren,
    // kein Zyklus) und sieht danach die aktive Zeile (SKIP ohne Aenderung, kein TASK-save).
    assertNoDeadlockLoserMayWait(outcome);
    assertThat(outcome.winnerError()).as("Gewinnerin: 204").isNull();
    assertThat(outcome.loserError()).as("Verliererin: 204 ohne Aenderung").isNull();
    assertThat(taskVersion(taskId))
        .as("TASK-VERSION genau +1 (nur die Gewinnerin aendert etwas)")
        .isEqualTo(taskVersionBefore + 1);
    assertAdded(taskId, SAME_P);
    assertSingleSignalContaining(SAME_P);
  }

  /**
   * Aufnahme zu zweit, verzahnt (Codex-Review #167): die Gewinnerin nimmt {P1, P3} auf, die
   * Verliererin {P2, P4}. Beide treffen sich nach Sync 1 (ohne Vorlauf der Gewinnerin), schreiben
   * je ihre erste Person (P1 bzw. P2) und treffen sich am Seam {@code onAfterParticipantAddForTest}
   * ein zweites Mal. Erst dann verarbeiten beide ihre zweite Person (P3 bzw. P4), waehrend die
   * andere ihre erste Person X-gesperrt haelt. Laese der Kern hier noch Zeilen, wartete jede auf
   * die andere (Deadlock 1205, so vor Fix-Runde 2). Danach Sync 2 wie in den anderen Szenarien.
   */
  private void addPairs(final Phase phase) {
    assertLockingReadCommitted();
    final long taskId = phase.taskId(ADD_PAIRS);
    final UUID taskUuid = phase.taskUuid(ADD_PAIRS);
    final int taskVersionBefore = taskVersion(taskId);
    final List<UUID> loserRefs = List.of(PAIR_P2, PAIR_P4);
    final List<Integer> participantVersionsBefore = new ArrayList<>();
    final List<Integer> stateVersionsBefore = new ArrayList<>();
    for (final UUID ref : loserRefs) {
      participantVersionsBefore.add(participantVersion(taskId, ref));
      stateVersionsBefore.add(userStateVersion(taskId, ref));
    }

    final Outcome outcome =
        runPair(
            "i167-add-pairs-" + phase,
            true,
            CREATOR_REF,
            () ->
                service.addParticipants(
                    taskUuid, new ParticipantAddInput(List.of(PAIR_P1, PAIR_P3))),
            ACTOR_A_REF,
            () ->
                service.addParticipants(
                    taskUuid, new ParticipantAddInput(List.of(PAIR_P2, PAIR_P4))));

    assertNoDeadlock(outcome, phase);
    assertThat(outcome.gates().betweenPersonsReached.get())
        .as("beide erreichten den Treffpunkt nach ihrer ersten Person (Verzahnung erreicht)")
        .isEqualTo(2);
    if (phase.loserMustNotWait) {
      assertThat(outcome.gates().blockedBetweenPersons.get())
          .as(
              "mit Fremdzeilen wartet nach der ersten Person keine Lesung auf eine X-Sperre der"
                  + " anderen")
          .isNull();
    }
    assertWinnerCommittedLoserVersionConflict(outcome);
    assertThat(taskVersion(taskId)).as("TASK-VERSION genau +1").isEqualTo(taskVersionBefore + 1);
    assertAdded(taskId, PAIR_P1);
    assertAdded(taskId, PAIR_P3);
    for (int i = 0; i < loserRefs.size(); i++) {
      final UUID ref = loserRefs.get(i);
      assertThat(exitedUnsubscribed(taskId, ref)).as("%s weiter ausgetreten", ref).isEqualTo(1);
      assertThat(participantVersion(taskId, ref))
          .as("Teilnehmerzeile %s unveraendert (voller Rollback)", ref)
          .isEqualTo(participantVersionsBefore.get(i));
      assertThat(userStateUnsubscribed(taskId, ref)).as("State %s abgemeldet", ref).isEqualTo(1);
      assertThat(userStateVersion(taskId, ref))
          .as("State %s unveraendert (voller Rollback)", ref)
          .isEqualTo(stateVersionsBefore.get(i));
      assertThat(notificationCount(taskId, ref)).as("keine Benachrichtigung an %s", ref).isZero();
    }
    assertSingleSignalContaining(PAIR_P1);
  }

  // ---- Ablauf und gemeinsame Pruefungen
  // ----------------------------------------------------------

  /**
   * Ergebnis eines Szenarios: Fehler beider Transaktionen und die Beobachtungen der Sync-Punkte.
   */
  record Outcome(Throwable winnerError, Throwable loserError, Gates gates) {}

  private Outcome runPair(
      final String label,
      final UUID winnerActor,
      final Runnable winnerCall,
      final UUID loserActor,
      final Runnable loserCall) {
    return runPair(label, false, winnerActor, winnerCall, loserActor, loserCall);
  }

  /**
   * Fuehrt beide Transaktionen aus.
   *
   * @param interleaved {@code false}: die Verliererin schreibt erst, wenn die Gewinnerin Sync 2
   *     erreicht hat; {@code true}: beide schreiben nach Sync 1 gleichzeitig und treffen sich nach
   *     ihrer ersten aufgenommenen Person ({@code onAfterParticipantAddForTest}) ein weiteres Mal
   */
  private Outcome runPair(
      final String label,
      final boolean interleaved,
      final UUID winnerActor,
      final Runnable winnerCall,
      final UUID loserActor,
      final Runnable loserCall) {
    inboxSignalPort.clear();
    notificationPushPort.clear();
    final Gates gates = new Gates(jdbcTemplate, new JdbcTemplate(master), interleaved);
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
    log.info(
        "{}: Gewinnerin-Fehler={}, Verliererin-Fehler={}, Gewinnerin wartete auf Verliererin={},"
            + " Verliererin wartete auf Gewinnerin={}",
        label,
        winnerError.get(),
        loserError.get(),
        gates.winnerBlockedByLoser.get(),
        gates.loserBlockedByWinner.get());
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
   * Kein Deadlock; die Gewinnerin wartet nach Sync 2 nie auf die Verliererin. Mit Fremdzeilen
   * wartet auch die Verliererin vor Sync 2 nicht auf die Gewinnerin (beide halten ihre Sperren
   * gleichzeitig). Die 1205-Pruefung steht vorn, damit das Fehlerbild den Deadlock nennt.
   */
  private static void assertNoDeadlock(final Outcome outcome, final Phase phase) {
    assertNoDeadlockLoserMayWait(outcome);
    if (phase.loserMustNotWait) {
      assertThat(outcome.gates().loserBlockedByWinner.get())
          .as(
              "mit Fremdzeilen wartet vor Sync 2 keine Lesung der Verliererin auf eine X-Sperre der"
                  + " Gewinnerin (beide halten ihre Sperren gleichzeitig)")
          .isNull();
    }
  }

  /** Wie {@link #assertNoDeadlock}, die Verliererin darf aber auf die Gewinnerin warten. */
  private static void assertNoDeadlockLoserMayWait(final Outcome outcome) {
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
    assertThat(gates.gateTimedOut.get()).as("kein Ablauf an den Sync-Punkten").isFalse();
    assertThat(gates.membershipWritesReached.get())
        .as("beide Transaktionen erreichten Sync 2")
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

  /** Aufnahme wirksam: reiner Teilnehmer, State aktiv, genau eine PARTICIPANT-Benachrichtigung. */
  private void assertAdded(final long taskId, final UUID ref) {
    assertThat(
            count(
                "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND"
                    + " PARTICIPANT_REF=? AND EXIT_KIND IS NULL AND PARTICIPANT_FLAG=1"
                    + " AND ASSIGNEE_FLAG=0 AND ENTITY_STATUS='ACTIVE'",
                taskId,
                ref))
        .as("%s als reiner Teilnehmer aufgenommen", ref)
        .isEqualTo(1);
    assertThat(userStateUnsubscribed(taskId, ref)).as("State %s aktiv", ref).isZero();
    assertThat(notificationCount(taskId, ref)).as("genau ein PARTICIPANT an %s", ref).isEqualTo(1);
  }

  /** Genau ein After-Commit-Signal (nur die Gewinnerin aendert etwas), das die Person enthaelt. */
  private void assertSingleSignalContaining(final UUID ref) {
    assertThat(inboxSignalPort.sends()).as("genau ein Signal (Gewinnerin)").hasSize(1);
    assertThat(inboxSignalPort.sends().get(0)).as("Signal enthaelt %s", ref).contains(ref);
  }

  // ---- Sync-Punkte
  // -------------------------------------------------------------------------------

  /**
   * Sync-Punkte eines Szenarios mit Sperr-Sonde. Ablaeufe und beobachtete Sperr-Wartezustaende
   * werden vermerkt; die Pruefungen entscheiden, welche davon den Test rot machen.
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
    private final boolean interleaved;
    private final CountDownLatch betweenPersonsArrived = new CountDownLatch(2);
    private final AtomicBoolean winnerPassedBetween = new AtomicBoolean(false);
    private final AtomicBoolean loserPassedBetween = new AtomicBoolean(false);
    private final AtomicInteger betweenPersonsReached = new AtomicInteger();
    private final AtomicReference<String> blockedBetweenPersons = new AtomicReference<>();

    /**
     * @param txJdbc JdbcTemplate der Anwendung; im Thread einer Transaktion laeuft er auf deren
     *     Verbindung (fuer {@code @@SPID})
     * @param probe unabhaengige master-Verbindung fuer {@code sys.dm_exec_requests}
     * @param interleaved {@code true}: kein Vorlauf der Gewinnerin nach Sync 1, dafuer ein
     *     Treffpunkt nach der ersten aufgenommenen Person ({@link #betweenPersons})
     */
    Gates(final JdbcTemplate txJdbc, final JdbcTemplate probe, final boolean interleaved) {
      this.txJdbc = txJdbc;
      this.probe = probe;
      this.interleaved = interleaved;
    }

    /**
     * Treffpunkt nach der ersten aufgenommenen Person (nur verzahnt, nur beim ersten Aufruf je
     * Rolle): beide halten jetzt die X-Sperren ihrer ersten Person, erst danach liest eine die
     * Zeilen ihrer zweiten. Wartet die andere dabei auf eine Sperre der eigenen Sitzung, wird das
     * vermerkt und der Treffpunkt verlassen.
     */
    void betweenPersons() {
      final String role = ROLE_PER_THREAD.get();
      if (role == null || !interleaved) {
        return;
      }
      final boolean winner = WINNER.equals(role);
      if ((winner ? winnerPassedBetween : loserPassedBetween).getAndSet(true)) {
        return;
      }
      betweenPersonsReached.incrementAndGet();
      betweenPersonsArrived.countDown();
      final AtomicReference<String> observed = new AtomicReference<>();
      awaitOrObserveBlock(
          betweenPersonsArrived,
          (winner ? loserSession : winnerSession).get(),
          (winner ? winnerSession : loserSession).get(),
          observed);
      if (observed.get() != null) {
        blockedBetweenPersons.set((winner ? LOSER : WINNER) + " wartete: " + observed.get());
      }
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
      if (LOSER.equals(role) && !interleaved) {
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
      // Brach eine Transaktion vor dem Treffpunkt ab, wartet die andere dort nicht bis zum Ablauf.
      if (!(WINNER.equals(role) ? winnerPassedBetween : loserPassedBetween).getAndSet(true)) {
        betweenPersonsArrived.countDown();
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

  /** Mitglied einer Szenario-Aufgabe (Teilnehmerzeile samt State). */
  record Member(UUID ref, boolean creator, boolean assignee, boolean exited) {}

  private static Member member(
      final UUID ref, final boolean creator, final boolean assignee, final boolean exited) {
    return new Member(ref, creator, assignee, exited);
  }

  /** Legt die Szenario-Aufgabe einer Phase samt Teilnehmerzeilen und States an. */
  private static void seedMembers(
      final JdbcTemplate jt, final Phase phase, final int scenario, final Member... members) {
    final long taskId = phase.taskId(scenario);
    insertTask(jt, taskId, phase.taskUuid(scenario), "I167-" + phase + "-" + scenario);
    for (int i = 0; i < members.length; i++) {
      final Member m = members[i];
      insertParticipant(
          jt, taskId * 100 + i + 1, taskId, m.ref(), m.creator(), m.assignee(), m.exited());
      insertUserState(jt, taskId * 100 + 50 + i + 1, taskId, m.ref(), m.exited());
    }
  }

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

  /** Spielt die Fremdzeilen einmal ein (erste Methode der Phase mit Fremdzeilen). */
  private void ensurePadded() {
    if (PADDED.compareAndSet(false, true)) {
      padMembershipTables(jdbcTemplate);
    }
  }

  /**
   * Fuellt Teilnehmer- und State-Tabelle mit Zeilen fremder Aufgaben desselben Mandanten (100
   * Aufgaben-IDs ohne TASK-Zeile, je 20 Personen), aktualisiert die Statistiken und markiert die
   * Plaene beider Tabellen zur Neuuebersetzung, damit die Lookups wie im Betrieb per Index-Seek
   * statt per Scan laufen (Klassen-Javadoc).
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
    jt.execute("EXEC sp_recompile N'dbo.TASK_PARTICIPANT'");
    jt.execute("EXEC sp_recompile N'dbo.TASK_USER_STATE'");
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

    @Override
    protected void onAfterParticipantAddForTest(final Long taskId, final UUID ref) {
      final Gates gates = GATES.get();
      if (gates != null) {
        gates.betweenPersons();
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
