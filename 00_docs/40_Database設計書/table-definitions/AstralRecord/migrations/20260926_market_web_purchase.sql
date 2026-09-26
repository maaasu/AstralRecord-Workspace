SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[market_web_purchase]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[market_web_purchase] (
        [operation_id] UNIQUEIDENTIFIER NOT NULL,
        [actor_user_uuid] UNIQUEIDENTIFIER NOT NULL,
        [buyer_account_id] UNIQUEIDENTIFIER NOT NULL,
        [listing_id] UNIQUEIDENTIFIER NOT NULL,
        [quantity] BIGINT NOT NULL,
        [status] NVARCHAR(16) NOT NULL,
        [transaction_id] UNIQUEIDENTIFIER NULL,
        [error_code] NVARCHAR(100) NULL,
        [created_at] DATETIME2(3) NOT NULL,
        [updated_at] DATETIME2(3) NOT NULL,
        CONSTRAINT [PK_market_web_purchase] PRIMARY KEY CLUSTERED ([operation_id]),
        CONSTRAINT [FK_market_web_purchase_buyer] FOREIGN KEY ([buyer_account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION,
        CONSTRAINT [FK_market_web_purchase_listing] FOREIGN KEY ([listing_id])
            REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION,
        CONSTRAINT [CK_market_web_purchase_quantity] CHECK ([quantity] > 0),
        CONSTRAINT [CK_market_web_purchase_status] CHECK ([status] IN (N'PENDING', N'COMPLETED', N'FAILED'))
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'[dbo].[market_web_purchase]')
    AND [name] = N'IX_market_web_purchase_account_status')
BEGIN
    CREATE NONCLUSTERED INDEX [IX_market_web_purchase_account_status]
        ON [dbo].[market_web_purchase] ([buyer_account_id], [status], [created_at]);
END;

COMMIT TRANSACTION;
GO
