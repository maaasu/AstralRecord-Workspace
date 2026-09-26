namespace AstralRecordWeb.Models;

public sealed class AstraldShopCatalog
{
    public IReadOnlyList<AstraldShopItem> Items { get; init; } = [];
    public IReadOnlyList<AstraldShopChannel> Channels { get; init; } = [];
}

public sealed class AstraldShopItem
{
    public string ItemId { get; init; } = "";
    public string Name { get; init; } = "";
    public long PricePaidAstrald { get; init; }
    public string EffectType { get; init; } = "";
    public double EffectValue { get; init; }
    public long? DurationSeconds { get; init; }
    public bool RequiresChannel { get; init; }
}

public sealed class AstraldShopChannel
{
    public string ChannelId { get; init; } = "";
    public string DisplayName { get; init; } = "";
}

public sealed class ChannelBoostSnapshot
{
    public IReadOnlyList<ChannelBoostState> Channels { get; init; } = [];
}

public sealed class ChannelBoostState
{
    public string ChannelId { get; init; } = "";
    public ChannelBoostEffect? Exp { get; init; }
    public ChannelBoostEffect? Drop { get; init; }
}

public sealed class ChannelBoostEffect
{
    public double Multiplier { get; init; }
    public DateTimeOffset ExpiresAt { get; init; }
}

public sealed class AccountBenefits
{
    public Guid AccountId { get; init; }
    public int InstancePriorityUses { get; init; }
    public string? VipTier { get; init; }
    public DateTimeOffset? VipExpiresAt { get; init; }
    public DateTimeOffset? DonerExpiresAt { get; init; }
    public DateTimeOffset? AstralderExpiresAt { get; init; }
    public int RemainingDays { get; init; }
    public int AstralderDailyCreditsRemaining { get; init; }
    public long? PaidAstraldBalance { get; init; }
}

public sealed class AstraldPurchase
{
    public Guid OperationId { get; init; }
    public string Status { get; init; } = "";
    public string? Reason { get; init; }
    public Guid AccountId { get; init; }
    public string ItemId { get; init; } = "";
    public string? ChannelId { get; init; }
    public long? PaidAstraldBalance { get; init; }
    public AccountBenefits? Benefits { get; init; }
}

public sealed class WebMail
{
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string Body { get; init; } = "";
    public bool IsRead { get; init; }
    public IReadOnlyList<WebMailReward> Rewards { get; init; } = [];
    public bool CanClaimCurrency { get; init; }
    public bool CurrencyClaimed { get; init; }
}

public sealed class WebMailReward
{
    public string ItemId { get; init; } = "";
    public string Category { get; init; } = "";
    public long Amount { get; init; }
    public string? InstanceId { get; init; }
}

public sealed class WebMailClaim
{
    public Guid OperationId { get; init; }
    public string Status { get; init; } = "";
    public string? Reason { get; init; }
    public string MailId { get; init; } = "";
    public Guid AccountId { get; init; }
}
