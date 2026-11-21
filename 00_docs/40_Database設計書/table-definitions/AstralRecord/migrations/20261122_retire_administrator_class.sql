-- RPGサーバーを停止し、アカウントの保存がすべて完了していることを確認する。
-- administrator を削除する Filebase の同期前に、この移行を実行する。
SET XACT_ABORT ON;
BEGIN TRANSACTION;

BEGIN TRY
    DECLARE @updated_at datetime2(3) = SYSUTCDATETIME();
    DECLARE @targets TABLE (
        account_id uniqueidentifier NOT NULL PRIMARY KEY,
        class_level int NOT NULL,
        class_experience bigint NOT NULL
    );

    INSERT INTO @targets (account_id, class_level, class_experience)
    SELECT a.uuid, a.class_level, a.class_experience
    FROM dbo.account AS a WITH (UPDLOCK, HOLDLOCK)
    WHERE a.class_id = N'administrator'
      AND a.is_deleted = 0;

    INSERT INTO dbo.account_class_progress (
        account_id, class_id, level, experience, updated_at, updated_by
    )
    SELECT t.account_id, N'administrator', t.class_level, t.class_experience,
           @updated_at, t.account_id
    FROM @targets AS t
    WHERE NOT EXISTS (
        SELECT 1
        FROM dbo.account_class_progress AS p WITH (UPDLOCK, HOLDLOCK)
        WHERE p.account_id = t.account_id
          AND p.class_id = N'administrator'
    );

    INSERT INTO dbo.account_class_progress (
        account_id, class_id, level, experience, updated_at, updated_by
    )
    SELECT t.account_id, N'adventurer', 1, 0, @updated_at, t.account_id
    FROM @targets AS t
    WHERE NOT EXISTS (
        SELECT 1
        FROM dbo.account_class_progress AS p WITH (UPDLOCK, HOLDLOCK)
        WHERE p.account_id = t.account_id
          AND p.class_id = N'adventurer'
    );

    UPDATE a
    SET class_id = p.class_id,
        class_level = p.level,
        class_experience = p.experience,
        progress_version = a.progress_version + 1,
        updated_at = @updated_at,
        updated_by = t.account_id
    FROM dbo.account AS a
    INNER JOIN @targets AS t ON t.account_id = a.uuid
    INNER JOIN dbo.account_class_progress AS p
        ON p.account_id = t.account_id
       AND p.class_id = N'adventurer';

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    THROW;
END CATCH;
