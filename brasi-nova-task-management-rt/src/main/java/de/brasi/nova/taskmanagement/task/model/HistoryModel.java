package de.brasi.nova.taskmanagement.task.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Antwort-DTO fuer einen Historieneintrag einer Aufgabe.
 *
 * @param id           externe UUID des Eintrags
 * @param actorRef     UUID des Akteurs (PROD_BENUTZER.EXTERNALID)
 * @param text         Kurzbeschreibung der Aenderung
 * @param changeDetail optionales Detail der Aenderung
 * @param createdAt    Zeitpunkt der Erstellung (UTC)
 */
public record HistoryModel(
    UUID id,
    UUID actorRef,
    String text,
    String changeDetail,
    Instant createdAt) {}
