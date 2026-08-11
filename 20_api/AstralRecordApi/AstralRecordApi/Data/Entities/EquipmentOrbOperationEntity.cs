namespace AstralRecordApi.Data.Entities;

/// <summary>
/// オーブによる装備操作の冪等な確定結果を保持する台帳エンティティ。
/// </summary>
public class EquipmentOrbOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public Guid OrbInventoryEntryId { get; set; }
    public string OrbItemId { get; set; } = string.Empty;
    public string OperationType { get; set; } = string.Empty;
    public string RequestHash { get; set; } = string.Empty;
    public string ResultCode { get; set; } = string.Empty;
    public string ResultPayloadJson { get; set; } = string.Empty;
    public bool PaymentConsumed { get; set; }
    public string AffectedInventoryEntryIdsJson { get; set; } = "[]";
    public DateTime CreatedAt { get; set; }
    public DateTime CompletedAt { get; set; }
    public Guid CreatedBy { get; set; }
}
