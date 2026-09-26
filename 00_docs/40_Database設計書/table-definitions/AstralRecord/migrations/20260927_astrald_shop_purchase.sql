SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.astrald_shop_purchase', N'U') IS NULL
CREATE TABLE [dbo].[astrald_shop_purchase] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_astrald_shop_purchase] PRIMARY KEY,
    [actor_user_uuid] UNIQUEIDENTIFIER NOT NULL,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [item_id] NVARCHAR(64) NOT NULL,
    [channel_id] NVARCHAR(64) NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [price_paid_astrald] INT NOT NULL,
    [effect_type] NVARCHAR(64) NOT NULL,
    [effect_value] FLOAT NOT NULL,
    [duration_seconds] INT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [reason] NVARCHAR(100) NULL,
    [response_json] NVARCHAR(MAX) NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [CK_astrald_shop_purchase_status] CHECK ([status] IN (N'PENDING', N'COMPLETED', N'REJECTED'))
);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_astrald_shop_purchase_account_status')
CREATE INDEX [IX_astrald_shop_purchase_account_status] ON [dbo].[astrald_shop_purchase] ([account_id], [status], [created_at]);
COMMIT;
GO
