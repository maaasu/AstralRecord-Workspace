-- ============================================================
-- AstralRecord initialization script
-- Generated from 00_docs/40_Database設計書/table-definitions
-- ============================================================

USE [master];
GO

IF DB_ID(N'AstralRecord') IS NULL
BEGIN
    CREATE DATABASE [AstralRecord];
END
GO

USE [AstralRecord];
GO

-- ============================================================
-- AstralRecord\dbo.user.md
-- ============================================================

-- STEP 1: dbo.user は account_id を NULL 許容で作成
CREATE TABLE [dbo].[user] (
    [uuid]             UNIQUEIDENTIFIER  NOT NULL,
    [mcid]             NVARCHAR(20)      NOT NULL,
    [join_date]        DATETIME2(3)      NOT NULL,
    [last_join_date]   DATETIME2(3)      NOT NULL,
    [global_ip]        NVARCHAR(45)      NOT NULL,
    [account_id]       UNIQUEIDENTIFIER      NULL,  -- 初回 INSERT 時は NULL、account 作成後に UPDATE
    [ban_indefinite]   BIT               NOT NULL  CONSTRAINT [DF_user_ban_indefinite]  DEFAULT (0),
    [ban_date]         DATETIME2(0)          NULL,
    [kick_ip]          BIT               NOT NULL  CONSTRAINT [DF_user_kick_ip]          DEFAULT (1),
    [permission]       INT               NOT NULL  CONSTRAINT [DF_user_permission]        DEFAULT (0),
    [created_at]       DATETIME2(3)      NOT NULL,
    [updated_at]       DATETIME2(3)      NOT NULL,
    [created_by]       UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]       UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]       BIT               NOT NULL  CONSTRAINT [DF_user_is_deleted]       DEFAULT (0),

    CONSTRAINT [PK_user] PRIMARY KEY CLUSTERED ([uuid]),
    CONSTRAINT [CK_user_permission] CHECK ([permission] IN (0, 99))
);
GO

CREATE NONCLUSTERED INDEX [IX_user_uuid]
    ON [dbo].[user] ([uuid]);
GO

CREATE NONCLUSTERED INDEX [IX_user_global_ip]
    ON [dbo].[user] ([global_ip]);
GO

-- STEP 3: dbo.account 作成後にインデックスを追加
CREATE NONCLUSTERED INDEX [IX_user_account_id]
    ON [dbo].[user] ([account_id]);
GO

-- ============================================================
-- AstralRecord\dbo.user_setting.md
-- ============================================================

CREATE TABLE [dbo].[user_setting] (
    [user_setting_id]    UNIQUEIDENTIFIER  NOT NULL,
    [user_id]            UNIQUEIDENTIFIER  NOT NULL,
    [setting_key]        NVARCHAR(100)     NOT NULL,
    [setting_value_json] NVARCHAR(MAX)     NOT NULL,
    [version]            INT               NOT NULL  CONSTRAINT [DF_user_setting_version]    DEFAULT (1),
    [created_at]         DATETIME2(3)      NOT NULL,
    [updated_at]         DATETIME2(3)      NOT NULL,
    [created_by]         UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]         BIT               NOT NULL  CONSTRAINT [DF_user_setting_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_user_setting] PRIMARY KEY CLUSTERED ([user_setting_id]),
    CONSTRAINT [FK_user_setting_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_user_setting_setting_key_not_blank] CHECK (LEN(LTRIM(RTRIM([setting_key]))) > 0),
    CONSTRAINT [CK_user_setting_value_json] CHECK (ISJSON([setting_value_json]) = 1),
    CONSTRAINT [CK_user_setting_version] CHECK ([version] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_user_setting_user_id]
    ON [dbo].[user_setting] ([user_id]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_user_setting_user_key_active]
    ON [dbo].[user_setting] ([user_id], [setting_key])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_user_setting_is_deleted]
    ON [dbo].[user_setting] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.player_mail_state.md
-- ============================================================

