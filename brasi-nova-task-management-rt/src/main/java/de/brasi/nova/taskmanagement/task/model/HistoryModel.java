package de.brasi.nova.taskmanagement.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Antwort-DTO fuer einen Historieneintrag einer Aufgabe.
 *
 * @param id           externe UUID des Eintrags
 * @param actorRef     UUID des Akteurs (PROD_BENUTZER.EXTERNALID)
 * @param text         Kurzbeschreibung der Aenderung
 * @param changeDetail optionales Detail der Aenderung
 * @param createdAt    Zeitpunkt der Erstellung (UTC)
 */
public record HistoryModel(
    @Schema(description = "Externe UUID des Historieneintrags.", example = "bb0e8400-e29b-41d4-a716-446655440005")
    UUID id,
    @Schema(description = "UUID des Akteurs, der die Aenderung vorgenommen hat.", example = "770e8400-e29b-41d4-a716-446655440002")
    UUID actorRef,
    @Schema(description = "Kurzbeschreibung der Aenderung.", example = "Titel geaendert")
    String text,
    @Schema(description = "Optionales Detail zur Aenderung (z.B. Altwert -> Neuwert).", example = "Altertitel -> Neuer Titel")
    String changeDetail,
    @Schema(description = "Zeitpunkt der Erstellung (UTC).", example = "2026-10-18T08:00:00Z")
    Instant createdAt) {}
