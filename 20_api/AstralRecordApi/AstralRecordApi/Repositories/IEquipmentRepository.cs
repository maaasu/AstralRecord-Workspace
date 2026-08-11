using AstralRecordApi.Data.Entities;

namespace AstralRecordApi.Repositories;

public interface IEquipmentRepository
{
    /// <summary>
    /// 装備インスタンスと関連エンティティを DB に保存する。
    /// </summary>
    Task AddAsync(
        EquipmentInstanceEntity instance,
        IReadOnlyList<EquipmentInstanceStatRollEntity> statRolls);

    /// <summary>
    /// 指定した装備インスタンス ID のエンティティを返す。論理削除済みは返さない。
    /// </summary>
    Task<EquipmentInstanceEntity?> FindInstanceAsync(Guid instanceId);

    /// <summary>
    /// 指定した装備インスタンスに紐づくステータス乱数ロールを返す。
    /// </summary>
    Task<IReadOnlyList<EquipmentInstanceStatRollEntity>> FindStatRollsAsync(Guid instanceId);

    /// <summary>
    /// 指定した装備インスタンスに紐づくエンチャントを返す。
    /// </summary>
    Task<IReadOnlyList<EquipmentInstanceEnchantEntity>> FindEnchantsAsync(Guid instanceId);

    /// <summary>
    /// 指定した装備インスタンスに紐づくルーンを返す。
    /// </summary>
    Task<IReadOnlyList<EquipmentInstanceRuneEntity>> FindRunesAsync(Guid instanceId);

    Task<bool> DeleteEnchantBySlotIndexAsync(Guid instanceId, int slotIndex, Guid accountId);

    Task<bool> UpsertRuneAsync(Guid instanceId, Guid accountId, EquipmentInstanceRuneEntity rune);

    Task<bool> DeleteRuneBySlotIndexAsync(Guid instanceId, int slotIndex);

    Task<EquipmentInstanceEntity?> UpdateDurabilityAsync(Guid instanceId, int durabilityValue, Guid updatedBy);

    Task<bool> SoftDeleteInstanceAsync(Guid instanceId);
}
