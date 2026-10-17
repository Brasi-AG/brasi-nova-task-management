package de.brasi.nova.taskmanagement.tag.model;

/**
 * Eingabe-DTO zum Anlegen eines Tags.
 *
 * @param name         Tag-Name (nicht leer)
 * @param severityCode CODE des Tag-Schweregrads (z.B. "PRIMARY", "DANGER")
 */
public record TagCreateInput(String name, String severityCode) {}
