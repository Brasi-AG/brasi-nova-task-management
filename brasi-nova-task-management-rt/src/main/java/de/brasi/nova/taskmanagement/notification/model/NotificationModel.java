package de.brasi.nova.taskmanagement.notification.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-Modell fuer eine Benachrichtigung (plan-v4 DTO-Register).
 *
 * @param id           externe UUID der Benachrichtigung (EXTERNALID)
 * @param typeCode     technischer CODE des Benachrichtigungs-Typs
 * @param taskId       externe UUID der Aufgabe (null bei System-Benachrichtigungen)
 * @param actorRef     UUID des Akteurs, der die Benachrichtigung ausgeloest hat (optional)
 * @param text         Anzeigetext
 * @param preview      optionale Vorschau (z.B. Aufgabentitel)
 * @param severityCode technischer CODE des Schweregrads (null wenn nicht gesetzt)
 * @param read         ob die Benachrichtigung gelesen wurde
 * @param createdAt    Erstellungszeitpunkt (AuditTimeConverter-Instant, R7)
 */
public record NotificationModel(
    @Schema(description = "Externe UUID der Benachrichtigung.", example = "880e8400-e29b-41d4-a716-446655440003")
    UUID id,
    @Schema(description = "Technischer CODE des Benachrichtigungs-Typs (z.B. ASSIGN, REPLY, MENTION, HANDOVER).",
        example = "ASSIGN")
    String typeCode,
    @Schema(description = "Externe UUID der betroffenen Aufgabe (null bei System-Benachrichtigungen).",
        example = "550e8400-e29b-41d4-a716-446655440000")
    UUID taskId,
    @Schema(description = "UUID des Akteurs, der die Benachrichtigung ausgeloest hat.",
        example = "770e8400-e29b-41d4-a716-446655440002")
    UUID actorRef,
    @Schema(description = "Anzeigetext der Benachrichtigung.", example = "Du wurdest der Aufgabe zugewiesen.")
    String text,
    @Schema(description = "Optionale Vorschau (z.B. Aufgabentitel).", example = "Jahresabschluss vorbereiten")
    String preview,
    @Schema(description = "Technischer CODE des Schweregrads (z.B. INFO, WARNING; null wenn nicht gesetzt).",
        example = "INFO")
    String severityCode,
    @Schema(description = "true wenn die Benachrichtigung gelesen wurde.", example = "false")
    boolean read,
    @Schema(description = "Erstellungszeitpunkt (UTC).", example = "2026-10-18T08:00:00Z")
    Instant createdAt) {}
