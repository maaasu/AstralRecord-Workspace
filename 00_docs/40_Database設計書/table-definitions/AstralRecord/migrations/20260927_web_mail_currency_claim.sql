SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.web_mail_currency_claim', N'U') IS NULL
CREATE TABLE [dbo].[web_mail_currency_claim] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_web_mail_currency_claim] PRIMARY KEY,
    [actor_user_uuid] UNIQUEIDENTIFIER NOT NULL,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [mail_id] NVARCHAR(200) NOT NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [currency_rewards_json] NVARCHAR(MAX) NOT NULL,
    [has_non_currency_rewards] BIT NOT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [reason] NVARCHAR(100) NULL,
    [response_json] NVARCHAR(MAX) NULL,
    [created_at] DATETIME2(3) NOT NULL,
    [updated_at] DATETIME2(3) NOT NULL,
    CONSTRAINT [CK_web_mail_currency_claim_status] CHECK ([status] IN (N'PENDING', N'COMPLETED', N'REJECTED'))
);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_web_mail_currency_claim_account_mail')
CREATE UNIQUE INDEX [UX_web_mail_currency_claim_account_mail] ON [dbo].[web_mail_currency_claim] ([account_id], [mail_id]) WHERE [status] <> N'REJECTED';
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_web_mail_currency_claim_account_status')
CREATE INDEX [IX_web_mail_currency_claim_account_status] ON [dbo].[web_mail_currency_claim] ([account_id], [status], [created_at]);
COMMIT;
GO
