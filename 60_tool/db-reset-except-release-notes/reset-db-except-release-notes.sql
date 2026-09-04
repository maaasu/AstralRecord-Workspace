-- ============================================================
-- Reset application data while preserving release note data
-- ============================================================
--
-- Execute this batch while connected to exactly one of:
--   AstralRecord, MasterDataDB, HistoryDB
--
-- AstralRecord.dbo.release_note and
-- AstralRecord.dbo.release_notification_outbox are preserved.
-- Every other user table is emptied. The schema is not dropped.
--
-- The script temporarily sets the target database to SINGLE_USER with
-- ROLLBACK IMMEDIATE, then restores its original access mode.
--
-- The batch is also used by DbResetExceptReleaseNotesTool. Do not add
-- GO statements because the .NET tool sends this file as one command.

SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @DatabaseName sysname = DB_NAME();

IF @DatabaseName NOT IN (N'AstralRecord', N'MasterDataDB', N'HistoryDB')
BEGIN
    THROW 51000, N'This reset script only accepts AstralRecord, MasterDataDB, or HistoryDB.', 1;
END;

DECLARE @PreserveReleaseNoteData bit =
    CASE WHEN @DatabaseName = N'AstralRecord' THEN 1 ELSE 0 END;
DECLARE @ReleaseNoteObjectId int = OBJECT_ID(N'dbo.release_note', N'U');
DECLARE @ReleaseNotificationOutboxObjectId int = OBJECT_ID(N'dbo.release_notification_outbox', N'U');
DECLARE @OriginalUserAccess nvarchar(60);
DECLARE @RestoreAccessMode nvarchar(20);
DECLARE @DatabaseIdentifier nvarchar(258) = QUOTENAME(@DatabaseName);
DECLARE @RestoreAccessSql nvarchar(max);
DECLARE @AccessModeChanged bit = 0;

SELECT @OriginalUserAccess = user_access_desc
FROM [master].sys.databases
WHERE name = @DatabaseName;

SET @RestoreAccessMode = CASE @OriginalUserAccess
    WHEN N'MULTI_USER' THEN N'MULTI_USER'
    WHEN N'SINGLE_USER' THEN N'SINGLE_USER'
    WHEN N'RESTRICTED_USER' THEN N'RESTRICTED_USER'
    ELSE NULL
END;

IF @RestoreAccessMode IS NULL
BEGIN
    THROW 51002, N'The target database access mode could not be determined.', 1;
END;

SET @RestoreAccessSql = N'USE [master]; ALTER DATABASE ' + @DatabaseIdentifier
    + N' SET ' + @RestoreAccessMode + N';';

IF @PreserveReleaseNoteData = 1
   AND (@ReleaseNoteObjectId IS NULL OR @ReleaseNotificationOutboxObjectId IS NULL)
BEGIN
    THROW 51001, N'AstralRecord release note tables are missing; reset was canceled to avoid losing the preserved data.', 1;
END;

DECLARE @KeptTables TABLE
(
    object_id int NOT NULL PRIMARY KEY
);

IF @PreserveReleaseNoteData = 1
BEGIN
    INSERT INTO @KeptTables (object_id)
    VALUES (@ReleaseNoteObjectId), (@ReleaseNotificationOutboxObjectId);
END;

DECLARE @TablesToReset TABLE
(
    object_id       int            NOT NULL PRIMARY KEY,
    qualified_name  nvarchar(517)  NOT NULL,
    has_identity    bit            NOT NULL
);

INSERT INTO @TablesToReset (object_id, qualified_name, has_identity)
SELECT
    table_info.object_id,
    QUOTENAME(table_info.schema_name) + N'.' + QUOTENAME(table_info.table_name),
    CONVERT(bit, CASE WHEN EXISTS
    (
        SELECT 1
        FROM sys.identity_columns AS identity_info
        WHERE identity_info.object_id = table_info.object_id
    ) THEN 1 ELSE 0 END)
FROM
(
    SELECT
        table_info.object_id,
        schema_info.name AS schema_name,
        table_info.name AS table_name
    FROM sys.tables AS table_info
    INNER JOIN sys.schemas AS schema_info
        ON schema_info.schema_id = table_info.schema_id
    WHERE table_info.is_ms_shipped = 0
) AS table_info
WHERE NOT EXISTS
(
    SELECT 1
    FROM @KeptTables AS kept
    WHERE kept.object_id = table_info.object_id
);

DECLARE @ForeignKeys TABLE
(
    foreign_key_object_id int            NOT NULL PRIMARY KEY,
    parent_table_name     nvarchar(517)  NOT NULL,
    constraint_name       sysname        NOT NULL,
    was_disabled           bit            NOT NULL,
    was_not_trusted        bit            NOT NULL
);

INSERT INTO @ForeignKeys
(
    foreign_key_object_id,
    parent_table_name,
    constraint_name,
    was_disabled,
    was_not_trusted
)
SELECT
    foreign_key_info.object_id,
    QUOTENAME(schema_info.name) + N'.' + QUOTENAME(table_info.name),
    foreign_key_info.name,
    CONVERT(bit, foreign_key_info.is_disabled),
    CONVERT(bit, foreign_key_info.is_not_trusted)
FROM sys.foreign_keys AS foreign_key_info
INNER JOIN sys.tables AS table_info
    ON table_info.object_id = foreign_key_info.parent_object_id
INNER JOIN sys.schemas AS schema_info
    ON schema_info.schema_id = table_info.schema_id
WHERE table_info.is_ms_shipped = 0;

DECLARE @TotalDeletedRows bigint = 0;
DECLARE @Sql nvarchar(max);
DECLARE @ParentTableName nvarchar(517);
DECLARE @ConstraintName sysname;
DECLARE @WasNotTrusted bit;

