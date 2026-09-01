namespace AstralRecordApi.Repositories;

/// <summary>未削除アカウント名が既に使用されている場合に発生します。</summary>
public sealed class AccountNameConflictException(string accountName)
    : InvalidOperationException($"Account name is already in use: {accountName}");
