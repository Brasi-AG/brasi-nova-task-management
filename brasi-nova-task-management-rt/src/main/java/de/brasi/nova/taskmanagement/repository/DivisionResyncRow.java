package de.brasi.nova.taskmanagement.repository;

import java.util.UUID;

/**
 * Zeile der mandantenuebergreifenden Resync-Liste {@code
 * TaskDivisionQueryRepository#findAllForResync} (Intake #190): eine nachzuladende Abteilung (mit
 * Kopfzeile oder nur mit {@code DEAD}-Quittung) mit ihrem Mandanten. KSF mappt die SQL-Aliase
 * {@code tenantId}/{@code tenantUuid}/{@code divisionRef} per Konstruktor (wie {@link
 * TaskTagRefRow}).
 *
 * @param tenantId interne Mandanten-ID ({@code TENANT_ID} der Kopfzeile oder der Quittung)
 * @param tenantUuid Mandanten-UUID ({@code PROD_FIRMA.EXTERNALID})
 * @param divisionRef UUID der Abteilung in application ({@code ABTEILUNG_REF} der Kopfzeile oder
 *     der {@code DEAD}-Quittung)
 */
public record DivisionResyncRow(long tenantId, UUID tenantUuid, UUID divisionRef) {}