BEGIN TRY
    -- Prevent application writes from racing with the reset. The dynamic batch
    -- changes context to master so this session remains the single user.
    SET @Sql = N'USE [master]; ALTER DATABASE ' + @DatabaseIdentifier
        + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE;';
    EXEC sys.sp_executesql @Sql;
    SET @AccessModeChanged = 1;

    BEGIN TRANSACTION;

    -- Disable only currently enabled foreign keys so every table can be emptied
    -- regardless of dependency order. The original state is restored below.
    DECLARE disable_foreign_keys_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT parent_table_name, constraint_name
        FROM @ForeignKeys
        WHERE was_disabled = 0;

    OPEN disable_foreign_keys_cursor;
    FETCH NEXT FROM disable_foreign_keys_cursor INTO @ParentTableName, @ConstraintName;

    WHILE @@FETCH_STATUS = 0
    BEGIN
        SET @Sql = N'ALTER TABLE ' + @ParentTableName
            + N' NOCHECK CONSTRAINT ' + QUOTENAME(@ConstraintName) + N';';
        EXEC sys.sp_executesql @Sql;

        FETCH NEXT FROM disable_foreign_keys_cursor INTO @ParentTableName, @ConstraintName;
    END;

    CLOSE disable_foreign_keys_cursor;
    DEALLOCATE disable_foreign_keys_cursor;

    DECLARE @QualifiedTableName nvarchar(517);
    DECLARE @HasIdentity bit;
    DECLARE @DeletedRows bigint;

    DECLARE reset_tables_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT qualified_name, has_identity
        FROM @TablesToReset;

    OPEN reset_tables_cursor;
    FETCH NEXT FROM reset_tables_cursor INTO @QualifiedTableName, @HasIdentity;

    WHILE @@FETCH_STATUS = 0
    BEGIN
        SET @DeletedRows = 0;
        SET @Sql = N'DELETE FROM ' + @QualifiedTableName
            + N'; SET @DeletedRows = CONVERT(bigint, @@ROWCOUNT);';
        EXEC sys.sp_executesql
            @Sql,
            N'@DeletedRows bigint OUTPUT',
            @DeletedRows = @DeletedRows OUTPUT;

        SET @TotalDeletedRows += @DeletedRows;
        PRINT N'Deleted ' + CONVERT(nvarchar(30), @DeletedRows)
            + N' rows from ' + @QualifiedTableName + N'.';

        IF @HasIdentity = 1
        BEGIN
            SET @Sql = N'DBCC CHECKIDENT (N''' + REPLACE(@QualifiedTableName, N'''', N'''''')
                + N''', RESEED, 0) WITH NO_INFOMSGS;';
            EXEC sys.sp_executesql @Sql;
        END;

        FETCH NEXT FROM reset_tables_cursor INTO @QualifiedTableName, @HasIdentity;
    END;

    CLOSE reset_tables_cursor;
    DEALLOCATE reset_tables_cursor;

    -- Restore every foreign key to its state before this script started.
    DECLARE restore_foreign_keys_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT parent_table_name, constraint_name, was_not_trusted
        FROM @ForeignKeys
        WHERE was_disabled = 0;

    OPEN restore_foreign_keys_cursor;
    FETCH NEXT FROM restore_foreign_keys_cursor
        INTO @ParentTableName, @ConstraintName, @WasNotTrusted;

    WHILE @@FETCH_STATUS = 0
    BEGIN
        IF @WasNotTrusted = 1
        BEGIN
            SET @Sql = N'ALTER TABLE ' + @ParentTableName
                + N' CHECK CONSTRAINT ' + QUOTENAME(@ConstraintName) + N';';
        END
        ELSE
        BEGIN
            SET @Sql = N'ALTER TABLE ' + @ParentTableName
                + N' WITH CHECK CHECK CONSTRAINT ' + QUOTENAME(@ConstraintName) + N';';
        END;

        EXEC sys.sp_executesql @Sql;

        FETCH NEXT FROM restore_foreign_keys_cursor
            INTO @ParentTableName, @ConstraintName, @WasNotTrusted;
    END;

    CLOSE restore_foreign_keys_cursor;
    DEALLOCATE restore_foreign_keys_cursor;

    COMMIT TRANSACTION;

    EXEC sys.sp_executesql @RestoreAccessSql;
    SET @AccessModeChanged = 0;
END TRY
BEGIN CATCH
    DECLARE @ResetErrorMessage nvarchar(2048) = ERROR_MESSAGE();

    IF XACT_STATE() <> 0
    BEGIN
        ROLLBACK TRANSACTION;
    END;

    IF @AccessModeChanged = 1
    BEGIN
        BEGIN TRY
            EXEC sys.sp_executesql @RestoreAccessSql;
            SET @AccessModeChanged = 0;
        END TRY
        BEGIN CATCH
            DECLARE @RestoreErrorMessage nvarchar(2048) = ERROR_MESSAGE();
            DECLARE @CombinedErrorMessage nvarchar(2048) = LEFT(
                N'Database reset failed and the original database access mode could not be restored. '
                + N'Reset error: ' + COALESCE(@ResetErrorMessage, N'')
                + N' Restore error: ' + COALESCE(@RestoreErrorMessage, N''),
                2048);
            THROW 51003, @CombinedErrorMessage, 1;
        END CATCH;
    END;

    THROW;
END CATCH;

SELECT
    @DatabaseName AS database_name,
    @PreserveReleaseNoteData AS release_note_data_preserved,
    @TotalDeletedRows AS deleted_rows;
