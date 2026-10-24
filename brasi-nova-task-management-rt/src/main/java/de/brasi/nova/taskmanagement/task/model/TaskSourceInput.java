package de.brasi.nova.taskmanagement.task.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.UUID;

/** Source identity supplied by a client; never a navigation URL or access assertion. */
public record TaskSourceInput(String module, String entityType, UUID entityRef) {
  public TaskSourceInput {
    if (!validName(module) || !validName(entityType) || entityRef == null) {
      throw new EntityValidationException("Ungueltiger Quellkontext.");
    }
  }

  // Spring Boot commonly ignores unknown JSON properties. Reject them here so a client cannot
  // smuggle a URL or an alleged access proof into this security-sensitive tuple.
  @JsonAnySetter
  public void rejectUnknown(final String name, final Object value) {
    throw new EntityValidationException("Unbekanntes Quellfeld.");
  }

  private static boolean validName(final String value) {
    return value != null && value.matches("[a-z][a-z0-9-]{0,99}");
  }
}
