namespace AstralRecordWeb.Models;

public sealed class DonationList
{
    public long TotalApprovedAmount { get; init; }
    public int TotalCount { get; init; }
    public IReadOnlyList<DonationRequest> Requests { get; init; } = [];
    public DiscordLink? DiscordLink { get; init; }
}

public sealed class DiscordLink
{
    public string DiscordUserId { get; init; } = "";
    public string DiscordName { get; init; } = "";
    public bool IsGuildMember { get; init; }
    public DateTimeOffset VerifiedAtUtc { get; init; }
}

public sealed class DonationRequest
{
    public Guid Id { get; init; }
    public Guid UserUuid { get; init; }
    public string Mcid { get; init; } = "";
    public int DeclaredAmount { get; init; }
    public int? ApprovedAmount { get; init; }
    public string Status { get; init; } = "";
    public string? Reason { get; init; }
    public DateTimeOffset CreatedAtUtc { get; init; }
    public DateTimeOffset? DecidedAtUtc { get; init; }
    public Guid? ReviewerUuid { get; init; }
    public string DiscordUserId { get; init; } = "";
    public string DiscordName { get; init; } = "";
    public IReadOnlyList<DonationEntry> Entries { get; init; } = [];
    public string StatusLabel => Status switch
    {
        "Pending" => "申請中", "Reviewing" => "確認中", "Approved" => "承認済み",
        "Rejected" => "否認", "Cancelled" => "取消済み", _ => "不明",
    };
}

public sealed class DonationEntry
{
    public string Method { get; set; } = "amazon";
    public string Value { get; set; } = "";
    public int DeclaredAmount { get; set; }
}
