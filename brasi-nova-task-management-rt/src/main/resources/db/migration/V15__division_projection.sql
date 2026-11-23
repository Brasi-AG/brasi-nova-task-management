-- =============================================================================
-- V15 — TASK_ABTEILUNG + TASK_ABTEILUNG_MITGLIED + TASK_EREIGNIS_QUITTUNG (Intake #190,
-- lokale Projektion der Abteilungsmitglieder fuer @Abteilung-Erwaehnungen)
--
-- TASK_ABTEILUNG: Kopf der Projektion je Mandant und Abteilung. ABTEILUNG_REF ist die UUID der
--   Division in application. NAME bleibt leer, bis application einen Anzeigenamen liefert.
--   REVISION haelt die revision des uebernommenen Stands (leer, wenn der letzte Abruf 404 war).
--   ABGERUFEN_AM ist die Startzeit des Abrufs, der den Stand lieferte; ein spaeterer Abruf
--   gewinnt. ENTITY_STATUS ACTIVE = Abteilung in application aktiv; DEACTIVE = letzter Abruf 404
--   (unbekannt, fremd oder inaktiv), dann ohne Mitglieder.
-- TASK_ABTEILUNG_MITGLIED: Mitglieder einer Abteilung mit den Anzeigedaten fuer die Empfaenger-
--   aufloesung. MITARBEITER_REF ist die Benutzer-UUID (employeeId), KERNMITGLIED das Kennzeichen
--   coreMembership. Die Mitglieder einer Abteilung werden bei jedem neuen Stand physisch ersetzt
--   (DELETE, dann Insert): die Zeilen sind abgeleitete Daten ohne fachliche Historie; ein
--   Soft-Delete liesse bei jedem Resync Leichen wachsen und braeche den Unique-Schluessel.
-- TASK_EREIGNIS_QUITTUNG: Inbox der verarbeiteten Ereignisse (Idempotenz nach
--   NovaEventIdempotencyStore). MANDANT_UUID ist der Scope (tenantId des Ereignisses),
--   EREIGNIS_ID die novaEventId. ABTEILUNG_REF setzt nur der Division-Consumer. STATUS
--   PROCESSED = verarbeitet, DEAD = endgueltig verworfen mit FEHLER_MELDUNG.
--
-- Schluessel und Indizes:
--   UQ_TASK_ABTEILUNG_REF (TENANT_ID, ABTEILUNG_REF): eine Projektion je Mandant und Abteilung,
--     zugleich Zugriffspfad beim Nachschlagen per Abteilungs-UUID.
--   IX_TASK_ABTEILUNG_STATUS (TENANT_ID, ENTITY_STATUS): aktive Abteilungen eines Mandanten
--     (Auswahl fuer Erwaehnungen und Resync).
--   UQ_TASK_ABTEILUNG_MITGLIED (ABTEILUNG_ID, MITARBEITER_REF): jede Person hoechstens einmal je
--     Abteilung.
--   IX_TASK_ABTEILUNG_MITGLIED_ABT (TENANT_ID, ABTEILUNG_ID): Mitglieder einer Abteilung lesen und
--     mandantengebunden loeschen.
--   UQ_TASK_EREIGNIS_QUITTUNG_EREIGNIS (MANDANT_UUID, EREIGNIS_ID): ein Ereignis wird je Mandant
--     genau einmal quittiert.
--   IX_TASK_EREIGNIS_QUITTUNG_ANGELEGT (ANGELEGT_AM): Aufbewahrungsbereinigung alter Quittungen.
--
-- Basis: Nova-Framework Tabellen-Template, Basisspalten und Defaults wie TASK_ANHANG in V14
-- (Vorlage TASK_SOURCE_CREATE_RECEIPT in V6). Enum-Spalte STATUS = nvarchar(255)
-- (KSF-Enum-Default) plus CHECK auf die name()-Werte. FK TENANT_ID -> PROD_FIRMA(ID) an allen
-- drei Tabellen, ABTEILUNG_ID -> TASK_ABTEILUNG(ID).
-- =============================================================================

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

CREATE TABLE [dbo].[TASK_ABTEILUNG] (
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
    [ABTEILUNG_REF]    uniqueidentifier NOT NULL,
    [NAME]             nvarchar(255)    NULL,
    [REVISION]         char(64)         NULL,
    [ABGERUFEN_AM]     datetime2(7)     NOT NULL,
    CONSTRAINT [PK_TASK_ABTEILUNG] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_ABTEILUNG_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_ABTEILUNG_REF] UNIQUE ([TENANT_ID], [ABTEILUNG_REF]),
    CONSTRAINT [FK_TASK_ABTEILUNG_Firma] FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG] ADD DEFAULT ((1))           FOR [VERSION]
