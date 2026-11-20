-- =============================================================================
-- V14 — TASK_ANHANG + TASK_ANHANG_GRENZE (Intake #152, Anhaenge an Aufgaben)
--
-- TASK_ANHANG: Metadaten je hochgeladener Datei an einer Aufgabe (KOMMENTAR_ID NULL) oder an
--   einem Kommentar (KOMMENTAR_ID gesetzt). Die Datei selbst liegt im Dateisystem unter dem
--   Basispfad (Config-Server-Eigenschaft); SPEICHERSCHLUESSEL ist der relative Pfad dorthin.
--   SPEICHERSCHLUESSEL ist nullable: KSF vergibt die ID erst beim Insert, der Schluessel wird aus
--   der ID gebildet und in derselben Transaktion per Update nachgetragen. Committet wird nur der
--   vollstaendige Zustand mit Schluessel; eine Zeile ohne Schluessel ist nach dem Commit nie
--   sichtbar. HAT_VORSCHAU haelt fest, ob beim Hochladen ein Vorschaubild entstanden ist
--   (Vorgabe 0). Entfernen setzt ENTITY_STATUS auf DEACTIVE, die Datei bleibt (kein physisches
--   Loeschen). CHECK GROESSE_BYTES > 0: leere Dateien sind keine gueltigen Anhaenge.
-- TASK_ANHANG_GRENZE: Tarif je Mandant fuer die Dateigroessen-Grenze (STANDARD, EXPERT,
--   ENTERPRISE) mit optionaler eigener Obergrenze MAX_DATEI_BYTES (NULL = Obergrenze der
--   Tarifspanne, sonst > 0). Hoechstens eine aktive Zeile je Mandant (gefilterter Unique-Index);
--   deaktivierte Zeilen bleiben als Verlauf stehen. Kein Seed: ohne Zeile gilt STANDARD.
--
-- Indizes:
--   IX_TASK_ANHANG_TASK (TENANT_ID, TASK_ID, ENTITY_STATUS): Liste der aktiven Anhaenge einer
--     Aufgabe und Zaehlung gegen die Hoechstzahl je Aufgabe.
--   IX_TASK_ANHANG_KOMMENTAR (TENANT_ID, KOMMENTAR_ID): Anhaenge je Kommentar (Kommentarliste
--     und Hoechstzahl je Kommentar).
--   UX_TASK_ANHANG_GRENZE_TENANT_ACTIVE (TENANT_ID) WHERE ENTITY_STATUS = 'ACTIVE': genau eine
--     gueltige Grenze je Mandant, zugleich Zugriffspfad fuer das Nachschlagen des Tarifs.
--
-- Basis: Nova-Framework Tabellen-Template, Basisspalten und Defaults wie TASK_SOURCE_CREATE_RECEIPT
-- in V6. Enum-Spalte TARIF = nvarchar(255) (KSF-Enum-Default) plus CHECK auf die name()-Werte.
-- FK TENANT_ID -> PROD_FIRMA(ID), TASK_ID -> TASK(ID), KOMMENTAR_ID -> TASK_COMMENT(ID).
-- =============================================================================

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

CREATE TABLE [dbo].[TASK_ANHANG] (
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
    [TASK_ID]             bigint           NOT NULL,
    [KOMMENTAR_ID]        bigint           NULL,
    [DATEINAME]           nvarchar(255)    NOT NULL,
    [MIME_TYP]            nvarchar(255)    NOT NULL,
    [DATEIENDUNG]         nvarchar(10)     NOT NULL,
    [GROESSE_BYTES]       bigint           NOT NULL,
    [SHA256]              char(64)         NOT NULL,
    [SPEICHERSCHLUESSEL]  nvarchar(400)    NULL,
    [HAT_VORSCHAU]        bit              NOT NULL,
    [HOCHGELADEN_VON_REF] uniqueidentifier NOT NULL,
    CONSTRAINT [PK_TASK_ANHANG] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_ANHANG_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_ANHANG_Firma] FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_ANHANG_Task] FOREIGN KEY ([TASK_ID])
        REFERENCES [dbo].[TASK] ([ID]),
    CONSTRAINT [FK_TASK_ANHANG_Kommentar] FOREIGN KEY ([KOMMENTAR_ID])
        REFERENCES [dbo].[TASK_COMMENT] ([ID]),
    CONSTRAINT [CHK_TASK_ANHANG_GROESSE] CHECK ([GROESSE_BYTES] > 0)
)
GO
ALTER TABLE [dbo].[TASK_ANHANG] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_ANHANG] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_ANHANG] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_ANHANG] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_ANHANG] ADD DEFAULT ((1))           FOR [VERSION]
GO
ALTER TABLE [dbo].[TASK_ANHANG] ADD DEFAULT ((0))           FOR [HAT_VORSCHAU]
GO

CREATE NONCLUSTERED INDEX [IX_TASK_ANHANG_TASK]
    ON [dbo].[TASK_ANHANG] ([TENANT_ID], [TASK_ID], [ENTITY_STATUS])
GO

CREATE NONCLUSTERED INDEX [IX_TASK_ANHANG_KOMMENTAR]
    ON [dbo].[TASK_ANHANG] ([TENANT_ID], [KOMMENTAR_ID])
GO

CREATE TABLE [dbo].[TASK_ANHANG_GRENZE] (
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
    [TARIF]            nvarchar(255)    NOT NULL,
    [MAX_DATEI_BYTES]  bigint           NULL,
    CONSTRAINT [PK_TASK_ANHANG_GRENZE] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_ANHANG_GRENZE_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [FK_TASK_ANHANG_GRENZE_Firma] FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [CHK_TASK_ANHANG_GRENZE_TARIF]
        CHECK ([TARIF] IN ('STANDARD', 'EXPERT', 'ENTERPRISE')),
    CONSTRAINT [CHK_TASK_ANHANG_GRENZE_MAX]
        CHECK ([MAX_DATEI_BYTES] IS NULL OR [MAX_DATEI_BYTES] > 0)
)
GO
ALTER TABLE [dbo].[TASK_ANHANG_GRENZE] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_ANHANG_GRENZE] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_ANHANG_GRENZE] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_ANHANG_GRENZE] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_ANHANG_GRENZE] ADD DEFAULT ((1))           FOR [VERSION]
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_TASK_ANHANG_GRENZE_TENANT_ACTIVE]
    ON [dbo].[TASK_ANHANG_GRENZE] ([TENANT_ID])
    WHERE [ENTITY_STATUS] = 'ACTIVE'
GO
