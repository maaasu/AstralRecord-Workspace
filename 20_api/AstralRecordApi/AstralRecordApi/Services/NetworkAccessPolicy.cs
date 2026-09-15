using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public static class NetworkAccessPolicy
{
    public static NetworkChannelAccessResponse Evaluate(Guid userUuid, string serverId, ManagedNetworkSettings? settings, int storedPermission)
    {
        var authority = settings?.AuthorityUsers.Contains(userUuid) == true;
        var permission = authority ? Math.Max(99, storedPermission) : storedPermission;
        var channel = settings?.Channels.FirstOrDefault(channel => string.Equals(channel.ServerId, serverId, StringComparison.OrdinalIgnoreCase));
        var debug = channel?.DebugUsers.Contains(userUuid) == true;
        var whitelist = channel?.WhitelistUsers.Contains(userUuid) == true;
        var allowed = channel is not null && (!channel.WhitelistEnabled || authority || debug || whitelist);
        return new(userUuid, serverId, channel is not null, authority, debug, whitelist, channel?.WhitelistEnabled ?? false, allowed, permission);
    }
}
