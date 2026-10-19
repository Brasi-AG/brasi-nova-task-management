-- =============================================================================
-- V4 — TASK_INBOX_GENERATION
-- Monotoner Generationszaehler je (Mandant, Benutzer) fuer die Inbox-Snapshot-Rejection
-- (Finding 2 / Option B). Standard-Nova-Entity (NovaEntity-Basis) + USER_REF + GENERATION.
--
-- Basis: Nova-Framework Tabellen-Template (wie TASK_USER_STATE in V1).
-- UNIQUE(TENANT_ID, USER_REF): genau eine Zaehlerzeile je Benutzer im Mandanten.
-- GENERATION bigint NOT NULL DEFAULT 0; +1 je destruktiver Membership-Mutation.
-- Keine Seeds: die Zeile wird lazy beim ersten Bump angelegt (Start 0).
-- =============================================================================

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

CREATE TABLE [dbo].[TASK_INBOX_GENERATION] (
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
    [USER_REF]         uniqueidentifier NOT NULL,
    [GENERATION]       bigint           NOT NULL,
    CONSTRAINT [PK_TASK_INBOX_GENERATION] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_INBOX_GENERATION_EXTERNALID]  UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_INBOX_GENERATION_TENANT_USER] UNIQUE ([TENANT_ID], [USER_REF]),
    CONSTRAINT [FK_TASK_INBOX_GENERATION_Firma]       FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_INBOX_GENERATION] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_INBOX_GENERATION] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_INBOX_GENERATION] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_INBOX_GENERATION] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_INBOX_GENERATION] ADD DEFAULT ((1))           FOR [VERSION]
GO
ALTER TABLE [dbo].[TASK_INBOX_GENERATION] ADD DEFAULT ((0))           FOR [GENERATION]
GO

-- Lookup-/UNIQUE-Pfad (TENANT_ID, USER_REF) ist durch UQ_..._TENANT_USER bereits index-gedeckt.
