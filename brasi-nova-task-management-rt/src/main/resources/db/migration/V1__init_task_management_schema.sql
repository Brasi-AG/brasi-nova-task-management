-- =============================================================================
-- V1 — task-management Schema-Initialisierung
-- Legt alle 6 DIC_*-Dictionaries (global, NovaDictionaryEntry-Basis + CODE),
-- 9 Fach-Tabellen (Task, Participant, UserState, Comment, HistoryEntry, Notification,
-- Tag, TagAssignment, ReminderReceipt) an und befuellt die System-Seeds (feste IDs).
--
-- Basis: Nova-Framework Tabellen-Templates (basis.sql).
-- TENANT_ID: DIC_*-Tabellen global (CHECK TENANT_ID=0, kein FIRMA-FK).
--            Fach-Tabellen: FK auf PROD_FIRMA(ID), AUSSER TASK_TAG (global).
-- IDs: keine IDENTITY; KSF-SequenceIdGenerator vergibt via SYS_SEQUENCE (Start 1000000000).
--      Seed-IDs in 100er-Bloecken: STATUS 101-103, PRIORITY 201-204, DIRECTION 301-303,
--      NOTIF_TYPE 401-408, NOTIF_SEVERITY 501-504, TAG_SEVERITY 601-605.
-- Spaltenlaengen: String-Felder folgen dem Entity-@NovaColumn.length (Default 255). Fuer lange
--      Freitext-Felder (TASK.DESCRIPTION, TASK_COMMENT.TEXT) ist bewusst nvarchar(max) gewaehlt
--      (arch-v1: Markdown/Langtext); @NovaColumn kann MAX nicht ausdruecken, ddl-auto=none -> KSF
--      generiert/vergleicht die DDL nie, Lese-/Schreibpfad ist laengenunabhaengig. Enum-Felder =
--      nvarchar(255) (KSF-MsSqlDialect-Enum-Default) + CHECK auf die erlaubten name()-Werte.
-- =============================================================================

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- =============================================================================
-- 1. DIC_TASK_STATUS
-- =============================================================================
CREATE TABLE [dbo].[DIC_TASK_STATUS] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [CODE]             nvarchar(50)     NOT NULL,
    CONSTRAINT [PK_DIC_TASK_STATUS] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_DIC_TASK_STATUS_TENANT_CODE]  UNIQUE ([TENANT_ID], [CODE]),
    CONSTRAINT [UQ_DIC_TASK_STATUS_EXTERNALID]   UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_DIC_TASK_STATUS_GLOBAL]      CHECK  ([TENANT_ID] = 0)
)
GO
ALTER TABLE [dbo].[DIC_TASK_STATUS] ADD DEFAULT ('ACTIVE')                   FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[DIC_TASK_STATUS] ADD DEFAULT (CONVERT(date, getdate()))   FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[DIC_TASK_STATUS] ADD DEFAULT (suser_sname())              FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[DIC_TASK_STATUS] ADD DEFAULT (getdate())                  FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[DIC_TASK_STATUS] ADD DEFAULT (newid())                    FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[DIC_TASK_STATUS] ADD DEFAULT ((1))                        FOR [VERSION]
GO

-- =============================================================================
-- 2. DIC_TASK_PRIORITY
-- =============================================================================
CREATE TABLE [dbo].[DIC_TASK_PRIORITY] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [CODE]             nvarchar(50)     NOT NULL,
    [SORT_ORDER]       int              NULL,
    CONSTRAINT [PK_DIC_TASK_PRIORITY] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_DIC_TASK_PRIORITY_TENANT_CODE] UNIQUE ([TENANT_ID], [CODE]),
    CONSTRAINT [UQ_DIC_TASK_PRIORITY_EXTERNALID]  UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_DIC_TASK_PRIORITY_GLOBAL]     CHECK  ([TENANT_ID] = 0)
)
GO
ALTER TABLE [dbo].[DIC_TASK_PRIORITY] ADD DEFAULT ('ACTIVE')                  FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[DIC_TASK_PRIORITY] ADD DEFAULT (CONVERT(date, getdate()))  FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[DIC_TASK_PRIORITY] ADD DEFAULT (suser_sname())             FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[DIC_TASK_PRIORITY] ADD DEFAULT (getdate())                 FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[DIC_TASK_PRIORITY] ADD DEFAULT (newid())                   FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[DIC_TASK_PRIORITY] ADD DEFAULT ((1))                       FOR [VERSION]
GO

