package de.brasi.nova.taskmanagement.inbox.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kompakte Listenzeilen-Repraesentation einer Aufgabe fuer Snapshot/Inbox/Archiv (arch-v2 §2.1,
 * FE-Proof §B.4), BE-1 additiv angereichert.
 *
 * <p>Status/Prioritaet werden als CODE-Strings (nie interne ID/Enum) nach aussen gegeben. Die
 * BE-1-Anreicherung (Ersteller/Erstellzeit/Beschreibung, Assignees/Teilnehmer/Tags pro Zeile,
 * persoenliche Marker, {@code isDone}, Archiv-{@code exit}) ist RUECKWAERTS-KOMPATIBEL ergaenzt: die
 * bestehenden 7 Felder bleiben unveraendert. PERSON-only fuer die Anzeigelisten (Team-Aufloesung
 * BE-T); {@code exit} nur fuer Archivzeilen gesetzt (sonst {@code null}).
 *
 * @param id             externe UUID der Aufgabe (EXTERNALID)
 * @param title          Titel der Aufgabe
 * @param statusCode     aktueller Status-CODE (z.B. "OPEN")
 * @param priorityCode   Prioritaets-CODE (z.B. "NORMAL")
 * @param dueDate        optionales Faelligkeitsdatum
 * @param lastActivityAt Zeitpunkt der letzten Aktivitaet (AuditTimeConverter-Instant)
 * @param commentCount   Anzahl der Kommentare (denormalisiert)
 * @param createdById    UUID des Erstellers ({@code CREATOR_FLAG}, PERSON) oder {@code null}
 * @param createdAt      Erstellzeitpunkt der Aufgabe (ANGELEGT_AM als Instant)
 * @param description    optionale Beschreibung (fuer clientseitige Suche mitgeliefert)
 * @param assigneeIds    UUIDs der aktiven PERSON-Assignees (ohne Austritt, stabil sortiert)
 * @param participantIds UUIDs der aktiven PERSON-Teilnehmer ohne Assignees/Ersteller (stabil sortiert)
 * @param tagIds         UUIDs der zugeordneten Tags (inkl. katalog-inaktiver, stabil sortiert)
 * @param isPinned       persoenlicher Marker des Anfragers
 * @param isFlagged      persoenlicher Marker des Anfragers
 * @param isMuted        persoenlicher Marker des Anfragers
 * @param isDone         {@code true}, wenn Status DONE ODER CANCELLED (abgeschlossen)
 * @param exit           Austritts-Info des Anfragers (nur Archiv; sonst {@code null})
 */
public record TaskSummaryModel(
    @Schema(description = "Externe UUID der Aufgabe.", example = "550e8400-e29b-41d4-a716-446655440000")
    UUID id,
    @Schema(description = "Titel der Aufgabe.", example = "Jahresabschluss vorbereiten")
    String title,
    @Schema(description = "Aktueller Status-CODE.", example = "OPEN")
    String statusCode,
    @Schema(description = "Prioritaets-CODE.", example = "NORMAL")
    String priorityCode,
    @Schema(description = "Optionales Faelligkeitsdatum.", example = "2026-12-31")
    LocalDate dueDate,
    @Schema(description = "Zeitpunkt der letzten Aktivitaet (UTC).", example = "2026-10-18T08:00:00Z")
    Instant lastActivityAt,
    @Schema(description = "Anzahl der Kommentare (denormalisiert).", example = "0")
    int commentCount,
    @Schema(
            description = "UUID des Erstellers (CREATOR_FLAG, PERSON) oder null.",
            example = "550e8400-e29b-41d4-a716-446655440000")
    UUID createdById,
    @Schema(description = "Erstellzeitpunkt der Aufgabe (UTC).", example = "2026-10-01T06:30:00Z")
    Instant createdAt,
    @Schema(description = "Optionale Beschreibung.", example = "Details zur Aufgabe")
    String description,
    @Schema(description = "UUIDs der aktiven PERSON-Assignees (ohne Austritt).")
    List<UUID> assigneeIds,
    @Schema(description = "UUIDs der aktiven PERSON-Teilnehmer (ohne Assignees/Ersteller).")
    List<UUID> participantIds,
    @Schema(description = "UUIDs der zugeordneten Tags (inkl. katalog-inaktiver).")
    List<UUID> tagIds,
    @Schema(description = "Persoenlicher Marker: angepinnt.", example = "false")
    boolean isPinned,
    @Schema(description = "Persoenlicher Marker: markiert.", example = "false")
    boolean isFlagged,
    @Schema(description = "Persoenlicher Marker: stummgeschaltet.", example = "false")
    boolean isMuted,
    @Schema(description = "Abgeschlossen (Status DONE oder CANCELLED).", example = "false")
    boolean isDone,
    @Schema(description = "Austritts-Info des Anfragers (nur Archiv; sonst null).")
    ExitModel exit) {}
