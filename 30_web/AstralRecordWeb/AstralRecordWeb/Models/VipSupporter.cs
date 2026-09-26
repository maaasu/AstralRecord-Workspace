namespace AstralRecordWeb.Models;

/// <summary>現在VIP期間中のアカウントの公開表示情報。</summary>
public sealed record VipSupporter(
    Guid AccountId, string DisplayName, string VipTier, DateTimeOffset VipExpiresAt, int RemainingDays);
