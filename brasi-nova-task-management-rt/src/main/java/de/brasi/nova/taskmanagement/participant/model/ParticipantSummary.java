package de.brasi.nova.taskmanagement.participant.model;

import java.util.UUID;

/**
 * Kompakte Zusammenfassung eines Teilnehmer-Benutzers.
 *
 * @param userRef   externe UUID des Benutzers (PROD_BENUTZER.EXTERNALID)
 * @param loginName Login-Name (PROD_BENUTZER.LOGIN_NAME)
 * @param fullName  vollstaendiger Anzeigename (PROD_BENUTZER.VOLLER_NAME)
 */
public record ParticipantSummary(UUID userRef, String loginName, String fullName) {}
