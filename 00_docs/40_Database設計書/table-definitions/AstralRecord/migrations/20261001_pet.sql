SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.pet_instance', N'U') IS NULL
CREATE TABLE [dbo].[pet_instance] (
    [instance_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_pet_instance] PRIMARY KEY,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [species_id] NVARCHAR(64) NOT NULL,
    [item_id] NVARCHAR(100) NOT NULL,
    [is_egg] BIT NOT NULL,
    [origin] NVARCHAR(8) NOT NULL,
    [details_json] NVARCHAR(MAX) NOT NULL,
    [male_parent_id] UNIQUEIDENTIFIER NULL,
    [female_parent_id] UNIQUEIDENTIFIER NULL,
    [version] BIGINT NOT NULL CONSTRAINT [DF_pet_instance_version] DEFAULT (1),
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    [created_by] UNIQUEIDENTIFIER NOT NULL,
    [updated_by] UNIQUEIDENTIFIER NOT NULL,
    [is_deleted] BIT NOT NULL CONSTRAINT [DF_pet_instance_is_deleted] DEFAULT (0),
    CONSTRAINT [FK_pet_instance_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
    CONSTRAINT [CK_pet_instance_origin] CHECK ([origin] IN (N'WILD',N'BRED')),
    CONSTRAINT [CK_pet_instance_details_json] CHECK (ISJSON([details_json]) = 1),
    CONSTRAINT [CK_pet_instance_version] CHECK ([version] >= 1),
    CONSTRAINT [CK_pet_instance_parent_pair] CHECK (([origin]=N'WILD' AND [male_parent_id] IS NULL AND [female_parent_id] IS NULL)
        OR ([origin]=N'BRED' AND [male_parent_id] IS NOT NULL AND [female_parent_id] IS NOT NULL AND [male_parent_id]<>[female_parent_id]))
);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.pet_instance') AND name=N'IX_pet_instance_owner')
CREATE INDEX [IX_pet_instance_owner] ON [dbo].[pet_instance] ([account_id],[is_deleted]);

IF OBJECT_ID(N'dbo.account_pet_state', N'U') IS NULL
CREATE TABLE [dbo].[account_pet_state] (
    [account_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_pet_state] PRIMARY KEY,
    [equipped_pet_id] UNIQUEIDENTIFIER NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    [updated_by] UNIQUEIDENTIFIER NOT NULL,
    CONSTRAINT [FK_account_pet_state_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
    CONSTRAINT [FK_account_pet_state_instance] FOREIGN KEY ([equipped_pet_id]) REFERENCES [dbo].[pet_instance] ([instance_id])
);

IF OBJECT_ID(N'dbo.pet_operation', N'U') IS NULL
CREATE TABLE [dbo].[pet_operation] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_pet_operation] PRIMARY KEY,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [request_hash] CHAR(64) NOT NULL,
    [response_json] NVARCHAR(MAX) NOT NULL,
    [affected_entry_ids_json] NVARCHAR(MAX) NOT NULL,
    [completed_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [FK_pet_operation_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
    CONSTRAINT [CK_pet_operation_json] CHECK (ISJSON([response_json])=1 AND ISJSON([affected_entry_ids_json])=1)
);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.pet_operation') AND name=N'IX_pet_operation_owner_completed')
CREATE INDEX [IX_pet_operation_owner_completed] ON [dbo].[pet_operation] ([account_id],[completed_at]);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.inventory_entry') AND name=N'UX_inventory_entry_pet_instance')
CREATE UNIQUE INDEX [UX_inventory_entry_pet_instance] ON [dbo].[inventory_entry] ([instance_id])
    WHERE [is_deleted]=0 AND [instance_type] IN (N'PET',N'PET_EGG');
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE parent_object_id=OBJECT_ID(N'dbo.inventory_entry') AND name=N'CK_inventory_entry_pet_quantity')
ALTER TABLE [dbo].[inventory_entry] ADD CONSTRAINT [CK_inventory_entry_pet_quantity]
    CHECK ([instance_type] NOT IN (N'PET',N'PET_EGG') OR [quantity]=1);
COMMIT;
GO
