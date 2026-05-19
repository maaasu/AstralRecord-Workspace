using AstralRecordApi.Data.Entities;

namespace AstralRecordApi.Repositories;

public interface IEquipmentRepository
{
    /// <summary>
    /// 装備インスタンスと関連エンティティを DB に保存する。
    /// </summary>
    Task AddAsync(
        EquipmentInstanceEntity instance,
        IReadOnlyList<EquipmentInstanceStatRollEntity> statRolls,
        IReadOnlyList<EquipmentInstanceEnchantPoolEntity> enchantPools);

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

    /// <summary>
    /// 指定した装備インスタンスに紐づくエンチャントプールを返す。
    /// </summary>
    Task<IReadOnlyList<EquipmentInstanceEnchantPoolEntity>> FindEnchantPoolsAsync(Guid instanceId);

    Task ApplyEnchantAsync(EquipmentInstanceEntity instance, EquipmentInstanceEnchantEntity enchant, Guid? overwriteEnchantId);

    Task<bool> DeleteEnchantByPoolIndexAsync(Guid instanceId, int poolIndex);

    Task UpsertRuneAsync(EquipmentInstanceEntity instance, EquipmentInstanceRuneEntity rune);

    Task<bool> DeleteRuneBySlotIndexAsync(Guid instanceId, int slotIndex);

    Task UpdateInstanceAsync(EquipmentInstanceEntity instance);
}
