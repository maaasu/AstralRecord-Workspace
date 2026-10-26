namespace AstralRecordApi.Models;

public sealed record WorldPlacementSnapshot(int SchemaVersion, string Revision, IReadOnlyList<WorldPlacement> Placements);

public sealed record WorldPlacement(
    string PlacementId, string Kind, string WorldId, string WorldName, string? TemplateId,
    double X, double Y, double Z, float Yaw, float Pitch, bool Enabled, WorldPlacementWaystone? Waystone);

public sealed record WorldPlacementWaystone(
    string Id, string Name, bool LockEnabled, long UnlockGold, string? Icon,
    string? CreatedAt, string? CreatedBy);

public sealed record WorldPlacementReplaceRequest(string ExpectedRevision, IReadOnlyList<WorldPlacement> Placements);
public sealed record WorldPlacementUpsertRequest(string ExpectedRevision, WorldPlacement Placement);
public sealed record WorldPlacementSyncRequest(string ExpectedDevRevision, string ExpectedChannelRevision);
