using System.Text.Json.Serialization;

namespace AstralRecordApi.Models;

public class AccountCreateRequest
{
    public Guid UserId { get; set; }
    public string AccountName { get; set; } = string.Empty;
    /// <summary>
    /// 作成先スロット。null の場合は API が 0..99 の最小空き番号を割り当てます。
    /// </summary>
    public int? SlotIndex { get; set; }
    public byte Mode { get; set; }
    [JsonRequired]
    public Guid CreatedBy { get; set; }
}
