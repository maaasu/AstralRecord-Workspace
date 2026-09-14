using AstralRecordApi.Data;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>個体出品の有無を判定し、同じinstance接頭辞の索引rangeを更新lockで直列化する。</summary>
internal static class MarketListingRangeLock
{
    /// <summary>
    /// 同一instanceの全statusを一つの連続した索引rangeとしてロックし、出品中かを返す。
    /// </summary>
    internal static async Task<bool> HasActiveOrSuspendedAsync(
        AstralRecordDbContext dbContext,
        string instanceType,
        Guid instanceId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            var found = await dbContext.Database
                .SqlQuery<int>(BuildActiveOrSuspendedQuery(instanceType, instanceId))
                .SingleAsync();
            return found == 1;
        }

        var normalizedType = instanceType.Trim().ToUpperInvariant();
        return await dbContext.MarketListings.AsNoTracking().AnyAsync(listing =>
            !listing.IsDeleted
            && (listing.Status == "ACTIVE" || listing.Status == "SUSPENDED")
            && listing.InstanceType != null
            && listing.InstanceType.ToUpper() == normalizedType
            && listing.InstanceId == instanceId);
    }

    /// <summary>
    /// statusごとの複数seekを避け、同一instance接頭辞を一方向に走査するSQL Server用クエリを構築する。
    /// </summary>
    internal static FormattableString BuildActiveOrSuspendedQuery(string instanceType, Guid instanceId)
    {
        var normalizedType = instanceType.Trim().ToUpperInvariant();
        return $"""
            SELECT COALESCE(MAX(CASE
                WHEN listing.[status] = 'ACTIVE' OR listing.[status] = 'SUSPENDED' THEN 1
                ELSE 0
            END), 0) AS [Value]
            FROM [dbo].[market_listing] AS listing WITH (
                UPDLOCK,
                HOLDLOCK,
                FORCESEEK([IX_market_listing_instance_active_status]
                    ([instance_type], [instance_id], [is_deleted])))
            WHERE listing.[instance_type] = {normalizedType}
              AND listing.[instance_id] = {instanceId}
              AND listing.[is_deleted] = 0
            """;
    }
}
