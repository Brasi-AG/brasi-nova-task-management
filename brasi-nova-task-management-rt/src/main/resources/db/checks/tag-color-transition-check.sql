-- =============================================================================
-- Transition-Detektion Tag-COLOR (Intake #135, Phase 1 -> Phase 3 GO-Gate)
-- =============================================================================
-- READ-ONLY. Kein Schreibzugriff. Vor der FE-Umstellung (Phase 3) auszufuehren
-- (Ausfuehrung gegen Live-DB bleibt owner-gated); die Query-Logik ist ueber
-- TagTransitionDetectionIT real-MSSQL getestet.
--
-- Drei eindeutige Skalar-Checks mit festem Soll (SPEC v7, Codex-R6-1):
--   D1  SOLL 0  - Alt-Writer: TASK_TAG mit COLOR NULL aber SEVERITY_ID gesetzt.
--                 Dank Write-Invariante (Phase 1) nur durch einen uebersehenen
--                 Alt-Backend-Writer erreichbar.
--   D2a SOLL 0  - Extra-Code: aktiv+gueltige DIC_TAG_SEVERITY-Zeile ausserhalb der
--                 5 kanonischen CODEs.
--   D2b SOLL 5  - Vollstaendigkeit: aktiv+gueltige DIC_TAG_SEVERITY-Zeilen mit CODE
--                 IN den 5 kanonischen (faengt einen deaktivierten/abgelaufenen/
--                 fehlenden kanonischen Code, den eine reine NOT-IN-Abfrage uebersaehe).
--
-- GO zu Phase 3 GENAU DANN, wenn D1 = 0 UND D2a = 0 UND D2b = 5.
--
-- "aktiv+gueltig" == ENTITY_STATUS='ACTIVE' AND GUELTIG_VON<=heute AND
--   (GUELTIG_BIS IS NULL OR GUELTIG_BIS>=heute) - identisch zur Resolver-Validity.
-- =============================================================================

-- D1: Alt-Writer (SOLL 0)
SELECT COUNT(*) AS D1_ALT_WRITER
FROM [dbo].[TASK_TAG]
WHERE [COLOR] IS NULL
  AND [SEVERITY_ID] IS NOT NULL;

-- Byte-exakter CODE-Vergleich (identisch zur Java-Map-Key-Semantik
-- TagSeverityColors.BY_CODE und zum V8-Preflight): die Server-Default-Kollation ist
-- SQL_Latin1_General_CP1_CI_AS (case-INSENSITIV) und '=' / IN PADDEN nachgestellte
-- Leerzeichen weg. Ohne Byte-Genauigkeit zaehlte ein 'primary' oder 'PRIMARY '
-- faelschlich als kanonisch (D2a saehe ihn nicht, D2b zaehlte ihn mit) -> GO-Gate
-- und Java-Domaene divergierten. Fail-closed: COLLATE Latin1_General_BIN2 (Case +
-- Leading-Space) UND DATALENGTH-Gleichheit (Trailing-Space). Nur die exakten 5
-- Byte-Folgen gelten als kanonisch.

-- D2a: zusaetzlicher aktiver+gueltiger Nicht-Kanon-Code (SOLL 0)
SELECT COUNT(*) AS D2A_EXTRA_CODE
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

-- D2b: aktiv+gueltige kanonische CODEs (SOLL 5)
SELECT COUNT(*) AS D2B_CANONICAL_ACTIVE
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
