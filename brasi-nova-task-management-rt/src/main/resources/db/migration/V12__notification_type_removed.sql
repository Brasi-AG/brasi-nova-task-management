-- =============================================================================
-- V12: Neuer Benachrichtigungs-Typ REMOVED (Intake #145)
-- =============================================================================
-- Wer ueber DELETE /api/v1/tasks/{id}/participants/{userId} von einem Ersteller
-- oder aktiven Assignee aus einer Aufgabe entfernt wird, bekommt eine
-- Benachrichtigung. Dafuer wird im globalen Katalog (TENANT_ID=0) GENAU EINE
-- kanonische, AKTIVE und GUELTIGE REMOVED-Zeile sichergestellt (heal-or-insert,
-- Muster V9/V10/V11). Der Typ ist direkt persoenlich wie PARTICIPANT und von der
-- Stummschaltung ausgenommen (NotificationMuteFilter).
--
-- Fail-closed/Reihenfolge: V12 muss den aktiven+gueltigen Typ bereitstellen,
-- BEVOR die erste Person entfernt wird; sonst scheitert der
-- DictionaryCodeResolver (findByCode filtert ENTITY_STATUS='ACTIVE' +
-- Gueltigkeit) und rollt die removeParticipant-Tx zurueck. Deploy-Ordnung
-- Flyway-vor-Traffic ist gegeben.
--
-- UNIQUE(TENANT_ID, CODE) (UQ_DIC_NOTIFICATION_TYPE_TENANT_CODE) garantiert bei
-- der case-INSENSITIVEN MSSQL-Server-Kollation hoechstens EINE Zeile je CODE.
--
-- heal-or-insert:
--   (a) Existiert bereits die Unique-Key-Zeile fuer REMOVED (auch abweichende
--       Schreibweise/stale Metadaten) -> NORMALISIERE sie auf die kanonischen
--       Werte (CODE='REMOVED', BEZEICHNUNG='Aus der Aufgabe entfernt',
--       ENTRY_TYPE='SYSTEM_PUBLIC', ENTITY_STATUS='ACTIVE', GUELTIG_VON<=heute,
--       GUELTIG_BIS=NULL). ID + EXTERNALID + Notification-FK-Referenzen bleiben
--       ERHALTEN (heal).
--   (b) Sonst INSERT mit festem Seed. ID=412 (V1 belegt 401..408, V9 409, V10 410,
--       V11 411 -> 412 ist frei), EXTERNALID '3a3786fe-28f8-4aef-9601-795c8d48cba5'
--       (feste gueltige UUIDv4), alle Pflichtspalten ohne DB-Default explizit,
--       TENANT_ID=0.
--
-- Das Skript ist SELBST zustands-idempotent (ein zweiter Lauf laesst genau eine
-- aktive kanonische Zeile unveraendert, ein gueltiges GUELTIG_VON bleibt).
--
-- Additive Daten-Migration (Katalog-Seed), keine Schemaaenderung. V1..V11 bleiben
-- unangetastet (Flyway-Checksum). String-Literale als N'...' (Datei UTF-8, analog
-- V7..V11).
-- =============================================================================

-- --- (a) HEAL: bestehende Unique-Key-Zeile fuer REMOVED kanonisieren -----------
UPDATE [dbo].[DIC_NOTIFICATION_TYPE]
   SET [CODE]          = N'REMOVED',
       [BEZEICHNUNG]   = N'Aus der Aufgabe entfernt',
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
   AND [CODE] = N'REMOVED';
GO

-- --- (b) INSERT: nur wenn die Unique-Key-Zeile fuer REMOVED noch nicht existiert
INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
SELECT 412, N'ACTIVE', N'Aus der Aufgabe entfernt', N'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), NULL,
       N'flyway-v12', GETDATE(), '3a3786fe-28f8-4aef-9601-795c8d48cba5', 1, 0, N'REMOVED'
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[DIC_NOTIFICATION_TYPE]
     WHERE [TENANT_ID] = 0
       AND [CODE] = N'REMOVED');
GO
