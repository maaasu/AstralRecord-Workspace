namespace AstralRecordApi.Data.Entities;

/// <summary>ゲーム初期化から独立したユーザー単位の承認累計です。</summary>
public sealed class DonationLedgerEntity
{
    public Guid UserUuid { get; set; }
    public long TotalApprovedAmount { get; set; }
    public int Revision { get; set; }
}

/// <summary>暗号化した支払情報と、申請・最終判断の監査記録です。</summary>
public sealed class DonationRequestEntity
{
    public Guid Id { get; set; }
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = "";
    public int DeclaredAmount { get; set; }
    public int? ApprovedAmount { get; set; }
    public long? ApprovedThroughAmount { get; set; }
    public string Status { get; set; } = "Pending";
    public string ProtectedEntries { get; set; } = "";
    public string TermsVersion { get; set; } = "";
    public string DiscordUserId { get; set; } = "";
    public string DiscordName { get; set; } = "";
    public string? Reason { get; set; }
    public Guid? ReviewerUuid { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime? ReviewStartedAtUtc { get; set; }
    public DateTime? DecidedAtUtc { get; set; }
}

/// <summary>受付中・承認済みのコードまたはURLの重複申請を防ぎます。</summary>
public sealed class DonationEntryFingerprintEntity
{
    public string Fingerprint { get; set; } = "";
    public Guid RequestId { get; set; }
}

/// <summary>ManagementDBで先に確定する、再送可能なアカウント別配布指示です。</summary>
public sealed class DonationGrantEntity
{
    public Guid Id { get; set; }
    public Guid UserUuid { get; set; }
    public Guid AccountUuid { get; set; }
    public long ThroughAmount { get; set; }
    public int Amount { get; set; }
    public string Message { get; set; } = "";
    public DateTime CreatedAtUtc { get; set; }
    public DateTime? DeliveredAtUtc { get; set; }
}

/// <summary>プレイヤー本人宛ての、再ログイン後も通知可能なイベントです。</summary>
public sealed class DonationNotificationEntity
{
    public Guid Id { get; set; }
    public Guid UserUuid { get; set; }
    public string Kind { get; set; } = "";
    public int Amount { get; set; }
    public string Message { get; set; } = "";
    public DateTime CreatedAtUtc { get; set; }
    public DateTime? AcknowledgedAtUtc { get; set; }
}
