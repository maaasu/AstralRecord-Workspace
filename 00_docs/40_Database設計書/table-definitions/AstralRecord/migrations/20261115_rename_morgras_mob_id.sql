-- Run with RPG servers stopped and pending player-state snapshots flushed.
-- Deploy/seed the morgras Filebase and update the RPG plugin before reopening admission.
-- Historical activity events keep the ID/name recorded when the event occurred.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

-- Do not guess how to combine independently existing records (including tombstones).
-- Fail before changing any row so an operator can resolve the collision explicitly.
IF EXISTS (
    SELECT 1
    FROM [dbo].[account_mob_record] AS old_record WITH (UPDLOCK, HOLDLOCK)
    INNER JOIN [dbo].[account_mob_record] AS new_record WITH (UPDLOCK, HOLDLOCK)
        ON new_record.[account_id] = old_record.[account_id]
        AND new_record.[mob_id] = N'morgras'
    WHERE old_record.[mob_id] = N'all_things_el'
)
BEGIN
    ROLLBACK TRANSACTION;
    THROW 51000, 'Both all_things_el and morgras records exist for an account; resolve the collision before retrying.', 1;
END;

-- Preserve the record UUID, counts, defeat dates and deletion state.
-- Repeating this migration after success changes no rows.
UPDATE [dbo].[account_mob_record]
SET [mob_id] = N'morgras',
    [updated_at] = SYSUTCDATETIME(),
    [updated_by] = [account_id]
WHERE [mob_id] = N'all_things_el';

COMMIT TRANSACTION;
