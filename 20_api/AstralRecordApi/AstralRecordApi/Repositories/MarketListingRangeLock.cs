using AstralRecordApi.Data;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>個体出品の有無を判定し、同じ索引rangeを更新lockで直列化する。</summary>
internal static class MarketListingRangeLock
{
    internal static async Task<bool> HasActiveOrSuspendedAsync(
        AstralRecordDbContext dbContext,
        string instanceType,
        Guid instanceId)
    {
        var normalizedType = instanceType.Trim().ToUpperInvariant();
        if (dbContext.Database.IsSqlServer())
        {
            var found = await dbContext.Database
                .SqlQuery<int>($"""
                    SELECT 1 AS [Value]
                    FROM [dbo].[market_listing] AS listing WITH (
                        UPDLOCK,
                        HOLDLOCK,
                        INDEX([IX_market_listing_instance_active_status]))
                    WHERE listing.[instance_type] = {normalizedType}
                      AND listing.[instance_id] = {instanceId}
                      AND listing.[is_deleted] = 0
                      AND listing.[status] IN ('ACTIVE', 'SUSPENDED')
                    """)
                .FirstOrDefaultAsync();
            return found == 1;
        }

        return await dbContext.MarketListings.AsNoTracking().AnyAsync(listing =>
            !listing.IsDeleted
            && (listing.Status == "ACTIVE" || listing.Status == "SUSPENDED")
            && listing.InstanceType != null
            && listing.InstanceType.ToUpper() == normalizedType
            && listing.InstanceId == instanceId);
    }
}
