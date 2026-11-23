package de.brasi.nova.taskmanagement.division;

import java.util.UUID;

/**
 * Gepruefter Inhalt eines Ereignisses {@code DivisionMembershipChanged} (Intake #190), wie ihn
 * {@link DivisionSignalGuard} allein aus den JMS-Properties gewinnt.
 *
 * <p>Eine revision traegt das Signal nicht: Sie stuende nur im Body, und der Body wird nie gelesen
 * (Vertrag P4 §2/§4). Der aktuelle Stand kommt immer ueber {@code self}.
 *
 * @param eventId novaEventId des Ereignisses (Idempotenzschluessel je Mandant)
 * @param tenantUuid Mandanten-UUID (novaTenantId, {@code PROD_FIRMA.EXTERNALID})
 * @param divisionRef UUID der Abteilung in application (aggregateId)
 */
public record DivisionSignal(UUID eventId, UUID tenantUuid, UUID divisionRef) {}
