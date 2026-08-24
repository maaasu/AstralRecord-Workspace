using System.ComponentModel.DataAnnotations;

namespace AstralRecordApi.Models;

/// <summary>
/// オーブ装備操作の要求。効果内容は API がオーブマスタから解決する。
/// </summary>
public class EquipmentOrbOperationRequest : IValidatableObject
{
    public const int OrbItemIdMaxLength = 128;

    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    /// <summary>
    /// Plugin が共通消費順で直近に解決したオーブentry。APIはこのIDを消費元の選択には使用せず、
    /// transaction内で同じitem IDの通常stackを共通順から選ぶ。
    /// </summary>
    public Guid OrbInventoryEntryId { get; set; }
    public required string OrbItemId { get; set; }

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        var normalized = OrbItemId?.Trim() ?? string.Empty;
        if (normalized.Length is < 1 or > OrbItemIdMaxLength)
        {
            yield return new ValidationResult(
                $"OrbItemId must be 1 to {OrbItemIdMaxLength} UTF-16 code units after normalization.",
                [nameof(OrbItemId)]);
        }
    }
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
