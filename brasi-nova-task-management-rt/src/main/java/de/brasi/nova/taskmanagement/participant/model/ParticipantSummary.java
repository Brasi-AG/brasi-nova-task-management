package de.brasi.nova.taskmanagement.participant.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Kompakte Zusammenfassung eines Teilnehmer-Benutzers.
 *
 * @param userRef   externe UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
 * @param loginName Login-Name (PROD_BENUTZER.LOGIN_NAME)
 * @param fullName  vollstaendiger Anzeigename (PROD_BENUTZER.VOLLER_NAME)
 */
public record ParticipantSummary(
    @Schema(description = "Externe UUID des Benutzers.", example = "770e8400-e29b-41d4-a716-446655440002")
    UUID userRef,
    @Schema(description = "Login-Name des Benutzers.", example = "m.mustermann@example.com")
    String loginName,
    @Schema(description = "Vollstaendiger Anzeigename des Benutzers.", example = "Max Mustermann")
    String fullName) {}
