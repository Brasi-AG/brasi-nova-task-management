-- =============================================================================
-- V11: Neuer Benachrichtigungs-Typ PARTICIPANT (Intake #147)
-- =============================================================================
-- Wer ueber POST /api/v1/tasks/{id}/participants als Beteiligter zu einer Aufgabe
-- hinzugefuegt wird, bekommt eine Benachrichtigung. Dafuer wird im globalen
-- Katalog (TENANT_ID=0) GENAU EINE kanonische, AKTIVE und GUELTIGE PARTICIPANT-
-- Zeile sichergestellt (heal-or-insert, Muster V9/V10). Der Typ ist direkt
-- persoenlich wie ASSIGN und HANDOVER und von der Stummschaltung ausgenommen
-- (NotificationMuteFilter).
--
-- Fail-closed/Reihenfolge: V11 muss den aktiven+gueltigen Typ bereitstellen,
-- BEVOR der erste Teilnehmer mit Empfaenger hinzugefuegt wird; sonst scheitert
-- der DictionaryCodeResolver (findByCode filtert ENTITY_STATUS='ACTIVE' +
-- Gueltigkeit) und rollt die addParticipants-Tx zurueck. Deploy-Ordnung
-- Flyway-vor-Traffic ist gegeben.
--
-- UNIQUE(TENANT_ID, CODE) (UQ_DIC_NOTIFICATION_TYPE_TENANT_CODE) garantiert bei
-- der case-INSENSITIVEN MSSQL-Server-Kollation hoechstens EINE Zeile je CODE.
--
-- heal-or-insert:
--   (a) Existiert bereits die Unique-Key-Zeile fuer PARTICIPANT (auch abweichende
--       Schreibweise/stale Metadaten) -> NORMALISIERE sie auf die kanonischen
--       Werte (CODE='PARTICIPANT', BEZEICHNUNG='Als Beteiligter hinzugefügt',
--       ENTRY_TYPE='SYSTEM_PUBLIC', ENTITY_STATUS='ACTIVE', GUELTIG_VON<=heute,
--       GUELTIG_BIS=NULL). ID + EXTERNALID + Notification-FK-Referenzen bleiben
--       ERHALTEN (heal).
--   (b) Sonst INSERT mit festem Seed. ID=411 (V1 belegt 401..408, V9 409, V10 410
--       -> 411 ist frei), EXTERNALID '7573f130-8c64-4e46-b5a3-a9f40406d0d2' (feste
--       gueltige UUIDv4), alle Pflichtspalten ohne DB-Default explizit,
--       TENANT_ID=0.
--
-- Das Skript ist SELBST zustands-idempotent (ein zweiter Lauf laesst genau eine
-- aktive kanonische Zeile unveraendert, ein gueltiges GUELTIG_VON bleibt).
--
-- Additive Daten-Migration, keine Schemaaenderung. V1..V10 bleiben unangetastet
-- (Flyway-Checksum). String-Literale als N'...' (Datei UTF-8, analog V7..V10).
-- =============================================================================

-- --- (a) HEAL: bestehende Unique-Key-Zeile fuer PARTICIPANT kanonisieren -------
UPDATE [dbo].[DIC_NOTIFICATION_TYPE]
   SET [CODE]          = N'PARTICIPANT',
       [BEZEICHNUNG]   = N'Als Beteiligter hinzugefügt',
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
   AND [CODE] = N'PARTICIPANT';
GO

-- --- (b) INSERT: nur wenn die Unique-Key-Zeile fuer PARTICIPANT noch nicht existiert
INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
SELECT 411, N'ACTIVE', N'Als Beteiligter hinzugefügt', N'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), NULL,
       N'flyway-v11', GETDATE(), '7573f130-8c64-4e46-b5a3-a9f40406d0d2', 1, 0, N'PARTICIPANT'
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[DIC_NOTIFICATION_TYPE]
     WHERE [TENANT_ID] = 0
       AND [CODE] = N'PARTICIPANT');
GO
