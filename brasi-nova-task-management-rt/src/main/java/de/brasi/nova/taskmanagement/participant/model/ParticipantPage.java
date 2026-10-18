package de.brasi.nova.taskmanagement.participant.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Seite von {@link ParticipantSummary}-Eintraegen mit Cursor-basierter Paginierung.
 *
 * <p>Cursor basiert auf {@code EXTERNALID} (ASC, stabil, non-null, unique; R-PART-KEYSET).
 *
 * @param items      Liste der Teilnehmer-Zusammenfassungen dieser Seite
 * @param nextCursor opaker Cursor fuer die naechste Seite (null wenn {@code hasMore=false})
 * @param hasMore    ob weitere Seiten vorhanden sind
 */
public record ParticipantPage(
    @Schema(description = "Teilnehmer-Kandidaten dieser Seite.")
    List<ParticipantSummary> items,
    @Schema(description = "Opaker Cursor fuer die naechste Seite (null wenn hasMore=false).",
        example = "eyJ1c2VyUmVmIjoiNTUwZTg0MDAifQ")
    String nextCursor,
    @Schema(description = "Gibt an, ob weitere Seiten vorhanden sind.", example = "false")
    boolean hasMore) {}
