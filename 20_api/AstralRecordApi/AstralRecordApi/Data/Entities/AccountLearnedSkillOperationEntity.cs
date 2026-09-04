namespace AstralRecordApi.Data.Entities;

/// <summary>
/// 習得済みスキル mutation の冪等結果を保持する台帳エンティティです。
/// </summary>
public class AccountLearnedSkillOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public string OperationType { get; set; } = string.Empty;
    public string RequestHash { get; set; } = string.Empty;
    public string ResultPayloadJson { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; }
    public DateTime CompletedAt { get; set; }
    public Guid CreatedBy { get; set; }
}
