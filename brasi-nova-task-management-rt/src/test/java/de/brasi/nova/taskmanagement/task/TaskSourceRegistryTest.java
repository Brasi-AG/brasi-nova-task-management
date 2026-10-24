package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.model.TaskSourceInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class TaskSourceRegistryTest {
  private static final UUID TENANT_UUID = UUID.randomUUID();
  private static final UUID ACTOR_UUID = UUID.randomUUID();
  private static final UUID SOURCE_UUID = UUID.randomUUID();
  private final CurrentActor actor =
      new CurrentActor(new TenantInfo(TENANT_UUID, 500L), new AuthUser(ACTOR_UUID, "actor@test.invalid"));

  @Test
  void unregisteredSourceCannotCreateAndHasNoLink() {
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of());
    final TaskSourceInput source = new TaskSourceInput("application", "customer", SOURCE_UUID);

    assertThatThrownBy(() -> registry.requireReadable(source, actor))
        .isInstanceOf(EntityValidationException.class);
    assertThat(registry.href(source, TENANT_UUID)).isNull();
  }

  @Test
  void registeredAdapterReceivesActorAndSourceRef() {
    final TaskSourceAdapter adapter = mock(TaskSourceAdapter.class);
    org.mockito.Mockito.when(adapter.module()).thenReturn("fixture");
    org.mockito.Mockito.when(adapter.entityType()).thenReturn("item");
    org.mockito.Mockito.when(adapter.href(TENANT_UUID, SOURCE_UUID))
        .thenReturn("/fixture/guarded-items/" + TENANT_UUID + "/" + SOURCE_UUID);
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of(adapter));
    final TaskSourceInput source = new TaskSourceInput("fixture", "item", SOURCE_UUID);

    registry.requireReadable(source, actor);

    verify(adapter).requireReadable(actor, SOURCE_UUID);
    assertThat(registry.href(source, TENANT_UUID))
        .isEqualTo("/fixture/guarded-items/" + TENANT_UUID + "/" + SOURCE_UUID);
  }

  @Test
  void adapterDeniedSourceIsNeutralNotFound() {
    final TaskSourceAdapter adapter = mock(TaskSourceAdapter.class);
    org.mockito.Mockito.when(adapter.module()).thenReturn("application");
    org.mockito.Mockito.when(adapter.entityType()).thenReturn("customer");
    org.mockito.Mockito.doThrow(new EntityNotFoundException("sensitive customer identity"))
        .when(adapter).requireReadable(actor, SOURCE_UUID);
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of(adapter));

    assertThatThrownBy(() -> registry.requireReadable(
            new TaskSourceInput("application", "customer", SOURCE_UUID), actor))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Quelle nicht gefunden.");
  }

  @Test
  void adapterForbiddenSourceIsNeutralNotFound() {
    final TaskSourceAdapter adapter = mock(TaskSourceAdapter.class);
    org.mockito.Mockito.when(adapter.module()).thenReturn("application");
    org.mockito.Mockito.when(adapter.entityType()).thenReturn("customer");
    org.mockito.Mockito.doThrow(new AccessDeniedException("sensitive policy"))
        .when(adapter).requireReadable(actor, SOURCE_UUID);
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of(adapter));

    assertThatThrownBy(() -> registry.requireReadable(
            new TaskSourceInput("application", "customer", SOURCE_UUID), actor))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Quelle nicht gefunden.");
  }

  @Test
  void adapterFailureIsSafeServiceUnavailable() {
    final TaskSourceAdapter adapter = mock(TaskSourceAdapter.class);
    org.mockito.Mockito.when(adapter.module()).thenReturn("application");
    org.mockito.Mockito.when(adapter.entityType()).thenReturn("customer");
    org.mockito.Mockito.doThrow(new IllegalStateException("secret internal host"))
        .when(adapter).requireReadable(actor, SOURCE_UUID);
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of(adapter));

    assertThatThrownBy(() -> registry.requireReadable(
            new TaskSourceInput("application", "customer", SOURCE_UUID), actor))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageNotContaining("secret internal host");
  }

  @Test
  void adapterCannotSupplyAbsoluteOrProtocolRelativeLink() {
    final TaskSourceAdapter adapter = mock(TaskSourceAdapter.class);
    org.mockito.Mockito.when(adapter.module()).thenReturn("application");
    org.mockito.Mockito.when(adapter.entityType()).thenReturn("customer");
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of(adapter));
    final TaskSourceInput source = new TaskSourceInput("application", "customer", SOURCE_UUID);

    for (final String unsafe : List.of("https://evil.invalid/customer", "//evil.invalid/customer", "/\\evil.invalid")) {
      org.mockito.Mockito.when(adapter.href(TENANT_UUID, SOURCE_UUID)).thenReturn(unsafe);
      assertThat(registry.href(source, TENANT_UUID)).isNull();
    }
  }

  @Test
  void adapterHrefFailureOmitsOptionalLink() {
    final TaskSourceAdapter adapter = mock(TaskSourceAdapter.class);
    org.mockito.Mockito.when(adapter.module()).thenReturn("fixture");
    org.mockito.Mockito.when(adapter.entityType()).thenReturn("item");
    org.mockito.Mockito.when(adapter.href(TENANT_UUID, SOURCE_UUID))
        .thenThrow(new IllegalStateException("internal route error"));
    final TaskSourceRegistry registry = new TaskSourceRegistry(List.of(adapter));

    assertThat(registry.href("fixture", "item", SOURCE_UUID, TENANT_UUID)).isNull();
  }
}
