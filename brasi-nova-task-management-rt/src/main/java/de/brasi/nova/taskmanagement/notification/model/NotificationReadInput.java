package de.brasi.nova.taskmanagement.notification.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optionaler Eingabe-Body fuer {@code PUT /notifications/{id}/read}: gewuenschter Lese-Zielzustand.
 *
 * <p>{@code value=true} markiert die Benachrichtigung als gelesen, {@code value=false} als ungelesen.
 * Kein Toggle - der Client gibt den Zielzustand vor (FE {@code setNotificationRead(id, isRead)}).
 *
 * <p><b>Bewusst backward-compatible</b> (Abweichung von {@code participant.model.MarkerInput}, das
 * {@code value} als Pflichtfeld erzwingt): {@code value} ist nullable und der Body selbst optional.
 * Fehlt der Body ODER ist {@code value} null (z.B. {@code {}} / {@code {"value":null}}), wird
 * {@code read=true} abgeleitet - das erhaelt das bisherige No-Body-Verhalten des Endpoints. Der
 * Service erhaelt den bereits abgeleiteten {@code boolean}; dieses DTO traegt KEINEN null-Guard.
 *
 * @param value gewuenschter Lese-Zielzustand (nullable; null/fehlend -> read=true)
 */
public record NotificationReadInput(
    @Schema(
            description =
                "Gewuenschter Lese-Zielzustand: true = gelesen, false = ungelesen."
                    + " Optional; fehlt der Wert (oder der ganze Body), wird true angenommen.",
            example = "true",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean value) {}