-- =============================================================================
-- 3. DIC_TASK_DIRECTION
-- =============================================================================
CREATE TABLE [dbo].[DIC_TASK_DIRECTION] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [CODE]             nvarchar(50)     NOT NULL,
    CONSTRAINT [PK_DIC_TASK_DIRECTION] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_DIC_TASK_DIRECTION_TENANT_CODE] UNIQUE ([TENANT_ID], [CODE]),
    CONSTRAINT [UQ_DIC_TASK_DIRECTION_EXTERNALID]  UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_DIC_TASK_DIRECTION_GLOBAL]     CHECK  ([TENANT_ID] = 0)
)
GO
ALTER TABLE [dbo].[DIC_TASK_DIRECTION] ADD DEFAULT ('ACTIVE')                  FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[DIC_TASK_DIRECTION] ADD DEFAULT (CONVERT(date, getdate()))  FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[DIC_TASK_DIRECTION] ADD DEFAULT (suser_sname())             FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[DIC_TASK_DIRECTION] ADD DEFAULT (getdate())                 FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[DIC_TASK_DIRECTION] ADD DEFAULT (newid())                   FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[DIC_TASK_DIRECTION] ADD DEFAULT ((1))                       FOR [VERSION]
GO

-- =============================================================================
-- 4. DIC_NOTIFICATION_TYPE
-- =============================================================================
CREATE TABLE [dbo].[DIC_NOTIFICATION_TYPE] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [CODE]             nvarchar(50)     NOT NULL,
    CONSTRAINT [PK_DIC_NOTIFICATION_TYPE] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_DIC_NOTIFICATION_TYPE_TENANT_CODE] UNIQUE ([TENANT_ID], [CODE]),
    CONSTRAINT [UQ_DIC_NOTIFICATION_TYPE_EXTERNALID]  UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_DIC_NOTIFICATION_TYPE_GLOBAL]     CHECK  ([TENANT_ID] = 0)
)
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_TYPE] ADD DEFAULT ('ACTIVE')                  FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_TYPE] ADD DEFAULT (CONVERT(date, getdate()))  FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_TYPE] ADD DEFAULT (suser_sname())             FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_TYPE] ADD DEFAULT (getdate())                 FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_TYPE] ADD DEFAULT (newid())                   FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_TYPE] ADD DEFAULT ((1))                       FOR [VERSION]
GO

-- =============================================================================
-- 5. DIC_NOTIFICATION_SEVERITY
-- =============================================================================
CREATE TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [CODE]             nvarchar(50)     NOT NULL,
    CONSTRAINT [PK_DIC_NOTIFICATION_SEVERITY] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_DIC_NOTIFICATION_SEVERITY_TENANT_CODE] UNIQUE ([TENANT_ID], [CODE]),
    CONSTRAINT [UQ_DIC_NOTIFICATION_SEVERITY_EXTERNALID]  UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_DIC_NOTIFICATION_SEVERITY_GLOBAL]     CHECK  ([TENANT_ID] = 0)
)
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] ADD DEFAULT ('ACTIVE')                  FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] ADD DEFAULT (CONVERT(date, getdate()))  FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] ADD DEFAULT (suser_sname())             FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] ADD DEFAULT (getdate())                 FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] ADD DEFAULT (newid())                   FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[DIC_NOTIFICATION_SEVERITY] ADD DEFAULT ((1))                       FOR [VERSION]
GO

