namespace AstralRecordApi.Data.Entities;

/// <summary>
/// トレード確定の冪等応答を保持する台帳です。
/// </summary>
public sealed class TradeCommitEntity
{
    public Guid OperationId { get; set; }
    public Guid PlayerAAccountId { get; set; }
    public Guid PlayerBAccountId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public string ResultPayloadJson { get; set; } = string.Empty;
    public DateTime CompletedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public Guid CreatedBy { get; set; }
}