GO

CREATE NONCLUSTERED INDEX [IX_TASK_ABTEILUNG_STATUS]
    ON [dbo].[TASK_ABTEILUNG] ([TENANT_ID], [ENTITY_STATUS])
GO

CREATE TABLE [dbo].[TASK_ABTEILUNG_MITGLIED] (
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
    [ABTEILUNG_ID]     bigint           NOT NULL,
    [MITARBEITER_REF]  uniqueidentifier NOT NULL,
    [ANZEIGENAME]      nvarchar(255)    NULL,
    [VORNAME]          nvarchar(255)    NULL,
    [NACHNAME]         nvarchar(255)    NULL,
    [LOGIN_NAME]       nvarchar(255)    NULL,
    [KERNMITGLIED]     bit              NOT NULL,
    CONSTRAINT [PK_TASK_ABTEILUNG_MITGLIED] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_ABTEILUNG_MITGLIED_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_ABTEILUNG_MITGLIED] UNIQUE ([ABTEILUNG_ID], [MITARBEITER_REF]),
    CONSTRAINT [FK_TASK_ABTEILUNG_MITGLIED_Firma] FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [FK_TASK_ABTEILUNG_MITGLIED_Abteilung] FOREIGN KEY ([ABTEILUNG_ID])
        REFERENCES [dbo].[TASK_ABTEILUNG] ([ID])
)
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG_MITGLIED] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG_MITGLIED] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG_MITGLIED] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG_MITGLIED] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_ABTEILUNG_MITGLIED] ADD DEFAULT ((1))           FOR [VERSION]
GO

CREATE NONCLUSTERED INDEX [IX_TASK_ABTEILUNG_MITGLIED_ABT]
    ON [dbo].[TASK_ABTEILUNG_MITGLIED] ([TENANT_ID], [ABTEILUNG_ID])
GO

CREATE TABLE [dbo].[TASK_EREIGNIS_QUITTUNG] (
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
    [MANDANT_UUID]     uniqueidentifier NOT NULL,
    [EREIGNIS_ID]      uniqueidentifier NOT NULL,
    [ABTEILUNG_REF]    uniqueidentifier NULL,
    [STATUS]           nvarchar(255)    NOT NULL,
    [FEHLER_MELDUNG]   nvarchar(900)    NULL,
    CONSTRAINT [PK_TASK_EREIGNIS_QUITTUNG] PRIMARY KEY CLUSTERED ([ID] ASC),
    CONSTRAINT [UQ_TASK_EREIGNIS_QUITTUNG_EXTERNALID] UNIQUE ([EXTERNALID]),
    CONSTRAINT [UQ_TASK_EREIGNIS_QUITTUNG_EREIGNIS] UNIQUE ([MANDANT_UUID], [EREIGNIS_ID]),
    CONSTRAINT [FK_TASK_EREIGNIS_QUITTUNG_Firma] FOREIGN KEY ([TENANT_ID])
        REFERENCES [dbo].[PROD_FIRMA] ([ID]),
    CONSTRAINT [CHK_TASK_EREIGNIS_QUITTUNG_STATUS]
        CHECK ([STATUS] IN ('PROCESSED', 'DEAD'))
)
GO
ALTER TABLE [dbo].[TASK_EREIGNIS_QUITTUNG] ADD DEFAULT ('ACTIVE')      FOR [ENTITY_STATUS]
GO
ALTER TABLE [dbo].[TASK_EREIGNIS_QUITTUNG] ADD DEFAULT (suser_sname()) FOR [ANGELEGT_VON]
GO
ALTER TABLE [dbo].[TASK_EREIGNIS_QUITTUNG] ADD DEFAULT (getdate())     FOR [ANGELEGT_AM]
GO
ALTER TABLE [dbo].[TASK_EREIGNIS_QUITTUNG] ADD DEFAULT (newid())       FOR [EXTERNALID]
GO
ALTER TABLE [dbo].[TASK_EREIGNIS_QUITTUNG] ADD DEFAULT ((1))           FOR [VERSION]
GO

CREATE NONCLUSTERED INDEX [IX_TASK_EREIGNIS_QUITTUNG_ANGELEGT]
    ON [dbo].[TASK_EREIGNIS_QUITTUNG] ([ANGELEGT_AM])
GO
