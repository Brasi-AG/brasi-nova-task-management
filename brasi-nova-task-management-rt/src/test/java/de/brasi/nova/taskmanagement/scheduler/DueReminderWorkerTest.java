package de.brasi.nova.taskmanagement.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.refentity.Firm;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.cache.IdMappingService;
import io.keel.framework.api.exception.BusinessRuleException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Unit-Tests fuer {@link DueReminderWorker}: Frueh-Abbruch per Laufzeit-Flag, Idempotenz bei
 * UNIQUE-Konflikt und Bean-Gating ueber {@code task.scheduler.worker-enabled}.
 */
class DueReminderWorkerTest {

  private static final long TENANT_ID = 560L;
  private static final long TASK_ID = 100L;
  private static final UUID RECIPIENT = UUID.randomUUID();
  private static final UUID TENANT_UUID = UUID.randomUUID();

  private final TaskKeysetQueryRepository taskQueryRepository = mock(TaskKeysetQueryRepository.class);
  private final TaskParticipantQueryRepository participantRepository =
      mock(TaskParticipantQueryRepository.class);
  private final DueReminderEmitter emitter = mock(DueReminderEmitter.class);
  private final DictionaryCodeResolver resolver = mock(DictionaryCodeResolver.class);
  private final IdMappingService idMappingService = mock(IdMappingService.class);
  private final AuditTimeConverter auditTimeConverter = mock(AuditTimeConverter.class);

  private DueReminderWorker worker(final boolean enabled) {
    return new DueReminderWorker(
        enabled,
        taskQueryRepository,
        participantRepository,
        emitter,
        resolver,
        idMappingService,
        auditTimeConverter);
  }

  // ---- gates_stoppen_frueh --------------------------------------------------------------------

  @Test
  @DisplayName("gates_stoppen_frueh: enabled=false -> keine Kandidatensuche, kein emit")
  void gates_stoppen_frueh() {
    worker(false).checkDueTasks();

    verify(taskQueryRepository, never()).findDueOpenTasks(any(), any());
    verify(emitter, never()).emitOnce(anyLong(), any(), any(), any(), any());
  }

  // ---- uniqueKonflikt_ist_erledigt ------------------------------------------------------------

  @Test
  @DisplayName(
      "uniqueKonflikt_ist_erledigt: DuplicateKeyException aus emitOnce wird als erledigt verschluckt")
  void uniqueKonflikt_ist_erledigt() {
    final Task task = mock(Task.class);
    when(task.getId()).thenReturn(TASK_ID);
    when(task.getTenantId()).thenReturn(TENANT_ID);
    when(task.getTitle()).thenReturn("Faellige Aufgabe");
    final TaskParticipant assignee = mock(TaskParticipant.class);
    when(assignee.getParticipantRef()).thenReturn(RECIPIENT);

    when(auditTimeConverter.zone()).thenReturn(ZoneId.of("Europe/Berlin"));
    when(resolver.resolveTaskStatusOpen()).thenReturn(1L);
    when(taskQueryRepository.findDueOpenTasks(any(LocalDate.class), eq(1L)))
        .thenReturn(List.of(task));
    when(idMappingService.resolveIdToUuid(eq(TENANT_ID), eq(Firm.class), isNull()))
        .thenReturn(TENANT_UUID.toString());
    when(participantRepository.findActiveAssigneesByTaskId(TASK_ID)).thenReturn(List.of(assignee));
    org.mockito.Mockito.doThrow(new BusinessRuleException("UNIQUE-Konflikt"))
        .when(emitter)
        .emitOnce(eq(TENANT_ID), eq(TASK_ID), eq(RECIPIENT), any(LocalDate.class), any());

    // Der Konflikt darf NICHT nach aussen propagieren (idempotent "bereits erledigt").
    assertThatCode(() -> worker(true).checkDueTasks()).doesNotThrowAnyException();
    verify(emitter, times(1))
        .emitOnce(eq(TENANT_ID), eq(TASK_ID), eq(RECIPIENT), any(LocalDate.class), any());
  }

  // ---- Bean-Gating (beide Flag-Zustaende) -----------------------------------------------------

  @Test
  @DisplayName("worker_bean_present_when_worker_enabled_true")
  void worker_bean_present_when_worker_enabled_true() {
    contextRunner()
        .withPropertyValues("task.scheduler.worker-enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(DueReminderWorker.class));
  }

  @Test
  @DisplayName("worker_bean_absent_when_worker_enabled_false_or_missing")
  void worker_bean_absent_when_worker_enabled_false_or_missing() {
    contextRunner()
        .withPropertyValues("task.scheduler.worker-enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(DueReminderWorker.class));
    contextRunner().run(ctx -> assertThat(ctx).doesNotHaveBean(DueReminderWorker.class));
  }

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(WorkerGatingTestConfig.class);
  }

  /** Stellt die Abhaengigkeiten der Worker-Bean als Mocks bereit + importiert die (bedingte) Bean. */
  @Configuration(proxyBeanMethods = false)
  @Import(DueReminderWorker.class)
  static class WorkerGatingTestConfig {

    @Bean
    TaskKeysetQueryRepository taskKeysetQueryRepository() {
      return mock(TaskKeysetQueryRepository.class);
    }

    @Bean
    TaskParticipantQueryRepository taskParticipantQueryRepository() {
      return mock(TaskParticipantQueryRepository.class);
    }

    @Bean
    DueReminderEmitter dueReminderEmitter() {
      return mock(DueReminderEmitter.class);
    }

    @Bean
    DictionaryCodeResolver dictionaryCodeResolver() {
      return mock(DictionaryCodeResolver.class);
    }

    @Bean
    IdMappingService idMappingService() {
      return mock(IdMappingService.class);
    }

    @Bean
    AuditTimeConverter auditTimeConverter() {
      return mock(AuditTimeConverter.class);
    }
  }
}
