USE [AstralRecord];
GO

SET XACT_ABORT ON;
GO

BEGIN TRY
    BEGIN TRANSACTION;

    /* 既存のユーザー単位の状態・動的配信は、仕様変更に伴い引き継がない。 */
    DELETE FROM [dbo].[player_mail_state];
    DELETE FROM [dbo].[player_mail_delivery];

    IF EXISTS (
        SELECT 1 FROM sys.foreign_keys
        WHERE [name] = N'FK_player_mail_state_user'
          AND [parent_object_id] = OBJECT_ID(N'[dbo].[player_mail_state]')
    )
        ALTER TABLE [dbo].[player_mail_state] DROP CONSTRAINT [FK_player_mail_state_user];

    IF EXISTS (
        SELECT 1 FROM sys.foreign_keys
        WHERE [name] = N'FK_player_mail_delivery_user'
          AND [parent_object_id] = OBJECT_ID(N'[dbo].[player_mail_delivery]')
    )
        ALTER TABLE [dbo].[player_mail_delivery] DROP CONSTRAINT [FK_player_mail_delivery_user];

    IF EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'UX_player_mail_state_user_mail'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_state]')
    )
        DROP INDEX [UX_player_mail_state_user_mail] ON [dbo].[player_mail_state];

    IF EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'IX_player_mail_state_user_id'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_state]')
    )
        DROP INDEX [IX_player_mail_state_user_id] ON [dbo].[player_mail_state];

    IF EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'UX_player_mail_delivery_user_mail'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_delivery]')
    )
        DROP INDEX [UX_player_mail_delivery_user_mail] ON [dbo].[player_mail_delivery];

    IF EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'IX_player_mail_delivery_user_id'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_delivery]')
    )
        DROP INDEX [IX_player_mail_delivery_user_id] ON [dbo].[player_mail_delivery];

    IF COL_LENGTH(N'dbo.player_mail_state', N'user_id') IS NOT NULL
       AND COL_LENGTH(N'dbo.player_mail_state', N'account_id') IS NULL
        EXEC sys.sp_rename N'dbo.player_mail_state.user_id', N'account_id', N'COLUMN';

    IF COL_LENGTH(N'dbo.player_mail_delivery', N'user_id') IS NOT NULL
       AND COL_LENGTH(N'dbo.player_mail_delivery', N'account_id') IS NULL
        EXEC sys.sp_rename N'dbo.player_mail_delivery.user_id', N'account_id', N'COLUMN';

    IF NOT EXISTS (
        SELECT 1 FROM sys.foreign_keys
        WHERE [name] = N'FK_player_mail_state_account'
          AND [parent_object_id] = OBJECT_ID(N'[dbo].[player_mail_state]')
    )
        ALTER TABLE [dbo].[player_mail_state]
            ADD CONSTRAINT [FK_player_mail_state_account] FOREIGN KEY ([account_id])
                REFERENCES [dbo].[account] ([uuid])
                ON DELETE NO ACTION ON UPDATE NO ACTION;

    IF NOT EXISTS (
        SELECT 1 FROM sys.foreign_keys
        WHERE [name] = N'FK_player_mail_delivery_account'
          AND [parent_object_id] = OBJECT_ID(N'[dbo].[player_mail_delivery]')
    )
        ALTER TABLE [dbo].[player_mail_delivery]
            ADD CONSTRAINT [FK_player_mail_delivery_account] FOREIGN KEY ([account_id])
                REFERENCES [dbo].[account] ([uuid])
                ON DELETE NO ACTION ON UPDATE NO ACTION;

    IF NOT EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'UX_player_mail_state_account_mail'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_state]')
    )
        CREATE UNIQUE NONCLUSTERED INDEX [UX_player_mail_state_account_mail]
            ON [dbo].[player_mail_state] ([account_id], [mail_id]);

    IF NOT EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'IX_player_mail_state_account_id'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_state]')
    )
        CREATE NONCLUSTERED INDEX [IX_player_mail_state_account_id]
            ON [dbo].[player_mail_state] ([account_id]);

    IF NOT EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'UX_player_mail_delivery_account_mail'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_delivery]')
    )
        CREATE UNIQUE NONCLUSTERED INDEX [UX_player_mail_delivery_account_mail]
            ON [dbo].[player_mail_delivery] ([account_id], [mail_id]);

    IF NOT EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'IX_player_mail_delivery_account_id'
          AND [object_id] = OBJECT_ID(N'[dbo].[player_mail_delivery]')
    )
        CREATE NONCLUSTERED INDEX [IX_player_mail_delivery_account_id]
            ON [dbo].[player_mail_delivery] ([account_id]);

    /* アカウント名は設計上 NVARCHAR(50) とする。既存値は切り捨てず、契約違反を明示して停止する。 */
    IF EXISTS (
        SELECT 1 FROM [dbo].[account]
        WHERE [account_name] IS NULL
           OR LEN([account_name]) = 0
           OR LEN([account_name]) > 50
    )
        THROW 51002, 'Existing account_name values must contain 1-50 characters.', 1;

    IF EXISTS (
        SELECT 1 FROM sys.columns
        WHERE [object_id] = OBJECT_ID(N'[dbo].[account]')
          AND [name] = N'account_name'
          AND (
                [system_type_id] <> 231 /* nvarchar */
                OR [max_length] <> 100 /* nvarchar(50) は100バイト */
                OR [is_nullable] = 1
          )
    )
    BEGIN
        /* 型変更前に、この更新で追加した補助列とそのインデックスを外す。 */
        IF EXISTS (
            SELECT 1 FROM sys.indexes
            WHERE [name] = N'UX_account_account_name_active'
              AND [object_id] = OBJECT_ID(N'[dbo].[account]')
        )
            DROP INDEX [UX_account_account_name_active] ON [dbo].[account];

        IF EXISTS (
            SELECT 1 FROM sys.columns
            WHERE [object_id] = OBJECT_ID(N'[dbo].[account]')
              AND [name] = N'account_name_normalized'
        )
            ALTER TABLE [dbo].[account]
                DROP COLUMN [account_name_normalized];

        ALTER TABLE [dbo].[account]
            ALTER COLUMN [account_name] NVARCHAR(50) NOT NULL;
    END;

    /* 既存の補助列が不正な型で残っている場合も、正しい定義へ再作成する。 */
    IF EXISTS (
        SELECT 1
        FROM sys.columns AS [c]
        LEFT JOIN sys.computed_columns AS [cc]
            ON [cc].[object_id] = [c].[object_id]
           AND [cc].[column_id] = [c].[column_id]
        WHERE [c].[object_id] = OBJECT_ID(N'[dbo].[account]')
          AND [c].[name] = N'account_name_normalized'
          AND (
                [c].[is_computed] = 0
                OR [c].[system_type_id] <> 231 /* nvarchar */
                OR [c].[max_length] <> 100 /* nvarchar(50) は100バイト */
                OR ISNULL([cc].[is_persisted], 0) <> 1
          )
    )
    BEGIN
        IF EXISTS (
            SELECT 1 FROM sys.indexes
            WHERE [name] = N'UX_account_account_name_active'
              AND [object_id] = OBJECT_ID(N'[dbo].[account]')
        )
            DROP INDEX [UX_account_account_name_active] ON [dbo].[account];

        ALTER TABLE [dbo].[account]
            DROP COLUMN [account_name_normalized];
    END;

    /* 既存の未削除重複は、先に作成された行を残して account_name(N) へ退避する。 */
    DECLARE @accountId UNIQUEIDENTIFIER;
    DECLARE @accountName NVARCHAR(50);
    DECLARE @normalizedName NVARCHAR(50);
    DECLARE @lastNormalizedName NVARCHAR(50) = NULL;
    DECLARE @suffixIndex INT;
    DECLARE @suffix NVARCHAR(50);
    DECLARE @candidate NVARCHAR(50);
    DECLARE @prefixLength INT;

    DECLARE account_name_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT [uuid], [account_name]
        FROM [dbo].[account]
        WHERE [is_deleted] = 0
        ORDER BY LOWER([account_name]), [created_at], [uuid];

    OPEN account_name_cursor;
    FETCH NEXT FROM account_name_cursor INTO @accountId, @accountName;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        SET @normalizedName = LOWER(@accountName);
        IF @lastNormalizedName IS NOT NULL AND @normalizedName = @lastNormalizedName
        BEGIN
            SET @suffixIndex = 1;
            WHILE 1 = 1
            BEGIN
                SET @suffix = CONCAT(N'(', @suffixIndex, N')');
                SET @prefixLength = 50 - LEN(@suffix);
                IF @prefixLength <= 0
                    THROW 51001, 'No generated account name is available.', 1;
                SET @candidate = LEFT(@accountName, @prefixLength) + @suffix;
                IF NOT EXISTS (
                    SELECT 1 FROM [dbo].[account]
                    WHERE [is_deleted] = 0
                      AND [uuid] <> @accountId
                      AND LOWER([account_name]) = LOWER(@candidate)
                )
                    BREAK;
                SET @suffixIndex += 1;
            END;
            UPDATE [dbo].[account]
            SET [account_name] = @candidate,
                [updated_at] = SYSUTCDATETIME()
            WHERE [uuid] = @accountId;
        END
        ELSE
            SET @lastNormalizedName = @normalizedName;

        FETCH NEXT FROM account_name_cursor INTO @accountId, @accountName;
    END;
    CLOSE account_name_cursor;
    DEALLOCATE account_name_cursor;

    IF NOT EXISTS (
        SELECT 1 FROM sys.columns
        WHERE [object_id] = OBJECT_ID(N'[dbo].[account]')
          AND [name] = N'account_name_normalized'
    )
        ALTER TABLE [dbo].[account]
            ADD [account_name_normalized] AS (CONVERT(NVARCHAR(50), LOWER([account_name]))) PERSISTED;

    IF NOT EXISTS (
        SELECT 1 FROM sys.indexes
        WHERE [name] = N'UX_account_account_name_active'
          AND [object_id] = OBJECT_ID(N'[dbo].[account]')
    )
        CREATE UNIQUE NONCLUSTERED INDEX [UX_account_account_name_active]
            ON [dbo].[account] ([account_name_normalized])
            WHERE [is_deleted] = 0;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    THROW;
END CATCH;
GO
