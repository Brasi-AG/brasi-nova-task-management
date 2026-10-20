package de.brasi.nova.taskmanagement.repository;

import java.util.UUID;

/**
 * Projektionszeile der Batch-Abfrage {@code findActiveTagUuidsByTaskIds}: je aktiver Tag-Zuordnung
 * genau EIN {@code (taskId, tagUuid)}-Paar.
 *
 * <p>Kein {@code @NovaTable}-Entity und kein Skalar -> KSF mappt ueber den
 * {@code NovaDtoRowMapper} die SQL-Aliase {@code taskId}/{@code tagUuid} per Konstruktor
 * (Record-Komponentennamen, {@code -parameters} im Build gesetzt). Die Paarung ist je Ergebniszeile
 * gesichert (beide Werte stammen aus DERSELBEN Zeile), nie ueber ungesicherte Listenreihenfolge
 * (BE-1-PLAN R2/R4).
 *
 * @param taskId  interne Aufgaben-ID der Zuordnung ({@code TASK_TAG_ASSIGNMENT.TASK_ID})
 * @param tagUuid externe UUID des zugeordneten Tags ({@code TASK_TAG.EXTERNALID})
 */
public record TaskTagUuidRow(Long taskId, UUID tagUuid) {}
