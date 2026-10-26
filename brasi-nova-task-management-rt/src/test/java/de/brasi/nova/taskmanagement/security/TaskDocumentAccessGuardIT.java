package de.brasi.nova.taskmanagement.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItDb;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnabled;
import de.brasi.nova.taskmanagement.security.it.RefSchemaItEnv;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.AuditorProvider;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.integration.autoconfigure.KsfIntegrationAutoConfiguration;
import io.keel.framework.persistence.autoconfigure.KsfPersistenceAutoConfiguration;
import io.keel.framework.persistence.factory.EnableNovaRepositories;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/** Real MSSQL proof of the document READ predicate, including inconsistent tenant columns. */
@SpringBootTest(
    classes = TaskDocumentAccessGuardIT.ItConfig.class,
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
    })
@RefSchemaItEnabled
@Execution(ExecutionMode.SAME_THREAD)
class TaskDocumentAccessGuardIT {

  private static final long TENANT = 581L;
  private static final long FOREIGN_TENANT = 582L;
  private static final UUID TENANT_UUID = UUID.fromString("00000581-0000-0000-0000-000000000581");
  private static final UUID FOREIGN_TENANT_UUID =
      UUID.fromString("00000582-0000-0000-0000-000000000582");
  private static final UUID ACTOR = UUID.fromString("00000581-0000-0000-0000-000000000001");
  private static final UUID OTHER_ACTOR = UUID.fromString("00000581-0000-0000-0000-000000000003");
  private static final AtomicLong IDS = new AtomicLong(581000L);

  private static HikariDataSource master;
  private static String dbName;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TaskDocumentAccessGuard guard;

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