CREATE TABLE [dbo].[player_mail_state] (
    [player_mail_state_id] UNIQUEIDENTIFIER NOT NULL,
    [user_id]              UNIQUEIDENTIFIER NOT NULL,
    [mail_id]              NVARCHAR(100)    NOT NULL,
    [is_read]              BIT              NOT NULL CONSTRAINT [DF_player_mail_state_is_read] DEFAULT (0),
    [read_at]              DATETIME2(3)         NULL,
    [version]              INT              NOT NULL CONSTRAINT [DF_player_mail_state_version] DEFAULT (1),
    [created_at]           DATETIME2(3)     NOT NULL,
    [updated_at]           DATETIME2(3)     NOT NULL,
    [created_by]           UNIQUEIDENTIFIER NOT NULL,
    [updated_by]           UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]           BIT              NOT NULL CONSTRAINT [DF_player_mail_state_is_deleted] DEFAULT (0),
    [deleted_at]           DATETIME2(3)         NULL,

    CONSTRAINT [PK_player_mail_state] PRIMARY KEY CLUSTERED ([player_mail_state_id]),
    CONSTRAINT [FK_player_mail_state_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_player_mail_state_mail_id_not_blank] CHECK (LEN(LTRIM(RTRIM([mail_id]))) > 0),
    CONSTRAINT [CK_player_mail_state_version] CHECK ([version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_player_mail_state_user_mail]
    ON [dbo].[player_mail_state] ([user_id], [mail_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_state_user_id]
    ON [dbo].[player_mail_state] ([user_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_state_mail_id]
    ON [dbo].[player_mail_state] ([mail_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_state_is_deleted]
    ON [dbo].[player_mail_state] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.player_mail_delivery.md
-- ============================================================

CREATE TABLE [dbo].[player_mail_delivery] (
    [player_mail_delivery_id] UNIQUEIDENTIFIER NOT NULL,
    [user_id]                 UNIQUEIDENTIFIER NOT NULL,
    [mail_id]                 NVARCHAR(128)    NOT NULL,
    [payload_json]            NVARCHAR(MAX)    NOT NULL,
    [version]                 INT              NOT NULL CONSTRAINT [DF_player_mail_delivery_version] DEFAULT (1),
    [created_at]              DATETIME2(3)     NOT NULL,
    [updated_at]              DATETIME2(3)     NOT NULL,
    [created_by]              UNIQUEIDENTIFIER NOT NULL,
    [updated_by]              UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]              BIT              NOT NULL CONSTRAINT [DF_player_mail_delivery_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_player_mail_delivery] PRIMARY KEY CLUSTERED ([player_mail_delivery_id]),
    CONSTRAINT [FK_player_mail_delivery_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_player_mail_delivery_mail_id_not_blank] CHECK (LEN(LTRIM(RTRIM([mail_id]))) > 0),
    CONSTRAINT [CK_player_mail_delivery_payload_json] CHECK (ISJSON([payload_json]) = 1),
    CONSTRAINT [CK_player_mail_delivery_version] CHECK ([version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_player_mail_delivery_user_mail]
    ON [dbo].[player_mail_delivery] ([user_id], [mail_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_delivery_user_id]
    ON [dbo].[player_mail_delivery] ([user_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_delivery_is_deleted]
    ON [dbo].[player_mail_delivery] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account.md
-- ============================================================

CREATE TABLE [dbo].[account] (
    [uuid]           UNIQUEIDENTIFIER  NOT NULL,
    [user_id]        UNIQUEIDENTIFIER  NOT NULL,
    [account_name]   NVARCHAR(50)      NOT NULL,
    [slot_index]     INT               NOT NULL,
    [is_active]      BIT               NOT NULL  CONSTRAINT [DF_account_is_active]   DEFAULT (0),
    [mode]           TINYINT           NOT NULL  CONSTRAINT [DF_account_mode]         DEFAULT (0),
    [menu_shortcuts_json] NVARCHAR(MAX) NOT NULL  CONSTRAINT [DF_account_menu_shortcuts_json] DEFAULT (N'["STATUS","NONE","INVENTORY_CURRENCY","EQUIPMENT_GUI"]'),
    [level]          INT               NOT NULL  CONSTRAINT [DF_account_level]        DEFAULT (1),
    [total_experience] BIGINT          NOT NULL  CONSTRAINT [DF_account_total_experience] DEFAULT (0),
    [class_id]       NVARCHAR(100)     NOT NULL  CONSTRAINT [DF_account_class_id]      DEFAULT (N'adventurer'),
    [class_level]    INT               NOT NULL  CONSTRAINT [DF_account_class_level]   DEFAULT (1),
    [class_experience] BIGINT          NOT NULL  CONSTRAINT [DF_account_class_experience] DEFAULT (0),
    [created_at]     DATETIME2(3)      NOT NULL,
    [updated_at]     DATETIME2(3)      NOT NULL,
    [created_by]     UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]     UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]     BIT               NOT NULL  CONSTRAINT [DF_account_is_deleted]  DEFAULT (0),

    CONSTRAINT [PK_account] PRIMARY KEY CLUSTERED ([uuid]),
    CONSTRAINT [FK_account_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_account_user_slot] UNIQUE ([user_id], [slot_index]),
    CONSTRAINT [CK_account_mode] CHECK ([mode] IN (0, 2)),
    CONSTRAINT [CK_account_menu_shortcuts_json] CHECK (ISJSON([menu_shortcuts_json]) = 1),
    CONSTRAINT [CK_account_level] CHECK ([level] >= 1),
    CONSTRAINT [CK_account_total_experience] CHECK ([total_experience] >= 0),
    CONSTRAINT [CK_account_class_id_not_blank] CHECK (LEN(LTRIM(RTRIM([class_id]))) > 0),
    CONSTRAINT [CK_account_class_level] CHECK ([class_level] >= 1),
    CONSTRAINT [CK_account_class_experience] CHECK ([class_experience] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_user_id]
    ON [dbo].[account] ([user_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_is_deleted]
    ON [dbo].[account] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account_class_progress.md
-- ============================================================

CREATE TABLE [dbo].[account_class_progress] (
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [class_id]   NVARCHAR(100)    NOT NULL,
    [level]      INT              NOT NULL CONSTRAINT [DF_account_class_progress_level] DEFAULT (1),
    [experience] BIGINT           NOT NULL CONSTRAINT [DF_account_class_progress_experience] DEFAULT (0),
    [updated_at] DATETIME2(3)     NOT NULL,
    [updated_by] UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_class_progress] PRIMARY KEY CLUSTERED ([account_id], [class_id]),
    CONSTRAINT [FK_account_class_progress_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_class_progress_class_id_not_blank]
        CHECK (LEN(LTRIM(RTRIM([class_id]))) > 0),
    CONSTRAINT [CK_account_class_progress_level] CHECK ([level] >= 1),
    CONSTRAINT [CK_account_class_progress_experience] CHECK ([experience] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_class_progress_class_id]
    ON [dbo].[account_class_progress] ([class_id]);
GO

-- ============================================================
-- AstralRecord\dbo.account_learned_skill.md
-- ============================================================

CREATE TABLE [dbo].[account_learned_skill] (
    [learned_skill_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]       UNIQUEIDENTIFIER NOT NULL,
    [skill_id]         NVARCHAR(128)    NOT NULL,
    [level]            INT              NOT NULL CONSTRAINT [DF_account_learned_skill_level] DEFAULT (1),
    [version]          INT              NOT NULL CONSTRAINT [DF_account_learned_skill_version] DEFAULT (1),
    [created_at]       DATETIME2(3)     NOT NULL,
    [updated_at]       DATETIME2(3)     NOT NULL,
    [created_by]       UNIQUEIDENTIFIER NOT NULL,
    [updated_by]       UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]       BIT              NOT NULL CONSTRAINT [DF_account_learned_skill_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_learned_skill] PRIMARY KEY CLUSTERED ([learned_skill_id]),
    CONSTRAINT [FK_account_learned_skill_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_learned_skill_skill_id_not_blank] CHECK (LEN(LTRIM(RTRIM([skill_id]))) > 0),
    CONSTRAINT [CK_account_learned_skill_level] CHECK ([level] >= 1),
    CONSTRAINT [CK_account_learned_skill_version] CHECK ([version] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_learned_skill_account_skill]
    ON [dbo].[account_learned_skill] ([account_id], [skill_id], [is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account_learned_skill_sigil.md
-- ============================================================

CREATE TABLE [dbo].[account_learned_skill_sigil] (
    [learned_skill_sigil_id] UNIQUEIDENTIFIER NOT NULL,
    [learned_skill_id]       UNIQUEIDENTIFIER NOT NULL,
    [sigil_id]               NVARCHAR(128)    NOT NULL,
    [equip_group_id]         NVARCHAR(128)    NOT NULL,
    [slot_index]             INT              NOT NULL,
    [created_at]             DATETIME2(3)     NOT NULL,
    [updated_at]             DATETIME2(3)     NOT NULL,
    [created_by]             UNIQUEIDENTIFIER NOT NULL,
    [updated_by]             UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]             BIT              NOT NULL CONSTRAINT [DF_account_learned_skill_sigil_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_learned_skill_sigil] PRIMARY KEY CLUSTERED ([learned_skill_sigil_id]),
    CONSTRAINT [FK_account_learned_skill_sigil_skill] FOREIGN KEY ([learned_skill_id])
        REFERENCES [dbo].[account_learned_skill] ([learned_skill_id]) ON DELETE CASCADE ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_learned_skill_sigil_id_not_blank] CHECK (LEN(LTRIM(RTRIM([sigil_id]))) > 0),
    CONSTRAINT [CK_account_learned_skill_sigil_group_not_blank] CHECK (LEN(LTRIM(RTRIM([equip_group_id]))) > 0),
    CONSTRAINT [CK_account_learned_skill_sigil_slot] CHECK ([slot_index] >= 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_learned_skill_sigil_group]
    ON [dbo].[account_learned_skill_sigil] ([learned_skill_id], [equip_group_id])
    WHERE [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_learned_skill_sigil_slot]
    ON [dbo].[account_learned_skill_sigil] ([learned_skill_id], [slot_index])
    WHERE [is_deleted] = 0;
GO

-- 既存 account の現在クラス進行度をクラス別進行度の初期値として引き継ぐ。
INSERT INTO [dbo].[account_class_progress] (
    [account_id], [class_id], [level], [experience], [updated_at], [updated_by]
)
SELECT
    [uuid], [class_id], [class_level], [class_experience], [updated_at], [updated_by]
FROM [dbo].[account];
GO

-- ============================================================
-- AstralRecord\dbo.account_guide_step_progress.md
-- ============================================================

CREATE TABLE [dbo].[account_guide_step_progress] (
    [account_guide_step_progress_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]                     UNIQUEIDENTIFIER NOT NULL,
    [guide_id]                       NVARCHAR(100)    NOT NULL,
    [step_id]                        NVARCHAR(100)    NOT NULL,
    [completed_at]                   DATETIME2(3)     NOT NULL,
    [created_at]                     DATETIME2(3)     NOT NULL,
    [created_by]                     UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_guide_step_progress] PRIMARY KEY CLUSTERED ([account_guide_step_progress_id]),
    CONSTRAINT [FK_account_guide_step_progress_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_guide_step_progress_guide_id_not_blank]
        CHECK (LEN(LTRIM(RTRIM([guide_id]))) > 0),
    CONSTRAINT [CK_account_guide_step_progress_step_id_not_blank]
        CHECK (LEN(LTRIM(RTRIM([step_id]))) > 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_guide_step_progress_account_guide_step]
    ON [dbo].[account_guide_step_progress] ([account_id], [guide_id], [step_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_guide_step_progress_account_completed]
    ON [dbo].[account_guide_step_progress] ([account_id], [completed_at]);
GO

-- ============================================================
-- AstralRecord\dbo.web_login_challenge.md
-- ============================================================

CREATE TABLE [dbo].[web_login_challenge] (
    [challenge_id]     UNIQUEIDENTIFIER NOT NULL,
    [user_id]          UNIQUEIDENTIFIER NOT NULL,
    [login_code_hash]  NVARCHAR(256)    NOT NULL,
    [issued_at]        DATETIME2(3)     NOT NULL,
    [expires_at]       DATETIME2(3)     NOT NULL,
    [consumed_at]      DATETIME2(3)         NULL,
    [revoked_at]       DATETIME2(3)         NULL,
    [failed_attempts]  INT              NOT NULL CONSTRAINT [DF_web_login_challenge_failed_attempts] DEFAULT (0),
    [issued_by_server] NVARCHAR(100)    NOT NULL,
    [created_at]       DATETIME2(3)     NOT NULL,

    CONSTRAINT [PK_web_login_challenge] PRIMARY KEY CLUSTERED ([challenge_id]),
    CONSTRAINT [FK_web_login_challenge_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_web_login_challenge_hash_not_blank] CHECK (LEN(LTRIM(RTRIM([login_code_hash]))) > 0),
    CONSTRAINT [CK_web_login_challenge_server_not_blank] CHECK (LEN(LTRIM(RTRIM([issued_by_server]))) > 0),
    CONSTRAINT [CK_web_login_challenge_expiry] CHECK ([expires_at] > [issued_at]),
    CONSTRAINT [CK_web_login_challenge_failed_attempts] CHECK ([failed_attempts] >= 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_web_login_challenge_login_code_hash]
    ON [dbo].[web_login_challenge] ([login_code_hash]);
GO

CREATE NONCLUSTERED INDEX [IX_web_login_challenge_user_expires]
    ON [dbo].[web_login_challenge] ([user_id], [expires_at]);
GO

-- ============================================================
-- AstralRecord\dbo.skill_bind_preset.md
-- ============================================================

CREATE TABLE [dbo].[skill_bind_preset] (
    [skill_bind_preset_id]    UNIQUEIDENTIFIER  NOT NULL,
    [account_id]              UNIQUEIDENTIFIER  NOT NULL,
    [preset_index]            INT               NOT NULL,
    [active_skill_slots_json]  NVARCHAR(MAX)     NOT NULL  CONSTRAINT [DF_skill_bind_preset_active_slots_json]  DEFAULT (N'[]'),
    [left_click_skill_id]      NVARCHAR(128)     NULL      CONSTRAINT [DF_skill_bind_preset_left_click_skill_id] DEFAULT (N'__weapon_normal_attack__'),
    [passive_skill_slots_json] NVARCHAR(MAX)     NOT NULL  CONSTRAINT [DF_skill_bind_preset_passive_slots_json] DEFAULT (N'[]'),
    [is_unlocked]             BIT               NOT NULL  CONSTRAINT [DF_skill_bind_preset_is_unlocked] DEFAULT (0),
    [version]                 INT               NOT NULL  CONSTRAINT [DF_skill_bind_preset_version]     DEFAULT (1),
    [created_at]              DATETIME2(3)      NOT NULL,
    [updated_at]              DATETIME2(3)      NOT NULL,
    [created_by]              UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]              UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]              BIT               NOT NULL  CONSTRAINT [DF_skill_bind_preset_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_skill_bind_preset] PRIMARY KEY CLUSTERED ([skill_bind_preset_id]),
    CONSTRAINT [FK_skill_bind_preset_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_skill_bind_preset_index] CHECK ([preset_index] BETWEEN 1 AND 6),
    CONSTRAINT [CK_skill_bind_preset_active_slots_json] CHECK (ISJSON([active_skill_slots_json]) = 1),
    CONSTRAINT [CK_skill_bind_preset_passive_slots_json] CHECK (ISJSON([passive_skill_slots_json]) = 1),
    CONSTRAINT [CK_skill_bind_preset_version] CHECK ([version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_skill_bind_preset_account_preset]
    ON [dbo].[skill_bind_preset] ([account_id], [preset_index])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_skill_bind_preset_account_id]
    ON [dbo].[skill_bind_preset] ([account_id]);
GO

CREATE NONCLUSTERED INDEX [IX_skill_bind_preset_is_deleted]
    ON [dbo].[skill_bind_preset] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account_skilltree_state.md
-- ============================================================

CREATE TABLE [dbo].[account_skilltree_state] (
    [account_skilltree_state_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]                 UNIQUEIDENTIFIER NOT NULL,
    [version]                    INT              NOT NULL CONSTRAINT [DF_account_skilltree_state_version] DEFAULT (1),
    [created_at]                 DATETIME2(3)     NOT NULL,
    [updated_at]                 DATETIME2(3)     NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_account_skilltree_state_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_skilltree_state] PRIMARY KEY CLUSTERED ([account_skilltree_state_id]),
    CONSTRAINT [FK_account_skilltree_state_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_skilltree_state_version] CHECK ([version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_skilltree_state_account]
    ON [dbo].[account_skilltree_state] ([account_id])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_account_skilltree_state_is_deleted]
    ON [dbo].[account_skilltree_state] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account_skilltree_unlocked_node.md
-- ============================================================

CREATE TABLE [dbo].[account_skilltree_unlocked_node] (
    [account_skilltree_unlocked_node_id] UNIQUEIDENTIFIER NOT NULL,
    [account_skilltree_state_id]         UNIQUEIDENTIFIER NOT NULL,
    [node_id]                            NVARCHAR(100)    NOT NULL,
    [consumed_class_id]                  NVARCHAR(100)    NULL,
    [created_at]                         DATETIME2(3)     NOT NULL,
    [updated_at]                         DATETIME2(3)     NOT NULL,
    [created_by]                         UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                         UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_skilltree_unlocked_node] PRIMARY KEY CLUSTERED ([account_skilltree_unlocked_node_id]),
    CONSTRAINT [FK_account_skilltree_unlocked_node_state] FOREIGN KEY ([account_skilltree_state_id])
        REFERENCES [dbo].[account_skilltree_state] ([account_skilltree_state_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_skilltree_unlocked_node_node_id_not_blank] CHECK (LEN(LTRIM(RTRIM([node_id]))) > 0),
    CONSTRAINT [CK_account_skilltree_unlocked_node_consumed_class_id_not_blank]
        CHECK ([consumed_class_id] IS NULL OR LEN(LTRIM(RTRIM([consumed_class_id]))) > 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_skilltree_unlocked_node_state_node]
    ON [dbo].[account_skilltree_unlocked_node] ([account_skilltree_state_id], [node_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_skilltree_unlocked_node_node_id]
    ON [dbo].[account_skilltree_unlocked_node] ([node_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_skilltree_unlocked_node_consumed_class]
    ON [dbo].[account_skilltree_unlocked_node] ([account_skilltree_state_id], [consumed_class_id])
    WHERE [consumed_class_id] IS NOT NULL;
GO

-- ============================================================
-- AstralRecord\dbo.account_waystone_unlock.md
-- ============================================================

CREATE TABLE [dbo].[account_waystone_unlock] (
    [account_waystone_unlock_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]                 UNIQUEIDENTIFIER NOT NULL,
    [waystone_id]                NVARCHAR(100)    NOT NULL,
    [unlocked_at]                DATETIME2(3)     NOT NULL,
    [created_at]                 DATETIME2(3)     NOT NULL,
    [updated_at]                 DATETIME2(3)     NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_account_waystone_unlock_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_waystone_unlock] PRIMARY KEY CLUSTERED ([account_waystone_unlock_id]),
    CONSTRAINT [FK_account_waystone_unlock_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_waystone_unlock_waystone_id_not_blank] CHECK (LEN(LTRIM(RTRIM([waystone_id]))) > 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_waystone_unlock_account_waystone]
    ON [dbo].[account_waystone_unlock] ([account_id], [waystone_id])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_account_waystone_unlock_waystone_id]
    ON [dbo].[account_waystone_unlock] ([waystone_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_waystone_unlock_is_deleted]
    ON [dbo].[account_waystone_unlock] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account_quest_state.md
-- ============================================================

CREATE TABLE [dbo].[account_quest_state] (
    [account_quest_state_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]             UNIQUEIDENTIFIER NOT NULL,
    [version]                INT              NOT NULL CONSTRAINT [DF_account_quest_state_version] DEFAULT (1),
    [created_at]             DATETIME2(3)     NOT NULL,
    [updated_at]             DATETIME2(3)     NOT NULL,
    [created_by]             UNIQUEIDENTIFIER NOT NULL,
    [updated_by]             UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]             BIT              NOT NULL CONSTRAINT [DF_account_quest_state_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_quest_state] PRIMARY KEY CLUSTERED ([account_quest_state_id]),
    CONSTRAINT [FK_account_quest_state_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_quest_state_version] CHECK ([version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_quest_state_account]
    ON [dbo].[account_quest_state] ([account_id])
    WHERE [is_deleted] = 0;
GO

CREATE TABLE [dbo].[account_quest_active] (
    [account_quest_active_id] UNIQUEIDENTIFIER NOT NULL,
    [account_quest_state_id]  UNIQUEIDENTIFIER NOT NULL,
    [quest_id]                NVARCHAR(100)    NOT NULL,
    [accepted_at]             DATETIME2(3)     NOT NULL,
    [accepted_npc_id]         NVARCHAR(100)    NULL,
    [ready_to_turn_in]        BIT              NOT NULL,
    [created_at]              DATETIME2(3)     NOT NULL,
    [updated_at]              DATETIME2(3)     NOT NULL,
    [created_by]              UNIQUEIDENTIFIER NOT NULL,
    [updated_by]              UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_quest_active] PRIMARY KEY CLUSTERED ([account_quest_active_id]),
    CONSTRAINT [FK_account_quest_active_state] FOREIGN KEY ([account_quest_state_id])
        REFERENCES [dbo].[account_quest_state] ([account_quest_state_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_quest_active_quest_id_not_blank] CHECK (LEN(LTRIM(RTRIM([quest_id]))) > 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_quest_active_state_quest]
    ON [dbo].[account_quest_active] ([account_quest_state_id], [quest_id]);
GO

CREATE TABLE [dbo].[account_quest_objective_progress] (
    [account_quest_objective_progress_id] UNIQUEIDENTIFIER NOT NULL,
    [account_quest_active_id]             UNIQUEIDENTIFIER NOT NULL,
    [objective_id]                        NVARCHAR(100)    NOT NULL,
    [progress]                            INT              NOT NULL CONSTRAINT [DF_account_quest_objective_progress_progress] DEFAULT (0),
    [created_at]                          DATETIME2(3)     NOT NULL,
    [updated_at]                          DATETIME2(3)     NOT NULL,
    [created_by]                          UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                          UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_quest_objective_progress] PRIMARY KEY CLUSTERED ([account_quest_objective_progress_id]),
    CONSTRAINT [FK_account_quest_objective_progress_active] FOREIGN KEY ([account_quest_active_id])
        REFERENCES [dbo].[account_quest_active] ([account_quest_active_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_quest_objective_progress_objective_id_not_blank] CHECK (LEN(LTRIM(RTRIM([objective_id]))) > 0),
    CONSTRAINT [CK_account_quest_objective_progress_progress] CHECK ([progress] >= 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_quest_objective_progress_active_objective]
    ON [dbo].[account_quest_objective_progress] ([account_quest_active_id], [objective_id]);
GO

CREATE TABLE [dbo].[account_quest_completion] (
    [account_quest_completion_id] UNIQUEIDENTIFIER NOT NULL,
    [account_quest_state_id]      UNIQUEIDENTIFIER NOT NULL,
    [quest_id]                    NVARCHAR(100)    NOT NULL,
    [completed_at]                DATETIME2(3)     NOT NULL,
    [created_at]                  DATETIME2(3)     NOT NULL,
    [updated_at]                  DATETIME2(3)     NOT NULL,
    [created_by]                  UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                  UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_quest_completion] PRIMARY KEY CLUSTERED ([account_quest_completion_id]),
    CONSTRAINT [FK_account_quest_completion_state] FOREIGN KEY ([account_quest_state_id])
        REFERENCES [dbo].[account_quest_state] ([account_quest_state_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_quest_completion_quest_id_not_blank] CHECK (LEN(LTRIM(RTRIM([quest_id]))) > 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_quest_completion_state_quest]
    ON [dbo].[account_quest_completion] ([account_quest_state_id], [quest_id]);
GO

CREATE TABLE [dbo].[account_quest_cooldown] (
    [account_quest_cooldown_id] UNIQUEIDENTIFIER NOT NULL,
    [account_quest_state_id]    UNIQUEIDENTIFIER NOT NULL,
    [quest_id]                  NVARCHAR(100)    NOT NULL,
    [cooldown_until]            DATETIME2(3)     NOT NULL,
    [created_at]                DATETIME2(3)     NOT NULL,
    [updated_at]                DATETIME2(3)     NOT NULL,
    [created_by]                UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_account_quest_cooldown] PRIMARY KEY CLUSTERED ([account_quest_cooldown_id]),
    CONSTRAINT [FK_account_quest_cooldown_state] FOREIGN KEY ([account_quest_state_id])
        REFERENCES [dbo].[account_quest_state] ([account_quest_state_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_account_quest_cooldown_quest_id_not_blank] CHECK (LEN(LTRIM(RTRIM([quest_id]))) > 0)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_quest_cooldown_state_quest]
    ON [dbo].[account_quest_cooldown] ([account_quest_state_id], [quest_id]);
GO

-- ============================================================
-- AstralRecord\dbo.login_bonus_claim.md
-- ============================================================

CREATE TABLE [dbo].[login_bonus_claim] (
    [login_bonus_claim_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]           UNIQUEIDENTIFIER NOT NULL,
    [claim_date]           DATE             NOT NULL,
    [claimed_at]           DATETIME2(3)     NOT NULL,
    [created_at]           DATETIME2(3)     NOT NULL,
    [updated_at]           DATETIME2(3)     NOT NULL,
    [created_by]           UNIQUEIDENTIFIER NOT NULL,
    [updated_by]           UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]           BIT              NOT NULL CONSTRAINT [DF_login_bonus_claim_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_login_bonus_claim] PRIMARY KEY CLUSTERED ([login_bonus_claim_id]),
    CONSTRAINT [FK_login_bonus_claim_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_login_bonus_claim_date] CHECK ([claim_date] >= CONVERT(date, '2000-01-01'))
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_login_bonus_claim_account_date]
    ON [dbo].[login_bonus_claim] ([account_id], [claim_date])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_login_bonus_claim_account_claimed_at]
    ON [dbo].[login_bonus_claim] ([account_id], [claimed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_login_bonus_claim_is_deleted]
    ON [dbo].[login_bonus_claim] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.inventory.md
-- ============================================================

CREATE TABLE [dbo].[inventory] (
    [inventory_id]       UNIQUEIDENTIFIER  NOT NULL,
    [account_id]         UNIQUEIDENTIFIER  NOT NULL,
    [inventory_type]     NVARCHAR(30)      NOT NULL,
    [inventory_profile]  NVARCHAR(20)      NOT NULL  CONSTRAINT [DF_inventory_profile] DEFAULT ('GAME'),
    [slot_capacity]      INT                   NULL,
    [is_enabled]         BIT               NOT NULL  CONSTRAINT [DF_inventory_is_enabled] DEFAULT (1),
    [metadata_json]      NVARCHAR(MAX)         NULL,
    [created_at]         DATETIME2(3)      NOT NULL,
    [updated_at]         DATETIME2(3)      NOT NULL,
    [created_by]         UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]         BIT               NOT NULL  CONSTRAINT [DF_inventory_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_inventory] PRIMARY KEY CLUSTERED ([inventory_id]),
    CONSTRAINT [FK_inventory_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_inventory_account_type_profile] UNIQUE ([account_id], [inventory_type], [inventory_profile]),
    CONSTRAINT [CK_inventory_profile] CHECK ([inventory_profile] IN (N'GAME', N'ADMIN')),
    CONSTRAINT [CK_inventory_slot_capacity] CHECK ([slot_capacity] IS NULL OR [slot_capacity] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_account_id]
    ON [dbo].[inventory] ([account_id]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_inventory_type]
    ON [dbo].[inventory] ([inventory_type]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_inventory_profile]
    ON [dbo].[inventory] ([inventory_profile]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_is_deleted]
    ON [dbo].[inventory] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.inventory_entry.md
-- ============================================================

CREATE TABLE [dbo].[inventory_entry] (
    [inventory_entry_id]    UNIQUEIDENTIFIER  NOT NULL,
    [inventory_id]          UNIQUEIDENTIFIER  NOT NULL,
    [slot_index]            INT                   NULL,
    [item_category]         NVARCHAR(30)      NOT NULL,
    [item_id]               NVARCHAR(100)         NULL,
    [instance_type]         NVARCHAR(30)          NULL,
    [instance_id]           UNIQUEIDENTIFIER      NULL,
    [quantity]              BIGINT            NOT NULL  CONSTRAINT [DF_inventory_entry_quantity] DEFAULT (1),
    [metadata_json]         NVARCHAR(MAX)         NULL,
    [created_at]            DATETIME2(3)      NOT NULL,
    [updated_at]            DATETIME2(3)      NOT NULL,
    [created_by]            UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]            UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]            BIT               NOT NULL  CONSTRAINT [DF_inventory_entry_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_inventory_entry] PRIMARY KEY CLUSTERED ([inventory_entry_id]),
    CONSTRAINT [FK_inventory_entry_inventory] FOREIGN KEY ([inventory_id])
        REFERENCES [dbo].[inventory] ([inventory_id])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_inventory_entry_slot_index] CHECK ([slot_index] IS NULL OR [slot_index] >= 0),
    CONSTRAINT [CK_inventory_entry_quantity] CHECK ([quantity] >= 1),
    CONSTRAINT [CK_inventory_entry_payload] CHECK (
        ([item_id] IS NOT NULL AND [instance_type] IS NULL AND [instance_id] IS NULL)
        OR ([item_id] IS NULL AND [instance_type] IS NOT NULL AND [instance_id] IS NOT NULL)
    )
);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_entry_inventory_id]
    ON [dbo].[inventory_entry] ([inventory_id]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_inventory_entry_inventory_slot]
    ON [dbo].[inventory_entry] ([inventory_id], [slot_index])
    WHERE [slot_index] IS NOT NULL
      AND [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_inventory_entry_inventory_item]
    ON [dbo].[inventory_entry] ([inventory_id], [item_id])
    WHERE [slot_index] IS NULL
      AND [item_id] IS NOT NULL
      AND [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_inventory_entry_instance]
    ON [dbo].[inventory_entry] ([instance_type], [instance_id]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_entry_is_deleted]
    ON [dbo].[inventory_entry] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_instance.md
-- ============================================================

CREATE TABLE [dbo].[equipment_instance] (
    [equipment_instance_id]  UNIQUEIDENTIFIER  NOT NULL,
    [account_id]             UNIQUEIDENTIFIER  NOT NULL,
    [item_id]                NVARCHAR(100)     NOT NULL,
    [enhance_level]          INT               NOT NULL  CONSTRAINT [DF_equipment_instance_enhance_level]      DEFAULT (0),
    [rune_max_slots]         INT               NOT NULL  CONSTRAINT [DF_equipment_instance_rune_max_slots]     DEFAULT (0),
    [transcendence_rank]     INT               NOT NULL  CONSTRAINT [DF_equipment_instance_transcendence_rank] DEFAULT (0),
    [transcendence_name]              NVARCHAR(100)        NULL,
    [transcendence_enhance_max_level] INT                  NULL,
    [transcendence_enchant_max_slots] INT                  NULL,
    [durability_max]         INT                   NULL,
    [durability_value]       INT                   NULL,
    [created_at]             DATETIME2(3)      NOT NULL,
    [updated_at]             DATETIME2(3)      NOT NULL,
    [created_by]             UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]             UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]             BIT               NOT NULL  CONSTRAINT [DF_equipment_instance_is_deleted]         DEFAULT (0),

    CONSTRAINT [PK_equipment_instance] PRIMARY KEY CLUSTERED ([equipment_instance_id]),
    CONSTRAINT [FK_equipment_instance_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_equipment_instance_enhance_level] CHECK ([enhance_level] >= 0),
    CONSTRAINT [CK_equipment_instance_rune_max_slots] CHECK ([rune_max_slots] >= 0),
    CONSTRAINT [CK_equipment_instance_transcendence_rank] CHECK ([transcendence_rank] >= 0),
    CONSTRAINT [CK_equipment_instance_transcendence_enhance_max_level] CHECK ([transcendence_enhance_max_level] IS NULL OR [transcendence_enhance_max_level] >= 0),
    CONSTRAINT [CK_equipment_instance_transcendence_enchant_max_slots] CHECK ([transcendence_enchant_max_slots] IS NULL OR [transcendence_enchant_max_slots] >= 0),
    CONSTRAINT [CK_equipment_instance_durability_pair] CHECK (
        ([durability_max] IS NULL AND [durability_value] IS NULL)
        OR ([durability_max] IS NOT NULL AND [durability_value] IS NOT NULL)
    ),
    CONSTRAINT [CK_equipment_instance_durability_range] CHECK (
        [durability_max] IS NULL
        OR ([durability_max] > 0 AND [durability_value] BETWEEN 0 AND [durability_max])
    )
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_account_id]
    ON [dbo].[equipment_instance] ([account_id]);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_item_id]
    ON [dbo].[equipment_instance] ([item_id]);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_is_deleted]
    ON [dbo].[equipment_instance] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_loadout.md
-- ============================================================

CREATE TABLE [dbo].[equipment_loadout] (
    [equipment_loadout_id]  UNIQUEIDENTIFIER  NOT NULL,
    [account_id]            UNIQUEIDENTIFIER  NOT NULL,
    [loadout_profile]       NVARCHAR(20)      NOT NULL  CONSTRAINT [DF_equipment_loadout_profile] DEFAULT ('GAME'),
    [loadout_name]          NVARCHAR(100)     NOT NULL,
    [sort_order]            INT               NOT NULL  CONSTRAINT [DF_equipment_loadout_sort_order] DEFAULT (0),
    [is_active]             BIT               NOT NULL  CONSTRAINT [DF_equipment_loadout_is_active] DEFAULT (0),
    [metadata_json]         NVARCHAR(MAX)         NULL,
    [created_at]            DATETIME2(3)      NOT NULL,
    [updated_at]            DATETIME2(3)      NOT NULL,
    [created_by]            UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]            UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]            BIT               NOT NULL  CONSTRAINT [DF_equipment_loadout_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_equipment_loadout] PRIMARY KEY CLUSTERED ([equipment_loadout_id]),
    CONSTRAINT [FK_equipment_loadout_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_equipment_loadout_profile] CHECK ([loadout_profile] IN (N'GAME', N'ADMIN'))
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_account_profile]
    ON [dbo].[equipment_loadout] ([account_id], [loadout_profile]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_active]
    ON [dbo].[equipment_loadout] ([account_id], [loadout_profile])
    WHERE [is_active] = 1
      AND [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_name]
    ON [dbo].[equipment_loadout] ([account_id], [loadout_profile], [loadout_name])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_is_deleted]
    ON [dbo].[equipment_loadout] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_loadout_slot.md
-- ============================================================

CREATE TABLE [dbo].[equipment_loadout_slot] (
    [equipment_loadout_slot_id]  UNIQUEIDENTIFIER  NOT NULL,
    [equipment_loadout_id]       UNIQUEIDENTIFIER  NOT NULL,
    [slot_type]                  NVARCHAR(30)      NOT NULL,
    [slot_index]                 INT               NOT NULL  CONSTRAINT [DF_equipment_loadout_slot_index] DEFAULT (0),
    [equipment_instance_id]      UNIQUEIDENTIFIER  NOT NULL,
    [created_at]                 DATETIME2(3)      NOT NULL,
    [updated_at]                 DATETIME2(3)      NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]                 BIT               NOT NULL  CONSTRAINT [DF_equipment_loadout_slot_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_equipment_loadout_slot] PRIMARY KEY CLUSTERED ([equipment_loadout_slot_id]),
    CONSTRAINT [FK_equipment_loadout_slot_loadout] FOREIGN KEY ([equipment_loadout_id])
        REFERENCES [dbo].[equipment_loadout] ([equipment_loadout_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [FK_equipment_loadout_slot_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_equipment_loadout_slot_index] CHECK ([slot_index] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_slot_loadout_id]
    ON [dbo].[equipment_loadout_slot] ([equipment_loadout_id]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_slot_position]
    ON [dbo].[equipment_loadout_slot] ([equipment_loadout_id], [slot_type], [slot_index])
    WHERE [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_slot_equipment]
    ON [dbo].[equipment_loadout_slot] ([equipment_loadout_id], [equipment_instance_id])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_slot_equipment_instance_id]
    ON [dbo].[equipment_loadout_slot] ([equipment_instance_id]);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_slot_is_deleted]
    ON [dbo].[equipment_loadout_slot] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_instance_stat_roll.md
-- ============================================================

CREATE TABLE [dbo].[equipment_instance_stat_roll] (
    [stat_roll_id]            UNIQUEIDENTIFIER  NOT NULL,
    [equipment_instance_id]   UNIQUEIDENTIFIER  NOT NULL,
    [status]                  NVARCHAR(50)      NOT NULL,
    [random_min]              NVARCHAR(20)      NOT NULL,
    [random_max]              NVARCHAR(20)      NOT NULL,
    [sort_order]              INT               NOT NULL  CONSTRAINT [DF_equipment_instance_stat_roll_sort_order]  DEFAULT (0),
    [created_at]              DATETIME2(3)      NOT NULL,
    [updated_at]              DATETIME2(3)      NOT NULL,
    [created_by]              UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]              UNIQUEIDENTIFIER  NOT NULL,

    CONSTRAINT [PK_equipment_instance_stat_roll] PRIMARY KEY CLUSTERED ([stat_roll_id]),
    CONSTRAINT [FK_equipment_instance_stat_roll_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_equipment_instance_stat_roll_instance_status] UNIQUE ([equipment_instance_id], [status], [sort_order])
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_stat_roll_equipment_instance_id]
    ON [dbo].[equipment_instance_stat_roll] ([equipment_instance_id]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_instance_enchant.md
-- ============================================================

CREATE TABLE [dbo].[equipment_instance_enchant] (
    [enchant_id]                  UNIQUEIDENTIFIER  NOT NULL,
    [equipment_instance_id]       UNIQUEIDENTIFIER  NOT NULL,
    [slot_index]                  INT               NOT NULL,
    [enchant_master_id]           NVARCHAR(100)     NOT NULL,
    [effect_id]                   NVARCHAR(100)     NOT NULL,
    [status]                      NVARCHAR(50)      NOT NULL,
    [type]                        NVARCHAR(20)      NOT NULL,
    [value]                       DECIMAL(18, 4)    NOT NULL,
    [created_at]                  DATETIME2(3)      NOT NULL,
    [updated_at]                  DATETIME2(3)      NOT NULL,
    [created_by]                  UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]                  UNIQUEIDENTIFIER  NOT NULL,

    CONSTRAINT [PK_equipment_instance_enchant] PRIMARY KEY CLUSTERED ([enchant_id]),
    CONSTRAINT [FK_equipment_instance_enchant_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_equipment_instance_enchant_slot_index] UNIQUE ([equipment_instance_id], [slot_index]),
    CONSTRAINT [UQ_equipment_instance_enchant_effect_id] UNIQUE ([equipment_instance_id], [effect_id])
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_enchant_equipment_instance_id]
    ON [dbo].[equipment_instance_enchant] ([equipment_instance_id]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_orb_operation.md
-- ============================================================

CREATE TABLE [dbo].[equipment_orb_operation] (
    [operation_id]                       UNIQUEIDENTIFIER  NOT NULL,
    [account_id]                         UNIQUEIDENTIFIER  NOT NULL,
    [equipment_instance_id]              UNIQUEIDENTIFIER  NOT NULL,
    [orb_inventory_entry_id]             UNIQUEIDENTIFIER  NOT NULL,
    [orb_item_id]                        NVARCHAR(128)     NOT NULL,
    [operation_type]                     NVARCHAR(32)      NOT NULL,
    [request_hash]                       CHAR(64)          NOT NULL,
    [result_code]                        NVARCHAR(32)      NOT NULL,
    [result_payload_json]                NVARCHAR(MAX)     NOT NULL,
    [payment_consumed]                   BIT               NOT NULL,
    [affected_inventory_entry_ids_json]  NVARCHAR(MAX)     NOT NULL,
    [created_at]                         DATETIME2(3)       NOT NULL,
    [completed_at]                       DATETIME2(3)       NOT NULL,
    [created_by]                         UNIQUEIDENTIFIER   NOT NULL,

    CONSTRAINT [PK_equipment_orb_operation] PRIMARY KEY CLUSTERED ([operation_id]),
    CONSTRAINT [CK_equipment_orb_operation_result_payload_json]
        CHECK (ISJSON([result_payload_json]) = 1),
    CONSTRAINT [CK_equipment_orb_operation_affected_entries_json]
        CHECK (ISJSON([affected_inventory_entry_ids_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_orb_operation_account_created_at]
    ON [dbo].[equipment_orb_operation] ([account_id], [created_at]);
GO

-- ============================================================
-- AstralRecord\dbo.account_mob_record.md
-- ============================================================

CREATE TABLE [dbo].[account_mob_record] (
    [account_mob_record_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]            UNIQUEIDENTIFIER NOT NULL,
    [mob_id]                NVARCHAR(100)    NOT NULL,
    [mob_category]          NVARCHAR(20)     NOT NULL,
    [defeat_count]          BIGINT           NOT NULL CONSTRAINT [DF_account_mob_record_defeat_count] DEFAULT (1),
    [first_defeated_at]     DATETIME2(3)     NOT NULL,
    [last_defeated_at]      DATETIME2(3)     NOT NULL,
    [created_at]            DATETIME2(3)     NOT NULL,
    [updated_at]            DATETIME2(3)     NOT NULL,
    [created_by]            UNIQUEIDENTIFIER NOT NULL,
    [updated_by]            UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]            BIT              NOT NULL CONSTRAINT [DF_account_mob_record_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_mob_record] PRIMARY KEY CLUSTERED ([account_mob_record_id]),
    CONSTRAINT [FK_account_mob_record_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UX_account_mob_record_account_mob] UNIQUE ([account_id], [mob_id]),
    CONSTRAINT [CK_account_mob_record_category] CHECK ([mob_category] IN (N'ENEMY', N'BOSS')),
    CONSTRAINT [CK_account_mob_record_defeat_count] CHECK ([defeat_count] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_mob_record_account_category_last_defeated]
    ON [dbo].[account_mob_record] ([account_id], [mob_category], [last_defeated_at] DESC);
GO

CREATE NONCLUSTERED INDEX [IX_account_mob_record_is_deleted]
    ON [dbo].[account_mob_record] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.account_dungeon_record.md
-- ============================================================

CREATE TABLE [dbo].[account_dungeon_record] (
    [account_dungeon_record_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]                 UNIQUEIDENTIFIER NOT NULL,
    [dungeon_id]                 NVARCHAR(100)    NOT NULL,
    [clear_count]                BIGINT           NOT NULL CONSTRAINT [DF_account_dungeon_record_clear_count] DEFAULT (1),
    [first_cleared_at]           DATETIME2(3)     NOT NULL,
    [last_cleared_at]            DATETIME2(3)     NOT NULL,
    [created_at]                 DATETIME2(3)     NOT NULL,
    [updated_at]                 DATETIME2(3)     NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_account_dungeon_record_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_dungeon_record] PRIMARY KEY CLUSTERED ([account_dungeon_record_id]),
    CONSTRAINT [FK_account_dungeon_record_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UX_account_dungeon_record_account_dungeon] UNIQUE ([account_id], [dungeon_id]),
    CONSTRAINT [CK_account_dungeon_record_clear_count] CHECK ([clear_count] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_dungeon_record_account_last_cleared]
    ON [dbo].[account_dungeon_record] ([account_id], [last_cleared_at] DESC);
GO

CREATE NONCLUSTERED INDEX [IX_account_dungeon_record_is_deleted]
    ON [dbo].[account_dungeon_record] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.equipment_instance_rune.md
-- ============================================================

CREATE TABLE [dbo].[equipment_instance_rune] (
    [rune_id]                UNIQUEIDENTIFIER NOT NULL,
    [equipment_instance_id]  UNIQUEIDENTIFIER NOT NULL,
    [slot_index]             INT              NOT NULL,
    [rune_instance_id]       UNIQUEIDENTIFIER     NULL,
    [item_id]                NVARCHAR(100)    NOT NULL,
    [created_at]             DATETIME2(3)     NOT NULL,
    [updated_at]             DATETIME2(3)     NOT NULL,
    [created_by]             UNIQUEIDENTIFIER NOT NULL,
    [updated_by]             UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_equipment_instance_rune] PRIMARY KEY CLUSTERED ([rune_id]),
    CONSTRAINT [FK_equipment_instance_rune_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_equipment_instance_rune_slot_index] UNIQUE ([equipment_instance_id], [slot_index])
);
GO

-- ============================================================
-- AstralRecord\dbo.rune_instance.md
-- ============================================================

CREATE TABLE [dbo].[rune_instance] (
    [rune_instance_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]        UNIQUEIDENTIFIER NOT NULL,
    [item_id]           NVARCHAR(100)    NOT NULL,
    [created_at]        DATETIME2(3)     NOT NULL,
    [updated_at]        DATETIME2(3)     NOT NULL,
    [created_by]        UNIQUEIDENTIFIER NOT NULL,
    [updated_by]        UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]        BIT              NOT NULL CONSTRAINT [DF_rune_instance_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_rune_instance] PRIMARY KEY CLUSTERED ([rune_instance_id])
);
GO

-- ============================================================
-- AstralRecord\dbo.rune_instance_stat_roll.md
-- ============================================================

CREATE TABLE [dbo].[rune_instance_stat_roll] (
    [stat_roll_id]       UNIQUEIDENTIFIER NOT NULL,
    [rune_instance_id]   UNIQUEIDENTIFIER NOT NULL,
    [status]             NVARCHAR(50)     NOT NULL,
    [type]               NVARCHAR(20)     NOT NULL,
    [random_value]       NVARCHAR(20)     NOT NULL,
    [sort_order]         INT              NOT NULL CONSTRAINT [DF_rune_instance_stat_roll_sort_order] DEFAULT (0),
    [created_at]         DATETIME2(3)     NOT NULL,
    [updated_at]         DATETIME2(3)     NOT NULL,
    [created_by]         UNIQUEIDENTIFIER NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]         BIT              NOT NULL CONSTRAINT [DF_rune_instance_stat_roll_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_rune_instance_stat_roll] PRIMARY KEY CLUSTERED ([stat_roll_id]),
    CONSTRAINT [FK_rune_instance_stat_roll_rune_instance] FOREIGN KEY ([rune_instance_id])
        REFERENCES [dbo].[rune_instance] ([rune_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_rune_instance_stat_roll_instance_status] UNIQUE ([rune_instance_id], [status], [sort_order])
);
GO

-- ============================================================
-- AstralRecord\dbo.market_account_state.md
-- ============================================================

CREATE TABLE [dbo].[market_account_state] (
    [account_id]                 UNIQUEIDENTIFIER NOT NULL,
    [completed_trade_count]      INT              NOT NULL CONSTRAINT [DF_market_account_state_completed_trade_count] DEFAULT (0),
    [tier]                       NVARCHAR(10)     NOT NULL CONSTRAINT [DF_market_account_state_tier] DEFAULT (N'T0'),
    [max_active_listing_count]   INT              NOT NULL CONSTRAINT [DF_market_account_state_max_active_listing_count] DEFAULT (3),
    [suspended_until]            DATETIME2(3)         NULL,
    [created_at]                 DATETIME2(3)     NOT NULL,
    [updated_at]                 DATETIME2(3)     NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_market_account_state_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_market_account_state] PRIMARY KEY CLUSTERED ([account_id]),
    CONSTRAINT [FK_market_account_state_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_account_state_trade_count] CHECK ([completed_trade_count] >= 0),
    CONSTRAINT [CK_market_account_state_tier] CHECK ([tier] IN (N'T0', N'T1', N'T2', N'T3', N'T4')),
    CONSTRAINT [CK_market_account_state_limit] CHECK ([max_active_listing_count] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_account_state_tier]
    ON [dbo].[market_account_state] ([tier]);
GO

CREATE NONCLUSTERED INDEX [IX_market_account_state_is_deleted]
    ON [dbo].[market_account_state] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.market_listing.md
-- ============================================================

CREATE TABLE [dbo].[market_listing] (
    [listing_id]               UNIQUEIDENTIFIER NOT NULL,
    [seller_account_id]        UNIQUEIDENTIFIER NOT NULL,
    [buyer_account_id]         UNIQUEIDENTIFIER     NULL,
    [source_inventory_entry_id] UNIQUEIDENTIFIER     NULL,
    [item_category]            NVARCHAR(50)     NOT NULL,
    [item_id]                  NVARCHAR(100)    NOT NULL,
    [instance_type]            NVARCHAR(30)         NULL,
    [instance_id]              UNIQUEIDENTIFIER     NULL,
    [quantity]                 INT              NOT NULL,
    [currency_id]              NVARCHAR(50)     NOT NULL,
    [unit_price]               BIGINT           NOT NULL,
    [total_price]              BIGINT           NOT NULL,
    [price_floor]              BIGINT           NOT NULL,
    [reference_unit_price]     BIGINT               NULL,
    [price_deviation_rate]     DECIMAL(18,6)        NULL,
    [price_confidence]         NVARCHAR(20)     NOT NULL,
    [valuation_signature]      NVARCHAR(300)        NULL,
    [valuation_snapshot_json]  NVARCHAR(MAX)        NULL,
    [status]                   NVARCHAR(20)     NOT NULL CONSTRAINT [DF_market_listing_status] DEFAULT (N'ACTIVE'),
    [status_reason]            NVARCHAR(200)        NULL,
    [listed_at]                DATETIME2(3)     NOT NULL,
    [expires_at]               DATETIME2(3)     NOT NULL,
    [sold_at]                  DATETIME2(3)         NULL,
    [canceled_at]              DATETIME2(3)         NULL,
    [version]                  INT              NOT NULL CONSTRAINT [DF_market_listing_version] DEFAULT (1),
    [created_at]               DATETIME2(3)     NOT NULL,
    [updated_at]               DATETIME2(3)     NOT NULL,
    [created_by]               UNIQUEIDENTIFIER NOT NULL,
    [updated_by]               UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]               BIT              NOT NULL CONSTRAINT [DF_market_listing_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_market_listing] PRIMARY KEY CLUSTERED ([listing_id]),
    CONSTRAINT [FK_market_listing_seller_account] FOREIGN KEY ([seller_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_listing_buyer_account] FOREIGN KEY ([buyer_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_listing_source_inventory_entry] FOREIGN KEY ([source_inventory_entry_id])
        REFERENCES [dbo].[inventory_entry] ([inventory_entry_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_listing_quantity] CHECK ([quantity] >= 1),
    CONSTRAINT [CK_market_listing_price] CHECK ([unit_price] >= 1 AND [total_price] = [unit_price] * [quantity] AND [price_floor] >= 0),
    CONSTRAINT [CK_market_listing_confidence] CHECK ([price_confidence] IN (N'HIGH', N'MEDIUM', N'LOW')),
    CONSTRAINT [CK_market_listing_status] CHECK ([status] IN (N'ACTIVE', N'SOLD', N'CANCELED', N'EXPIRED', N'SUSPENDED')),
    CONSTRAINT [CK_market_listing_version] CHECK ([version] >= 1),
    CONSTRAINT [CK_market_listing_valuation_json] CHECK ([valuation_snapshot_json] IS NULL OR ISJSON([valuation_snapshot_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_status_listed_at]
    ON [dbo].[market_listing] ([status], [listed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_seller_status]
    ON [dbo].[market_listing] ([seller_account_id], [status]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_item_status_price]
    ON [dbo].[market_listing] ([item_category], [item_id], [status], [unit_price]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_instance_active_status]
    ON [dbo].[market_listing] ([instance_type], [instance_id], [is_deleted], [status]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_is_deleted]
    ON [dbo].[market_listing] ([is_deleted]);
GO

-- ============================================================
-- AstralRecord\dbo.market_transaction.md
-- ============================================================

CREATE TABLE [dbo].[market_transaction] (
    [transaction_id]          UNIQUEIDENTIFIER NOT NULL,
    [listing_id]              UNIQUEIDENTIFIER NOT NULL,
    [seller_account_id]       UNIQUEIDENTIFIER NOT NULL,
    [buyer_account_id]        UNIQUEIDENTIFIER NOT NULL,
    [item_category]           NVARCHAR(50)     NOT NULL,
    [item_id]                 NVARCHAR(100)    NOT NULL,
    [instance_type]           NVARCHAR(30)         NULL,
    [instance_id]             UNIQUEIDENTIFIER     NULL,
    [quantity]                INT              NOT NULL,
    [currency_id]             NVARCHAR(50)     NOT NULL,
    [unit_price]              BIGINT           NOT NULL,
    [total_price]             BIGINT           NOT NULL,
    [fee_amount]              BIGINT           NOT NULL CONSTRAINT [DF_market_transaction_fee_amount] DEFAULT (0),
    [seller_proceeds]         BIGINT           NOT NULL,
    [valuation_signature]     NVARCHAR(300)        NULL,
    [valuation_snapshot_json] NVARCHAR(MAX)        NULL,
    [idempotency_key]         NVARCHAR(100)    NOT NULL,
    [completed_at]            DATETIME2(3)     NOT NULL,
    [created_at]              DATETIME2(3)     NOT NULL,
    [created_by]              UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_market_transaction] PRIMARY KEY CLUSTERED ([transaction_id]),
    CONSTRAINT [FK_market_transaction_listing] FOREIGN KEY ([listing_id])
        REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_transaction_seller_account] FOREIGN KEY ([seller_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_transaction_buyer_account] FOREIGN KEY ([buyer_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [UQ_market_transaction_listing] UNIQUE ([listing_id]),
    CONSTRAINT [UQ_market_transaction_idempotency] UNIQUE ([buyer_account_id], [idempotency_key]),
    CONSTRAINT [CK_market_transaction_quantity] CHECK ([quantity] >= 1),
    CONSTRAINT [CK_market_transaction_price] CHECK ([unit_price] >= 1 AND [total_price] = [unit_price] * [quantity] AND [fee_amount] >= 0 AND [seller_proceeds] = [total_price] - [fee_amount]),
    CONSTRAINT [CK_market_transaction_valuation_json] CHECK ([valuation_snapshot_json] IS NULL OR ISJSON([valuation_snapshot_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_item_completed]
    ON [dbo].[market_transaction] ([item_category], [item_id], [completed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_signature_completed]
    ON [dbo].[market_transaction] ([valuation_signature], [completed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_seller]
    ON [dbo].[market_transaction] ([seller_account_id], [completed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_buyer]
    ON [dbo].[market_transaction] ([buyer_account_id], [completed_at]);
GO

-- ============================================================
-- AstralRecord\dbo.market_price_snapshot.md
-- ============================================================

CREATE TABLE [dbo].[market_price_snapshot] (
    [snapshot_id]            UNIQUEIDENTIFIER NOT NULL,
    [listing_id]             UNIQUEIDENTIFIER     NULL,
    [transaction_id]         UNIQUEIDENTIFIER     NULL,
    [item_category]          NVARCHAR(50)     NOT NULL,
    [item_id]                NVARCHAR(100)    NOT NULL,
    [instance_type]          NVARCHAR(30)         NULL,
    [instance_id]            UNIQUEIDENTIFIER     NULL,
    [valuation_signature]    NVARCHAR(300)        NULL,
    [reference_scope]        NVARCHAR(50)     NOT NULL,
    [sample_count]           INT              NOT NULL,
    [confidence]             NVARCHAR(20)     NOT NULL,
    [sell_price]             BIGINT           NOT NULL,
    [suggested_unit_price]   BIGINT           NOT NULL,
    [reference_unit_price]   BIGINT               NULL,
    [allowed_min_unit_price] BIGINT           NOT NULL,
    [allowed_max_unit_price] BIGINT           NOT NULL,
    [judgement]              NVARCHAR(50)     NOT NULL,
    [roll_quality_score]     DECIMAL(8,4)         NULL,
    [roll_quality_bucket]    NVARCHAR(10)         NULL,
    [evaluated_at]           DATETIME2(3)     NOT NULL,
    [created_at]             DATETIME2(3)     NOT NULL,

    CONSTRAINT [PK_market_price_snapshot] PRIMARY KEY CLUSTERED ([snapshot_id]),
    CONSTRAINT [FK_market_price_snapshot_listing] FOREIGN KEY ([listing_id])
        REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_price_snapshot_transaction] FOREIGN KEY ([transaction_id])
        REFERENCES [dbo].[market_transaction] ([transaction_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_price_snapshot_sample_count] CHECK ([sample_count] >= 0),
    CONSTRAINT [CK_market_price_snapshot_confidence] CHECK ([confidence] IN (N'HIGH', N'MEDIUM', N'LOW')),
    CONSTRAINT [CK_market_price_snapshot_price] CHECK (
        [sell_price] >= 0
        AND [suggested_unit_price] >= 0
        AND ([reference_unit_price] IS NULL OR [reference_unit_price] >= 0)
        AND [allowed_min_unit_price] >= 0
        AND [allowed_max_unit_price] >= [allowed_min_unit_price]
    ),
    CONSTRAINT [CK_market_price_snapshot_roll_score] CHECK ([roll_quality_score] IS NULL OR [roll_quality_score] BETWEEN 0 AND 100)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_price_snapshot_listing]
    ON [dbo].[market_price_snapshot] ([listing_id]);
GO

CREATE NONCLUSTERED INDEX [IX_market_price_snapshot_item_evaluated]
    ON [dbo].[market_price_snapshot] ([item_category], [item_id], [evaluated_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_price_snapshot_signature_evaluated]
    ON [dbo].[market_price_snapshot] ([valuation_signature], [evaluated_at]);
GO
