namespace AstralRecordApi.Models;

public sealed class WebBestiaryListResponse
{
    public WebBestiaryAccountResponse? CurrentAccount { get; init; }
    public required IReadOnlyList<WebBestiaryAccountResponse> Accounts { get; init; }
    public required IReadOnlyList<WebBestiaryMobSummaryResponse> Mobs { get; init; }
    public required long TotalDefeats { get; init; }
}

public sealed class WebBestiaryDetailResponse
{
    public required WebBestiaryAccountResponse CurrentAccount { get; init; }
    public required IReadOnlyList<WebBestiaryAccountResponse> Accounts { get; init; }
    public required long TotalDefeats { get; init; }
    public required WebBestiaryMobDetailResponse Mob { get; init; }
}

public sealed class WebBestiaryAccountResponse
{
    public required Guid AccountId { get; init; }
    public required string AccountName { get; init; }
    public required int SlotIndex { get; init; }
}

public sealed class WebBestiaryMobSummaryResponse
{
    public required string MobId { get; init; }
    public required string Category { get; init; }
    public required string Name { get; init; }
    public required int Level { get; init; }
    public required string EntityType { get; init; }
    public string? Icon { get; init; }
    public string? IconTexture { get; init; }
    public required long DefeatCount { get; init; }
    public required DateTime LastDefeatedAt { get; init; }
}

public sealed class WebBestiaryMobDetailResponse
{
    public required string MobId { get; init; }
    public required string Category { get; init; }
    public required string Name { get; init; }
    public string? Title { get; init; }
    public required int Level { get; init; }
    public required string EntityType { get; init; }
    public string? Icon { get; init; }
    public string? IconTexture { get; init; }
    public IReadOnlyList<string> Lore { get; init; } = [];
    public MobVariantResponse? Variant { get; init; }
    public required IReadOnlyList<WebBestiaryStatusResponse> BaseStats { get; init; }
    public required WebBestiaryDropsResponse Drops { get; init; }
    public required long DefeatCount { get; init; }
    public required DateTime FirstDefeatedAt { get; init; }
    public required DateTime LastDefeatedAt { get; init; }
}

public sealed class WebBestiaryStatusResponse
{
    public required string Status { get; init; }
    public required string DisplayName { get; init; }
    public required double Value { get; init; }
    public required string DisplayValue { get; init; }
}

public sealed class WebBestiaryDropsResponse
{
    public required int Exp { get; init; }
    public WebBestiaryMoneyDropResponse? Money { get; init; }
    public required IReadOnlyList<WebBestiaryDropItemResponse> Items { get; init; }
    public required bool HasAdditionalDrops { get; init; }
}

public sealed class WebBestiaryMoneyDropResponse
{
    public required int Min { get; init; }
    public required int Max { get; init; }
}

public sealed class WebBestiaryDropItemResponse
{
    public required string ItemId { get; init; }
    public required string Name { get; init; }
    public required string Icon { get; init; }
    public string? IconTexture { get; init; }
    public required double Rate { get; init; }
    public required string Amount { get; init; }
    public required bool LuckAffected { get; init; }
}
