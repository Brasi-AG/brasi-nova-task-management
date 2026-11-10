package de.brasi.nova.taskmanagement.participant.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Kompakte Zusammenfassung eines Teilnehmer-Benutzers.
 *
 * @param userRef externe UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
 * @param loginName Login-Name (PROD_BENUTZER.LOGIN_NAME)
 * @param fullName vollstaendiger Anzeigename (PROD_BENUTZER.VOLLER_NAME)
 */
public record ParticipantSummary(
    @Schema(
            description =
                "Externe Benutzer-UUID; Referenz fuer assignees, participants, Uebergabe und"
                    + " mentions.",
            example = "550e8400-e29b-41d4-a716-446655440000")
        UUID userRef,
    @Schema(
            description = "Login-Name des Benutzers (i.d.R. die Mailadresse).",
            example = "max.mustermann@example.com")
        String loginName,
    @Schema(
            description = "Anzeigename des Benutzers; zur Anzeige und Sortierung im Frontend.",
            example = "Max Mustermann")
        String fullName) {}
