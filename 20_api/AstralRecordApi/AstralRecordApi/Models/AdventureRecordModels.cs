namespace AstralRecordApi.Models;

public class AccountMobRecordResponse
{
    public Guid AccountMobRecordId { get; init; }
    public Guid AccountId { get; init; }
    public required string MobId { get; init; }
    public required string MobCategory { get; init; }
    public long DefeatCount { get; init; }
    public DateTime FirstDefeatedAt { get; init; }
    public DateTime LastDefeatedAt { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountMobDefeatRequest
{
    public Guid AccountId { get; init; }
    public required string MobId { get; init; }
    public required string MobCategory { get; init; }
    public Guid UpdatedBy { get; init; }
}
