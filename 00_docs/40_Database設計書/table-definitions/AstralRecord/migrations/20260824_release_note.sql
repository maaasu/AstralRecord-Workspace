USE [AstralRecord];
GO

SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[release_note]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[release_note] (
        [release_note_id]   UNIQUEIDENTIFIER NOT NULL,
        [slug]              NVARCHAR(80)     NOT NULL,
        [version]           NVARCHAR(64)     NOT NULL,
        [title]             NVARCHAR(200)    NOT NULL,
        [summary]           NVARCHAR(500)    NOT NULL,
        [release_url]       NVARCHAR(512)    NOT NULL,
        [source_path]       NVARCHAR(260)    NOT NULL,
        [content_sha256]    NVARCHAR(64)     NOT NULL,
        [published_at_utc]  DATETIME2(3)     NOT NULL,
        [is_published]      BIT              NOT NULL,
        [notify_discord]    BIT              NOT NULL,
        [created_at_utc]    DATETIME2(3)     NOT NULL,
        [updated_at_utc]    DATETIME2(3)     NOT NULL,

        CONSTRAINT [PK_release_note] PRIMARY KEY CLUSTERED ([release_note_id]),
        CONSTRAINT [CK_release_note_slug_not_blank] CHECK (LEN(LTRIM(RTRIM([slug]))) > 0),
        CONSTRAINT [CK_release_note_url_not_blank] CHECK (LEN(LTRIM(RTRIM([release_url]))) > 0),
        CONSTRAINT [CK_release_note_sha256] CHECK ([content_sha256] LIKE N'[0-9A-Fa-f]' + REPLICATE(N'[0-9A-Fa-f]', 63))
    );

    CREATE UNIQUE NONCLUSTERED INDEX [UX_release_note_slug]
        ON [dbo].[release_note] ([slug]);

    CREATE NONCLUSTERED INDEX [IX_release_note_published_at]
        ON [dbo].[release_note] ([is_published], [published_at_utc]);
END;

IF OBJECT_ID(N'[dbo].[release_notification_outbox]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[release_notification_outbox] (
        [outbox_id]            UNIQUEIDENTIFIER NOT NULL,
        [release_note_id]      UNIQUEIDENTIFIER NOT NULL,
        [channel]              NVARCHAR(50)     NOT NULL,
        [status]               INT              NOT NULL CONSTRAINT [DF_release_notification_outbox_status] DEFAULT (0),
        [attempt_count]        INT              NOT NULL CONSTRAINT [DF_release_notification_outbox_attempt_count] DEFAULT (0),
        [next_attempt_at_utc]  DATETIME2(3)     NOT NULL,
        [lease_until_utc]      DATETIME2(3)         NULL,
        [lease_token]          UNIQUEIDENTIFIER     NULL,
        [sent_at_utc]          DATETIME2(3)         NULL,
        [discord_message_id]   NVARCHAR(64)         NULL,
        [last_error]           NVARCHAR(2000)        NULL,
        [created_at_utc]       DATETIME2(3)     NOT NULL,
        [updated_at_utc]       DATETIME2(3)     NOT NULL,

        CONSTRAINT [PK_release_notification_outbox] PRIMARY KEY CLUSTERED ([outbox_id]),
        CONSTRAINT [FK_release_notification_outbox_note] FOREIGN KEY ([release_note_id])
            REFERENCES [dbo].[release_note] ([release_note_id]) ON DELETE CASCADE ON UPDATE NO ACTION,
        CONSTRAINT [CK_release_notification_outbox_channel_not_blank] CHECK (LEN(LTRIM(RTRIM([channel]))) > 0),
        CONSTRAINT [CK_release_notification_outbox_status] CHECK ([status] IN (0, 1, 2, 3)),
        CONSTRAINT [CK_release_notification_outbox_attempt_count] CHECK ([attempt_count] >= 0)
    );

    CREATE UNIQUE NONCLUSTERED INDEX [UX_release_notification_outbox_note_channel]
        ON [dbo].[release_notification_outbox] ([release_note_id], [channel]);

    CREATE NONCLUSTERED INDEX [IX_release_notification_outbox_due]
        ON [dbo].[release_notification_outbox] ([channel], [status], [next_attempt_at_utc]);
END;

COMMIT TRANSACTION;
GO
