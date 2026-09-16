namespace AstralRecordWeb.Models;

public sealed class WebBestiaryListResponse
{
    public WebBestiaryAccountResponse? CurrentAccount { get; init; }
    public Guid? AccountId => CurrentAccount?.AccountId;
    public string? AccountName => CurrentAccount?.AccountName;
    public IReadOnlyList<WebBestiaryAccountResponse> Accounts { get; init; } = [];
    public IReadOnlyList<WebBestiaryMobSummaryResponse> Mobs { get; init; } = [];
    public long TotalDefeats { get; init; }
}

public sealed class WebBestiaryAccountResponse
{
    public Guid AccountId { get; init; }
    public string AccountName { get; init; } = "";
    public int SlotIndex { get; init; }
}

public sealed class WebBestiaryMobSummaryResponse
{
    public string MobId { get; init; } = "";
    public string Category { get; init; } = "";
    public string Name { get; init; } = "";
    public int Level { get; init; }
    public string EntityType { get; init; } = "";
    public long DefeatCount { get; init; }
    public DateTime? LastDefeatedAt { get; init; }
}

public sealed class WebBestiaryDetailResponse
{
    public required WebBestiaryAccountResponse CurrentAccount { get; init; }
    public Guid AccountId => CurrentAccount.AccountId;
    public string AccountName => CurrentAccount.AccountName;
    public WebBestiaryMobResponse Mob { get; init; } = new();
    public long DefeatCount => Mob.DefeatCount;
    public DateTime? FirstDefeatedAt => Mob.FirstDefeatedAt;
    public DateTime? LastDefeatedAt => Mob.LastDefeatedAt;
}

public sealed class WebBestiaryMobResponse
{
    public long DefeatCount { get; init; }
    public DateTime? FirstDefeatedAt { get; init; }
    public DateTime? LastDefeatedAt { get; init; }
    public string MobId { get; init; } = "";
    public string Category { get; init; } = "";
    public string Name { get; init; } = "";
    public string? Title { get; init; }
    public int Level { get; init; }
    public string EntityType { get; init; } = "";
    public IReadOnlyList<string> Lore { get; init; } = [];
    public IReadOnlyList<WebBestiaryStatResponse> BaseStats { get; init; } = [];
    public WebBestiaryDropsResponse? Drops { get; init; }
}

public sealed class WebBestiaryStatResponse
{
    public string Status { get; init; } = "";
    public string DisplayName { get; init; } = "";
    public string DisplayValue { get; init; } = "";
    public double Value { get; init; }
}

public sealed class WebBestiaryDropsResponse
{
    public int Exp { get; init; }
    public WebBestiaryMoneyResponse? Money { get; init; }
    public IReadOnlyList<WebBestiaryDropItemResponse> Items { get; init; } = [];
    public bool HasAdditionalDrops { get; init; }
}

public sealed class WebBestiaryMoneyResponse
{
    public int Min { get; init; }
    public int Max { get; init; }
}

public sealed class WebBestiaryDropItemResponse
{
    public string ItemId { get; init; } = "";
    public string Name { get; init; } = "";
    public string? Icon { get; init; }
    public double Rate { get; init; }
    public string Amount { get; init; } = "1";
    public bool LuckAffected { get; init; }
}

public static class BestiaryDisplay
{
    public static string Category(string category) => category switch { "BOSS" => "ボス", "ENEMY" => "通常モブ", _ => "モブ" };
    public static string Date(DateTime? date) => date.HasValue
        ? DateTime.SpecifyKind(date.Value, DateTimeKind.Utc).AddHours(9).ToString("yyyy/MM/dd HH:mm") : "—";
}
