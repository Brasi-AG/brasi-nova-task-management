package de.brasi.nova.taskmanagement.inbox.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Austritts-Information des Anfragers zu einer (Archiv-)Aufgabe (BE-1/BE-7). Nur gesetzt, wenn der
 * Anfrager die Aufgabe zum {@code asOf}-Wasserzeichen verlassen hatte; sonst ist das umschliessende
 * Feld {@code TaskSummaryModel.exit} {@code null}.
 *
 * <p>{@code handedOverToIds} (Uebergabeziele, BE-7) ist nur bei {@code kind="handover"} relevant
 * und dann niemals {@code null}: die Ziele des sichtbaren Austritts-Handover-Ereignisses (ggf. leer
 * bei einem Selbst-Handover ohne Ziele). Bei {@code kind="unsubscribed"} ist die Liste stets leer.
 *
 * @param kind Austrittsart: {@code "handover"} (Uebergabe) oder {@code "unsubscribed"} (Abmeldung)
 *     <p>{@code exitedAt} (Intake #150) ist der Austrittszeitpunkt aus {@code EXIT_AT} der
 *     Teilnehmerzeile, gesetzt bei {@code handover} und {@code unsubscribed}. Nach einem
 *     Wiedereintritt (Zuweisung oder Aufnahme als Teilnehmer) ist das gesamte Feld {@code exit}
 *     wieder {@code null}; die Historie der Aufgabe bleibt davon unberuehrt.
 * @param kind Austrittsart: {@code "handover"} (Uebergabe) oder {@code "unsubscribed"} (Abmeldung)
 * @param handedOverToIds Uebergabeziele (UUIDs) bei Handover; leer bei Unsubscribed oder Handover
 *     ohne Ziele, nie {@code null}
 * @param exitedAt Austrittszeitpunkt (UTC); {@code null} nur bei Altdaten ohne erfassten Zeitpunkt
 */
public record ExitModel(
    @Schema(
            description = "Austrittsart des Anfragers: 'handover' (Uebergabe) oder 'unsubscribed'.",
            example = "handover")
        String kind,
    @ArraySchema(
            schema =
                @Schema(
                    type = "string",
                    format = "uuid",
                    description = "UUID eines Uebergabeziels (PROD_BENUTZER.EXTERNALID)."))
        @Schema(
            description =
                "Uebergabeziele bei Handover (leer bei Unsubscribed / Handover ohne Ziele).")
        List<UUID> handedOverToIds,
    @Schema(
            description =
                "Zeitpunkt des Austritts (ISO 8601, UTC) bei Uebergabe und Abmeldung. null nur bei"
                    + " Altdaten, fuer die kein Austrittszeitpunkt erfasst ist.",
            example = "2026-11-17T09:30:00Z",
            nullable = true)
        Instant exitedAt) {}
