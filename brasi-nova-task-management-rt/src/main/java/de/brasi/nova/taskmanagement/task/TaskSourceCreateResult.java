package de.brasi.nova.taskmanagement.task;

import java.util.UUID;

/** HTTP-facing result: only the first committed create reports {@code created=true}. */
public record TaskSourceCreateResult(UUID taskId, boolean created) {}
