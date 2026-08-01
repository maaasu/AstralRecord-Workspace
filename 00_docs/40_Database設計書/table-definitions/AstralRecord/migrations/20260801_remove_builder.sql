/*
  Builder 廃止 migration。
  既存の account mode=1 と ADMIN(99) 以外の user permission を PLAYER(0) へ移し、
  管理用 inventory/loadout profile は BUILDER から ADMIN へ改名します。
  再実行しても同じ最終状態になるよう、制約を再作成します。
*/
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[account]', N'U') IS NOT NULL
BEGIN
    UPDATE [dbo].[account]
    SET [mode] = 0
    WHERE [mode] = 1;

    IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_account_mode' AND [parent_object_id] = OBJECT_ID(N'[dbo].[account]'))
        ALTER TABLE [dbo].[account] DROP CONSTRAINT [CK_account_mode];

    ALTER TABLE [dbo].[account]
        ADD CONSTRAINT [CK_account_mode] CHECK ([mode] IN (0, 2));
END;

IF OBJECT_ID(N'[dbo].[user]', N'U') IS NOT NULL
BEGIN
    UPDATE [dbo].[user]
    SET [permission] = 0
    WHERE [permission] <> 99;

    IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_user_permission' AND [parent_object_id] = OBJECT_ID(N'[dbo].[user]'))
        ALTER TABLE [dbo].[user] DROP CONSTRAINT [CK_user_permission];

    ALTER TABLE [dbo].[user]
        ADD CONSTRAINT [CK_user_permission] CHECK ([permission] IN (0, 99));
END;

IF OBJECT_ID(N'[dbo].[inventory]', N'U') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_inventory_profile' AND [parent_object_id] = OBJECT_ID(N'[dbo].[inventory]'))
        ALTER TABLE [dbo].[inventory] DROP CONSTRAINT [CK_inventory_profile];

    /*
      旧 Builder snapshot は {"builder": {...}} または直形式で保存されている。
      既に admin がある場合は admin を正本として優先し、builder を上書きしない。
    */
    UPDATE [dbo].[inventory]
    SET [metadata_json] = JSON_MODIFY(
        [metadata_json],
        '$.admin',
        JSON_QUERY([metadata_json], '$.builder')
    )
    WHERE ISJSON([metadata_json]) = 1
      AND JSON_QUERY([metadata_json], '$.admin') IS NULL
      AND JSON_QUERY([metadata_json], '$.builder') IS NOT NULL;

    /*
      直形式は OPENJSON で key の有無を判定する。
      JSON_VALUE は 4,000 文字を超える contents を NULL とするため使用しない。
    */
    UPDATE [dbo].[inventory]
    SET [metadata_json] = JSON_MODIFY(N'{}', '$.admin', JSON_QUERY([metadata_json], '$'))
    WHERE [inventory_profile] = N'BUILDER'
      AND ISJSON([metadata_json]) = 1
      AND JSON_QUERY([metadata_json], '$.admin') IS NULL
      AND EXISTS (
          SELECT 1
          FROM OPENJSON([metadata_json])
          WHERE [key] = N'format'
            AND [type] = 1
      )
      AND EXISTS (
          SELECT 1
          FROM OPENJSON([metadata_json])
          WHERE [key] = N'contents'
            AND [type] = 1
      );

    UPDATE [dbo].[inventory]
    SET [metadata_json] = JSON_MODIFY([metadata_json], '$.builder', NULL)
    WHERE ISJSON([metadata_json]) = 1
      AND JSON_QUERY([metadata_json], '$.builder') IS NOT NULL;

    UPDATE [dbo].[inventory]
    SET [inventory_profile] = N'ADMIN'
    WHERE [inventory_profile] = N'BUILDER';

    ALTER TABLE [dbo].[inventory]
        ADD CONSTRAINT [CK_inventory_profile] CHECK ([inventory_profile] IN (N'GAME', N'ADMIN'));
END;

IF OBJECT_ID(N'[dbo].[equipment_loadout]', N'U') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_equipment_loadout_profile' AND [parent_object_id] = OBJECT_ID(N'[dbo].[equipment_loadout]'))
        ALTER TABLE [dbo].[equipment_loadout] DROP CONSTRAINT [CK_equipment_loadout_profile];

    UPDATE [dbo].[equipment_loadout]
    SET [loadout_profile] = N'ADMIN'
    WHERE [loadout_profile] = N'BUILDER';

    ALTER TABLE [dbo].[equipment_loadout]
        ADD CONSTRAINT [CK_equipment_loadout_profile] CHECK ([loadout_profile] IN (N'GAME', N'ADMIN'));
END;

COMMIT TRANSACTION;
