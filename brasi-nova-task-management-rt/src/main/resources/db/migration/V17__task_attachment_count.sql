-- =============================================================================
-- V17: Anhang-Zaehler ANHANG_ANZAHL an TASK (Intake #194)
-- =============================================================================
-- Zweck: denormalisierte Anzahl der Anhaenge je Aufgabe fuer die
-- Aufgabenzusammenfassungen (aktive Liste, Archiv, Archivsuche, Snapshot),
-- gepflegt wie COMMENT_COUNT am versionierten TASK-save (Upload an der Aufgabe,
-- Entfernen, Kommentar mit Anhaengen).
--
-- Zaehlregel: gezaehlt werden nur TASK_ANHANG-Zeilen mit ENTITY_STATUS 'ACTIVE';
-- ein Kommentar-Anhang (KOMMENTAR_ID gesetzt) zaehlt nur, wenn der Kommentar
-- selbst 'ACTIVE' ist. Entfernte Anhaenge (DEACTIVE) zaehlen nie.
--
-- Spalte: int NOT NULL mit benanntem Default 0 (DF_TASK_ANHANG_ANZAHL), damit
-- Alt-Inserts ohne die Spalte gueltig bleiben, und CHECK >= 0
-- (CHK_TASK_ANHANG_ANZAHL) gegen einen negativen Zaehler.
--
-- Versionsnummer: V16 gehoert dem Benachrichtigungstyp UNASSIGN (Intake #188),
-- der vor diesem Thema gelandet ist. V17 darf nie vor V16 auf eine Datenbank
-- kommen (Flyway ohne outOfOrder).
--
-- Idempotent: die Spalte wird nur angelegt, wenn sie fehlt. Der Backfill setzt
-- fuer jede Aufgabe den Ist-Wert (ohne gezaehlte Anhaenge 0) und schreibt nur
-- Zeilen, deren Wert abweicht; ein zweiter Lauf aendert nichts.
--
-- Backfill ohne fachliche Aenderung: VERSION, AKTUALISIERT_AM und
-- LAST_ACTIVITY_AT bleiben unberuehrt (kein Bump, offene Cursor bleiben
-- gueltig). Das GO zwischen ALTER und UPDATE ist Pflicht, weil SQL Server den
-- Batch vor dem Anlegen der Spalte kompiliert. V1..V16 bleiben unangetastet
-- (Flyway-Checksum).
-- =============================================================================

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

IF COL_LENGTH(N'dbo.TASK', N'ANHANG_ANZAHL') IS NULL
    ALTER TABLE [dbo].[TASK] ADD [ANHANG_ANZAHL] int NOT NULL
        CONSTRAINT [DF_TASK_ANHANG_ANZAHL] DEFAULT ((0))
        CONSTRAINT [CHK_TASK_ANHANG_ANZAHL] CHECK ([ANHANG_ANZAHL] >= 0);
GO

-- Backfill: aktive Anhaenge an der Aufgabe und an aktiven Kommentaren,
-- Aufgaben ohne solche Anhaenge auf 0; nur abweichende Zeilen werden geschrieben.
UPDATE t
SET t.[ANHANG_ANZAHL] = COALESCE(x.[ANZAHL], 0)
FROM [dbo].[TASK] t
LEFT JOIN (
    SELECT a.[TASK_ID], COUNT(*) AS [ANZAHL]
    FROM [dbo].[TASK_ANHANG] a
    LEFT JOIN [dbo].[TASK_COMMENT] c ON c.[ID] = a.[KOMMENTAR_ID]
    WHERE a.[ENTITY_STATUS] = 'ACTIVE'
      AND (a.[KOMMENTAR_ID] IS NULL OR c.[ENTITY_STATUS] = 'ACTIVE')
    GROUP BY a.[TASK_ID]
) x ON x.[TASK_ID] = t.[ID]
WHERE t.[ANHANG_ANZAHL] <> COALESCE(x.[ANZAHL], 0);
GO
