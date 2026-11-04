-- =============================================================================
-- V8: Tag-COLOR additiv einfuehren + SEVERITY_ID nullable (Intake #135, Phase 1)
-- =============================================================================
-- Additiv und nicht brechend:
--   (a) PREFLIGHT fail-closed (P1 Domaenen-Gleichheit bidirektional + P2 Referenz-
--       Totalitaet) VOR jeder Aenderung - bei Verletzung THROW (Migration FAIL,
--       kein Backfill auf unbekannter/unvollstaendiger Domaene).
--   (b) ALTER TABLE TASK_TAG ADD COLOR nvarchar(7) NULL (Hex #RRGGBB/#RGB).
--   (c) FK-sichere Nullable-Aenderung von SEVERITY_ID (FK blockiert ALTER COLUMN):
--       DROP CONSTRAINT -> ALTER COLUMN NULL -> FK identisch wiederherstellen.
--   (d) Einmaliger, idempotenter Backfill (WHERE COLOR IS NULL): COLOR aus severity
--       abgeleitet ueber ein deterministisches Mapping der 5 kanonischen CODEs
--       (JOIN auf DIC_TAG_SEVERITY.CODE, NICHT per ID).
--
-- "aktiv+gueltig" == ENTITY_STATUS='ACTIVE' AND GUELTIG_VON<=heute AND
--   (GUELTIG_BIS IS NULL OR GUELTIG_BIS>=heute) - identisch zur Resolver-Validity.
-- V1..V7 bleiben unangetastet (Flyway-Checksum). Hex-Werte = nova-ui-kit @2152d89
--   (identisch zur Java-Konstante TagSeverityColors).
-- =============================================================================

-- --- (a) PREFLIGHT P1: Domaenen-Gleichheit (bidirektional, validity-aware) ----
-- Die aktiv+gueltigen DIC_TAG_SEVERITY-CODEs MUESSEN exakt die 5 kanonischen sein:
--   kein zusaetzlicher aktiver+gueltiger Code UND keiner der 5 fehlend/deaktiviert.
--
-- Byte-exakter CODE-Vergleich (identisch zur Java-Map-Key-Semantik
-- TagSeverityColors.BY_CODE): die Server-Default-Kollation ist
-- SQL_Latin1_General_CP1_CI_AS (case-INSENSITIV) und '=' / IN PADDEN nachgestellte
-- Leerzeichen weg ('PRIMARY ' = 'PRIMARY'). Ein gespeicherter CODE 'primary' oder
-- 'PRIMARY ' (trailing space) wuerde so faelschlich als kanonisch zaehlen und
-- gefaerbt, waere aber KEIN BY_CODE-Key -> SQL- und Java-Domaene divergieren.
-- Fail-closed: COLLATE Latin1_General_BIN2 erzwingt Case- (und Leading-Space-)
-- Genauigkeit, DATALENGTH-Gleichheit faengt den von BIN2 noch weggepaddeten
-- Trailing-Space. Nur die exakten 5 Byte-Folgen gelten als kanonisch.
DECLARE @extraActive int;
DECLARE @canonicalActive int;

SELECT @extraActive = COUNT(*)
FROM [dbo].[DIC_TAG_SEVERITY]
WHERE [TENANT_ID] = 0
  AND [ENTITY_STATUS] = 'ACTIVE'
  AND [GUELTIG_VON] <= CAST(GETDATE() AS DATE)
  AND ([GUELTIG_BIS] IS NULL OR [GUELTIG_BIS] >= CAST(GETDATE() AS DATE))
  AND NOT (
    ([CODE] COLLATE Latin1_General_BIN2 = N'PRIMARY'   COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'PRIMARY'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'SUCCESS'   COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'SUCCESS'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'WARN'      COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'WARN'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'DANGER'    COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'DANGER'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'SECONDARY' COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'SECONDARY'))
  );

SELECT @canonicalActive = COUNT(*)
FROM [dbo].[DIC_TAG_SEVERITY]
WHERE [TENANT_ID] = 0
  AND [ENTITY_STATUS] = 'ACTIVE'
  AND [GUELTIG_VON] <= CAST(GETDATE() AS DATE)
  AND ([GUELTIG_BIS] IS NULL OR [GUELTIG_BIS] >= CAST(GETDATE() AS DATE))
  AND (
    ([CODE] COLLATE Latin1_General_BIN2 = N'PRIMARY'   COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'PRIMARY'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'SUCCESS'   COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'SUCCESS'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'WARN'      COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'WARN'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'DANGER'    COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'DANGER'))
    OR ([CODE] COLLATE Latin1_General_BIN2 = N'SECONDARY' COLLATE Latin1_General_BIN2 AND DATALENGTH([CODE]) = DATALENGTH(N'SECONDARY'))
  );

IF (@extraActive <> 0)
    THROW 51080, 'V8-Preflight P1 verletzt: zusaetzlicher aktiver+gueltiger DIC_TAG_SEVERITY-CODE ausserhalb der 5 kanonischen. Backfill-Domaene unbekannt - Migration abgebrochen.', 1;

IF (@canonicalActive <> 5)
    THROW 51081, 'V8-Preflight P1 verletzt: nicht alle 5 kanonischen DIC_TAG_SEVERITY-CODEs sind aktiv+gueltig. Backfill-Domaene unvollstaendig - Migration abgebrochen.', 1;
