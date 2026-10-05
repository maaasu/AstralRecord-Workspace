namespace AstralRecordApi.Models;

/// <summary>釣れる魚のマスタ定義。</summary>
public sealed class FishingFishResponse
{
    public int SchemaVersion { get; init; }
    public required string Id { get; init; }
    public required string Type { get; init; }
    public required string Name { get; init; }
    public required string EntityType { get; init; }
    public double MaxHealth { get; init; }
    public double SwimSpeed { get; init; }
    public double FleeSpeed { get; init; }
    public double PullPerTick { get; init; }
    public double BiteChancePercent { get; init; }
    public double LandFleeSpeed { get; init; }
    public double HopSpeed { get; init; }
    public required string CaughtItemId { get; init; }
    public int CaughtAmount { get; init; }
    public string? LootTableId { get; init; }
}

/// <summary>釣り挙動の共通設定。</summary>
public sealed class FishingRulesResponse
{
    public int SchemaVersion { get; init; }
    public required string Id { get; init; }
    public required string Type { get; init; }
    public double SearchRadius { get; init; }
    public int FishCountMin { get; init; }
    public int FishCountMax { get; init; }
    public int WaitingTicks { get; init; }
    public int AttackTicks { get; init; }
    public int TurnTicks { get; init; }
    public double ReelMultiplier { get; init; }
    public double SlackDistance { get; init; }
    public int LandingTicks { get; init; }
    public int HangingTicks { get; init; }
    public int EscapeTicks { get; init; }
    public int NibbleTicks { get; init; }
    public int NibbleCycleTicks { get; init; }
    public double NibbleDistance { get; init; }
    public int ReelCooldownTicks { get; init; }
}

/// <summary>ワールドと釣りレベルによる魚の選択表。</summary>
public sealed class FishingAreaResponse
{
    public int SchemaVersion { get; init; }
    public required string Id { get; init; }
    public required string Type { get; init; }
    public required string Name { get; init; }
    public required string WorldId { get; init; }
    public required string RulesId { get; init; }
    public bool Enabled { get; init; }
    public IReadOnlyList<FishingLevelRangeResponse> LevelRanges { get; init; } = [];
}

public sealed class FishingLevelRangeResponse
{
    public int MinLevel { get; init; }
    public int MaxLevel { get; init; }
    public IReadOnlyList<FishingChanceResponse> Fish { get; init; } = [];
}

public sealed class FishingChanceResponse
{
    public required string FishId { get; init; }
    public double ChancePercent { get; init; }
}
