package de.brasi.nova.taskmanagement.repository;

import java.util.UUID;

/**
 * Zeile der Vorschlags-API fuer Abteilungen ({@code TaskDivisionQueryRepository#findCandidates} und
 * {@code #findCandidatesByRefs}, Intake #190). KSF mappt die SQL-Aliase {@code divisionRef}/{@code
 * name}/{@code memberCount} per Konstruktor (wie {@link DivisionResyncRow}).
 *
 * @param divisionRef UUID der Abteilung in application ({@code ABTEILUNG_REF})
 * @param name Anzeigename, {@code null}, solange application keinen liefert
 * @param memberCount Mitglieder der Abteilung mit aktivem Benutzer im selben Mandanten
 */
public record DivisionCandidateRow(UUID divisionRef, String name, int memberCount) {}
