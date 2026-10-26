-- Run once in each explicitly selected WorldPlacementDevDB / WorldPlacementDB.
-- This script does not create databases or touch player/master databases.
IF DB_NAME() NOT IN (N'WorldPlacementDevDB', N'WorldPlacementDB')
    THROW 51000, 'This script must run in a dedicated WorldPlacement database.', 1;
IF OBJECT_ID(N'dbo.world_placement_snapshot', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.world_placement_snapshot (
        id int NOT NULL CONSTRAINT PK_world_placement_snapshot PRIMARY KEY,
        revision bigint NOT NULL,
        placements_json nvarchar(max) NOT NULL,
        updated_at_utc datetime2(7) NOT NULL,
        CONSTRAINT CK_world_placement_snapshot_id CHECK (id = 1),
        CONSTRAINT CK_world_placement_snapshot_revision CHECK (revision >= 0),
        CONSTRAINT CK_world_placement_snapshot_json CHECK (ISJSON(placements_json) = 1)
    );
END;
IF NOT EXISTS (SELECT 1 FROM dbo.world_placement_snapshot WHERE id = 1)
    INSERT INTO dbo.world_placement_snapshot (id, revision, placements_json, updated_at_utc)
    VALUES (1, 0, N'[]', SYSUTCDATETIME());
