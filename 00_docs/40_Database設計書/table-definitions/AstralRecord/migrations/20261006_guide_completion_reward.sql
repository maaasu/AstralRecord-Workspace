SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.account_guide_completion_reward', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[account_guide_completion_reward] (
        [account_id] UNIQUEIDENTIFIER NOT NULL,
        [mail_id] NVARCHAR(128) NULL,
        [claimed_at] DATETIME2(3) NOT NULL,
        [claimed_by] UNIQUEIDENTIFIER NOT NULL,
        [inherited_from_account_id] UNIQUEIDENTIFIER NULL,
        CONSTRAINT [PK_account_guide_completion_reward] PRIMARY KEY CLUSTERED ([account_id]),
        CONSTRAINT [FK_account_guide_completion_reward_account] FOREIGN KEY ([account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [CK_account_guide_completion_reward_mail] CHECK ([mail_id] IS NULL OR LEN(LTRIM(RTRIM([mail_id]))) > 0),
        CONSTRAINT [CK_account_guide_completion_reward_origin] CHECK (([mail_id] IS NULL AND [inherited_from_account_id] IS NOT NULL) OR ([mail_id] IS NOT NULL AND [inherited_from_account_id] IS NULL))
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.account_guide_completion_reward') AND [name] = N'UX_account_guide_completion_reward_mail')
    CREATE UNIQUE INDEX [UX_account_guide_completion_reward_mail] ON [dbo].[account_guide_completion_reward] ([mail_id]) WHERE [mail_id] IS NOT NULL;

COMMIT TRANSACTION;