-- =============================================================================
-- 6. DIC_TAG_SEVERITY
-- =============================================================================
CREATE TABLE [dbo].[DIC_TAG_SEVERITY] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [CODE]             nvarchar(50)     NOT NULL,
    CONSTRAINT [PK_DIC_TAG_SEVERITY] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_DIC_TAG_SEVERITY_TENANT_CODE] UNIQUE ([TENANT_ID], [CODE]),
    CONSTRAINT [UQ_DIC_TAG_SEVERITY_EXTERNALID]  UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_DIC_TAG_SEVERITY_GLOBAL]     CHECK  ([TENANT_ID] = 0)
)
GO
ALTER TABLE [dbo].[DIC_TAG_SEVERITY] ADD DEFAULT ('ACTIVE')                  FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[DIC_TAG_SEVERITY] ADD DEFAULT (CONVERT(date, getdate()))  FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[DIC_TAG_SEVERITY] ADD DEFAULT (suser_sname())             FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[DIC_TAG_SEVERITY] ADD DEFAULT (getdate())                 FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[DIC_TAG_SEVERITY] ADD DEFAULT (newid())                   FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[DIC_TAG_SEVERITY] ADD DEFAULT ((1))                       FOR [VERSION]
GO

-- =============================================================================
-- 7. TASK_TAG (globaler user-verwalteter Tag-Katalog; NovaDictionaryEntry)
--    Kein eigenes CODE-Feld (entryValue = BEZEICHNUNG = Tag-Name).
-- =============================================================================
CREATE TABLE [dbo].[TASK_TAG] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [BEZEICHNUNG]      nvarchar(200)    NOT NULL,
    [ENTRY_TYPE]       nvarchar(255)    NOT NULL,
    [GUELTIG_VON]      date             NOT NULL,
    [GUELTIG_BIS]      date             NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten: FK auf globalen Tag-Severity-Dictionary
    [SEVERITY_ID]      bigint           NOT NULL,
    CONSTRAINT [PK_TASK_TAG] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_TAG_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [CHK_TASK_TAG_GLOBAL]    CHECK  ([TENANT_ID] = 0),
    CONSTRAINT [FK_TASK_TAG_Severity]   FOREIGN KEY ([SEVERITY_ID])
        REFERENCES [dbo].[DIC_TAG_SEVERITY] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_TAG] ADD DEFAULT ('ACTIVE')                  FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_TAG] ADD DEFAULT (CONVERT(date, getdate()))  FOR [GUELTIG_VON]
GO
ALTER TABLE [dbo].[TASK_TAG] ADD DEFAULT (suser_sname())             FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_TAG] ADD DEFAULT (getdate())                 FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_TAG] ADD DEFAULT (newid())                   FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_TAG] ADD DEFAULT ((1))                       FOR [VERSION]
GO

-- =============================================================================
-- 8. TASK
-- =============================================================================
CREATE TABLE [dbo].[TASK] (
    [ID]                  bigint           NOT NULL,
    [ENTITY_STATUS]       nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]        nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON]    nvarchar(255)    NULL,
    [GELOESCHT_VON]       nvarchar(255)    NULL,
    [ANGELEGT_AM]         datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]     datetime2(7)     NULL,
    [GELOESCHT_AM]        datetime2(7)     NULL,
    [EXTERNALID]          uniqueidentifier NOT NULL,
    [VERSION]             int              NOT NULL,
    [TENANT_ID]           bigint           NOT NULL,
    -- Fachliche Spalten
    [TITLE]               nvarchar(500)    NOT NULL,
    [DESCRIPTION]         nvarchar(max)    NULL,
    [TASK_STATUS_ID]      bigint           NOT NULL,
    [PRIORITY_ID]         bigint           NOT NULL,
    [DIRECTION_ID]        bigint           NOT NULL,
    [ORIGIN_TENANT_ID]    bigint           NOT NULL,
    [START_DATE]          date             NULL,
    [DUE_DATE]            date             NULL,
    [LAST_ACTIVITY_AT]    datetime2(7)     NOT NULL,
    [COMMENT_COUNT]       int              NOT NULL,
    [SOURCE_MODULE]       nvarchar(100)    NULL,
    [SOURCE_ENTITY_TYPE]  nvarchar(100)    NULL,
    [SOURCE_ENTITY_REF]   uniqueidentifier NULL,
    CONSTRAINT [PK_TASK] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_EXTERNALID]      UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_Firma]           FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_TaskStatus]      FOREIGN KEY ([TASK_STATUS_ID])
        REFERENCES [dbo].[DIC_TASK_STATUS] ([ID]),
    CONSTRAINT [FK_TASK_TaskPriority]    FOREIGN KEY ([PRIORITY_ID])
        REFERENCES [dbo].[DIC_TASK_PRIORITY] ([ID]),
    CONSTRAINT [FK_TASK_TaskDirection]   FOREIGN KEY ([DIRECTION_ID])
        REFERENCES [dbo].[DIC_TASK_DIRECTION] ([ID])
)
GO
ALTER TABLE [dbo].[TASK] ADD DEFAULT ('ACTIVE')   FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK] ADD DEFAULT (getdate())  FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK] ADD DEFAULT (newid())    FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK] ADD DEFAULT ((1))        FOR [VERSION]
GO
ALTER TABLE [dbo].[TASK] ADD DEFAULT ((0))        FOR [COMMENT_COUNT]
GO

