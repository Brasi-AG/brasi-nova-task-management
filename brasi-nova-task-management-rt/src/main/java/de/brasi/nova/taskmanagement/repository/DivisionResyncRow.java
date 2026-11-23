package de.brasi.nova.taskmanagement.repository;

import java.util.UUID;

/**
 * Zeile der mandantenuebergreifenden Resync-Liste {@code
 * TaskDivisionQueryRepository#findAllForResync} (Intake #190): eine bekannte Abteilung mit ihrem
 * Mandanten. KSF mappt die SQL-Aliase {@code tenantId}/{@code tenantUuid}/{@code divisionRef} per
 * Konstruktor (wie {@link TaskTagRefRow}).
 *
 * @param tenantId interne Mandanten-ID ({@code TASK_ABTEILUNG.TENANT_ID})
 * @param tenantUuid Mandanten-UUID ({@code PROD_FIRMA.EXTERNALID})
 * @param divisionRef UUID der Abteilung in application ({@code TASK_ABTEILUNG.ABTEILUNG_REF})
 */
public record DivisionResyncRow(long tenantId, UUID tenantUuid, UUID divisionRef) {}
