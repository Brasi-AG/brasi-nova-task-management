package de.brasi.nova.taskmanagement.inbox.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Austritts-Information des Anfragers zu einer (Archiv-)Aufgabe (BE-1). Nur gesetzt, wenn der
 * Anfrager die Aufgabe zum {@code asOf}-Wasserzeichen verlassen hatte; sonst ist das umschliessende
 * Feld {@code TaskSummaryModel.exit} {@code null}.
 *
 * <p>{@code handedOverToIds} (Uebergabeziele) folgt mit BE-7 (V5-Migration) und ist in BE-1 bewusst
 * noch nicht enthalten.
 *
 * @param kind Austrittsart: {@code "handover"} (Uebergabe) oder {@code "unsubscribed"} (Abmeldung)
 */
public record ExitModel(
    @Schema(
            description = "Austrittsart des Anfragers: 'handover' (Uebergabe) oder 'unsubscribed'.",
            example = "handover")
        String kind) {}
