-- =============================================================================
-- V3: Teilnehmer-Eindeutigkeit (Final-Review P2-6) + Audit-Query-Indizes
--     (Pre-Release-Audit P1/P5).
--
-- 1. UQ_TASK_PARTICIPANT_TASK_USER - UNIQUE(TASK_ID, PARTICIPANT_TENANT_ID, PARTICIPANT_REF):
--    Bislang garantierte nur die Service-Logik (AssigneeService toggelt EXIT_KIND auf GENAU einer
--    Zeile je (Task, Teilnehmer) statt eine zweite anzulegen) die Invariante "max. eine Zeile je
--    (Task, Benutzer)". Ohne DB-Constraint koennen zwei nebenlaeufige updateAssignees beide "kein
--    Participant" sehen und doppelt einfuegen -> doppelte aktive Assignees + doppelte
--    Notifications. Der UNIQUE erzwingt die Invariante hart (spiegelt UQ_TASK_USER_STATE_TASK_USER
--    aus V1). Der zweite nebenlaeufige INSERT scheitert dann als UNIQUE-Verletzung (KSF -> 409).
--    Datenbestand-Preflight (dev-backend-DB brasi-nova-task-management-backend, 2026-10-18):
--    TASK_PARTICIPANT existiert dort noch nicht (Schema nie migriert, kein Altbestand).
--    Upgrade-Sicherheit (Codex P1): die Pre-V3-updateAssignees-Logik (check-then-insert) KONNTE
--    unter Nebenlaeufigkeit Mehrfachzeilen je (Task, Benutzer) erzeugen. Damit die Migration auf
--    JEDER bestehenden V1/V2-DB durchlaeuft (sonst bricht das ADD CONSTRAINT ab und auch die
--    Audit-Indizes unten entstehen nicht), werden Duplikate ZUERST deterministisch bereinigt:
--    je Gruppe bleibt genau eine Zeile erhalten (Creator vor aktiv vor Assignee vor kleinster ID),
--    die uebrigen werden entfernt. Auf einer frischen/leeren DB ist das ein No-Op. Ein gefilterter
--    UNIQUE (nur aktive Zeilen) wird bewusst NICHT genutzt - die Codebase vermeidet gefilterte
--    Indizes wegen der SET-Option-Abhaengigkeiten bei DML (siehe V2-Kommentar).
--    Der plain UNIQUE fuehrt mit TASK_ID und macht damit zugleich die Teilnehmer-Reads
--    (findByTaskIdAndParticipantRef, findActiveAssigneesByTaskId, Archiv-EXISTS, Scheduler-
--    Assignee-Read) seek-bar - ein zusaetzlicher (TENANT_ID, TASK_ID, PARTICIPANT_REF)-Index waere
--    bei kleiner Teilnehmerzahl je Aufgabe redundant und erzeugt nur Schreib-Overhead.
--
-- 2. IX_TASK_NOTIFICATION_RECIPIENT (Audit P1): jeder Notification-Read (Inbox-Snapshot + jede
--    Seite/Poll) filtert TENANT_ID + RECIPIENT_REF und sortiert ANGELEGT_AM DESC, EXTERNALID DESC
--    (Keyset). Ohne Index Full-Scan + Sort. Deckt "all" (Ordering) und "unread" (IS_READ als
--    Residual-Praedikat) ab.
--
-- 3. IX_TASK_HISTORY_ENTRY_TASK (Audit P5): Task-Detail-Open liest die Historie je TASK_ID
--    (ANGELEGT_AM DESC). FK TASK_ID war unindiziert -> Full-Scan bei Volumen.
-- =============================================================================

