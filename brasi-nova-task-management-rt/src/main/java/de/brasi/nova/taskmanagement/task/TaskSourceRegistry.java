package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.task.model.TaskSourceInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/** Registered, explicitly reviewed source adapters. Empty until a source read gate is proven. */
@Component
public class TaskSourceRegistry {
  private final Map<String, TaskSourceAdapter> adapters;

  public TaskSourceRegistry(final List<TaskSourceAdapter> adapters) {
    final Map<String, TaskSourceAdapter> registered = new HashMap<>();
    for (final TaskSourceAdapter adapter : adapters) {
      final String key = key(adapter.module(), adapter.entityType());
      if (registered.putIfAbsent(key, adapter) != null) {
        throw new IllegalStateException("Doppelter Task-Quelltyp: " + key);
      }
    }
    this.adapters = Map.copyOf(registered);
  }

  public void requireReadable(final TaskSourceInput source, final CurrentActor actor) {
    final TaskSourceAdapter adapter = adapters.get(key(source.module(), source.entityType()));
    if (adapter == null) {
      throw new EntityValidationException("Unregistrierter Quelltyp.");
    }
    try {
      adapter.requireReadable(actor, source.entityRef());
    } catch (final EntityNotFoundException | AccessDeniedException denied) {
      throw new EntityNotFoundException("Quelle nicht gefunden.");
    } catch (final HttpClientErrorException denied) {
      if (denied.getStatusCode().value() == 403 || denied.getStatusCode().value() == 404) {
        throw new EntityNotFoundException("Quelle nicht gefunden.");
      }
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Quellpruefung nicht verfuegbar.");
    } catch (final ResponseStatusException denied) {
      if (denied.getStatusCode().value() == 403 || denied.getStatusCode().value() == 404) {
        throw new EntityNotFoundException("Quelle nicht gefunden.");
      }
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Quellpruefung nicht verfuegbar.");
    } catch (final RuntimeException unavailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Quellpruefung nicht verfuegbar.");
    }
  }

  public String href(final TaskSourceInput source, final UUID tenantUuid) {
    return href(source.module(), source.entityType(), source.entityRef(), tenantUuid);
  }

  /** Read-side lookup accepts stored legacy tuples without applying create-input validation. */
  public String href(final String module, final String entityType, final UUID entityRef,
      final UUID tenantUuid) {
    if (module == null || entityType == null || entityRef == null || tenantUuid == null) {
      return null;
    }
    final TaskSourceAdapter adapter = adapters.get(key(module, entityType));
    if (adapter == null) {
      return null;
    }
    final String href;
    try {
      href = adapter.href(tenantUuid, entityRef);
    } catch (final RuntimeException unavailable) {
      return null;
    }
    if (href == null || !href.matches("/[A-Za-z0-9_./-]+") || href.startsWith("//")
        || href.contains("/../") || href.contains("/./") || href.endsWith("/..")
        || href.endsWith("/.")) {
      return null;
    }
    return href;
  }

  private static String key(final String module, final String entityType) {
    return module + ":" + entityType;
  }
}
