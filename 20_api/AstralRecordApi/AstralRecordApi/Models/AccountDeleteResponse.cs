namespace AstralRecordApi.Models;

/// <summary>アカウント削除後に選択されたアカウントを表します。</summary>
public class AccountDeleteResponse
{
    public Guid DeletedAccountId { get; set; }
    public Guid UserId { get; set; }
    public int DeletedSlotIndex { get; set; }
    public Guid SelectedAccountId { get; set; }
    public bool CreatedReplacement { get; set; }
}
