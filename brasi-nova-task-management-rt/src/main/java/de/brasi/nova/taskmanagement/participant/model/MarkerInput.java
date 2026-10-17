package de.brasi.nova.taskmanagement.participant.model;

/**
 * Eingabe-DTO zum expliziten Setzen eines persoenlichen Markers (pin/flag/mute).
 *
 * <p>Der Wert {@code true} aktiviert den Marker, {@code false} deaktiviert ihn.
 * Kein Toggle — der Client gibt den gewuenschten Zielzustand vor.
 *
 * @param value gewuenschter Zielwert des Markers
 */
public record MarkerInput(boolean value) {}
