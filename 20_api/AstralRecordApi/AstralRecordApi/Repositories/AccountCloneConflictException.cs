namespace AstralRecordApi.Repositories;

/// <summary>複製の確認状態または実行中セッションが競合した場合に発生します。</summary>
public sealed class AccountCloneConflictException(string code, string message, Guid? targetAccountId = null)
    : InvalidOperationException(message)
{
    public string Code { get; } = code;
    public Guid? TargetAccountId { get; } = targetAccountId;
}
