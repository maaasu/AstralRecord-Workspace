using System.Text.Json.Serialization;

namespace AstralRecordApi.Models;

/// <summary>アカウント複製の作成先と上書き確認情報です。</summary>
public sealed class AccountCloneRequest
{
    public Guid TargetUserId { get; set; }
    public int TargetSlotIndex { get; set; }
    public Guid? ExpectedTargetAccountId { get; set; }
    public bool Overwrite { get; set; }
    [JsonRequired]
    public Guid CreatedBy { get; set; }
}

/// <summary>アカウント複製結果です。</summary>
public sealed class AccountCloneResponse
{
    public AccountResponse Account { get; set; } = new();
    public Guid? ReplacedAccountId { get; set; }
}
