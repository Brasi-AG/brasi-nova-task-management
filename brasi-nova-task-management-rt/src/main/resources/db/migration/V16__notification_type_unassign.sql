-- =============================================================================
-- V16: Neuer Benachrichtigungs-Typ UNASSIGN (Intake #188)
-- =============================================================================
-- Wer im PUT /api/v1/tasks/{id}/assignees aus den Zugewiesenen genommen wird
-- (Nicht-Ersteller mit Austritt oder Ersteller, der nur de-assignt wird),
-- bekommt eine Benachrichtigung, nie der Akteur selbst. Dafuer wird im globalen
-- Katalog (TENANT_ID=0) GENAU EINE kanonische, AKTIVE und GUELTIGE
-- UNASSIGN-Zeile sichergestellt (heal-or-insert, Muster V9..V12). Der Typ ist
-- das Gegenstueck zu ASSIGN, direkt persoenlich wie REMOVED und von der
-- Stummschaltung ausgenommen (NotificationMuteFilter).
--
-- Fail-closed/Reihenfolge: V16 muss den aktiven+gueltigen Typ bereitstellen,
-- BEVOR die erste Abwahl laeuft; sonst scheitert der DictionaryCodeResolver
-- (findByCode filtert ENTITY_STATUS='ACTIVE' + Gueltigkeit) und rollt die
-- updateAssignees-Tx zurueck. Deploy-Ordnung Flyway-vor-Traffic ist gegeben.
--
-- UNIQUE(TENANT_ID, CODE) (UQ_DIC_NOTIFICATION_TYPE_TENANT_CODE) garantiert bei
-- der case-INSENSITIVEN MSSQL-Server-Kollation hoechstens EINE Zeile je CODE.
--
-- heal-or-insert:
--   (a) Existiert bereits die Unique-Key-Zeile fuer UNASSIGN (auch abweichende
--       Schreibweise/stale Metadaten) -> NORMALISIERE sie auf die kanonischen
--       Werte (CODE='UNASSIGN', BEZEICHNUNG='Zuweisung entfernt',
--       ENTRY_TYPE='SYSTEM_PUBLIC', ENTITY_STATUS='ACTIVE', GUELTIG_VON<=heute,
--       GUELTIG_BIS=NULL). ID + EXTERNALID + Notification-FK-Referenzen bleiben
--       ERHALTEN (heal).
--   (b) Sonst INSERT mit festem Seed. ID=413 (V1 belegt 401..408, V9 409, V10 410,
--       V11 411, V12 412 -> 413 ist frei; V13..V15 seeden keine Typen),
--       EXTERNALID '1e3ffc90-7beb-4c14-bf12-a11be3623af9' (feste gueltige UUIDv4),
--       alle Pflichtspalten ohne DB-Default explizit, TENANT_ID=0.
--
-- Das Skript ist SELBST zustands-idempotent (ein zweiter Lauf laesst genau eine
-- aktive kanonische Zeile unveraendert, ein gueltiges GUELTIG_VON bleibt).
--
-- Additive Daten-Migration (Katalog-Seed), keine Schemaaenderung. V1..V15 bleiben
-- unangetastet (Flyway-Checksum). String-Literale als N'...' (Datei UTF-8, analog
-- V7..V12).
-- =============================================================================

-- --- (a) HEAL: bestehende Unique-Key-Zeile fuer UNASSIGN kanonisieren ----------
UPDATE [dbo].[DIC_NOTIFICATION_TYPE]
   SET [CODE]          = N'UNASSIGN',
       [BEZEICHNUNG]   = N'Zuweisung entfernt',
       [ENTRY_TYPE]    = N'SYSTEM_PUBLIC',
       [ENTITY_STATUS] = N'ACTIVE',
       -- Zustands-Idempotenz: ein bereits gueltiges GUELTIG_VON (<= heute) bleibt unveraendert.
       [GUELTIG_VON]   = CASE
                           WHEN [GUELTIG_VON] IS NULL OR [GUELTIG_VON] > CAST(GETDATE() AS DATE)
                           THEN CAST(GETDATE() AS DATE)
                           ELSE [GUELTIG_VON]
                         END,
       [GUELTIG_BIS]   = NULL
 WHERE [TENANT_ID] = 0
   AND [CODE] = N'UNASSIGN';
GO

-- --- (b) INSERT: nur wenn die Unique-Key-Zeile fuer UNASSIGN noch nicht existiert
INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
SELECT 413, N'ACTIVE', N'Zuweisung entfernt', N'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), NULL,
       N'flyway-v16', GETDATE(), '1e3ffc90-7beb-4c14-bf12-a11be3623af9', 1, 0, N'UNASSIGN'
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[DIC_NOTIFICATION_TYPE]
     WHERE [TENANT_ID] = 0
       AND [CODE] = N'UNASSIGN');
GO
