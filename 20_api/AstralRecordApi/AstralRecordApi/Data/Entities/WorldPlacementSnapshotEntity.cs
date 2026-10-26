namespace AstralRecordApi.Data.Entities;

/// <summary>A single, revisioned placement snapshot in a dedicated placement database.</summary>
public sealed class WorldPlacementSnapshotEntity
{
    public int Id { get; set; }
    public long Revision { get; set; }
    public string PlacementsJson { get; set; } = "[]";
    public DateTime UpdatedAtUtc { get; set; }
}
