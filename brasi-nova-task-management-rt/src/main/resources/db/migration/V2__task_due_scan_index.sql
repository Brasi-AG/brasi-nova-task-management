-- =============================================================================
-- V2: Index fuer die mandantenuebergreifende Due-Reminder-Kandidatensuche (Task 16).
--
-- Der DueReminderWorker ruft TaskKeysetQueryRepository.findDueOpenTasks (@GlobalQuery) auf:
--   WHERE DUE_DATE = :today AND TASK_STATUS_ID = :openStatusId AND ENTITY_STATUS = 'ACTIVE'
-- Diese Abfrage hat KEINEN TENANT_ID-Praedikat (global, system-scope) und laeuft - wenn der
-- Scheduler scharf ist - je Tick. Der V1-Index (TENANT_ID, TASK_STATUS_ID, LAST_ACTIVITY_AT,
-- EXTERNALID) traegt DUE_DATE nicht als fuehrenden Schluessel -> ohne diesen Index muesste SQL
-- Server bei wachsendem Volumen wiederholt die ganze Tabelle scannen.
--
-- DUE_DATE als fuehrender Schluessel macht den taeglichen Scan seek-bar; TASK_STATUS_ID grenzt
-- auf OPEN ein. ENTITY_STATUS bleibt Residual-Praedikat (bewusst kein gefilterter Index, um
-- SET-Option-Abhaengigkeiten bei DML zu vermeiden).
-- =============================================================================
CREATE NONCLUSTERED INDEX [IX_TASK_DUE_SCAN]
    ON [dbo].[TASK] ([DUE_DATE], [TASK_STATUS_ID])
GO
