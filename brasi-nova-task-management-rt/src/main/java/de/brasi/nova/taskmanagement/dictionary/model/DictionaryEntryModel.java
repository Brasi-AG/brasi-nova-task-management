package de.brasi.nova.taskmanagement.dictionary.model;

import java.util.UUID;

/**
 * Lese-Modell fuer einen Dictionary-Eintrag.
 *
 * @param id        externe UUID des Eintrags (EXTERNALID)
 * @param code      technischer CODE-Schluessel (unveraenderlich)
 * @param label     Anzeigebezeichnung (BEZEICHNUNG via getEntryValue())
 * @param sortOrder Anzeigereihenfolge (nur bei DIC_TASK_PRIORITY gesetzt, sonst null)
 */
public record DictionaryEntryModel(UUID id, String code, String label, Integer sortOrder) {}
