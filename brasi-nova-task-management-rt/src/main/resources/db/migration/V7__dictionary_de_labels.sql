-- =============================================================================
-- V7: Deutsche Labels fuer alle System-Dictionary-Eintraege (Intake #133 / AC1)
-- =============================================================================
-- Setzt BEZEICHNUNG (= Response-Feld `label` in GET /api/v1/dictionaries) je CODE
-- auf einen deutschen Begriff. Vorher war BEZEICHNUNG == CODE (englisch, V1-Seed).
--
-- Nur BEZEICHNUNG wird geaendert; CODE und EXTERNALID bleiben unveraendert.
-- Idempotent/wiederholbar (reines UPDATE-by-CODE auf TENANT_ID=0; kein INSERT).
-- V1 bleibt unangetastet (Flyway-Checksum); V7 heilt bestehende DBs additiv und
-- bringt frische DBs (V1->V7) in denselben Zielzustand.
-- Umlaute als echte Unicode-N'...'-Literale (Datei UTF-8).
-- =============================================================================

-- --- DIC_TASK_STATUS ---
UPDATE [dbo].[DIC_TASK_STATUS] SET [BEZEICHNUNG] = N'Offen'       WHERE [TENANT_ID] = 0 AND [CODE] = 'OPEN';
UPDATE [dbo].[DIC_TASK_STATUS] SET [BEZEICHNUNG] = N'Erledigt'    WHERE [TENANT_ID] = 0 AND [CODE] = 'DONE';
UPDATE [dbo].[DIC_TASK_STATUS] SET [BEZEICHNUNG] = N'Abgebrochen' WHERE [TENANT_ID] = 0 AND [CODE] = 'CANCELLED';
GO

-- --- DIC_TASK_PRIORITY ---
UPDATE [dbo].[DIC_TASK_PRIORITY] SET [BEZEICHNUNG] = N'Niedrig'  WHERE [TENANT_ID] = 0 AND [CODE] = 'LOW';
UPDATE [dbo].[DIC_TASK_PRIORITY] SET [BEZEICHNUNG] = N'Normal'   WHERE [TENANT_ID] = 0 AND [CODE] = 'NORMAL';
UPDATE [dbo].[DIC_TASK_PRIORITY] SET [BEZEICHNUNG] = N'Hoch'     WHERE [TENANT_ID] = 0 AND [CODE] = 'HIGH';
UPDATE [dbo].[DIC_TASK_PRIORITY] SET [BEZEICHNUNG] = N'Dringend' WHERE [TENANT_ID] = 0 AND [CODE] = 'URGENT';
GO

-- --- DIC_TASK_DIRECTION ---
UPDATE [dbo].[DIC_TASK_DIRECTION] SET [BEZEICHNUNG] = N'Brasi ↔ Kunde'       WHERE [TENANT_ID] = 0 AND [CODE] = 'BRASI_CUSTOMER';
UPDATE [dbo].[DIC_TASK_DIRECTION] SET [BEZEICHNUNG] = N'Kundenintern'        WHERE [TENANT_ID] = 0 AND [CODE] = 'CUSTOMER_INTERNAL';
UPDATE [dbo].[DIC_TASK_DIRECTION] SET [BEZEICHNUNG] = N'Firmenübergreifend'  WHERE [TENANT_ID] = 0 AND [CODE] = 'INTER_COMPANY';
GO

-- --- DIC_NOTIFICATION_TYPE ---
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Zuweisung' WHERE [TENANT_ID] = 0 AND [CODE] = 'ASSIGN';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Antwort'   WHERE [TENANT_ID] = 0 AND [CODE] = 'REPLY';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Fällig'    WHERE [TENANT_ID] = 0 AND [CODE] = 'DUE';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Erwähnung' WHERE [TENANT_ID] = 0 AND [CODE] = 'MENTION';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Erledigt'  WHERE [TENANT_ID] = 0 AND [CODE] = 'DONE';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Änderung'  WHERE [TENANT_ID] = 0 AND [CODE] = 'CHANGE';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'Übergabe'  WHERE [TENANT_ID] = 0 AND [CODE] = 'HANDOVER';
UPDATE [dbo].[DIC_NOTIFICATION_TYPE] SET [BEZEICHNUNG] = N'System'    WHERE [TENANT_ID] = 0 AND [CODE] = 'SYSTEM';
GO

-- --- DIC_NOTIFICATION_SEVERITY ---
UPDATE [dbo].[DIC_NOTIFICATION_SEVERITY] SET [BEZEICHNUNG] = N'Information' WHERE [TENANT_ID] = 0 AND [CODE] = 'INFO';
UPDATE [dbo].[DIC_NOTIFICATION_SEVERITY] SET [BEZEICHNUNG] = N'Erfolg'      WHERE [TENANT_ID] = 0 AND [CODE] = 'SUCCESS';
UPDATE [dbo].[DIC_NOTIFICATION_SEVERITY] SET [BEZEICHNUNG] = N'Warnung'     WHERE [TENANT_ID] = 0 AND [CODE] = 'WARN';
UPDATE [dbo].[DIC_NOTIFICATION_SEVERITY] SET [BEZEICHNUNG] = N'Fehler'      WHERE [TENANT_ID] = 0 AND [CODE] = 'ERROR';
GO

-- --- DIC_TAG_SEVERITY ---
UPDATE [dbo].[DIC_TAG_SEVERITY] SET [BEZEICHNUNG] = N'Primär'    WHERE [TENANT_ID] = 0 AND [CODE] = 'PRIMARY';
UPDATE [dbo].[DIC_TAG_SEVERITY] SET [BEZEICHNUNG] = N'Erfolg'    WHERE [TENANT_ID] = 0 AND [CODE] = 'SUCCESS';
UPDATE [dbo].[DIC_TAG_SEVERITY] SET [BEZEICHNUNG] = N'Warnung'   WHERE [TENANT_ID] = 0 AND [CODE] = 'WARN';
UPDATE [dbo].[DIC_TAG_SEVERITY] SET [BEZEICHNUNG] = N'Gefahr'    WHERE [TENANT_ID] = 0 AND [CODE] = 'DANGER';
UPDATE [dbo].[DIC_TAG_SEVERITY] SET [BEZEICHNUNG] = N'Sekundär'  WHERE [TENANT_ID] = 0 AND [CODE] = 'SECONDARY';
GO
