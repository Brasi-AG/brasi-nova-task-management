package de.brasi.nova.taskmanagement.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.dictionary.NotificationSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.NotificationType;
import de.brasi.nova.taskmanagement.entity.dictionary.TagSeverity;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskDirection;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskPriority;
import de.brasi.nova.taskmanagement.entity.dictionary.TaskStatus;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TaskStatusCodes;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.NotificationTypeRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TagSeverityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskDirectionRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskPriorityRepository;
import de.brasi.nova.taskmanagement.repository.dictionary.TaskStatusRepository;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer {@link DictionaryCodeResolver}:
 * CODE->Id-Aufloesung, fail-loud bei 0/mehr als 1 Treffer, Cache-Hit.
 * Ohne DB — Repository gemockt.
 */
class DictionaryCodeResolverTest {

  private TaskStatusRepository statusRepo;
  private TaskPriorityRepository priorityRepo;
  private TaskDirectionRepository directionRepo;
  private NotificationTypeRepository notifTypeRepo;
  private NotificationSeverityRepository notifSeverityRepo;
  private TagSeverityRepository tagSeverityRepo;
  private DictionaryCodeResolver resolver;

  @BeforeEach
  void setUp() {
    statusRepo = mock(TaskStatusRepository.class);
    priorityRepo = mock(TaskPriorityRepository.class);
    directionRepo = mock(TaskDirectionRepository.class);
    notifTypeRepo = mock(NotificationTypeRepository.class);
    notifSeverityRepo = mock(NotificationSeverityRepository.class);
    tagSeverityRepo = mock(TagSeverityRepository.class);

    resolver = new DictionaryCodeResolver(
        statusRepo, priorityRepo, directionRepo,
        notifTypeRepo, notifSeverityRepo, tagSeverityRepo);

    // Einen normalen Mandantenkontext setzen; der Resolver nutzt intern runWithTenant(global())
    // welches den Kontext temporaer ueberschreibt.
    TenantContextHolder.setTenant(new TenantInfo(UUID.randomUUID(), 500L));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  // ---- 1. CODE->Id bei genau 1 Treffer --------------------------------------------------------

  @Test
  @DisplayName("resolve_task_status_open_liefert_korrekte_id_bei_einem_treffer")
  void resolve_task_status_open_liefert_korrekte_id_bei_einem_treffer() {
    final TaskStatus status = statusWithId(101L);
    when(statusRepo.findByCode(TaskStatusCodes.OPEN)).thenReturn(List.of(status));

    final Long id = resolver.resolveTaskStatusOpen();

    assertThat(id).as("Erwartet: ID=101 fuer OPEN").isEqualTo(101L);
  }

  // ---- 2. fail-loud bei 0 Treffern -----------------------------------------------------------

  @Test
  @DisplayName("resolve_fail_loud_bei_0_treffern_wirft_exception")
  void resolve_fail_loud_bei_0_treffern_wirft_exception() {
    when(statusRepo.findByCode(anyString())).thenReturn(List.of());

    assertThatThrownBy(() -> resolver.resolveTaskStatusOpen())
        .isInstanceOf(DictionaryResolverException.class)
        .hasMessageContaining("OPEN")
        .hasMessageContaining("0 Treffer");
  }

  // ---- 3. fail-loud bei mehr als 1 Treffer ---------------------------------------------------

  @Test
  @DisplayName("resolve_fail_loud_bei_mehr_als_1_treffer_wirft_exception")
  void resolve_fail_loud_bei_mehr_als_1_treffer_wirft_exception() {
    final TaskStatus s1 = statusWithId(101L);
    final TaskStatus s2 = statusWithId(999L);
    when(statusRepo.findByCode(TaskStatusCodes.OPEN)).thenReturn(List.of(s1, s2));

    assertThatThrownBy(() -> resolver.resolveTaskStatusOpen())
        .isInstanceOf(DictionaryResolverException.class)
        .hasMessageContaining("OPEN")
        .hasMessageContaining("2 Treffer");
  }

  // ---- 4. Cache-Hit: zweiter Aufruf nutzt Cache, Repo wird nur einmal aufgerufen -------------

  @Test
  @DisplayName("resolve_cache_hit_repo_nur_einmal_aufgerufen")
  void resolve_cache_hit_repo_nur_einmal_aufgerufen() {
    final TaskStatus status = statusWithId(101L);
    when(statusRepo.findByCode(TaskStatusCodes.OPEN)).thenReturn(List.of(status));

    final Long id1 = resolver.resolveTaskStatusOpen();
    final Long id2 = resolver.resolveTaskStatusOpen();

    assertThat(id1).isEqualTo(101L);
    assertThat(id2).isEqualTo(101L);

    // Repository darf nur EINMAL aufgerufen worden sein (Cache-Hit beim zweiten Aufruf)
    verify(statusRepo, times(1)).findByCode(TaskStatusCodes.OPEN);
  }

  // ---- 5. Cache-Hit mit direkter resolve-Methode -----------------------------------------------

  @Test
  @DisplayName("resolve_generisch_code_done_liefert_id_und_cachet")
  void resolve_generisch_code_done_liefert_id_und_cachet() {
    final TaskStatus done = statusWithId(102L);
    when(statusRepo.findByCode(TaskStatusCodes.DONE)).thenReturn(List.of(done));

    final Long id = resolver.resolveTaskStatus(TaskStatusCodes.DONE);
    assertThat(id).isEqualTo(102L);

    // Cache-Eintrag fuer DONE darf OPEN nicht verdraengen
    verify(statusRepo, times(0)).findByCode(TaskStatusCodes.OPEN);
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private TaskStatus statusWithId(final long id) {
    final TaskStatus s = new TaskStatus();
    s.setId(id);
    s.setCode("CODE_" + id);
    return s;
  }
}
