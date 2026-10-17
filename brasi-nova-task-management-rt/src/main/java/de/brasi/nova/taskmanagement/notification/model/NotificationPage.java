package de.brasi.nova.taskmanagement.notification.model;

import java.util.List;

/**
 * Seite von {@link NotificationModel}-Eintraegen mit Cursor-basierter Paginierung.
 *
 * @param items      Liste der Benachrichtigungen dieser Seite
 * @param nextCursor opaker Cursor fuer die naechste Seite (null wenn {@code hasMore=false})
 * @param hasMore    ob weitere Seiten vorhanden sind
 */
public record NotificationPage(
    List<NotificationModel> items,
    String nextCursor,
    boolean hasMore) {}
