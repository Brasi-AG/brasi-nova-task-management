-- =============================================================================
-- V13: Gesehen-Marker SEEN_AT in TASK_USER_STATE (Intake #150)
-- =============================================================================
-- Additiv und nicht brechend: eine neue, nullable Fachspalte. Basisspalten und
-- bestehende Spalten bleiben unberuehrt, kein Backfill (NULL = noch nie als
-- gesehen markiert; dann gelten alle Kommentare anderer als neu).
--
-- SEEN_AT setzt ausschliesslich PUT /api/v1/tasks/{id}/seen. READ_AT bleibt
-- daneben bestehen: GET /api/v1/tasks/{id} setzt READ_AT bei jedem Lesen und
-- taugt deshalb nicht als Gesehen-Marker.
--
-- Zeitbasis: datetime2(7) als lokale Wanduhr der Persistenz-Zone wie READ_AT,
-- UNSUBSCRIBED_AT und die Audit-Spalte ANGELEGT_AM der Kommentare, mit der
-- SEEN_AT verglichen wird (Lesson audit-time-zone). Nach aussen liefert die API
-- den Wert als UTC-Instant.
--
-- Kein Index: gelesen wird SEEN_AT nur ueber die vorhandene Zeile je
-- (Aufgabe, Benutzer) (UQ_TASK_USER_STATE_TASK_USER).
--
-- Versionsnummer: V12 ist fuer ein paralleles Thema (Intake #145) reserviert.
-- #145 (V12) landet vor diesem Thema, beide werden gemeinsam ausgerollt; V13
-- darf nie vor V12 auf eine Datenbank kommen, sonst laesst Flyway V12 ohne
-- outOfOrder nicht mehr regulaer nachziehen.
--
-- Idempotent: die Spalte wird nur angelegt, wenn sie fehlt. V1..V11 bleiben
-- unangetastet (Flyway-Checksum).
-- =============================================================================

IF COL_LENGTH(N'dbo.TASK_USER_STATE', N'SEEN_AT') IS NULL
    ALTER TABLE [dbo].[TASK_USER_STATE] ADD [SEEN_AT] datetime2(7) NULL;
GO