-- Index fuer Inbox-Paginierung (arch-v3 §3.2)
CREATE INDEX [IX_TASK_INBOX]
    ON [dbo].[TASK] ([TENANT_ID], [TASK_STATUS_ID], [LAST_ACTIVITY_AT] DESC, [EXTERNALID] DESC)
GO

-- =============================================================================
-- 9. TASK_PARTICIPANT
-- =============================================================================
CREATE TABLE [dbo].[TASK_PARTICIPANT] (
    [ID]                   bigint           NOT NULL,
    [ENTITY_STATUS]        nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]         nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON]     nvarchar(255)    NULL,
    [GELOESCHT_VON]        nvarchar(255)    NULL,
    [ANGELEGT_AM]          datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]      datetime2(7)     NULL,
    [GELOESCHT_AM]         datetime2(7)     NULL,
    [EXTERNALID]           uniqueidentifier NOT NULL,
    [VERSION]              int              NOT NULL,
    [TENANT_ID]            bigint           NOT NULL,
    -- Fachliche Spalten
    [TASK_ID]              bigint           NOT NULL,
    [PARTICIPANT_REF]      uniqueidentifier NOT NULL,
    -- Enum-Spalten: nvarchar(255) = KSF-MsSqlDialect-Default fuer Enum-Felder (@NovaColumn ohne
    -- length-Override). Wertebereich zusaetzlich per CHECK eingeengt.
    [PARTICIPANT_KIND]     nvarchar(255)    NOT NULL,
    [PARTICIPANT_TENANT_ID] bigint          NOT NULL,
    [CREATOR_FLAG]         bit              NOT NULL,
    [ASSIGNEE_FLAG]        bit              NOT NULL,
    [PARTICIPANT_FLAG]     bit              NOT NULL,
    [EXIT_KIND]            nvarchar(255)    NULL,
    [EXIT_AT]              datetime2(7)     NULL,
    CONSTRAINT [PK_TASK_PARTICIPANT] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_PARTICIPANT_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_PARTICIPANT_Firma]      FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [CHK_TASK_PARTICIPANT_KIND]
        CHECK ([PARTICIPANT_KIND] IN ('PERSON', 'TEAM')),
    CONSTRAINT [CHK_TASK_PARTICIPANT_EXIT_KIND]
        CHECK ([EXIT_KIND] IS NULL OR [EXIT_KIND] IN ('HANDOVER', 'UNSUBSCRIBED'))
)
GO
ALTER TABLE [dbo].[TASK_PARTICIPANT] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_PARTICIPANT] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_PARTICIPANT] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_PARTICIPANT] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_PARTICIPANT] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- =============================================================================
-- 10. TASK_USER_STATE
-- =============================================================================
CREATE TABLE [dbo].[TASK_USER_STATE] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [TASK_ID]          bigint           NOT NULL,
    [USER_REF]         uniqueidentifier NOT NULL,
    [USER_TENANT_ID]   bigint           NOT NULL,
    [IS_PINNED]        bit              NOT NULL,
    [IS_FLAGGED]       bit              NOT NULL,
    [IS_MUTED]         bit              NOT NULL,
    [READ_AT]          datetime2(7)     NULL,
    [UNSUBSCRIBED_AT]  datetime2(7)     NULL,
    CONSTRAINT [PK_TASK_USER_STATE] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_USER_STATE_EXTERNALID]   UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_USER_STATE_TASK_USER]    UNIQUE ([TASK_ID], [USER_TENANT_ID], [USER_REF]),
    CONSTRAINT [FK_TASK_USER_STATE_Firma]         FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_USER_STATE] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_USER_STATE] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_USER_STATE] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_USER_STATE] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_USER_STATE] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- Index fuer benutzer-scoped Inbox-Archiv-Query (arch-v2 §2.1)
