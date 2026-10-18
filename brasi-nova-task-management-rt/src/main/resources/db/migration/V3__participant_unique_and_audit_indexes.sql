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
--    TASK_PARTICIPANT existiert dort noch nicht (Schema nie migriert, kein Altbestand) -> der
--    plain UNIQUE ist sicher; ein gefilterter Index (nur aktive/nicht-ausgetretene Zeilen) ist
--    nicht noetig, da die Exit-/Reassign-Logik keine Mehrfachzeilen je (Task, Benutzer) erzeugt.
--    Der UNIQUE fuehrt mit TASK_ID und macht damit zugleich die Teilnehmer-Reads
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
