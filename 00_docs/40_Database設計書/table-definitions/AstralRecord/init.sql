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

    CONSTRAINT [PK_user] PRIMARY KEY CLUSTERED ([uuid])
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
-- AstralRecord\dbo.account.md
-- ============================================================

CREATE TABLE [dbo].[account] (
    [uuid]           UNIQUEIDENTIFIER  NOT NULL,
    [user_id]        UNIQUEIDENTIFIER  NOT NULL,
    [account_name]   NVARCHAR(50)      NOT NULL,
    [slot_index]     INT               NOT NULL,
    [is_active]      BIT               NOT NULL  CONSTRAINT [DF_account_is_active]   DEFAULT (0),
    [mode]           TINYINT           NOT NULL  CONSTRAINT [DF_account_mode]         DEFAULT (0),
    [menu_shortcuts_json] NVARCHAR(MAX) NOT NULL  CONSTRAINT [DF_account_menu_shortcuts_json] DEFAULT (N'["INVENTORY_NORMAL","INVENTORY_EQUIPMENT","INVENTORY_RUNE","INVENTORY_CURRENCY"]'),
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
    CONSTRAINT [CK_account_mode] CHECK ([mode] IN (0, 1, 2)),
    CONSTRAINT [CK_account_menu_shortcuts_json] CHECK (ISJSON([menu_shortcuts_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_account_user_id]
    ON [dbo].[account] ([user_id]);
GO

CREATE NONCLUSTERED INDEX [IX_account_is_deleted]
    ON [dbo].[account] ([is_deleted]);
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
    CONSTRAINT [CK_inventory_profile] CHECK ([inventory_profile] IN (N'GAME', N'BUILDER')),
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
    CONSTRAINT [CK_equipment_loadout_profile] CHECK ([loadout_profile] IN (N'GAME', N'BUILDER'))
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
    [pool_index]                  INT               NOT NULL,
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
    CONSTRAINT [UQ_equipment_instance_enchant_pool_index] UNIQUE ([equipment_instance_id], [pool_index])
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_enchant_equipment_instance_id]
    ON [dbo].[equipment_instance_enchant] ([equipment_instance_id]);
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