CREATE INDEX [IX_TASK_USER_STATE_USER]
    ON [dbo].[TASK_USER_STATE] ([TENANT_ID], [USER_REF], [UNSUBSCRIBED_AT], [TASK_ID])
GO

-- =============================================================================
-- 11. TASK_COMMENT
-- =============================================================================
CREATE TABLE [dbo].[TASK_COMMENT] (
    [ID]                bigint           NOT NULL,
    [ENTITY_STATUS]     nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]      nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON]  nvarchar(255)    NULL,
    [GELOESCHT_VON]     nvarchar(255)    NULL,
    [ANGELEGT_AM]       datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]   datetime2(7)     NULL,
    [GELOESCHT_AM]      datetime2(7)     NULL,
    [EXTERNALID]        uniqueidentifier NOT NULL,
    [VERSION]           int              NOT NULL,
    [TENANT_ID]         bigint           NOT NULL,
    -- Fachliche Spalten
    [TASK_ID]           bigint           NOT NULL,
    [AUTHOR_REF]        uniqueidentifier NOT NULL,
    [PARENT_COMMENT_ID] bigint           NULL,
    [TEXT]              nvarchar(max)    NOT NULL,
    [IS_EDITED]         bit              NOT NULL,
    CONSTRAINT [PK_TASK_COMMENT] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_COMMENT_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_COMMENT_Firma]      FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_COMMENT] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_COMMENT] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_COMMENT] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_COMMENT] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_COMMENT] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- =============================================================================
-- 12. TASK_HISTORY_ENTRY
-- =============================================================================
CREATE TABLE [dbo].[TASK_HISTORY_ENTRY] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [TASK_ID]          bigint           NOT NULL,
    [ACTOR_REF]        uniqueidentifier NOT NULL,
    [TEXT]             nvarchar(500)    NOT NULL,
    [CHANGE_DETAIL]    nvarchar(500)    NULL,
    CONSTRAINT [PK_TASK_HISTORY_ENTRY] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_HISTORY_ENTRY_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_HISTORY_ENTRY_Firma]      FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_HISTORY_ENTRY] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_HISTORY_ENTRY] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_HISTORY_ENTRY] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_HISTORY_ENTRY] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_HISTORY_ENTRY] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- =============================================================================
-- 13. TASK_NOTIFICATION
-- =============================================================================
CREATE TABLE [dbo].[TASK_NOTIFICATION] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [RECIPIENT_REF]    uniqueidentifier NOT NULL,
    [TASK_ID]          bigint           NULL,
    [TYPE_ID]          bigint           NOT NULL,
    [IS_READ]          bit              NOT NULL,
    [ACTOR_REF]        uniqueidentifier NULL,
    [TEXT]             nvarchar(500)    NOT NULL,
    [PREVIEW]          nvarchar(500)    NULL,
    [SEVERITY_ID]      bigint           NULL,
    CONSTRAINT [PK_TASK_NOTIFICATION] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_NOTIFICATION_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_NOTIFICATION_Firma]      FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_NOTIFICATION_Type]       FOREIGN KEY ([TYPE_ID])
        REFERENCES [dbo].[DIC_NOTIFICATION_TYPE] ([ID]),
    CONSTRAINT [FK_TASK_NOTIFICATION_Severity]   FOREIGN KEY ([SEVERITY_ID])
        REFERENCES [dbo].[DIC_NOTIFICATION_SEVERITY] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_NOTIFICATION] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_NOTIFICATION] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_NOTIFICATION] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_NOTIFICATION] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_NOTIFICATION] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- =============================================================================
