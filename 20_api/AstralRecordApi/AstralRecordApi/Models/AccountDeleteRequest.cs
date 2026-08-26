namespace AstralRecordApi.Models;

/// <summary>アカウント削除を実行した主体を表します。</summary>
public class AccountDeleteRequest
{
    public Guid DeletedBy { get; set; }
}
