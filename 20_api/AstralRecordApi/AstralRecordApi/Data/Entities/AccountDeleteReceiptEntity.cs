namespace AstralRecordApi.Data.Entities;

/// <summary>
/// アカウント削除の確定結果を保持する冪等応答台帳です。
/// </summary>
public sealed class AccountDeleteReceiptEntity
{
    public Guid DeletedAccountId { get; set; }
    public Guid UserId { get; set; }
    public int DeletedSlotIndex { get; set; }
    public Guid SelectedAccountId { get; set; }
    public bool CreatedReplacement { get; set; }
    public Guid DeletedBy { get; set; }
    public DateTime CompletedAt { get; set; }
}
