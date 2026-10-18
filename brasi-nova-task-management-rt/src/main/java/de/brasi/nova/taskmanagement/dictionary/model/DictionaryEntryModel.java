package de.brasi.nova.taskmanagement.dictionary.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Lese-Modell fuer einen Dictionary-Eintrag.
 *
 * @param id        externe UUID des Eintrags (EXTERNALID)
 * @param code      technischer CODE-Schluessel (unveraenderlich)
 * @param label     Anzeigebezeichnung (BEZEICHNUNG via getEntryValue())
 * @param sortOrder Anzeigereihenfolge (nur bei DIC_TASK_PRIORITY gesetzt, sonst null)
 */
public record DictionaryEntryModel(
    @Schema(description = "Externe UUID des Dictionary-Eintrags.", example = "cc0e8400-e29b-41d4-a716-446655440006")
    UUID id,
    @Schema(description = "Technischer CODE-Schluessel (stabil, unveraenderlich).", example = "OPEN")
    String code,
    @Schema(description = "Anzeigebezeichnung.", example = "Offen")
    String label,
    @Schema(description = "Anzeigereihenfolge (nur bei taskPriority gesetzt, sonst null).", example = "2")
    Integer sortOrder) {}
