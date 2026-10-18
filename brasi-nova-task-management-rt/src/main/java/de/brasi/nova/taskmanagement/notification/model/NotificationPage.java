package de.brasi.nova.taskmanagement.notification.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Seite von {@link NotificationModel}-Eintraegen mit Cursor-basierter Paginierung.
 *
 * @param items      Liste der Benachrichtigungen dieser Seite
 * @param nextCursor opaker Cursor fuer die naechste Seite (null wenn {@code hasMore=false})
 * @param hasMore    ob weitere Seiten vorhanden sind
 */
public record NotificationPage(
    @Schema(description = "Benachrichtigungen dieser Seite.")
    List<NotificationModel> items,
    @Schema(description = "Opaker Cursor fuer die naechste Seite (null wenn hasMore=false).",
        example = "eyJjcmVhdGVkQXQiOiIyMDI2LTEwLTE4VDA4OjAwOjAwIn0")
    String nextCursor,
    @Schema(description = "Gibt an, ob weitere Seiten vorhanden sind.", example = "false")
    boolean hasMore) {}