-- Upgrade-Reconciliation (Codex P1): vorhandene Duplikate je (TASK_ID, PARTICIPANT_TENANT_ID,
-- PARTICIPANT_REF) bereinigen, bevor der UNIQUE greift. Je Gruppe bleibt genau eine Zeile.
-- Zwei Schritte, damit WEDER Sichtbarkeit NOCH Rollen-Semantik verloren geht:
--   (1) MERGE: die Rollen-Flags (CREATOR/ASSIGNEE/PARTICIPANT) der GESAMTEN Gruppe werden in die
--       ueberlebende Zeile uebernommen (bitweises ODER via MAX(CONVERT(int,...))). Damit geht die
--       Creator-Immobilitaet (AssigneeService.updateAssignees/exit stuetzen sich auf CREATOR_FLAG)
--       auch dann nicht verloren, wenn die Creator-Zeile selbst nicht die ueberlebende ist. (In der
--       Praxis ist CREATOR_FLAG je (Task, Benutzer) ohnehin konstant - derselbe Benutzer ist in
--       derselben Aufgabe entweder Ersteller oder nicht; der Merge ist Defense-in-Depth gegen
--       inkonsistente Altbestaende.)
--   (2) DELETE: alle Nicht-Ueberlebenden entfernen.
-- Survivor-Auswahl (in BEIDEN Schritten identisch, bewusst UNABHAENGIG von den in (1) gemergten
-- Flags): ZUERST ENTITY_STATUS='ACTIVE' (alle Teilnehmer-Reads filtern ACTIVE -> eine sichtbare
-- Zeile muss ueberleben), dann nicht-ausgetreten (EXIT_KIND NULL), dann kleinste ID. No-Op auf
-- leerer/frischer DB. TASK_PARTICIPANT.ID wird von keiner Tabelle per FK referenziert -> Hard-Delete
-- sicher.
;WITH [ranked] AS (
    SELECT [ID],
           ROW_NUMBER() OVER (
               PARTITION BY [TASK_ID], [PARTICIPANT_TENANT_ID], [PARTICIPANT_REF]
               ORDER BY CASE WHEN [ENTITY_STATUS] = 'ACTIVE' THEN 0 ELSE 1 END,
                        CASE WHEN [EXIT_KIND] IS NULL THEN 0 ELSE 1 END,
                        [ID]
           ) AS [rn]
    FROM [dbo].[TASK_PARTICIPANT]
)
UPDATE [p]
SET [p].[CREATOR_FLAG]     = [agg].[max_creator],
    [p].[ASSIGNEE_FLAG]    = [agg].[max_assignee],
    [p].[PARTICIPANT_FLAG] = [agg].[max_participant]
FROM [dbo].[TASK_PARTICIPANT] [p]
INNER JOIN [ranked] [r] ON [r].[ID] = [p].[ID] AND [r].[rn] = 1
INNER JOIN (
    SELECT [TASK_ID], [PARTICIPANT_TENANT_ID], [PARTICIPANT_REF],
           MAX(CONVERT(int, [CREATOR_FLAG]))     AS [max_creator],
           MAX(CONVERT(int, [ASSIGNEE_FLAG]))    AS [max_assignee],
           MAX(CONVERT(int, [PARTICIPANT_FLAG])) AS [max_participant]
    FROM [dbo].[TASK_PARTICIPANT]
    GROUP BY [TASK_ID], [PARTICIPANT_TENANT_ID], [PARTICIPANT_REF]
    HAVING COUNT(*) > 1
) [agg]
    ON  [agg].[TASK_ID]              = [p].[TASK_ID]
    AND [agg].[PARTICIPANT_TENANT_ID] = [p].[PARTICIPANT_TENANT_ID]
    AND [agg].[PARTICIPANT_REF]      = [p].[PARTICIPANT_REF]
GO

;WITH [ranked] AS (
    SELECT [ID],
           ROW_NUMBER() OVER (
               PARTITION BY [TASK_ID], [PARTICIPANT_TENANT_ID], [PARTICIPANT_REF]
               ORDER BY CASE WHEN [ENTITY_STATUS] = 'ACTIVE' THEN 0 ELSE 1 END,
                        CASE WHEN [EXIT_KIND] IS NULL THEN 0 ELSE 1 END,
                        [ID]
           ) AS [rn]
    FROM [dbo].[TASK_PARTICIPANT]
)
DELETE FROM [ranked] WHERE [rn] > 1
GO

ALTER TABLE [dbo].[TASK_PARTICIPANT]
    ADD CONSTRAINT [UQ_TASK_PARTICIPANT_TASK_USER]
        UNIQUE ([TASK_ID], [PARTICIPANT_TENANT_ID], [PARTICIPANT_REF])
GO

CREATE NONCLUSTERED INDEX [IX_TASK_NOTIFICATION_RECIPIENT]
    ON [dbo].[TASK_NOTIFICATION]
        ([TENANT_ID], [RECIPIENT_REF], [ANGELEGT_AM] DESC, [EXTERNALID] DESC)
GO

CREATE NONCLUSTERED INDEX [IX_TASK_HISTORY_ENTRY_TASK]
    ON [dbo].[TASK_HISTORY_ENTRY]
        ([TENANT_ID], [TASK_ID], [ANGELEGT_AM] DESC)
GO
