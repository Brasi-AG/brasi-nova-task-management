package de.brasi.nova.taskmanagement.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.entity.enums.ExitKind;
import de.brasi.nova.taskmanagement.inbox.InboxSignalService;
import de.brasi.nova.taskmanagement.notification.NotificationService;
import de.brasi.nova.taskmanagement.participant.model.AssigneeUpdateInput;
import de.brasi.nova.taskmanagement.participant.model.ExitInput;
import de.brasi.nova.taskmanagement.push.InboxSignalPort;
import de.brasi.nova.taskmanagement.push.NotificationPushEnvelope;
import de.brasi.nova.taskmanagement.push.NotificationPushPort;
import de.brasi.nova.taskmanagement.refentity.UserReadRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskHandoverTargetRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskNotificationRepository;
import de.brasi.nova.taskmanagement.repository.BaseTaskRepository;
import de.brasi.nova.taskmanagement.repository.TaskHandoverQueryRepository;
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
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import de.brasi.nova.taskmanagement.task.TaskHistoryService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real-MSSQL-Nebenlaeufigkeits-IT fuer {@code AssigneeService.exit()} Handover (BE-7, PA4/PA4+).
 *
 * <p><b>Echte Services statt Mocks:</b> {@link NotificationService} + {@link InboxSignalService}
 * sind REAL verdrahtet (mit aufzeichnenden Ports {@link RecordingNotificationPushPort} /
 * {@link RecordingInboxSignalPort}). Dadurch ist der VOLLSTAENDIGE Rollback des Verlierers belegbar:
 * in-Tx persistierte Notifications verschwinden bei Rollback, und ein in Block 5 registrierter
 * After-Commit-Push-Hook darf bei Rollback NICHT feuern.
 *
 * <ul>
 *   <li><b>IT6 Ziel-Race ({@link #it6_ziel_race_pures_teilnehmer_add_rollback}):</b> Zwischen
 *       Vorab-Read und save des Handover-Ziels wird ueber eine UNABHAENGIGE Verbindung eine bereits
 *       committete REINE-Teilnehmer-Zeile (ASSIGNEE_FLAG=0) desselben Ziels eingefuegt -> UNIQUE ->
 *       Gewinnerpraedikat verfehlt -> definierter Konflikt ({@link BusinessRuleException}) + voller
 *       Rollback.
 *   <li><b>IT7 Versions-Verlierer, DREI Paarungen</b> ({@link #it7_vs_handover_voller_rollback},
 *       {@link #it7_vs_exit_voller_rollback}, {@link #it7_vs_put_voller_rollback}): der Verlierer
 *       (ACTOR -&gt; TARGET_LOSE, HANDOVER !shouldStay) pausiert am Top-of-exit-Seam
 *       ({@code onBeforeExitMutationForTest}) - zu diesem Zeitpunkt haelt er NOCH KEINE
 *       Schreibsperren (der {@code requireTask}-SELECT gab seine Shared-Locks frei). Ein nebenlaeufiger
 *       Gewinner (Handover / Exit / PUT) durch ACTOR2 committet dann BLOCKIERUNGSFREI auf einem eigenen
 *       Thread und bumpt die TASK-VERSION. Nach der Fortsetzung trifft der Verlierer beim versionierten
 *       {@code taskRepository.save} die stale VERSION -&gt; {@link BusinessRuleException} (409) -&gt;
 *       die GANZE Verlierer-Tx rollt zurueck (Ereignis, Targets, Rollen, History, Notifications UND
 *       After-Commit-Signale).
 * </ul>
 *
 * <p>Uebersprungen ohne MSSQL-Zugang ({@link RefSchemaItEnabled}).
 */
@SpringBootTest(
    classes = HandoverConcurrencyIT.ItConfig.class,
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
      // Codex-Auflage 1: mindestens zwei Verbindungen (Verlierer haelt seine Tx-Verbindung waehrend
      // er am Seam pausiert; der Gewinner-Thread braucht eine zweite).
      "spring.datasource.hikari.maximum-pool-size=5",
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class HandoverConcurrencyIT {

  private static final long TENANT_ID = 594L;
  private static final UUID TENANT_UUID = UUID.fromString("00000059-0000-0059-0000-000000000594");

  /** Verlierer (uebergibt an TARGET_LOSE und verliert den Versions-Race). */
  private static final UUID ACTOR_REF = UUID.fromString("00000059-00a0-0059-0000-000000000594");
  /** Gewinner (committet die nebenlaeufige Operation im Seam). */
  private static final UUID ACTOR2_REF = UUID.fromString("00000059-00a2-0059-0000-000000000594");

  private static final long STATUS_OPEN = 101L;
  private static final long PRIORITY_NORMAL = 202L;
  private static final long DIRECTION = 301L;

  // IT6 Ziel-Race.
  private static final long TASK_RACE_ID = 594101L;
  private static final UUID TASK_RACE_UUID = UUID.fromString("00000059-0000-0059-aaaa-000000594101");
  private static final UUID TARGET_RACE = UUID.fromString("00000059-00b0-0059-0000-000000000594");
  private static final long ACTOR_PART_ID = 594301L;
  private static final long INJECTED_TARGET_PART_ID = 594401L;

  // IT7: der Verlierer uebergibt an TARGET_LOSE; der Gewinner (Handover/PUT) nutzt TARGET_WIN.
  private static final UUID TARGET_LOSE = UUID.fromString("00000059-00c0-0059-0000-000000000594");
  private static final UUID TARGET_WIN = UUID.fromString("00000059-00d0-0059-0000-000000000594");

  private static final long TASK_HANDOVER_ID = 594110L;
  private static final UUID TASK_HANDOVER_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000594110");
  private static final long TASK_EXIT_ID = 594111L;
  private static final UUID TASK_EXIT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000594111");
  private static final long TASK_PUT_ID = 594112L;
  private static final UUID TASK_PUT_UUID =
      UUID.fromString("00000059-0000-0059-aaaa-000000594112");

  /** Join-Timeout fuer den Gewinner-Thread; bei Ueberschreitung gilt der Test als fehlgeschlagen. */
  private static final long WINNER_JOIN_TIMEOUT_MS = 60_000L;

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private TestAssigneeService service;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private RecordingInboxSignalPort inboxSignalPort;
  @Autowired private RecordingNotificationPushPort notificationPushPort;

  /** Thread-lokaler Akteur (kein geteiltes Mock-Re-Stubbing; der UserAccessor liest ihn). */
  private static final ThreadLocal<UUID> ACTOR_PER_THREAD = ThreadLocal.withInitial(() -> ACTOR_REF);

  private final AtomicReference<Throwable> winnerError = new AtomicReference<>();
  private final AtomicBoolean winnerTimedOut = new AtomicBoolean(false);

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
  static void seed(@Autowired JdbcTemplate jt) {
    seedFirma(jt, TENANT_ID, TENANT_UUID);
    seedBenutzer(jt, 594501L, ACTOR_REF, "actor@brasi.de");
    seedBenutzer(jt, 594502L, ACTOR2_REF, "actor2@brasi.de");
    seedBenutzer(jt, 594503L, TARGET_RACE, "target-race@brasi.de");
    seedBenutzer(jt, 594504L, TARGET_LOSE, "target-lose@brasi.de");
    seedBenutzer(jt, 594505L, TARGET_WIN, "target-win@brasi.de");

    // IT6: Aufgabe + aktiver Nicht-Creator-Assignee ACTOR (darf uebergeben).
    insertTask(jt, TASK_RACE_ID, TASK_RACE_UUID, "IT6-Ziel-Race");
    insertActiveAssignee(jt, ACTOR_PART_ID, TASK_RACE_ID, ACTOR_REF, false);

    // IT7: drei Aufgaben, je ACTOR (Verlierer) + ACTOR2 (Gewinner) als aktive Nicht-Creator-Assignees
    // + vorab angelegte aktive UserState-Zeilen (Codex-Auflage 3: ACTOR-UserState vorab anlegen und
    // nach dem Rollback vergleichen).
    seedIt7Task(jt, TASK_HANDOVER_ID, TASK_HANDOVER_UUID, "IT7-vs-Handover", 594610L, 594611L, 594710L, 594711L);
    seedIt7Task(jt, TASK_EXIT_ID, TASK_EXIT_UUID, "IT7-vs-Exit", 594620L, 594621L, 594720L, 594721L);
    seedIt7Task(jt, TASK_PUT_ID, TASK_PUT_UUID, "IT7-vs-PUT", 594630L, 594631L, 594730L, 594731L);
  }

  private static void seedIt7Task(
      final JdbcTemplate jt,
      final long taskId,
      final UUID taskUuid,
      final String title,
      final long actorPartId,
      final long actor2PartId,
      final long actorStateId,
      final long actor2StateId) {
    insertTask(jt, taskId, taskUuid, title);
    insertActiveAssignee(jt, actorPartId, taskId, ACTOR_REF, false);
    insertActiveAssignee(jt, actor2PartId, taskId, ACTOR2_REF, false);
    insertActiveUserState(jt, actorStateId, taskId, ACTOR_REF);
    insertActiveUserState(jt, actor2StateId, taskId, ACTOR2_REF);
  }

  @BeforeEach
  void resetState() {
    winnerError.set(null);
    winnerTimedOut.set(false);
    inboxSignalPort.clear();
    notificationPushPort.clear();
    ACTOR_PER_THREAD.set(ACTOR_REF);
  }

  // ---- IT6: Ziel-Race -> definierter Konflikt + voller Rollback, nie 204 mit ASSIGNEE_FLAG=0 -----

  @Test
  @DisplayName(
      "it6_ziel_race_pures_teilnehmer_add_rollback: committeter reiner-Teilnehmer-Insert des Ziels"
          + " zwischen Read und save -> UNIQUE -> Gewinnerpraedikat verfehlt -> BusinessRuleException"
          + " (409) + voller Rollback")
  void it6_ziel_race_pures_teilnehmer_add_rollback() {
    service.armTargetInjector(
        () ->
            injectCommittedPureParticipant(
                dataSource, INJECTED_TARGET_PART_ID, TASK_RACE_ID, TARGET_RACE),
        TARGET_RACE);

    final int versionBefore = taskVersion(TASK_RACE_UUID);

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    try {
      // Geschaerft (BLOCKER): der definierte Konflikt ist eine BusinessRuleException (409), NICHT
      // irgendeine RuntimeException.
      assertThatThrownBy(
              () ->
                  service.exit(
                      TASK_RACE_UUID,
                      new ExitInput(ExitKind.HANDOVER, List.of(TARGET_RACE), false)))
          .isInstanceOf(BusinessRuleException.class);
    } finally {
      TenantContextHolder.clear();
    }

    // Voller Rollback: kein Ereignis, kein Target, keine Notification, Akteur NICHT ausgetreten,
    // injizierte Zeile bleibt ASSIGNEE_FLAG=0, TASK-VERSION unveraendert.
    assertThat(handoverEventCount(TASK_RACE_ID, ACTOR_REF)).as("kein TASK_HANDOVER-Ereignis").isZero();
    assertThat(handoverTargetCount(TASK_RACE_ID, TARGET_RACE)).as("kein TASK_HANDOVER_TARGET").isZero();
    assertThat(notificationCount(TASK_RACE_ID, TARGET_RACE)).as("keine Ziel-Notification").isZero();
    assertThat(actorExitedCount(TASK_RACE_ID, ACTOR_REF))
        .as("Akteur NICHT ausgetreten (voller Rollback)").isZero();
    assertThat(activeAssigneeCount(TASK_RACE_ID, TARGET_RACE))
        .as("Ziel wurde NICHT Assignee (nie 204 ueber den Gewinner mit ASSIGNEE_FLAG=1)").isZero();
    assertThat(taskVersion(TASK_RACE_UUID)).as("TASK-VERSION unveraendert").isEqualTo(versionBefore);
  }

  // ---- IT7: Versions-Verlierer, drei Paarungen -> definierter 409 + voller Rollback --------------

  @Test
  @DisplayName(
      "it7_vs_handover_voller_rollback: nebenlaeufiger Handover (ACTOR2->TARGET_WIN) gewinnt die"
          + " TASK-VERSION -> Verlierer-Handover 409 + voller Rollback; Gewinner committet (Event/"
          + " Target/History/Notify+Push/Signal)")
  void it7_vs_handover_voller_rollback() {
    armWinnerOnSeparateThread(
        () ->
            service.exit(
                TASK_HANDOVER_UUID, new ExitInput(ExitKind.HANDOVER, List.of(TARGET_WIN), false)));

    final int participantVersionBefore = participantVersion(TASK_HANDOVER_ID, ACTOR_REF);
    runLoserAndAssertFullRollback(TASK_HANDOVER_ID, TASK_HANDOVER_UUID, participantVersionBefore);

    // Gewinner-Handover committet (Positiv-Kontrast, pfadgerecht): Event/Target/Rolle/History/Push/Signal.
    assertThat(handoverEventCount(TASK_HANDOVER_ID, ACTOR2_REF)).as("Gewinner-Ereignis").isEqualTo(1);
    assertThat(handoverTargetCount(TASK_HANDOVER_ID, TARGET_WIN)).as("Gewinner-Target").isEqualTo(1);
    assertThat(activeAssigneeCount(TASK_HANDOVER_ID, TARGET_WIN)).as("TARGET_WIN Assignee").isEqualTo(1);
    assertThat(actorExitedCount(TASK_HANDOVER_ID, ACTOR2_REF)).as("Gewinner ACTOR2 ausgetreten").isEqualTo(1);
    assertThat(historyCount(TASK_HANDOVER_ID, ACTOR2_REF)).as("Gewinner-History").isEqualTo(1);
    assertThat(notificationPushPort.pushedTo(TARGET_WIN)).as("Push an TARGET_WIN").isTrue();
    assertThat(inboxSignalPort.signaled(TARGET_WIN)).as("Signal an TARGET_WIN").isTrue();
  }

  @Test
  @DisplayName(
      "it7_vs_exit_voller_rollback: nebenlaeufiger Austritt (ACTOR2 UNSUBSCRIBED) gewinnt die"
          + " TASK-VERSION -> Verlierer-Handover 409 + voller Rollback; Gewinner committet (Rolle/"
          + " UserState/History/Signal, OHNE Push/Notification)")
  void it7_vs_exit_voller_rollback() {
    armWinnerOnSeparateThread(
        () -> service.exit(TASK_EXIT_UUID, new ExitInput(ExitKind.UNSUBSCRIBED)));

    final int participantVersionBefore = participantVersion(TASK_EXIT_ID, ACTOR_REF);
    runLoserAndAssertFullRollback(TASK_EXIT_ID, TASK_EXIT_UUID, participantVersionBefore);

    // Gewinner-Austritt committet (pfadgerecht): UNSUBSCRIBED + UserState + History + Signal, KEINE
    // Notification/Push (ein Unsubscribe emittiert keine Benachrichtigung).
    assertThat(unsubscribedCount(TASK_EXIT_ID, ACTOR2_REF)).as("Gewinner ACTOR2 UNSUBSCRIBED").isEqualTo(1);
    assertThat(userStateUnsubscribedCount(TASK_EXIT_ID, ACTOR2_REF)).as("Gewinner-UserState UNSUBSCRIBED_AT").isEqualTo(1);
    assertThat(historyCount(TASK_EXIT_ID, ACTOR2_REF)).as("Gewinner-History").isEqualTo(1);
    assertThat(inboxSignalPort.sends()).as("Gewinner-Signal gefeuert").isNotEmpty();
    assertThat(notificationPushPort.pushedRecipients())
        .as("Unsubscribe erzeugt KEINE Notification/Push; Verlierer rollte zurueck -> gar kein Push")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "it7_vs_put_voller_rollback: nebenlaeufiges PUT /assignees (ACTOR2 fuegt TARGET_WIN hinzu)"
          + " gewinnt die TASK-VERSION -> Verlierer-Handover 409 + voller Rollback; Gewinner committet"
          + " (Assignee + ASSIGN-Notify/Push + Signal)")
  void it7_vs_put_voller_rollback() {
    // PUT-Zielset = aktuelle Assignees {ACTOR, ACTOR2} + TARGET_WIN -> nur TARGET_WIN wird ergaenzt
    // (ACTOR NICHT in toRemove), VERSION gebumpt. Beruehrt die ACTOR-Zeile NICHT.
    armWinnerOnSeparateThread(
        () ->
            service.updateAssignees(
                TASK_PUT_UUID,
                new AssigneeUpdateInput(Set.of(ACTOR_REF, ACTOR2_REF, TARGET_WIN))));

    final int participantVersionBefore = participantVersion(TASK_PUT_ID, ACTOR_REF);
    runLoserAndAssertFullRollback(TASK_PUT_ID, TASK_PUT_UUID, participantVersionBefore);

    // Gewinner-PUT committet (pfadgerecht): TARGET_WIN Assignee + ASSIGN-Notify/Push + Signal; KEIN
    // Handover-Ereignis fuer ACTOR2.
    assertThat(activeAssigneeCount(TASK_PUT_ID, TARGET_WIN)).as("TARGET_WIN Assignee").isEqualTo(1);
    assertThat(notificationCount(TASK_PUT_ID, TARGET_WIN)).as("ASSIGN-Notification an TARGET_WIN").isEqualTo(1);
    assertThat(notificationPushPort.pushedTo(TARGET_WIN)).as("Push an TARGET_WIN").isTrue();
    assertThat(inboxSignalPort.signaled(TARGET_WIN)).as("Signal an TARGET_WIN").isTrue();
    assertThat(handoverEventCount(TASK_PUT_ID, ACTOR2_REF)).as("PUT erzeugt kein Handover-Ereignis").isZero();
  }

  // ---- Gemeinsamer Verlierer-Ablauf + Rollback-Beweis -------------------------------------------

  /**
   * Fuehrt den Verlierer-Handover (ACTOR -&gt; TARGET_LOSE) aus (erwartet 409) und beweist den
   * VOLLSTAENDIGEN Rollback aller Dimensionen. Setzt voraus, dass der Gewinner bereits via
   * {@link #armWinnerOnSeparateThread} am Seam committet.
   */
  private void runLoserAndAssertFullRollback(
      final long taskId, final UUID taskUuid, final int actorParticipantVersionBefore) {
    final int taskVersionBefore = taskVersion(taskUuid);
    final long targetLoseGenerationBefore = inboxGeneration(TARGET_LOSE);

    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
    ACTOR_PER_THREAD.set(ACTOR_REF);
    try {
      assertThatThrownBy(
              () ->
                  service.exit(
                      taskUuid, new ExitInput(ExitKind.HANDOVER, List.of(TARGET_LOSE), false)))
          .as("Versions-Verlierer -> 409")
          .isInstanceOf(BusinessRuleException.class);
    } finally {
      ACTOR_PER_THREAD.remove();
      TenantContextHolder.clear();
    }

    // Codex-Auflage 1: Gewinner-Thread sauber beendet (kein Hang/Deadlock), fehlerfrei committet.
    assertThat(winnerTimedOut.get())
        .as("Gewinner-Thread beendete rechtzeitig (kein Deadlock/Wartezyklus)").isFalse();
    assertThat(winnerError.get()).as("Gewinner committet fehlerfrei").isNull();

    // Positiv: der Gewinner hat die TASK-VERSION GENAU um 1 gebumpt.
    assertThat(taskVersion(taskUuid))
        .as("Gewinner bumpt die TASK-VERSION um genau 1").isEqualTo(taskVersionBefore + 1);

    // Verlierer voll zurueckgerollt - alle Dimensionen:
    assertThat(handoverEventCount(taskId, ACTOR_REF))
        .as("kein Verlierer-TASK_HANDOVER (FROM_REF=ACTOR)").isZero();
    assertThat(handoverTargetCount(taskId, TARGET_LOSE))
        .as("kein TASK_HANDOVER_TARGET fuer TARGET_LOSE").isZero();
    assertThat(activeAssigneeCount(taskId, ACTOR_REF))
        .as("ACTOR weiter aktiver Assignee").isEqualTo(1);
    assertThat(actorExitedCount(taskId, ACTOR_REF)).as("ACTOR NICHT ausgetreten").isZero();
    assertThat(participantVersion(taskId, ACTOR_REF))
        .as("ACTOR-Participant-VERSION unveraendert (kein stiller Rollforward)")
        .isEqualTo(actorParticipantVersionBefore);
    assertThat(userStateUnsubscribedCount(taskId, ACTOR_REF))
        .as("ACTOR-UserState unveraendert (UNSUBSCRIBED_AT bleibt NULL)").isZero();
    assertThat(participantExistsCount(taskId, TARGET_LOSE))
        .as("TARGET_LOSE ist KEIN Participant").isZero();
    assertThat(userStateExistsCount(taskId, TARGET_LOSE))
        .as("TARGET_LOSE hat KEINEN UserState").isZero();
    assertThat(inboxGeneration(TARGET_LOSE))
        .as("TARGET_LOSE-Generation unveraendert (kein Bump)").isEqualTo(targetLoseGenerationBefore);
    assertThat(notificationCount(taskId, TARGET_LOSE))
        .as("keine persistierte Notification fuer TARGET_LOSE (in-Tx Rollback)").isZero();
    assertThat(historyCount(taskId, ACTOR_REF))
        .as("kein History-Eintrag des ACTOR-Handovers").isZero();
    // After-Commit-Hook-Ruecknahme: der in Block 5 registrierte Push-Hook an TARGET_LOSE darf NICHT
    // feuern, das after-commit-Signal den TARGET_LOSE nicht enthalten.
    assertThat(notificationPushPort.pushedTo(TARGET_LOSE))
        .as("kein After-Commit-Push an TARGET_LOSE (Hook zurueckgerollt)").isFalse();
    assertThat(inboxSignalPort.signaled(TARGET_LOSE))
        .as("kein After-Commit-Signal an TARGET_LOSE").isFalse();
  }

  /**
   * Armt den Gewinner so, dass er am Top-of-exit-Seam des Verlierers auf einem EIGENEN Thread (eigener
   * Tenant + Akteur ACTOR2, eigene @Transactional ueber den Service-Proxy) vollstaendig laeuft und
   * committet, bevor der Verlierer fortsetzt. {@code join} mit Timeout; Timeout/lebender Thread ->
   * Test faellt (Codex-Auflage 1). Der Seam-Injector ist One-Shot, sodass die exit()-Re-Entry des
   * Gewinners (bei Handover/Exit) den Seam als No-Op sieht.
   */
  private void armWinnerOnSeparateThread(final Runnable winnerOp) {
    service.armExitMutationInjector(
        () -> {
          final AtomicReference<Throwable> err = new AtomicReference<>();
          final Thread winner =
              new Thread(
                  () -> {
                    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT_ID));
                    ACTOR_PER_THREAD.set(ACTOR2_REF);
                    try {
                      winnerOp.run();
                    } catch (final Throwable t) {
                      err.set(t);
                    } finally {
                      ACTOR_PER_THREAD.remove();
                      TenantContextHolder.clear();
                    }
                  },
                  "it7-winner");
          winner.start();
          try {
            winner.join(WINNER_JOIN_TIMEOUT_MS);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            err.set(e);
          }
          if (winner.isAlive()) {
            winnerTimedOut.set(true);
            winner.interrupt();
          }
          winnerError.set(err.get());
        });
  }

  // ---- Injektion IT6 ---------------------------------------------------------------------------

  private static void injectCommittedPureParticipant(
      final DataSource ds, final long id, final long taskId, final UUID ref) {
    final String sql =
        "INSERT INTO [dbo].[TASK_PARTICIPANT] "
            + "(ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TASK_ID, PARTICIPANT_REF, "
            + " PARTICIPANT_KIND, PARTICIPANT_TENANT_ID, CREATOR_FLAG, ASSIGNEE_FLAG, "
            + " PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM) "
            + "VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, 0, 0, 1, 'it-inject', GETDATE())";
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(true);
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, id);
        ps.setLong(2, TENANT_ID);
        ps.setLong(3, taskId);
        ps.setString(4, ref.toString());
        ps.setLong(5, TENANT_ID);
        ps.executeUpdate();
      }
    } catch (final java.sql.SQLException e) {
      throw new IllegalStateException("Ziel-Konflikt-Injektion fehlgeschlagen", e);
    }
  }

  // ---- Sonden (jdbcTemplate) -------------------------------------------------------------------

  private Integer count(final String sql, final Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private int handoverEventCount(final long taskId, final UUID fromRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER] WHERE TASK_ID=? AND TENANT_ID=? AND FROM_REF=?",
        taskId, TENANT_ID, fromRef);
  }

  private int handoverTargetCount(final long taskId, final UUID toRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_HANDOVER_TARGET] tgt"
            + " JOIN [dbo].[TASK_HANDOVER] h ON tgt.HANDOVER_ID=h.ID"
            + " WHERE h.TASK_ID=? AND h.TENANT_ID=? AND tgt.TO_REF=?",
        taskId, TENANT_ID, toRef);
  }

  private int notificationCount(final long taskId, final UUID recipientRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_NOTIFICATION] WHERE TASK_ID=? AND TENANT_ID=? AND RECIPIENT_REF=?",
        taskId, TENANT_ID, recipientRef);
  }

  private int activeAssigneeCount(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
            + " AND ASSIGNEE_FLAG=1 AND EXIT_KIND IS NULL",
        taskId, ref);
  }

  private int actorExitedCount(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
            + " AND EXIT_KIND='HANDOVER'",
        taskId, ref);
  }

  private int unsubscribedCount(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?"
            + " AND EXIT_KIND='UNSUBSCRIBED'",
        taskId, ref);
  }

  private int participantExistsCount(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?",
        taskId, ref);
  }

  private int participantVersion(final long taskId, final UUID ref) {
    return count(
        "SELECT VERSION FROM [dbo].[TASK_PARTICIPANT] WHERE TASK_ID=? AND PARTICIPANT_REF=?",
        taskId, ref);
  }

  private int userStateUnsubscribedCount(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?"
            + " AND UNSUBSCRIBED_AT IS NOT NULL",
        taskId, ref);
  }

  private int userStateExistsCount(final long taskId, final UUID ref) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_USER_STATE] WHERE TASK_ID=? AND USER_REF=?", taskId, ref);
  }

  private int historyCount(final long taskId, final UUID actorRef) {
    return count(
        "SELECT COUNT(*) FROM [dbo].[TASK_HISTORY_ENTRY] WHERE TASK_ID=? AND TENANT_ID=? AND ACTOR_REF=?",
        taskId, TENANT_ID, actorRef);
  }

  private long inboxGeneration(final UUID ref) {
    final Long g =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(GENERATION), 0) FROM [dbo].[TASK_INBOX_GENERATION]"
                + " WHERE TENANT_ID=? AND USER_REF=?",
            Long.class, TENANT_ID, ref);
    return g == null ? 0L : g;
  }

  private int taskVersion(final UUID taskUuid) {
    return jdbcTemplate.queryForObject(
        "SELECT VERSION FROM [dbo].[TASK] WHERE EXTERNALID=?", Integer.class, taskUuid);
  }

  // ---- Seed-Helfer -----------------------------------------------------------------------------

  private static void seedFirma(final JdbcTemplate jt, final long id, final UUID externalId) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_FIRMA] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id, id, externalId, id);
  }

  private static void seedBenutzer(
      final JdbcTemplate jt, final long id, final UUID externalId, final String loginName) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[PROD_BENUTZER] WHERE ID = ?) "
            + "INSERT INTO [dbo].[PROD_BENUTZER] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " LOGIN_NAME, VOLLER_NAME) VALUES (?, 'ACTIVE', ?, 1, ?, ?, 'IT Test User')",
        id, id, externalId, TENANT_ID, loginName);
  }

  private static void insertTask(
      final JdbcTemplate jt, final long id, final UUID externalId, final String title) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID, TITLE,"
            + " TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID, LAST_ACTIVITY_AT,"
            + " COMMENT_COUNT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', ?, 1, ?, ?, ?, ?, ?, ?, GETDATE(), 0, 'it-seed', GETDATE())",
        id, id, externalId, TENANT_ID, title, STATUS_OPEN, PRIORITY_NORMAL, DIRECTION, TENANT_ID);
  }

  private static void insertActiveAssignee(
      final JdbcTemplate jt, final long id, final long taskId, final UUID ref, final boolean creator) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_PARTICIPANT] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION,"
            + " TENANT_ID, TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, 'PERSON', ?, ?, 1, 1, 'it-seed', GETDATE())",
        id, id, TENANT_ID, taskId, ref, TENANT_ID, creator ? 1 : 0);
  }

  /** Aktive (nicht ausgetretene) UserState-Zeile fuer die ACTOR-/ACTOR2-Rollback-Beweise. */
  private static void insertActiveUserState(
      final JdbcTemplate jt, final long id, final long taskId, final UUID ref) {
    jt.update(
        "IF NOT EXISTS (SELECT 1 FROM [dbo].[TASK_USER_STATE] WHERE ID = ?) "
            + "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED, ANGELEGT_VON,"
            + " ANGELEGT_AM) VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 0, 0, 0, 'it-seed', GETDATE())",
        id, id, TENANT_ID, taskId, ref, TENANT_ID);
  }

  // ---- Aufzeichnende Ports (echte Services, beobachtbar statt gemockt) --------------------------

  /** Zeichnet die After-Commit-Signal-Sends auf (statt eines No-Op/WS-Ports). */
  static final class RecordingInboxSignalPort implements InboxSignalPort {
    private final List<Set<UUID>> sends = new CopyOnWriteArrayList<>();

    @Override
    public void send(final long tenantId, final Collection<UUID> userRefs) {
      sends.add(new HashSet<>(userRefs));
    }

    List<Set<UUID>> sends() {
      return sends;
    }

    boolean signaled(final UUID ref) {
      return sends.stream().anyMatch(s -> s.contains(ref));
    }

    void clear() {
      sends.clear();
    }
  }

  /** Zeichnet die After-Commit-Notification-Pushes (je Empfaenger) auf. */
  static final class RecordingNotificationPushPort implements NotificationPushPort {
    private final List<UUID> pushedRecipients = new CopyOnWriteArrayList<>();

    @Override
    public void push(
        final long tenantId, final UUID recipientRef, final NotificationPushEnvelope envelope) {
      pushedRecipients.add(recipientRef);
    }

    List<UUID> pushedRecipients() {
      return pushedRecipients;
    }

    boolean pushedTo(final UUID ref) {
      return pushedRecipients.contains(ref);
    }

    void clear() {
      pushedRecipients.clear();
    }
  }

  // ---- Test-Service: Seams fuer deterministische Nebenlaeufigkeit -------------------------------

  /**
   * Test-Subklasse: injiziert IT6 eine committete Konflikt-Zeile am Ziel-Save-Seam
   * ({@link #onBeforeHandoverAssigneeSaveForTest}) ODER laesst IT7 am Top-of-exit-Seam
   * ({@link #onBeforeExitMutationForTest}) einen nebenlaeufigen Gewinner committen.
   */
  static class TestAssigneeService extends AssigneeService {

    private final AtomicReference<Runnable> targetInjector = new AtomicReference<>();
    private volatile UUID targetInjectorRef;
    private final AtomicReference<Runnable> exitMutationInjector = new AtomicReference<>();

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
        final TaskHistoryService taskHistoryService) {
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
          taskHistoryService);
    }

    void armTargetInjector(final Runnable injector, final UUID targetRef) {
      this.targetInjector.set(injector);
      this.targetInjectorRef = targetRef;
      this.exitMutationInjector.set(null);
    }

    void armExitMutationInjector(final Runnable injector) {
      this.exitMutationInjector.set(injector);
      this.targetInjector.set(null);
    }

    @Override
    protected void onBeforeHandoverAssigneeSaveForTest(final Long taskId, final UUID targetRef) {
      if (targetRef.equals(targetInjectorRef)) {
        final Runnable inj = targetInjector.getAndSet(null);
        if (inj != null) {
          inj.run();
        }
      }
    }

    @Override
    protected void onBeforeExitMutationForTest(final Long taskId) {
      // One-Shot: der Verlierer loest den Gewinner aus; dessen exit()-Re-Entry sieht null -> No-Op.
      final Runnable inj = exitMutationInjector.getAndSet(null);
      if (inj != null) {
        inj.run();
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
      return () -> "handover-concurrency-it";
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
    TaskHistoryService taskHistoryService(
        final de.brasi.nova.taskmanagement.repository.TaskHistoryEntryQueryRepository repo) {
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

    // ECHTE Services (NICHT gemockt) - damit der Verlierer-Rollback persistierter Notifications UND
    // der After-Commit-Signale real belegbar ist.
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
      // Thread-lokaler Akteur: der Verlierer-Thread liest ACTOR, der Gewinner-Thread ACTOR2.
      return () -> {
        final UUID ref = ACTOR_PER_THREAD.get();
        return new AuthUser(ref, ref + "@brasi.de");
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
        final TaskHistoryService taskHistoryService) {
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
          taskHistoryService);
    }
  }
}
