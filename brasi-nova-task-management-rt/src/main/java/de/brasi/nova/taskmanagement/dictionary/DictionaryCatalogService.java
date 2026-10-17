package de.brasi.nova.taskmanagement.dictionary;

import de.brasi.nova.taskmanagement.dictionary.model.DictionaryCatalogModel;
import de.brasi.nova.taskmanagement.dictionary.model.DictionaryEntryModel;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;
import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationTypeRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TagSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskDirectionRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskPriorityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Liefert den gesamten Dictionary-Katalog (alle 6 System-Dictionaries).
 *
 * <p>Alle Reads laufen unter {@code TenantContextHolder.runWithTenant(TenantInfo.global(), ...)},
 * da alle System-Dictionaries TENANT_ID=0 haben. Diese Methode ist read-only (seed-only in 1a).
 */
@Service
public class DictionaryCatalogService {

  private final TaskStatusRepository taskStatusRepository;
  private final TaskPriorityRepository taskPriorityRepository;
  private final TaskDirectionRepository taskDirectionRepository;
  private final NotificationTypeRepository notificationTypeRepository;
  private final NotificationSeverityRepository notificationSeverityRepository;
  private final TagSeverityRepository tagSeverityRepository;

  /** Konstruktor-Injektion aller Dictionary-Repositories. */
  public DictionaryCatalogService(
      final TaskStatusRepository taskStatusRepository,
      final TaskPriorityRepository taskPriorityRepository,
      final TaskDirectionRepository taskDirectionRepository,
      final NotificationTypeRepository notificationTypeRepository,
      final NotificationSeverityRepository notificationSeverityRepository,
      final TagSeverityRepository tagSeverityRepository) {
    this.taskStatusRepository = taskStatusRepository;
    this.taskPriorityRepository = taskPriorityRepository;
    this.taskDirectionRepository = taskDirectionRepository;
    this.notificationTypeRepository = notificationTypeRepository;
    this.notificationSeverityRepository = notificationSeverityRepository;
    this.tagSeverityRepository = tagSeverityRepository;
  }

  /**
   * Laedt alle 6 System-Dictionaries und gibt sie als {@link DictionaryCatalogModel} zurueck.
   *
   * <p>Alle Reads laufen im globalen Scope (TENANT_ID=0), da die Dictionaries system-global sind.
   *
   * @return vollstaendiger Katalog aller aktiven Dictionary-Eintraege
   */
  public DictionaryCatalogModel list() {
    return TenantContextHolder.runWithTenant(
        TenantInfo.global(),
        () -> {
          final List<DictionaryEntryModel> taskStatus =
              taskStatusRepository.findAllValidAndActive().stream()
                  .map(e -> toEntry(e, null))
                  .toList();

          final List<DictionaryEntryModel> taskPriority =
              taskPriorityRepository.findAllValidAndActive().stream()
                  .map(e -> new DictionaryEntryModel(
                      e.getUuid(), e.getCode(), e.getEntryValue(), e.getSortOrder()))
                  .toList();

          final List<DictionaryEntryModel> taskDirection =
              taskDirectionRepository.findAllValidAndActive().stream()
                  .map(e -> toEntry(e, null))
                  .toList();

          final List<DictionaryEntryModel> notificationType =
              notificationTypeRepository.findAllValidAndActive().stream()
                  .map(e -> toEntry(e, null))
                  .toList();

          final List<DictionaryEntryModel> notificationSeverity =
              notificationSeverityRepository.findAllValidAndActive().stream()
                  .map(e -> toEntry(e, null))
                  .toList();

          final List<DictionaryEntryModel> tagSeverity =
              tagSeverityRepository.findAllValidAndActive().stream()
                  .map(e -> toEntry(e, null))
                  .toList();

          return new DictionaryCatalogModel(
              taskStatus,
              taskPriority,
              taskDirection,
              notificationType,
              notificationSeverity,
              tagSeverity);
        });
  }

  // ---- Hilfsmethoden ----------------------------------------------------------------

  private <T extends io.keel.framework.api.model.NovaDictionaryEntry<T>> DictionaryEntryModel toEntry(
      final T entry, final Integer sortOrder) {
    return new DictionaryEntryModel(
        entry.getUuid(),
        getCode(entry),
        entry.getEntryValue(),
        sortOrder);
  }

  /** Extrahiert den CODE per Reflection-freiem Ansatz ueber den Typ-Cast. */
  @SuppressWarnings("unchecked")
  private <T extends io.keel.framework.api.model.NovaDictionaryEntry<T>> String getCode(
      final T entry) {
    if (entry instanceof TaskStatus s) {
      return s.getCode();
    } else if (entry instanceof TaskDirection d) {
      return d.getCode();
    } else if (entry instanceof NotificationType nt) {
      return nt.getCode();
    } else if (entry instanceof NotificationSeverity ns) {
      return ns.getCode();
    } else if (entry instanceof TagSeverity ts) {
      return ts.getCode();
    }
    throw new IllegalArgumentException("Unbekannter Dictionary-Typ: " + entry.getClass());
  }
}
