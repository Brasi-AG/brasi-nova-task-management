package de.brasi.nova.taskmanagement.tag.model;

import java.util.UUID;

/**
 * Modell-DTO fuer einen Tag im Katalog.
 *
 * @param id           externe UUID des Tags (EXTERNALID)
 * @param name         Tag-Name (BEZEICHNUNG / entryValue)
 * @param severityCode Anzeigeschwere (CODE aus DIC_TAG_SEVERITY)
 */
public record TagModel(UUID id, String name, String severityCode) {}
