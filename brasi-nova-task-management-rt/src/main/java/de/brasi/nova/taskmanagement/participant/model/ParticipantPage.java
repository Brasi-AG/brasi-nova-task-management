package de.brasi.nova.taskmanagement.participant.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Seite von {@link ParticipantSummary}-Eintraegen mit Cursor-basierter Paginierung.
 *
 * <p>Cursor basiert auf {@code EXTERNALID} (ASC, stabil, non-null, unique; R-PART-KEYSET).
 *
 * @param items Liste der Teilnehmer-Zusammenfassungen dieser Seite
 * @param nextCursor opaker Cursor fuer die naechste Seite (null wenn {@code hasMore=false})
 * @param hasMore ob weitere Seiten vorhanden sind
 */
public record ParticipantPage(
    @Schema(
            description =
                "Aktive Benutzer des eigenen Mandanten als Kandidaten dieser Seite."
                    + " Reihenfolge stabil (technisch nach userRef), nicht alphabetisch.")
        List<ParticipantSummary> items,
    @Schema(
            description =
                "Opaker Cursor fuer die naechste Seite. Nur gesetzt, wenn hasMore=true;"
                    + " unveraendert und mit gleichem q als cursor-Parameter weitergeben.",
            example = "NjYwZTg0MDAtZTI5Yi00MWQ0LWE3MTYtNDQ2NjU1NDQwMDAx")
        String nextCursor,
    @Schema(
            description = "true, wenn weitere Seiten vorhanden sind (dann ist nextCursor gesetzt).",
            example = "true")
        boolean hasMore) {}
