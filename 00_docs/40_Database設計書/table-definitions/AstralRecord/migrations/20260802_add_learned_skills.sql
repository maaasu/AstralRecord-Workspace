SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[account_learned_skill]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[account_learned_skill] (
        [learned_skill_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_learned_skill] PRIMARY KEY,
        [account_id] UNIQUEIDENTIFIER NOT NULL,
        [skill_id] NVARCHAR(128) NOT NULL,
        [level] INT NOT NULL CONSTRAINT [DF_account_learned_skill_level] DEFAULT (1),
        [version] INT NOT NULL CONSTRAINT [DF_account_learned_skill_version] DEFAULT (1),
        [created_at] DATETIME2(3) NOT NULL,
        [updated_at] DATETIME2(3) NOT NULL,
        [created_by] UNIQUEIDENTIFIER NOT NULL,
        [updated_by] UNIQUEIDENTIFIER NOT NULL,
        [is_deleted] BIT NOT NULL CONSTRAINT [DF_account_learned_skill_is_deleted] DEFAULT (0),
        CONSTRAINT [FK_account_learned_skill_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
        CONSTRAINT [CK_account_learned_skill_skill_id_not_blank] CHECK (LEN(LTRIM(RTRIM([skill_id]))) > 0),
        CONSTRAINT [CK_account_learned_skill_level] CHECK ([level] >= 1),
        CONSTRAINT [CK_account_learned_skill_version] CHECK ([version] >= 1)
    );
    CREATE INDEX [IX_account_learned_skill_account_skill]
        ON [dbo].[account_learned_skill] ([account_id], [skill_id], [is_deleted]);
END;

IF OBJECT_ID(N'[dbo].[account_learned_skill_sigil]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[account_learned_skill_sigil] (
        [learned_skill_sigil_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_learned_skill_sigil] PRIMARY KEY,
        [learned_skill_id] UNIQUEIDENTIFIER NOT NULL,
        [sigil_id] NVARCHAR(128) NOT NULL,
        [equip_group_id] NVARCHAR(128) NOT NULL,
        [slot_index] INT NOT NULL,
        [created_at] DATETIME2(3) NOT NULL,
        [updated_at] DATETIME2(3) NOT NULL,
        [created_by] UNIQUEIDENTIFIER NOT NULL,
        [updated_by] UNIQUEIDENTIFIER NOT NULL,
        [is_deleted] BIT NOT NULL CONSTRAINT [DF_account_learned_skill_sigil_is_deleted] DEFAULT (0),
        CONSTRAINT [FK_account_learned_skill_sigil_skill] FOREIGN KEY ([learned_skill_id])
            REFERENCES [dbo].[account_learned_skill] ([learned_skill_id]) ON DELETE CASCADE,
        CONSTRAINT [CK_account_learned_skill_sigil_id_not_blank] CHECK (LEN(LTRIM(RTRIM([sigil_id]))) > 0),
        CONSTRAINT [CK_account_learned_skill_sigil_group_not_blank] CHECK (LEN(LTRIM(RTRIM([equip_group_id]))) > 0),
        CONSTRAINT [CK_account_learned_skill_sigil_slot] CHECK ([slot_index] >= 0)
    );
    CREATE UNIQUE INDEX [UX_account_learned_skill_sigil_group]
        ON [dbo].[account_learned_skill_sigil] ([learned_skill_id], [equip_group_id]) WHERE [is_deleted] = 0;
    CREATE UNIQUE INDEX [UX_account_learned_skill_sigil_slot]
        ON [dbo].[account_learned_skill_sigil] ([learned_skill_id], [slot_index]) WHERE [is_deleted] = 0;
END;

IF OBJECT_ID(N'[dbo].[skill_bind_preset]', N'U') IS NOT NULL
BEGIN
    DELETE FROM [dbo].[skill_bind_preset] WHERE [preset_index] > 6;
    IF OBJECT_ID(N'[dbo].[CK_skill_bind_preset_index]', N'C') IS NOT NULL
        ALTER TABLE [dbo].[skill_bind_preset] DROP CONSTRAINT [CK_skill_bind_preset_index];
    ALTER TABLE [dbo].[skill_bind_preset]
        ADD CONSTRAINT [CK_skill_bind_preset_index] CHECK ([preset_index] BETWEEN 1 AND 6);
END;

IF OBJECT_ID(N'[dbo].[player_mail_delivery]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[player_mail_delivery] (
        [player_mail_delivery_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_player_mail_delivery] PRIMARY KEY,
        [user_id] UNIQUEIDENTIFIER NOT NULL,
        [mail_id] NVARCHAR(128) NOT NULL,
        [payload_json] NVARCHAR(MAX) NOT NULL,
        [version] INT NOT NULL CONSTRAINT [DF_player_mail_delivery_version] DEFAULT (1),
        [created_at] DATETIME2(3) NOT NULL,
        [updated_at] DATETIME2(3) NOT NULL,
        [created_by] UNIQUEIDENTIFIER NOT NULL,
        [updated_by] UNIQUEIDENTIFIER NOT NULL,
        [is_deleted] BIT NOT NULL CONSTRAINT [DF_player_mail_delivery_is_deleted] DEFAULT (0),
        CONSTRAINT [FK_player_mail_delivery_user] FOREIGN KEY ([user_id]) REFERENCES [dbo].[user] ([uuid]),
        CONSTRAINT [CK_player_mail_delivery_mail_id_not_blank] CHECK (LEN(LTRIM(RTRIM([mail_id]))) > 0),
        CONSTRAINT [CK_player_mail_delivery_payload_json] CHECK (ISJSON([payload_json]) = 1),
        CONSTRAINT [CK_player_mail_delivery_version] CHECK ([version] >= 1)
    );
    CREATE UNIQUE INDEX [UX_player_mail_delivery_user_mail]
        ON [dbo].[player_mail_delivery] ([user_id], [mail_id]);
    CREATE INDEX [IX_player_mail_delivery_user_id]
        ON [dbo].[player_mail_delivery] ([user_id]);
    CREATE INDEX [IX_player_mail_delivery_is_deleted]
        ON [dbo].[player_mail_delivery] ([is_deleted]);
END;

COMMIT TRANSACTION;
