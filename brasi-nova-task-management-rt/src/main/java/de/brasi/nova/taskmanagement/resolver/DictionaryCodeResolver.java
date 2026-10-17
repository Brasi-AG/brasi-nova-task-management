package de.brasi.nova.taskmanagement.resolver;

import de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;
import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.NotificationTypeCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskDirectionCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskPriorityCodes;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationTypeRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TagSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskDirectionRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskPriorityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository;
import io.keel.framework.api.model.NovaDictionaryEntry;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Gecachter Resolver: loest Dictionary-CODE-Werte auf interne Long-IDs auf.
 *
 * <p>Alle Reads laufen im globalen Mandanten-Scope ({@code TenantContextHolder.runWithTenant(
 * TenantInfo.global(), ...)}), da alle System-Dictionaries TENANT_ID=0 haben.
 *
 * <p>Cache: {@code ConcurrentHashMap<CacheKey, Long>} (Dictionary-Klasse + CODE -> ID).
 * Cache-Invalidierung ist in Phase 1a bewusst ignoriert: alle 6 System-Dictionaries sind
 * seed-only/read-only (keine Write-Controller) und aendern sich im Betrieb nicht.
 *
 * <p><b>Fail-loud-Semantik:</b> Liefert die Suche 0 oder mehr als 1 Treffer, wird sofort eine
 * {@link DictionaryResolverException} geworfen. NIEMALS wird {@code findByEntryValue} verwendet.
 */
@Component
public class DictionaryCodeResolver {

  /** Cache-Schluessel: Dictionary-Klasse + CODE-String. */
  record CacheKey(Class<?> dictionaryClass, String code) {}

  private final ConcurrentHashMap<CacheKey, Long> cache = new ConcurrentHashMap<>();

  private final TaskStatusRepository taskStatusRepository;
  private final TaskPriorityRepository taskPriorityRepository;
  private final TaskDirectionRepository taskDirectionRepository;
  private final NotificationTypeRepository notificationTypeRepository;
  private final NotificationSeverityRepository notificationSeverityRepository;
  private final TagSeverityRepository tagSeverityRepository;

  /** Konstruktor-Injektion aller Dictionary-Repositories. */
  public DictionaryCodeResolver(
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

  // ---- Komfort-Methoden fuer bekannte Codes -----------------------------------------------

  /** Loest {@link TaskStatusCodes#OPEN} auf die interne ID auf. */
  public Long resolveTaskStatusOpen() {
    return resolve(TaskStatus.class, taskStatusRepository::findByCode, TaskStatusCodes.OPEN);
  }

  /** Loest {@link TaskStatusCodes#DONE} auf die interne ID auf. */
  public Long resolveTaskStatusDone() {
    return resolve(TaskStatus.class, taskStatusRepository::findByCode, TaskStatusCodes.DONE);
  }

  /** Loest {@link TaskStatusCodes#CANCELLED} auf die interne ID auf. */
  public Long resolveTaskStatusCancelled() {
    return resolve(
        TaskStatus.class, taskStatusRepository::findByCode, TaskStatusCodes.CANCELLED);
  }

  /** Loest einen beliebigen {@link TaskStatus}-CODE auf die interne ID auf. */
  public Long resolveTaskStatus(final String code) {
    return resolve(TaskStatus.class, taskStatusRepository::findByCode, code);
  }

  /** Loest einen beliebigen {@link TaskPriority}-CODE auf die interne ID auf. */
  public Long resolveTaskPriority(final String code) {
    return resolve(TaskPriority.class, taskPriorityRepository::findByCode, code);
  }

  /** Loest einen beliebigen {@link TaskDirection}-CODE auf die interne ID auf. */
  public Long resolveTaskDirection(final String code) {
    return resolve(TaskDirection.class, taskDirectionRepository::findByCode, code);
  }

  /** Loest einen beliebigen {@link NotificationType}-CODE auf die interne ID auf. */
  public Long resolveNotificationType(final String code) {
    return resolve(NotificationType.class, notificationTypeRepository::findByCode, code);
  }

  /** Loest einen beliebigen {@link NotificationSeverity}-CODE auf die interne ID auf. */
  public Long resolveNotificationSeverity(final String code) {
    return resolve(NotificationSeverity.class, notificationSeverityRepository::findByCode, code);
  }

  /** Loest einen beliebigen {@link TagSeverity}-CODE auf die interne ID auf. */
  public Long resolveTagSeverity(final String code) {
    return resolve(TagSeverity.class, tagSeverityRepository::findByCode, code);
  }

  // ---- Generischer Resolver (intern) --------------------------------------------------------

  /**
   * Loest einen CODE fuer ein Dictionary auf die interne Long-ID auf.
   * Liest im globalen Scope ({@code TenantInfo.global()}, TENANT_ID=0).
   * Ergebnis wird gecacht; bei 0 oder mehr als 1 Treffern: fail-loud.
   *
   * @param <T>             Dictionary-Entity-Typ
   * @param dictionaryClass Dictionary-Klasse (Cache-Key)
   * @param finder          Lambda/Methodenreferenz auf {@code findByCode(String)}
   * @param code            technischer CODE-Wert
   * @return interne Long-ID (nie null)
   * @throws DictionaryResolverException bei 0 oder >1 Treffern
   */
  public <T extends NovaDictionaryEntry<T>> Long resolve(
      final Class<T> dictionaryClass,
      final CodeFinder<T> finder,
      final String code) {
    Objects.requireNonNull(dictionaryClass, "dictionaryClass darf nicht null sein");
    Objects.requireNonNull(finder, "finder darf nicht null sein");
    Objects.requireNonNull(code, "code darf nicht null sein");

    final CacheKey key = new CacheKey(dictionaryClass, code);
    final Long cached = cache.get(key);
    if (cached != null) {
      return cached;
    }

    // Global-Scope: TENANT_ID=0 im TenantContextHolder setzen
    final List<T> results =
        TenantContextHolder.runWithTenant(TenantInfo.global(), () -> finder.findByCode(code));

    if (results == null || results.isEmpty()) {
      throw new DictionaryResolverException(
          String.format(
              "DictionaryCodeResolver: CODE '%s' nicht gefunden in Dictionary '%s'"
                  + " (0 Treffer). Seed-Daten pruefen.",
              code,
              dictionaryClass.getSimpleName()));
    }
    if (results.size() > 1) {
      throw new DictionaryResolverException(
          String.format(
              "DictionaryCodeResolver: CODE '%s' ist mehrdeutig in Dictionary '%s'"
                  + " (%d Treffer). UNIQUE(TENANT_ID, CODE)-Constraint pruefen.",
              code,
              dictionaryClass.getSimpleName(),
              results.size()));
    }

    final Long id = results.get(0).getId();
    cache.put(key, id);
    return id;
  }

  /**
   * Funktionales Interface: ein Aufruf, der {@code findByCode(String)} ausfuehrt.
   *
   * @param <T> Dictionary-Entity-Typ
   */
  @FunctionalInterface
  public interface CodeFinder<T> {

    /**
     * Sucht Dictionary-Eintraege anhand ihres CODE-Werts.
     *
     * @param code technischer CODE-Wert
     * @return Liste der Treffer
     */
    List<T> findByCode(String code);
  }
}
