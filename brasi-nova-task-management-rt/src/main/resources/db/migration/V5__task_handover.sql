-- =============================================================================
-- V5 — TASK_HANDOVER + TASK_HANDOVER_TARGET
-- Uebergabe-Ereignismodell fuer Handover (BE-7): handedOverToIds + shouldStay.
--
-- TASK_HANDOVER: ein Ereignis je Uebergabe (auch wiederholte). Ereigniszeit = Nova-ANGELEGT_AM.
--   KEIN (TASK_ID, FROM_REF)-UNIQUE (wiederholte Uebergaben = eigene Ereignisse).
-- TASK_HANDOVER_TARGET: die Uebergabeziele (-> Assignees) je Ereignis.
--   UNIQUE(HANDOVER_ID, TO_REF) verhindert doppelte Ziele im selben Ereignis.
--
-- Basis: Nova-Framework Tabellen-Template (wie TASK_INBOX_GENERATION in V4). FK TENANT_ID->PROD_FIRMA,
-- FK HANDOVER_ID->TASK_HANDOVER(ID). Index A7 fuer den Read-Batch
-- (TENANT_ID, TASK_ID, FROM_REF, SHOULD_STAY, ANGELEGT_AM, ID).
-- =============================================================================

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

CREATE TABLE [dbo].[TASK_HANDOVER] (
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
    [FROM_REF]         uniqueidentifier NOT NULL,
    [SHOULD_STAY]      bit              NOT NULL,
    CONSTRAINT [PK_TASK_HANDOVER] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_HANDOVER_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_HANDOVER_Firma]      FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_HANDOVER] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_HANDOVER] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_HANDOVER] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_HANDOVER] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_HANDOVER] ADD DEFAULT ((1))           FOR [VERSION]
GO
ALTER TABLE [dbo].[TASK_HANDOVER] ADD DEFAULT ((0))           FOR [SHOULD_STAY]
GO

-- A7: Read-Batch-Index (Event-Auswahl je Task: FROM_REF + SHOULD_STAY=false + ANGELEGT_AM<=asOf,
-- Tie-Break ID). Reihenfolge deckt das Praedikat (TENANT_ID, TASK_ID, FROM_REF, SHOULD_STAY) +
-- die Ordnung (ANGELEGT_AM, ID).
CREATE INDEX [IX_TASK_HANDOVER_READ]
    ON [dbo].[TASK_HANDOVER] ([TENANT_ID], [TASK_ID], [FROM_REF], [SHOULD_STAY], [ANGELEGT_AM], [ID])
GO

CREATE TABLE [dbo].[TASK_HANDOVER_TARGET] (
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
    [HANDOVER_ID]      bigint           NOT NULL,
    [TO_REF]           uniqueidentifier NOT NULL,
    CONSTRAINT [PK_TASK_HANDOVER_TARGET] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_HANDOVER_TARGET_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_HANDOVER_TARGET_EVENT_TO]   UNIQUE ([HANDOVER_ID], [TO_REF]),
    CONSTRAINT [FK_TASK_HANDOVER_TARGET_Firma]      FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_HANDOVER_TARGET_Handover]   FOREIGN KEY ([HANDOVER_ID])
        REFERENCES [dbo].[TASK_HANDOVER] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_HANDOVER_TARGET] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_HANDOVER_TARGET] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_HANDOVER_TARGET] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_HANDOVER_TARGET] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_HANDOVER_TARGET] ADD DEFAULT ((1))           FOR [VERSION]
GO
