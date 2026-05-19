using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IEquipmentService
{
    /// <summary>
    /// マスタデータをもとに装備インスタンスを生成して DB に保存する。
    /// <para><paramref name="request"/> の <c>EquipmentId</c> が存在しない、または equipment カテゴリでない場合は <c>null</c> を返す。</para>
    /// </summary>
    Task<EquipmentInstanceResponse?> CreateAsync(EquipmentCreateRequest request);

    /// <summary>
    /// 指定した装備インスタンス ID のデータを取得する。論理削除済みは返さない。
    /// </summary>
    Task<EquipmentInstanceResponse?> GetByInstanceIdAsync(Guid instanceId);

    Task<EquipmentInstanceResponse?> EnchantAsync(EquipmentEnchantRequest request);

    Task<EquipmentInstanceResponse?> DeleteEnchantAsync(EquipmentEnchantDeleteRequest request);

    Task<EquipmentInstanceResponse?> EnhanceAsync(EquipmentEnhanceRequest request);

    Task<EquipmentInstanceResponse?> TranscendAsync(EquipmentTranscendenceRequest request);

    Task<EquipmentInstanceResponse?> AttachRuneAsync(EquipmentRuneAttachRequest request);

    Task<EquipmentInstanceResponse?> DetachRuneAsync(EquipmentRuneDetachRequest request);
}
