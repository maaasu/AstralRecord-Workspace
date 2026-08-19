/*
  寄付者権限 DONOR(5) を追加する migration。
  既存ユーザーの permission は変更せず、dbo.user の許可値だけを更新します。
  再実行時も同じ制約を再作成できるようにします。
*/
SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[user]', N'U') IS NOT NULL
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sys.check_constraints
        WHERE [name] = N'CK_user_permission'
          AND [parent_object_id] = OBJECT_ID(N'[dbo].[user]')
    )
    BEGIN
        ALTER TABLE [dbo].[user] DROP CONSTRAINT [CK_user_permission];
    END;

    ALTER TABLE [dbo].[user]
        ADD CONSTRAINT [CK_user_permission] CHECK ([permission] IN (0, 5, 99));
END;

COMMIT TRANSACTION;
GO
