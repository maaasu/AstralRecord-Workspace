-- 既存のバインド値を保持し、プリセット番号の上限だけを拡張する。
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.skill_bind_preset', N'U') IS NOT NULL
BEGIN
    IF OBJECT_ID(N'dbo.CK_skill_bind_preset_index', N'C') IS NOT NULL
        ALTER TABLE dbo.skill_bind_preset DROP CONSTRAINT CK_skill_bind_preset_index;
    ALTER TABLE dbo.skill_bind_preset WITH CHECK
        ADD CONSTRAINT CK_skill_bind_preset_index CHECK (preset_index BETWEEN 1 AND 9);
END;
COMMIT TRANSACTION;
