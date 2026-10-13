using System.Data.SqlTypes;
using System.Text;
using Microsoft.Data.SqlClient;
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
        var marketIds = marketCheckIds.Distinct().OrderBy(id => new SqlGuid(id)).ToArray();
        var ids = equipmentIds.Distinct().OrderBy(id => new SqlGuid(id)).ToArray();
        if (!context.Database.IsSqlServer())
        {
            foreach (var id in marketIds)
                if (await MarketListingRangeLock.HasActiveOrSuspendedAsync(context, "EQUIPMENT", id)) return null;
            return await context.EquipmentInstances.Where(row => ids.Contains(row.EquipmentInstanceId))
                .ToDictionaryAsync(row => row.EquipmentInstanceId);
        }
        // 各seekを順に実行するSQL batch。IN句の実行計画にlock取得順を委ねない。
        foreach (var chunk in marketIds.Chunk(128))
        {
            var sql = new StringBuilder("DECLARE @listed int = 0;\n");
            var parameters = chunk.Select((id, index) => new SqlParameter($"@id{index}", id)).ToArray();
            for (var index = 0; index < chunk.Length; index++)
                sql.Append($"""
                    SELECT @listed = @listed | COALESCE(MAX(CASE WHEN [status] IN ('ACTIVE','SUSPENDED') THEN 1 ELSE 0 END),0)
                    FROM [dbo].[market_listing] WITH (UPDLOCK, HOLDLOCK,
                        FORCESEEK([IX_market_listing_instance_active_status] ([instance_type], [instance_id], [is_deleted])))
                    WHERE [instance_type] = N'EQUIPMENT' AND [instance_id] = @id{index} AND [is_deleted] = 0;

                    """);
            sql.Append("SELECT @listed AS [Value];");
            if ((await context.Database.SqlQueryRaw<int>(sql.ToString(), parameters).ToListAsync()).Single() == 1) return null;
        }
        var result = new Dictionary<Guid, EquipmentInstanceEntity>();
        foreach (var chunk in ids.Chunk(128))
        {
            var (sql, parameters) = BuildLockBatch("equipment_instance", "equipment_instance_id", "PK_equipment_instance",
                chunk.Cast<object>().ToArray());
            foreach (var row in await context.EquipmentInstances.FromSqlRaw(sql, parameters).ToListAsync())
                result.Add(row.EquipmentInstanceId, row);
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
        foreach (var chunk in ids.Chunk(128))
        {
            var (sql, parameters) = BuildLockBatch("account_mob_record", "account_mob_record_id",
                "UX_account_mob_record_account_mob", chunk.Cast<object>().ToArray(), accountId, "mob_id");
            result.AddRange(await context.AccountMobRecords.FromSqlRaw(sql, parameters).ToListAsync());
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
        foreach (var chunk in ids.Chunk(128))
        {
            var (sql, parameters) = BuildLockBatch("account_dungeon_record", "account_dungeon_record_id",
                "UX_account_dungeon_record_account_dungeon", chunk.Cast<object>().ToArray(), accountId, "dungeon_id");
            result.AddRange(await context.AccountDungeonRecords.FromSqlRaw(sql, parameters).ToListAsync());
        }
        return result;
    }

    /// <summary>定数のテーブル/索引名とparameterだけで、固定順のseekを1回の往復へまとめる。</summary>
    private static (string Sql, object[] Parameters) BuildLockBatch(
        string table, string primaryKey, string index, object[] ids, Guid? accountId = null, string? naturalKey = null)
    {
        var sql = new StringBuilder("DECLARE @locked TABLE ([id] uniqueidentifier PRIMARY KEY);\n");
        var parameters = new List<object>();
        if (accountId.HasValue) parameters.Add(new SqlParameter("@account", accountId.Value));
        for (var position = 0; position < ids.Length; position++)
        {
            var parameter = new SqlParameter($"@id{position}", ids[position]);
            if (ids[position] is string) parameter.Size = 100;
            parameters.Add(parameter);
            var predicate = accountId.HasValue
                ? $"[account_id] = @account AND [{naturalKey}] = @id{position}"
                : $"[{primaryKey}] = @id{position}";
            var seekColumns = accountId.HasValue ? $"[account_id], [{naturalKey}]" : $"[{primaryKey}]";
            sql.Append($"""
                INSERT INTO @locked ([id]) SELECT [{primaryKey}]
                FROM [dbo].[{table}] WITH (UPDLOCK, HOLDLOCK, FORCESEEK([{index}] ({seekColumns})))
                WHERE {predicate};

                """);
        }
        sql.Append($"""
            SELECT target.* FROM @locked AS captured
            INNER JOIN [dbo].[{table}] target WITH (UPDLOCK, HOLDLOCK, FORCESEEK) ON target.[{primaryKey}] = captured.[id]
            OPTION (LOOP JOIN, FORCE ORDER, MAXDOP 1);
            """);
        return (sql.ToString(), parameters.ToArray());
    }

}
