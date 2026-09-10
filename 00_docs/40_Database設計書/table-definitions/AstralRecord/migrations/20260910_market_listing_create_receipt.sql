SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[market_listing_create_receipt]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[market_listing_create_receipt] (
        [operation_id]      UNIQUEIDENTIFIER NOT NULL,
        [seller_account_id] UNIQUEIDENTIFIER NOT NULL,
        [request_hash]      CHAR(64)         NOT NULL,
        [listing_id]        UNIQUEIDENTIFIER NOT NULL,
        [response_json]     NVARCHAR(MAX)    NOT NULL,
        [completed_at]      DATETIME2(3)     NOT NULL,
        [created_at]        DATETIME2(3)     NOT NULL,

        CONSTRAINT [PK_market_listing_create_receipt] PRIMARY KEY CLUSTERED ([operation_id]),
        CONSTRAINT [FK_market_listing_create_receipt_seller_account] FOREIGN KEY ([seller_account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [FK_market_listing_create_receipt_listing] FOREIGN KEY ([listing_id])
            REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [CK_market_listing_create_receipt_hash] CHECK (LEN([request_hash]) = 64),
        CONSTRAINT [CK_market_listing_create_receipt_response_json] CHECK (ISJSON([response_json]) = 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_market_listing_create_receipt_seller_operation'
      AND [object_id] = OBJECT_ID(N'[dbo].[market_listing_create_receipt]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_market_listing_create_receipt_seller_operation]
        ON [dbo].[market_listing_create_receipt] ([seller_account_id], [operation_id]);
END;

COMMIT TRANSACTION;
GO