-- 14. TASK_TAG_ASSIGNMENT
-- =============================================================================
CREATE TABLE [dbo].[TASK_TAG_ASSIGNMENT] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [TASK_ID]          bigint           NOT NULL,
    [TAG_ID]           bigint           NOT NULL,
    CONSTRAINT [PK_TASK_TAG_ASSIGNMENT] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_TAG_ASSIGNMENT_EXTERNALID]    UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_TAG_ASSIGNMENT_TASK_TAG]      UNIQUE ([TASK_ID], [TAG_ID]),
    CONSTRAINT [FK_TASK_TAG_ASSIGNMENT_Firma]         FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_TAG_ASSIGNMENT_Tag]           FOREIGN KEY ([TAG_ID])
        REFERENCES [dbo].[TASK_TAG] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_TAG_ASSIGNMENT] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_TAG_ASSIGNMENT] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_TAG_ASSIGNMENT] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_TAG_ASSIGNMENT] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_TAG_ASSIGNMENT] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- =============================================================================
-- 15. TASK_REMINDER_RECEIPT
-- =============================================================================
CREATE TABLE [dbo].[TASK_REMINDER_RECEIPT] (
    [ID]               bigint           NOT NULL,
    [ENTITY_STATUS]    nvarchar(255)    NOT NULL,
    [ANGELEGT_VON]     nvarchar(255)    NOT NULL,
    [AKTUALISIERT_VON] nvarchar(255)    NULL,
    [GELOESCHT_VON]    nvarchar(255)    NULL,
    [ANGELEGT_AM]      datetime2(7)     NOT NULL,
    [AKTUALISIERT_AM]  datetime2(7)     NULL,
    [GELOESCHT_AM]     datetime2(7)     NULL,
    [EXTERNALID]       uniqueidentifier NOT NULL,
    [VERSION]          int              NOT NULL,
    [TENANT_ID]        bigint           NOT NULL,
    -- Fachliche Spalten
    [TASK_ID]          bigint           NOT NULL,
    [RECIPIENT_REF]    uniqueidentifier NOT NULL,
    [OCCURRENCE_DATE]  date             NOT NULL,
    CONSTRAINT [PK_TASK_REMINDER_RECEIPT] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_REMINDER_RECEIPT_EXTERNALID]    UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_REMINDER_IDEMPOTENZ]
        UNIQUE ([TENANT_ID], [TASK_ID], [RECIPIENT_REF], [OCCURRENCE_DATE]),
    CONSTRAINT [FK_TASK_REMINDER_RECEIPT_Firma]  FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_REMINDER_RECEIPT] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_REMINDER_RECEIPT] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_REMINDER_RECEIPT] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_REMINDER_RECEIPT] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_REMINDER_RECEIPT] ADD DEFAULT ((1))           FOR [VERSION]
GO

-- =============================================================================
-- SEEDS
-- Deterministische Ids (100er-Bloecke, kollidieren nicht mit KSF-Sequenz >=1000000000).
-- TENANT_ID=0 (global), ENTRY_TYPE='SYSTEM_PUBLIC', ENTITY_STATUS='ACTIVE'.
-- BEZEICHNUNG = CODE-Wert (editierbares Label).
-- Fail-loud bei Kollision (UNIQUE-/PK-Verletzung, kein IF-NOT-EXISTS-Schlucken).
-- =============================================================================

