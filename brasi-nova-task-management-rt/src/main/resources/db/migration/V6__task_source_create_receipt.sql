-- A source-create receipt is inserted in the same transaction as its Task and all create effects.
-- The unique scope is the external tenant UUID, current actor UUID and exact admitted ASCII key.
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

CREATE TABLE [dbo].[TASK_SOURCE_CREATE_RECEIPT] (
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
    [TENANT_UUID]      uniqueidentifier NOT NULL,
    [ACTOR_REF]        uniqueidentifier NOT NULL,
    [IDEMPOTENCY_KEY]  nvarchar(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
    [INPUT_SHA256]     char(64)         NOT NULL,
    [TASK_UUID]        uniqueidentifier NOT NULL,
    CONSTRAINT [PK_TASK_SOURCE_CREATE_RECEIPT] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_SOURCE_CREATE_RECEIPT_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_SOURCE_CREATE_RECEIPT_SCOPE]
        UNIQUE ([TENANT_UUID], [ACTOR_REF], [IDEMPOTENCY_KEY]),
    CONSTRAINT [FK_TASK_SOURCE_CREATE_RECEIPT_Firma] FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_SOURCE_CREATE_RECEIPT_Task] FOREIGN KEY ([TASK_UUID])
        REFERENCES [dbo].[TASK] ([EXTERNALID])
)
GO
ALTER TABLE [dbo].[TASK_SOURCE_CREATE_RECEIPT] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_SOURCE_CREATE_RECEIPT] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_SOURCE_CREATE_RECEIPT] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_SOURCE_CREATE_RECEIPT] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_SOURCE_CREATE_RECEIPT] ADD DEFAULT ((1))           FOR [VERSION]
GO
