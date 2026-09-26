-- Apply to the game database before deploying the VIP API. No existing DONOR permission is converted to paid VIP.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_user_permission')
    ALTER TABLE [dbo].[user] DROP CONSTRAINT [CK_user_permission];
UPDATE [dbo].[user] SET [permission] = 0 WHERE [permission] = 5;
ALTER TABLE [dbo].[user] ADD CONSTRAINT [CK_user_permission] CHECK ([permission] IN (0, 99));
IF OBJECT_ID(N'dbo.account_benefits', N'U') IS NULL
BEGIN
CREATE TABLE [dbo].[account_benefits] (
    [account_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_benefits] PRIMARY KEY,
    [instance_priority_uses] INT NOT NULL CONSTRAINT [DF_account_benefits_uses] DEFAULT (0),
    [doner_expires_at] DATETIME2(3) NULL,
    [astralder_expires_at] DATETIME2(3) NULL,
    [astralder_daily_credits_remaining] INT NOT NULL CONSTRAINT [DF_account_benefits_daily] DEFAULT (0),
    [last_daily_claim_date] DATE NULL,
    CONSTRAINT [FK_account_benefits_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
    CONSTRAINT [CK_account_benefits_uses] CHECK ([instance_priority_uses] >= 0),
    CONSTRAINT [CK_account_benefits_daily] CHECK ([astralder_daily_credits_remaining] >= 0)
);
END;
IF OBJECT_ID(N'dbo.account_benefit_operation', N'U') IS NULL
BEGIN

CREATE TABLE [dbo].[account_benefit_operation] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_benefit_operation] PRIMARY KEY,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [kind] NVARCHAR(16) NOT NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [reason] NVARCHAR(100) NULL,
    [inventory_entry_id] UNIQUEIDENTIFIER NULL,
    [awarded_priority_uses] INT NOT NULL,
    [refunded] BIT NOT NULL,
    [created_at_utc] DATETIME2(3) NOT NULL,
    CONSTRAINT [FK_account_benefit_operation_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid])
);
END;
COMMIT;
GO
