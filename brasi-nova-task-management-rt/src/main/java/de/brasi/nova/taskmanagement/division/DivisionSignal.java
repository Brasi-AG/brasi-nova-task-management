package de.brasi.nova.taskmanagement.division;

import java.util.UUID;

/**
 * Gepruefter Inhalt eines Ereignisses {@code DivisionMembershipChanged} (Intake #190), wie ihn
 * {@link DivisionSignalGuard} aus Header und Body gewinnt.
 *
 * @param eventId novaEventId des Ereignisses (Idempotenzschluessel je Mandant)
 * @param tenantUuid Mandanten-UUID (novaTenantId, {@code PROD_FIRMA.EXTERNALID})
 * @param divisionRef UUID der Abteilung in application (aggregateId)
 * @param revision revision des gemeldeten Stands (64 Kleinbuchstaben-Hex)
 */
public record DivisionSignal(UUID eventId, UUID tenantUuid, UUID divisionRef, String revision) {}
