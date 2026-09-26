using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public static class NetworkAccessPolicy
{
    public static NetworkChannelAccessResponse Evaluate(Guid userUuid, string serverId, ManagedNetworkSettings? settings, int storedPermission, AccountBenefitsResponse? benefits = null)
    {
        var authority = settings?.AuthorityUsers.Contains(userUuid) == true;
        var permission = authority ? 99 : storedPermission == 99 ? 99 : 0;
        var vip = benefits?.VipTier is "DONER" or "ASTRALDER";
        var channel = settings?.Channels.FirstOrDefault(channel => string.Equals(channel.ServerId, serverId, StringComparison.OrdinalIgnoreCase));
        var debug = channel?.DebugUsers.Contains(userUuid) == true;
        var whitelist = channel?.WhitelistUsers.Contains(userUuid) == true;
        var allowed = channel is not null && (!channel.WhitelistEnabled || authority || debug || whitelist)
            && (!channel.DonorOnly || permission >= 99 || debug || whitelist || vip);
        return new(userUuid, serverId, channel is not null, authority, debug, whitelist, channel?.WhitelistEnabled ?? false, allowed, permission, channel?.DonorOnly ?? false, vip, benefits?.VipTier ?? "NONE", benefits?.VipExpiresAt);
    }
}
