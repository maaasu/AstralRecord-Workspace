-- アカウント論理削除後に同一 user / slot へ新規アカウントを作成できるようにする。
ALTER TABLE [dbo].[account]
    DROP CONSTRAINT [UQ_account_user_slot];
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_account_user_slot_active]
    ON [dbo].[account] ([user_id], [slot_index])
    WHERE [is_deleted] = 0;
GO
