-- =============================================================================
-- V10: Neuer Benachrichtigungs-Typ CANCELLED (Intake #157)
-- =============================================================================
-- Das Abbrechen einer Aufgabe (Statuswechsel nach CANCELLED) benachrichtigt alle
-- aktiven Beteiligten (analog DONE, Intake #151). Der Typ-CODE spiegelt den
-- Status-CODE wie bei DONE. Dafuer wird im globalen Katalog (TENANT_ID=0) GENAU
-- EINE kanonische, AKTIVE und GUELTIGE CANCELLED-Zeile sichergestellt
-- (heal-or-insert, Muster V9).
--
-- Fail-closed/Reihenfolge: V10 muss den aktiven+gueltigen Typ bereitstellen,
-- BEVOR der erste Abbruch mit Empfaengern laeuft; sonst scheitert der
-- DictionaryCodeResolver (findByCode filtert ENTITY_STATUS='ACTIVE' + Gueltigkeit)
-- und rollt die Patch-Tx zurueck. Deploy-Ordnung Flyway-vor-Traffic ist gegeben.
--
-- UNIQUE(TENANT_ID, CODE) (UQ_DIC_NOTIFICATION_TYPE_TENANT_CODE) garantiert bei
-- der case-INSENSITIVEN MSSQL-Server-Kollation hoechstens EINE Zeile je CODE.
--
-- heal-or-insert:
--   (a) Existiert bereits die Unique-Key-Zeile fuer CANCELLED (auch abweichende
--       Schreibweise/stale Metadaten) -> NORMALISIERE sie auf die kanonischen
--       Werte (CODE='CANCELLED', BEZEICHNUNG='Abgebrochen',
--       ENTRY_TYPE='SYSTEM_PUBLIC', ENTITY_STATUS='ACTIVE', GUELTIG_VON<=heute,
--       GUELTIG_BIS=NULL). ID + EXTERNALID + Notification-FK-Referenzen bleiben
--       ERHALTEN (heal).
--   (b) Sonst INSERT mit festem Seed. ID=410 (V1 belegt 401..408, V9 409 ->
--       410 ist frei), EXTERNALID 'd5a8c3e7-2f61-4b9d-8e14-6c0b7a9f3d25' (feste
--       gueltige UUIDv4), alle Pflichtspalten ohne DB-Default explizit, TENANT_ID=0.
--
-- Das Skript ist SELBST zustands-idempotent (ein zweiter Lauf laesst genau eine
-- aktive kanonische Zeile unveraendert, ein gueltiges GUELTIG_VON bleibt).
--
-- Additive Daten-Migration, keine Schemaaenderung. V1..V9 bleiben unangetastet
-- (Flyway-Checksum). String-Literale als N'...' (Datei UTF-8, analog V7..V9).
-- =============================================================================

-- --- (a) HEAL: bestehende Unique-Key-Zeile fuer CANCELLED kanonisieren ---------
UPDATE [dbo].[DIC_NOTIFICATION_TYPE]
   SET [CODE]          = N'CANCELLED',
       [BEZEICHNUNG]   = N'Abgebrochen',
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
   AND [CODE] = N'CANCELLED';
GO

-- --- (b) INSERT: nur wenn die Unique-Key-Zeile fuer CANCELLED noch nicht existiert
INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
SELECT 410, N'ACTIVE', N'Abgebrochen', N'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), NULL,
       N'flyway-v10', GETDATE(), 'd5a8c3e7-2f61-4b9d-8e14-6c0b7a9f3d25', 1, 0, N'CANCELLED'
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[DIC_NOTIFICATION_TYPE]
     WHERE [TENANT_ID] = 0
       AND [CODE] = N'CANCELLED');
GO