GO

-- --- (a) PREFLIGHT P2: Referenz-Totalitaet (UNABHAENGIG vom Active/Validity-Flag) --
-- Jede von TASK_TAG referenzierte SEVERITY_ID muss via JOIN auf einen der 5
-- kanonischen CODEs zeigen; ein Verweis auf einen nicht-kanonischen (z.B. inaktiven
-- 6.) Dictionary-Eintrag -> ABBRUCH (es entstuende sonst eine ungefaerbte Zeile).
DECLARE @nonCanonicalRefs int;

-- Byte-exakter CODE-Vergleich (siehe P1): nur die exakten 5 Byte-Folgen gelten als
-- kanonisch; ein Verweis auf 'primary'/'PRIMARY ' zaehlt als nicht-kanonisch -> ABBRUCH.
SELECT @nonCanonicalRefs = COUNT(*)
FROM [dbo].[TASK_TAG] t
JOIN [dbo].[DIC_TAG_SEVERITY] s ON s.[ID] = t.[SEVERITY_ID]
WHERE t.[SEVERITY_ID] IS NOT NULL
  AND NOT (
    (s.[CODE] COLLATE Latin1_General_BIN2 = N'PRIMARY'   COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'PRIMARY'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'SUCCESS'   COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'SUCCESS'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'WARN'      COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'WARN'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'DANGER'    COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'DANGER'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'SECONDARY' COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'SECONDARY'))
  );

IF (@nonCanonicalRefs <> 0)
    THROW 51082, 'V8-Preflight P2 verletzt: TASK_TAG referenziert eine SEVERITY_ID, deren CODE nicht kanonisch ist. Mapping nicht total - Migration abgebrochen.', 1;
GO

-- --- (b) COLOR-Spalte additiv (Hex #RRGGBB/#RGB; NULL = keine Farbe) ----------
ALTER TABLE [dbo].[TASK_TAG] ADD [COLOR] nvarchar(7) NULL;
GO

-- --- (c) FK-sichere Nullable-Aenderung von SEVERITY_ID -------------------------
-- Der FK blockiert ALTER COLUMN: Drop -> Alter -> identisch wiederherstellen.
ALTER TABLE [dbo].[TASK_TAG] DROP CONSTRAINT [FK_TASK_TAG_Severity];
GO
ALTER TABLE [dbo].[TASK_TAG] ALTER COLUMN [SEVERITY_ID] bigint NULL;
GO
ALTER TABLE [dbo].[TASK_TAG] ADD CONSTRAINT [FK_TASK_TAG_Severity]
    FOREIGN KEY ([SEVERITY_ID]) REFERENCES [dbo].[DIC_TAG_SEVERITY] ([ID]);
GO

-- --- (d) Backfill (einmalig, idempotent via WHERE COLOR IS NULL) ---------------
-- COLOR aus severity abgeleitet (JOIN auf CODE). Hex-Werte identisch zu
-- TagSeverityColors (nova-ui-kit @2152d89). Nur NULL-color-Zeilen werden gefaerbt;
-- ein spaeterer Re-Lauf aendert bewusst gesetzte Farben NICHT.
-- Byte-exakter CODE-Vergleich (siehe P1): sowohl die WHERE-Filterung als auch die
-- CASE-Zuordnung matchen nur die exakten 5 Byte-Folgen; eine Near-Miss-Referenz
-- ('primary'/'PRIMARY ') wird NICHT gefaerbt (konsistent zu TagSeverityColors.BY_CODE).
UPDATE t
SET t.[COLOR] = CASE
                    WHEN s.[CODE] COLLATE Latin1_General_BIN2 = N'PRIMARY'   COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'PRIMARY')   THEN '#60A5FA'
                    WHEN s.[CODE] COLLATE Latin1_General_BIN2 = N'SUCCESS'   COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'SUCCESS')   THEN '#4ADE80'
                    WHEN s.[CODE] COLLATE Latin1_General_BIN2 = N'WARN'      COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'WARN')      THEN '#FBBF24'
                    WHEN s.[CODE] COLLATE Latin1_General_BIN2 = N'DANGER'    COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'DANGER')    THEN '#F87171'
                    WHEN s.[CODE] COLLATE Latin1_General_BIN2 = N'SECONDARY' COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'SECONDARY') THEN '#CBD5E1'
                END
FROM [dbo].[TASK_TAG] t
JOIN [dbo].[DIC_TAG_SEVERITY] s ON s.[ID] = t.[SEVERITY_ID]
WHERE t.[COLOR] IS NULL
  AND t.[SEVERITY_ID] IS NOT NULL
  AND (
    (s.[CODE] COLLATE Latin1_General_BIN2 = N'PRIMARY'   COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'PRIMARY'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'SUCCESS'   COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'SUCCESS'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'WARN'      COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'WARN'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'DANGER'    COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'DANGER'))
    OR (s.[CODE] COLLATE Latin1_General_BIN2 = N'SECONDARY' COLLATE Latin1_General_BIN2 AND DATALENGTH(s.[CODE]) = DATALENGTH(N'SECONDARY'))
  );
GO
