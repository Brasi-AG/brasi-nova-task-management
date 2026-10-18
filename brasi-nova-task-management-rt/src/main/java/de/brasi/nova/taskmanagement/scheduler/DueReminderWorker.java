package de.brasi.nova.taskmanagement.scheduler;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.entity.TaskParticipant;
import de.brasi.nova.taskmanagement.refentity.Firm;
import de.brasi.nova.taskmanagement.repository.TaskKeysetQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskParticipantQueryRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.time.AuditTimeConverter;
import io.keel.framework.api.cache.IdMappingService;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Due-Reminder-Scheduler (arch-v2 §5, plan-v3 Task 16).
 *
 * <p>Zwei-Stufen-Steuerung (arch-v2 §5): Die Bean existiert nur bei
 * {@code task.scheduler.worker-enabled=true} (dann registriert {@code @EnableScheduling} auf der
 * Anwendung das {@code @Scheduled}); der Lauf selbst bricht beim Laufzeit-Flag
 * {@code task.scheduler.enabled=false} frueh ab. Beide sind per Default aus -> der Scheduler ist
 * inert.
 *
 * <p>Ablauf je Lauf: {@code runAsSystem} -> {@link TaskKeysetQueryRepository#findDueOpenTasks}
 * (global, ohne {@code :tenantId}) -> pro Treffer {@code runWithTenant(tenantOf(row))} -> aktive
 * Assignees -> pro Empfaenger {@link DueReminderEmitter#emitOnce} (eigene
 * {@code REQUIRES_NEW}-Transaktion). Der UNIQUE-Konflikt auf der Idempotenz-Quittung wird von KSF
 * als {@link BusinessRuleException} (HTTP 409) gemeldet und hier (ausserhalb der
 * Emitter-Transaktion) als "bereits erledigt" verschluckt - idempotent, kein zweites DUE. Die
 * einzige {@code BusinessRuleException}-Quelle in {@code emitOnce} ist dieser
 * Quittungs-UNIQUE-Konflikt.
 */
@Component
@ConditionalOnProperty(
    prefix = "task.scheduler",
    name = "worker-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class DueReminderWorker {

  private static final Logger log = LoggerFactory.getLogger(DueReminderWorker.class);

  private final boolean enabled;
  private final TaskKeysetQueryRepository taskQueryRepository;
  private final TaskParticipantQueryRepository participantRepository;
  private final DueReminderEmitter emitter;
  private final DictionaryCodeResolver resolver;
  private final IdMappingService idMappingService;
  private final AuditTimeConverter auditTimeConverter;

  /**
   * Konstruktor-Injektion. Das Laufzeit-Flag kommt direkt per {@code @Value}
   * ({@code task.scheduler.enabled}, Default {@code false}) - die Bean-Existenz regelt separat
   * {@code @ConditionalOnProperty(task.scheduler.worker-enabled)}.
   */
  public DueReminderWorker(
      @Value("${task.scheduler.enabled:false}") final boolean enabled,
      final TaskKeysetQueryRepository taskQueryRepository,
      final TaskParticipantQueryRepository participantRepository,
      final DueReminderEmitter emitter,
      final DictionaryCodeResolver resolver,
      final IdMappingService idMappingService,
      final AuditTimeConverter auditTimeConverter) {
    this.enabled = enabled;
    this.taskQueryRepository = taskQueryRepository;
    this.participantRepository = participantRepository;
    this.emitter = emitter;
    this.resolver = resolver;
    this.idMappingService = idMappingService;
    this.auditTimeConverter = auditTimeConverter;
  }

  /** Geplanter Einstieg; das Laufzeit-Flag {@code enabled} entscheidet, ob ein Lauf stattfindet. */
  @Scheduled(fixedDelayString = "${task.scheduler.delay-ms:60000}")
  public void checkDueTasks() {
    if (!enabled) {
      log.debug("DueReminderWorker: Laufzeit-Flag 'enabled' aus -> Lauf uebersprungen.");
      return;
    }
    runDueReminderCycle();
  }

  /**
   * Fuehrt einen vollstaendigen Due-Reminder-Lauf aus (paketsichtbar fuer Tests). Laeuft im
   * System-Scope; die Kandidatensuche ist bewusst mandantenuebergreifend.
   */
  void runDueReminderCycle() {
    TenantContextHolder.runAsSystem(
        () -> {
          final LocalDate today = LocalDate.now(auditTimeConverter.zone());
          final Long openStatusId = resolver.resolveTaskStatusOpen();
          final List<Task> due = taskQueryRepository.findDueOpenTasks(today, openStatusId);
          log.info("DueReminderWorker: {} faellige OPEN-Aufgaben am Tag {}.", due.size(), today);
          for (final Task task : due) {
            processTask(task, today);
          }
          return null;
        });
  }

  /** Verarbeitet eine faellige Aufgabe im Mandanten-Kontext: Assignees aufloesen + emittieren. */
  private void processTask(final Task task, final LocalDate today) {
    final String tenantUuid =
        idMappingService.resolveIdToUuid(task.getTenantId(), Firm.class, null);
    final TenantInfo tenant = new TenantInfo(UUID.fromString(tenantUuid), task.getTenantId());
    TenantContextHolder.runWithTenant(
        tenant,
        () -> {
          final List<TaskParticipant> assignees =
              participantRepository.findActiveAssigneesByTaskId(task.getId());
          for (final TaskParticipant assignee : assignees) {
            try {
              emitter.emitOnce(
                  task.getTenantId(),
                  task.getId(),
                  assignee.getParticipantRef(),
                  today,
                  task.getTitle());
            } catch (BusinessRuleException alreadyEmitted) {
              // UNIQUE-Konflikt auf der Idempotenz-Quittung (KSF -> 409): bereits erledigt.
              log.debug(
                  "DueReminderWorker: DUE fuer Aufgabe {} / Empfaenger {} bereits erledigt"
                      + " (idempotent, UNIQUE-Konflikt).",
                  task.getId(),
                  assignee.getParticipantRef());
            }
          }
          return null;
        });
  }
}
