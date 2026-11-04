-- =============================================================================
-- V9: Neuer Benachrichtigungs-Typ COMMENT (Intake #136)
-- =============================================================================
-- Ein neuer Top-Level-Kommentar an einer Aufgabe benachrichtigt die zugewiesene
-- Person (aktive PERSON-Assignees) auch OHNE @-Erwaehnung -> dafuer wird im
-- globalen Katalog (TENANT_ID=0) GENAU EINE kanonische, AKTIVE und GUELTIGE
-- COMMENT-Zeile sichergestellt (heal-or-insert).
--
-- Fail-closed/Reihenfolge: V9 muss den aktiven+gueltigen Typ bereitstellen, BEVOR
-- der erste createComment() laeuft; sonst scheitert der DictionaryCodeResolver
-- (findByCode filtert ENTITY_STATUS='ACTIVE' + Gueltigkeit) und rollt die
-- Comment-Tx zurueck. Deploy-Ordnung Flyway-vor-Traffic ist gegeben.
--
-- UNIQUE(TENANT_ID, CODE) (UQ_DIC_NOTIFICATION_TYPE_TENANT_CODE) garantiert bei
-- der dokumentierten case-INSENSITIVEN MSSQL-Server-Kollation hoechstens EINE
-- Zeile je CODE (auch 'comment'/'COMMENT ' belegen denselben Unique-Key).
--
-- heal-or-insert:
--   (a) Existiert bereits die Unique-Key-Zeile fuer COMMENT (auch abweichende
--       Schreibweise/stale Metadaten) -> NORMALISIERE sie auf die kanonischen
--       Werte (CODE='COMMENT', BEZEICHNUNG='Neuer Kommentar',
--       ENTRY_TYPE='SYSTEM_PUBLIC', ENTITY_STATUS='ACTIVE', GUELTIG_VON<=heute,
--       GUELTIG_BIS=NULL). ID + EXTERNALID + Notification-FK-Referenzen bleiben
--       ERHALTEN (heal). Begruendung: der Reverse-Resolver (DictionaryCodeResolver
--       .resolveNotificationTypeCode) gibt den GESPEICHERTEN CODE unveraendert als
--       typeCode nach aussen; eine abweichende Schreibweise oder stale Metadaten
--       wuerden sonst persistieren.
--   (b) Sonst INSERT mit festem, kollisionsgeprueften Seed. ID=409 (die 8
--       bestehenden Typen belegen 401..408 in V1 -> 409 ist frei), EXTERNALID
--       '9c4e1f82-7a3d-4b6e-8f05-2d9a1c7e4b60' (feste gueltige UUIDv4: Version-
--       Nibble 4, Variant-Nibble 8; EXTERNALID-UNIQUE kollisionsfrei), alle
--       Pflichtspalten ohne DB-Default explizit (VERSION, Nova-Audit), TENANT_ID=0.
--
-- Das Skript ist SELBST zustands-idempotent (ein zweiter Lauf - auch an einem
-- spaeteren Tag - laesst genau eine aktive kanonische Zeile UNVERAENDERT): der
-- UPDATE setzt beim zweiten Lauf dieselben kanonischen Werte und bewahrt ein
-- bereits gueltiges GUELTIG_VON (<= heute); der INSERT greift dank Existenzpruefung
-- nicht erneut.
--
-- Additive Daten-Migration, keine Schemaaenderung. V1..V8 bleiben unangetastet
-- (Flyway-Checksum). String-Literale als N'...' (Datei UTF-8, analog V7/V8).
-- =============================================================================

-- --- (a) HEAL: bestehende Unique-Key-Zeile fuer COMMENT kanonisieren -----------
-- Case-insensitiver '='-Vergleich = exakt die UNIQUE(TENANT_ID,CODE)-Semantik des
-- Index: findet 'COMMENT'/'comment'/'COMMENT ' gleichermassen (<=1 Zeile).
UPDATE [dbo].[DIC_NOTIFICATION_TYPE]
   SET [CODE]          = N'COMMENT',
       [BEZEICHNUNG]   = N'Neuer Kommentar',
       [ENTRY_TYPE]    = N'SYSTEM_PUBLIC',
       [ENTITY_STATUS] = N'ACTIVE',
       -- Zustands-Idempotenz: ein bereits gueltiges GUELTIG_VON (<= heute) bleibt UNVERAENDERT,
       -- nur NULL oder future-dated (> heute) wird auf heute korrigiert. Sonst wuerde ein
       -- Zweitlauf an einem spaeteren Tag eine bereits geheilte Zeile veraendern.
       [GUELTIG_VON]   = CASE
                           WHEN [GUELTIG_VON] IS NULL OR [GUELTIG_VON] > CAST(GETDATE() AS DATE)
                           THEN CAST(GETDATE() AS DATE)
                           ELSE [GUELTIG_VON]
                         END,
       [GUELTIG_BIS]   = NULL
 WHERE [TENANT_ID] = 0
   AND [CODE] = N'COMMENT';
GO

-- --- (b) INSERT: nur wenn die Unique-Key-Zeile fuer COMMENT noch nicht existiert -
-- Fester Seed (ID=409, feste UUIDv4-EXTERNALID). Die WHERE-NOT-EXISTS-Bedingung
-- nutzt denselben case-insensitiven '='-Vergleich wie (a) -> hat (a) bereits eine
-- vorhandene Zeile geheilt, greift der INSERT NICHT (Idempotenz, kein UNIQUE-Fehler).
INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
SELECT 409, N'ACTIVE', N'Neuer Kommentar', N'SYSTEM_PUBLIC', CAST(GETDATE() AS DATE), NULL,
       N'flyway-v9', GETDATE(), '9c4e1f82-7a3d-4b6e-8f05-2d9a1c7e4b60', 1, 0, N'COMMENT'
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[DIC_NOTIFICATION_TYPE]
     WHERE [TENANT_ID] = 0
       AND [CODE] = N'COMMENT');
GO
