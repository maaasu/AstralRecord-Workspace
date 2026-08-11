namespace AstralRecordApi.Models;

/// <summary>
/// オーブ装備操作の要求。効果内容は API がオーブマスタから解決する。
/// </summary>
public class EquipmentOrbOperationRequest
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public Guid OrbInventoryEntryId { get; set; }
    public required string OrbItemId { get; set; }
}

/// <summary>
/// オーブ装備操作の確定結果。同一 operationId の再送では同じ業務結果を返し、装備は安全な現在値を返す。
/// </summary>
public class EquipmentOrbOperationResponse
{
    public Guid OperationId { get; init; }
    public required string Result { get; init; }
    public required string OperationType { get; init; }
    public EquipmentInstanceResponse? Equipment { get; init; }
    /// <summary>台帳accountが対象装備を現在も所有し、BAG/HOTBARまたはactive loadoutに保持する場合true。</summary>
    public bool TargetAvailable { get; init; }
    public IReadOnlyList<Guid> AffectedInventoryEntryIds { get; init; } = [];
    public bool PaymentConsumed { get; init; }
    public bool EnhancementSucceeded { get; init; }
    public string? FailAction { get; init; }
    public double? SuccessRate { get; init; }
    public int? RepairedAmount { get; init; }
    public string? TransitionName { get; init; }
}
