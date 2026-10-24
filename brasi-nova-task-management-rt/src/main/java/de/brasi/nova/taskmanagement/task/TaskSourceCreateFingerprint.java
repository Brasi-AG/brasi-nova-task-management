package de.brasi.nova.taskmanagement.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brasi.nova.taskmanagement.task.model.TaskCreateInput;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stable hash of the effective context-create input; the format is versioned for future changes.
 */
final class TaskSourceCreateFingerprint {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TaskSourceCreateFingerprint() {}

  static String sha256(
      final TaskCreateInput input, final UUID actorRef, final String canonicalPriorityCode) {
    final Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("version", 1);
    fields.put("title", input.title() == null ? null : input.title().strip());
    fields.put("description", input.description());
    fields.put("priorityCode", canonicalPriorityCode);
    fields.put("startDate", input.startDate() == null ? null : input.startDate().toString());
    fields.put("dueDate", input.dueDate() == null ? null : input.dueDate().toString());
    fields.put(
        "assignees",
        sortedDistinct(input.assignees().stream().filter(ref -> !ref.equals(actorRef)).toList()));
    fields.put("tagIds", sortedDistinct(input.tagIds()));
    fields.put("isPinned", Boolean.TRUE.equals(input.isPinned()));
    fields.put("isFlagged", Boolean.TRUE.equals(input.isFlagged()));
    fields.put("sourceModule", input.source().module());
    fields.put("sourceEntityType", input.source().entityType());
    fields.put("sourceEntityRef", input.source().entityRef().toString());
    try {
      final byte[] canonical = MAPPER.writeValueAsBytes(fields);
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (final JsonProcessingException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "Create-Fingerprint konnte nicht berechnet werden.", impossible);
    }
  }

  private static List<String> sortedDistinct(final List<UUID> ids) {
    return ids.stream().map(UUID::toString).distinct().sorted().toList();
  }
}
