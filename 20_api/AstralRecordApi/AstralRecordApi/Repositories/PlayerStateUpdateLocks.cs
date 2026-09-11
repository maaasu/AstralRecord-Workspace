using System.Data.SqlTypes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>保存対象を最初から更新ロックで読み、共有範囲ロックからの昇格競合を防ぐ。</summary>
internal static class PlayerStateUpdateLocks
{
    /// <summary>
    /// 呼出元のtransaction内で出品範囲、装備の順にロックする。
    /// GUIDはSQL Serverの索引順で取得し、装備とロードアウトの参照をまとめて処理する。
    /// 出品中の個体を含む場合はnullを返し、呼出元がtransaction全体をrollbackする。
    /// </summary>
    internal static async Task<Dictionary<Guid, EquipmentInstanceEntity>?> EquipmentAsync(
        AstralRecordDbContext context, IEnumerable<Guid> equipmentIds, IEnumerable<Guid> marketCheckIds)
    {
        foreach (var id in marketCheckIds.Distinct().OrderBy(id => new SqlGuid(id)))
        {
            if (await MarketListingRangeLock.HasActiveOrSuspendedAsync(context, "EQUIPMENT", id))
                return null;
        }

        var result = new Dictionary<Guid, EquipmentInstanceEntity>();
        var ids = equipmentIds.Distinct().OrderBy(id => new SqlGuid(id)).ToArray();
        if (!context.Database.IsSqlServer())
            return await context.EquipmentInstances.Where(row => ids.Contains(row.EquipmentInstanceId))
                .ToDictionaryAsync(row => row.EquipmentInstanceId);

        foreach (var id in ids)
        {
            var row = await context.EquipmentInstances.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[equipment_instance] WITH (
                    UPDLOCK, HOLDLOCK, FORCESEEK([PK_equipment_instance] ([equipment_instance_id])))
                WHERE [equipment_instance_id] = {id}
                """).SingleOrDefaultAsync();
            if (row is not null) result.Add(id, row);
        }
        return result;
    }

    /// <summary>同じtransaction内で更新・新規作成する討伐記録を、自然キーの索引でロックする。</summary>
    internal static async Task<List<AccountMobRecordEntity>> MobsAsync(
        AstralRecordDbContext context, Guid accountId, IEnumerable<string> mobIds)
    {
        var ids = mobIds.Distinct(StringComparer.OrdinalIgnoreCase).Order(StringComparer.OrdinalIgnoreCase).ToArray();
        if (!context.Database.IsSqlServer())
            return await context.AccountMobRecords.Where(row => row.AccountId == accountId && ids.Contains(row.MobId)).ToListAsync();
        var result = new List<AccountMobRecordEntity>();
        foreach (var id in ids)
        {
            var row = await context.AccountMobRecords.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[account_mob_record] WITH (
                    UPDLOCK, HOLDLOCK, FORCESEEK([UX_account_mob_record_account_mob] ([account_id], [mob_id])))
                WHERE [account_id] = {accountId} AND [mob_id] = {id}
                """).SingleOrDefaultAsync();
            if (row is not null) result.Add(row);
        }
        return result;
    }

    /// <summary>同じtransaction内で更新・新規作成する踏破記録を、自然キーの索引でロックする。</summary>
    internal static async Task<List<AccountDungeonRecordEntity>> DungeonsAsync(
        AstralRecordDbContext context, Guid accountId, IEnumerable<string> dungeonIds)
    {
        var ids = dungeonIds.Distinct(StringComparer.OrdinalIgnoreCase).Order(StringComparer.OrdinalIgnoreCase).ToArray();
        if (!context.Database.IsSqlServer())
            return await context.AccountDungeonRecords.Where(row => row.AccountId == accountId && ids.Contains(row.DungeonId)).ToListAsync();
        var result = new List<AccountDungeonRecordEntity>();
        foreach (var id in ids)
        {
            var row = await context.AccountDungeonRecords.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[account_dungeon_record] WITH (
                    UPDLOCK, HOLDLOCK, FORCESEEK([UX_account_dungeon_record_account_dungeon] ([account_id], [dungeon_id])))
                WHERE [account_id] = {accountId} AND [dungeon_id] = {id}
                """).SingleOrDefaultAsync();
            if (row is not null) result.Add(row);
        }
        return result;
    }
}
