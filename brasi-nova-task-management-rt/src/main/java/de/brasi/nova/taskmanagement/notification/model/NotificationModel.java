package de.brasi.nova.taskmanagement.notification.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-Modell fuer eine Benachrichtigung (plan-v4 DTO-Register).
 *
 * @param id           externe UUID der Benachrichtigung (EXTERNALID)
 * @param typeCode     technischer CODE des Benachrichtigungs-Typs
 * @param taskId       externe UUID der Aufgabe (null bei System-Benachrichtigungen)
 * @param actorRef     UUID des Akteurs, der die Benachrichtigung ausgeloest hat (optional)
 * @param text         Anzeigetext
 * @param preview      optionale Vorschau (z.B. Aufgabentitel)
 * @param severityCode technischer CODE des Schweregrads (null wenn nicht gesetzt)
 * @param read         ob die Benachrichtigung gelesen wurde
 * @param createdAt    Erstellungszeitpunkt (AuditTimeConverter-Instant, R7)
 */
public record NotificationModel(
    UUID id,
    String typeCode,
    UUID taskId,
    UUID actorRef,
    String text,
    String preview,
    String severityCode,
    boolean read,
    Instant createdAt) {}
