package de.brasi.nova.taskmanagement.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Stored source identity with optional server-built gateway-relative navigation. */
public record TaskSourceModel(
    String module,
    String entityType,
    UUID entityRef,
    @Schema(description = "Gateway-relativer Link, nur fuer registrierte sichere Quelltypen.")
    String href) {}