-- --- DIC_TASK_STATUS (101-103) ---
INSERT INTO [dbo].[DIC_TASK_STATUS]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
VALUES
    (101, 'ACTIVE', 'OPEN',      'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '11111101-0000-0000-0000-000000000000', 1, 0, 'OPEN'),
    (102, 'ACTIVE', 'DONE',      'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '11111102-0000-0000-0000-000000000000', 1, 0, 'DONE'),
    (103, 'ACTIVE', 'CANCELLED', 'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '11111103-0000-0000-0000-000000000000', 1, 0, 'CANCELLED')
GO

-- --- DIC_TASK_PRIORITY (201-204) ---
INSERT INTO [dbo].[DIC_TASK_PRIORITY]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE], [SORT_ORDER])
VALUES
    (201, 'ACTIVE', 'LOW',    'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '22222201-0000-0000-0000-000000000000', 1, 0, 'LOW',    1),
    (202, 'ACTIVE', 'NORMAL', 'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '22222202-0000-0000-0000-000000000000', 1, 0, 'NORMAL', 2),
    (203, 'ACTIVE', 'HIGH',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '22222203-0000-0000-0000-000000000000', 1, 0, 'HIGH',   3),
    (204, 'ACTIVE', 'URGENT', 'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '22222204-0000-0000-0000-000000000000', 1, 0, 'URGENT', 4)
GO

-- --- DIC_TASK_DIRECTION (301-303) ---
INSERT INTO [dbo].[DIC_TASK_DIRECTION]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
VALUES
    (301, 'ACTIVE', 'BRASI_CUSTOMER',     'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '33333301-0000-0000-0000-000000000000', 1, 0, 'BRASI_CUSTOMER'),
    (302, 'ACTIVE', 'CUSTOMER_INTERNAL',  'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '33333302-0000-0000-0000-000000000000', 1, 0, 'CUSTOMER_INTERNAL'),
    (303, 'ACTIVE', 'INTER_COMPANY',      'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '33333303-0000-0000-0000-000000000000', 1, 0, 'INTER_COMPANY')
GO

-- --- DIC_NOTIFICATION_TYPE (401-408) ---
INSERT INTO [dbo].[DIC_NOTIFICATION_TYPE]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
VALUES
    (401, 'ACTIVE', 'ASSIGN',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444401-0000-0000-0000-000000000000', 1, 0, 'ASSIGN'),
    (402, 'ACTIVE', 'REPLY',    'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444402-0000-0000-0000-000000000000', 1, 0, 'REPLY'),
    (403, 'ACTIVE', 'DUE',      'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444403-0000-0000-0000-000000000000', 1, 0, 'DUE'),
    (404, 'ACTIVE', 'MENTION',  'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444404-0000-0000-0000-000000000000', 1, 0, 'MENTION'),
    (405, 'ACTIVE', 'DONE',     'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444405-0000-0000-0000-000000000000', 1, 0, 'DONE'),
    (406, 'ACTIVE', 'CHANGE',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444406-0000-0000-0000-000000000000', 1, 0, 'CHANGE'),
    (407, 'ACTIVE', 'HANDOVER', 'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444407-0000-0000-0000-000000000000', 1, 0, 'HANDOVER'),
    (408, 'ACTIVE', 'SYSTEM',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '44444408-0000-0000-0000-000000000000', 1, 0, 'SYSTEM')
GO

-- --- DIC_NOTIFICATION_SEVERITY (501-504) ---
INSERT INTO [dbo].[DIC_NOTIFICATION_SEVERITY]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
VALUES
    (501, 'ACTIVE', 'INFO',    'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '55555501-0000-0000-0000-000000000000', 1, 0, 'INFO'),
    (502, 'ACTIVE', 'SUCCESS', 'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '55555502-0000-0000-0000-000000000000', 1, 0, 'SUCCESS'),
    (503, 'ACTIVE', 'WARN',    'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '55555503-0000-0000-0000-000000000000', 1, 0, 'WARN'),
    (504, 'ACTIVE', 'ERROR',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '55555504-0000-0000-0000-000000000000', 1, 0, 'ERROR')
GO

-- --- DIC_TAG_SEVERITY (601-605) ---
INSERT INTO [dbo].[DIC_TAG_SEVERITY]
    ([ID], [ENTITY_STATUS], [BEZEICHNUNG], [ENTRY_TYPE], [GUELTIG_VON], [GUELTIG_BIS],
     [ANGELEGT_VON], [ANGELEGT_AM], [EXTERNALID], [VERSION], [TENANT_ID], [CODE])
VALUES
    (601, 'ACTIVE', 'PRIMARY',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '66666601-0000-0000-0000-000000000000', 1, 0, 'PRIMARY'),
    (602, 'ACTIVE', 'SUCCESS',   'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '66666602-0000-0000-0000-000000000000', 1, 0, 'SUCCESS'),
    (603, 'ACTIVE', 'WARN',      'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '66666603-0000-0000-0000-000000000000', 1, 0, 'WARN'),
    (604, 'ACTIVE', 'DANGER',    'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '66666604-0000-0000-0000-000000000000', 1, 0, 'DANGER'),
    (605, 'ACTIVE', 'SECONDARY', 'SYSTEM_PUBLIC', CONVERT(date, getdate()), NULL,
     'flyway-v1', getdate(), '66666605-0000-0000-0000-000000000000', 1, 0, 'SECONDARY')
GO