  @BeforeAll
  static void seedFirms(@Autowired final JdbcTemplate jdbc) {
    insertFirm(jdbc, TENANT, TENANT_UUID);
    insertFirm(jdbc, FOREIGN_TENANT, FOREIGN_TENANT_UUID);
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

  @BeforeEach
  void setTenant() {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, TENANT));
  }

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void active_person_with_active_subscribed_state_can_read_only_own_active_task() {
    final Fixture active = seedActive();
    allowed(active.taskId());
    denied(active.taskId(), OTHER_ACTOR);
    denied(999999999L, ACTOR);

    final Fixture inactiveTask = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK] SET ENTITY_STATUS='INACTIVE' WHERE ID=?", inactiveTask.taskId());
    denied(inactiveTask.taskId(), ACTOR);

    final long foreignTask = insertTask(FOREIGN_TENANT);
    insertParticipant(foreignTask, FOREIGN_TENANT, FOREIGN_TENANT, "PERSON");
    insertState(foreignTask, FOREIGN_TENANT, FOREIGN_TENANT);
    denied(foreignTask, ACTOR);

    // Contradictory data: membership is entirely in 581, but the task itself belongs to 582.
    // V1 has no participant/state -> TASK foreign key, so this can be persisted.
    final long foreignTaskWithLocalMembership = insertTask(FOREIGN_TENANT);
    insertParticipant(foreignTaskWithLocalMembership, TENANT, TENANT, "PERSON");
    insertState(foreignTaskWithLocalMembership, TENANT, TENANT);
    denied(foreignTaskWithLocalMembership, ACTOR);
  }

  @Test
  void inactive_exited_team_or_unsubscribed_membership_is_denied() {
    final Fixture inactiveParticipant = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET ENTITY_STATUS='INACTIVE' WHERE ID=?",
        inactiveParticipant.participantId());
    denied(inactiveParticipant.taskId(), ACTOR);

    final Fixture exited = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET EXIT_KIND='HANDOVER', EXIT_AT=GETDATE() WHERE ID=?",
        exited.participantId());
    denied(exited.taskId(), ACTOR);

    final Fixture team = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET PARTICIPANT_KIND='TEAM' WHERE ID=?",
        team.participantId());
    denied(team.taskId(), ACTOR);

    final Fixture unsubscribed = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_USER_STATE] SET UNSUBSCRIBED_AT=GETDATE() WHERE ID=?",
        unsubscribed.stateId());
    denied(unsubscribed.taskId(), ACTOR);
  }

  @Test
  void missing_or_inactive_state_fails_closed_and_reactivation_restores_read() {
    final long missingStateTask = insertTask(TENANT);
    insertParticipant(missingStateTask, TENANT, TENANT, "PERSON");
    denied(missingStateTask, ACTOR);

    final Fixture inactiveState = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_USER_STATE] SET ENTITY_STATUS='INACTIVE' WHERE ID=?",
        inactiveState.stateId());
    denied(inactiveState.taskId(), ACTOR);

    final Fixture reactivated = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_USER_STATE] SET UNSUBSCRIBED_AT=GETDATE() WHERE ID=?",
        reactivated.stateId());
    denied(reactivated.taskId(), ACTOR);
    jdbc.update(
        "UPDATE [dbo].[TASK_USER_STATE] SET UNSUBSCRIBED_AT=NULL WHERE ID=?",
        reactivated.stateId());
    allowed(reactivated.taskId());
  }

  @Test
  void contradictory_participant_and_state_tenant_columns_each_fail_closed() {
    final Fixture participantBaseTenant = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET TENANT_ID=? WHERE ID=?",
        FOREIGN_TENANT,
        participantBaseTenant.participantId());
    denied(participantBaseTenant.taskId(), ACTOR);

    final Fixture participantDomainTenant = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET PARTICIPANT_TENANT_ID=? WHERE ID=?",
        FOREIGN_TENANT,
        participantDomainTenant.participantId());
    denied(participantDomainTenant.taskId(), ACTOR);

    final Fixture stateBaseTenant = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_USER_STATE] SET TENANT_ID=? WHERE ID=?",
        FOREIGN_TENANT,
        stateBaseTenant.stateId());
    denied(stateBaseTenant.taskId(), ACTOR);

    final Fixture stateDomainTenant = seedActive();
    jdbc.update(
        "UPDATE [dbo].[TASK_USER_STATE] SET USER_TENANT_ID=? WHERE ID=?",
        FOREIGN_TENANT,
        stateDomainTenant.stateId());
    denied(stateDomainTenant.taskId(), ACTOR);
  }

  @Test
  void remaining_active_participant_can_read_after_original_participant_exits() {
    final Fixture original = seedActive();
    insertParticipant(original.taskId(), TENANT, TENANT, "PERSON", OTHER_ACTOR);
    insertState(original.taskId(), TENANT, TENANT, OTHER_ACTOR);

    jdbc.update(
        "UPDATE [dbo].[TASK_PARTICIPANT] SET EXIT_KIND='HANDOVER', EXIT_AT=GETDATE() WHERE ID=?",
        original.participantId());

    denied(original.taskId(), ACTOR);
    allowed(original.taskId(), OTHER_ACTOR);
  }

  private void allowed(final long taskId) {
    allowed(taskId, ACTOR);
  }

  private void allowed(final long taskId, final UUID actor) {
    assertThatCode(() -> guard.requireRead(taskId, actor, TENANT)).doesNotThrowAnyException();
  }

  private void denied(final long taskId, final UUID actor) {
    assertThatThrownBy(() -> guard.requireRead(taskId, actor, TENANT))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Aufgabe nicht gefunden.");
  }

  private Fixture seedActive() {
    final long taskId = insertTask(TENANT);
    final long participantId = insertParticipant(taskId, TENANT, TENANT, "PERSON");
    final long stateId = insertState(taskId, TENANT, TENANT);
    return new Fixture(taskId, participantId, stateId);
  }

  private long insertTask(final long tenant) {
    final long id = IDS.incrementAndGet();
    jdbc.update(
        "INSERT INTO [dbo].[TASK] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " TITLE, TASK_STATUS_ID, PRIORITY_ID, DIRECTION_ID, ORIGIN_TENANT_ID,"
            + " LAST_ACTIVITY_AT, COMMENT_COUNT, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, 'document-read-it', 101, 201, 301, ?,"
            + " GETDATE(), 0, 'it-seed', GETDATE())",
        id,
        tenant,
        tenant);
    return id;
  }

  private long insertParticipant(
      final long taskId, final long tenant, final long participantTenant, final String kind) {
    return insertParticipant(taskId, tenant, participantTenant, kind, ACTOR);
  }

  private long insertParticipant(
      final long taskId,
      final long tenant,
      final long participantTenant,
      final String kind,
      final UUID actor) {
    final long id = IDS.incrementAndGet();
    jdbc.update(
        "INSERT INTO [dbo].[TASK_PARTICIPANT] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, PARTICIPANT_REF, PARTICIPANT_KIND, PARTICIPANT_TENANT_ID,"
            + " CREATOR_FLAG, ASSIGNEE_FLAG, PARTICIPANT_FLAG, ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, ?, 0, 0, 1, 'it-seed', GETDATE())",
        id,
        tenant,
        taskId,
        actor,
        kind,
        participantTenant);
    return id;
  }

  private long insertState(final long taskId, final long tenant, final long userTenant) {
    return insertState(taskId, tenant, userTenant, ACTOR);
  }

  private long insertState(
      final long taskId, final long tenant, final long userTenant, final UUID actor) {
    final long id = IDS.incrementAndGet();
    jdbc.update(
        "INSERT INTO [dbo].[TASK_USER_STATE] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " TASK_ID, USER_REF, USER_TENANT_ID, IS_PINNED, IS_FLAGGED, IS_MUTED,"
            + " ANGELEGT_VON, ANGELEGT_AM)"
            + " VALUES (?, 'ACTIVE', NEWID(), 1, ?, ?, ?, ?, 0, 0, 0, 'it-seed', GETDATE())",
        id,
        tenant,
        taskId,
        actor,
        userTenant);
    return id;
  }

  private static void insertFirm(final JdbcTemplate jdbc, final long id, final UUID externalId) {
    jdbc.update(
        "INSERT INTO [dbo].[PROD_FIRMA] (ID, ENTITY_STATUS, EXTERNALID, VERSION, TENANT_ID,"
            + " GUELTIG_VON) VALUES (?, 'ACTIVE', ?, 1, ?, CAST(GETDATE() AS DATE))",
        id,
        externalId,
        id);
  }

  private record Fixture(long taskId, long participantId, long stateId) {}

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
      return () -> "document-read-it";
    }

    @Bean
    TaskDocumentAccessGuard taskDocumentAccessGuard(
        final TaskParticipantQueryRepository repository) {
      return new TaskDocumentAccessGuard(repository);
    }
  }
}
