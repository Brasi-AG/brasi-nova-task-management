package de.brasi.nova.taskmanagement.repository;

import java.util.UUID;

/**
 * Projektionszeile der status-agnostischen Tag-Anreicherung {@code findActiveTagRefsByTaskId}
 * (Intake #138, Slice 138-1): je aktiver Tag-Zuordnung einer Aufgabe genau EIN {@code (uuid, name,
 * color)}-Tripel des Katalog-Tags.
 *
 * <p>Kein {@code @NovaTable}-Entity und kein Skalar -&gt; KSF mappt ueber den {@code
 * NovaDtoRowMapper} die SQL-Aliase {@code uuid}/{@code name}/{@code color} per Konstruktor
 * (Record-Komponentennamen, {@code -parameters} im Build gesetzt). Alle drei Werte stammen aus
 * DERSELBEN Ergebniszeile (JOIN TASK_TAG_ASSIGNMENT -&gt; TASK_TAG), die Paarung ist damit
 * gesichert und nie ueber ungesicherte Listenreihenfolge.
 *
 * @param uuid externe UUID des zugeordneten Tags ({@code TASK_TAG.EXTERNALID})
 * @param name Tag-Name ({@code TASK_TAG.BEZEICHNUNG}) - auch fuer katalog-inaktive Tags
 * @param color UI-Farbe ({@code TASK_TAG.COLOR}) oder {@code null}
 */
public record TaskTagRefRow(UUID uuid, String name, String color) {}
