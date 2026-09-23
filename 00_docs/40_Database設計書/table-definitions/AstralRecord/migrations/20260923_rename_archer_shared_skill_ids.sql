-- Run during maintenance with all game servers stopped and admission closed.
-- Do not resume learned-skill reconciliation until the new Filebase masters have been seeded.
-- The deployment runner may apply this before Filebase deployment; keep servers stopped throughout.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

DECLARE @AirShiftUpdatedRows INT = 0;
DECLARE @HealArrowAlphaUpdatedRows INT = 0;
DECLARE @SkillBindPresetUpdatedRows INT = 0;

UPDATE [dbo].[account_learned_skill]
SET [skill_id] = N'archer_air_shift',
    [version] = [version] + 1,
    [updated_at] = SYSUTCDATETIME(),
    [updated_by] = [account_id]
WHERE [skill_id] = N'hunter_air_shift'
  AND [is_deleted] = 0;
SET @AirShiftUpdatedRows = @@ROWCOUNT;

UPDATE [dbo].[account_learned_skill]
SET [skill_id] = N'archer_heal_arrow_alpha',
    [version] = [version] + 1,
    [updated_at] = SYSUTCDATETIME(),
    [updated_by] = [account_id]
WHERE [skill_id] = N'hunter_heal_arrow_alpha'
  AND [is_deleted] = 0;
SET @HealArrowAlphaUpdatedRows = @@ROWCOUNT;

-- Some older presets store raw skill IDs instead of learned_skill_id UUIDs.
-- Rename those aliases too, so the API can resolve them to the preserved learned-skill instances.
UPDATE [dbo].[skill_bind_preset]
SET [active_skill_slots_json] = REPLACE(
        REPLACE([active_skill_slots_json], N'hunter_air_shift', N'archer_air_shift'),
        N'hunter_heal_arrow_alpha', N'archer_heal_arrow_alpha'),
    [passive_skill_slots_json] = REPLACE(
        REPLACE([passive_skill_slots_json], N'hunter_air_shift', N'archer_air_shift'),
        N'hunter_heal_arrow_alpha', N'archer_heal_arrow_alpha'),
    [left_click_skill_id] = CASE [left_click_skill_id]
        WHEN N'hunter_air_shift' THEN N'archer_air_shift'
        WHEN N'hunter_heal_arrow_alpha' THEN N'archer_heal_arrow_alpha'
        ELSE [left_click_skill_id]
    END,
    [version] = [version] + 1,
    [updated_at] = SYSUTCDATETIME(),
    [updated_by] = [account_id]
WHERE [is_deleted] = 0
  AND (
      CHARINDEX(N'hunter_air_shift', [active_skill_slots_json]) > 0
      OR CHARINDEX(N'hunter_heal_arrow_alpha', [active_skill_slots_json]) > 0
      OR CHARINDEX(N'hunter_air_shift', [passive_skill_slots_json]) > 0
      OR CHARINDEX(N'hunter_heal_arrow_alpha', [passive_skill_slots_json]) > 0
      OR [left_click_skill_id] IN (N'hunter_air_shift', N'hunter_heal_arrow_alpha')
  );
SET @SkillBindPresetUpdatedRows = @@ROWCOUNT;

COMMIT TRANSACTION;

SELECT @AirShiftUpdatedRows AS [archer_air_shift_rows_updated],
       @HealArrowAlphaUpdatedRows AS [archer_heal_arrow_alpha_rows_updated],
       @SkillBindPresetUpdatedRows AS [skill_bind_preset_rows_updated];
