package de.brasi.nova.taskmanagement.participant.model;

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
    List<ParticipantSummary> items,
    String nextCursor,
    boolean hasMore) {}
