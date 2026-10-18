using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Operations;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Inventory;

public sealed partial class InventoryStore
{
    public async Task<BackupProfile[]> BackupProfilesAsync(Guid? serverId = null)
    {
        var hosts = await db.Hosts.AsNoTracking().ToArrayAsync();
        var result = new List<BackupProfile>();
        foreach (var host in hosts)
        {
            var version = await db.InventoryVersions.AsNoTracking().SingleAsync(v => v.HostId == host.HostId && v.Revision == host.DesiredInventoryRevision);
            result.AddRange(ControlJson.Deserialize<InventorySnapshot>(version.SnapshotJson).BackupProfiles.Select(p => ControlJson.Deserialize<BackupProfile>(p.GetRawText())));
        }
        return result.Where(p => !serverId.HasValue || p.ServerId == serverId).OrderBy(p => p.BackupProfileId).ToArray();
    }

    public async Task<BackupProfile> SaveBackupProfileAsync(BackupProfile profile, long? expectedRevision, Guid key) => await WriteAsync(async () =>
    {
        if (configuration?.GetValue<bool>("Control:EnableFileControl") != true || !ProcessControlEnabled) throw new InventoryFault(409, "UnsupportedCapability");
        BackupValidation.Validate(profile);
        var server = await ServerAsync(profile.ServerId);
        var host = await HostAsync(server.HostId);
        var hash = ControlProtocol.Hash("BackupProfile:" + expectedRevision + ":" + ControlJson.Serialize(profile));
        var replay = await ReplayAsync(key, hash);
        var profiles = await BackupProfilesAsync(server.ServerId);
        if (replay != null) return profiles.Single(p => p.BackupProfileId == replay.EntityId);
        if (!ControlJson.Deserialize<HostResources>(host.ResourcesJson).Roots.Any(r => r.Id == profile.DestinationRootId)) throw new InventoryFault(400, "PathOutsideRoot");
        if (await db.Reservations.AnyAsync(r => r.ResourceKey == ReservationService.ServerKey(server.ServerId) && r.State != ReservationStatus.Released)) throw new InventoryFault(409, "ReservationConflict");
        var previous = profiles.SingleOrDefault(p => p.BackupProfileId == profile.BackupProfileId);
        if (expectedRevision == null)
        {
            if (previous != null || profile.Revision != 1 || (await BackupProfilesAsync()).Any(p => p.BackupProfileId == profile.BackupProfileId)) throw new InventoryFault(409, "StaleRevision");
        }
        else if (previous == null || previous.Revision != expectedRevision || profile.Revision != checked(expectedRevision.Value + 1)) throw new InventoryFault(409, "StaleRevision");
        var allProfiles = (await BackupProfilesAsync()).Where(p => p.BackupProfileId != profile.BackupProfileId).Append(profile).ToArray();
        var hostServerIds = await db.Servers.Where(s => s.HostId == host.HostId).Select(s => s.ServerId).ToArrayAsync();
        var configs = (await db.Servers.Where(s => s.HostId == host.HostId).ToArrayAsync()).Select(s => ControlJson.Deserialize<ServerConfig>(s.DesiredConfigJson)).ToArray();
        SaveInventory(host, configs, allProfiles.Where(p => hostServerIds.Contains(p.ServerId)).Select(ControlJson.Element).ToArray());
        SaveRequest(key, hash, profile.BackupProfileId, "BackupProfile");
        return profile;
    });

    public async Task<BackupProfile> AppliedBackupProfileAsync(Guid serverId, Guid profileId, long? revision = null)
    {
        var server = await ServerAsync(serverId);
        var host = await HostAsync(server.HostId);
        var desired = (await BackupProfilesAsync(serverId)).SingleOrDefault(p => p.BackupProfileId == profileId) ?? throw new InventoryFault(404, "NotFound");
        if (host.AppliedInventoryRevision < 1) throw new InventoryFault(409, "StaleRevision");
        var version = await db.InventoryVersions.AsNoTracking().SingleAsync(v => v.HostId == host.HostId && v.Revision == host.AppliedInventoryRevision);
        var applied = ControlJson.Deserialize<InventorySnapshot>(version.SnapshotJson).BackupProfiles.Select(p => ControlJson.Deserialize<BackupProfile>(p.GetRawText())).SingleOrDefault(p => p.BackupProfileId == profileId);
        if (applied == null || applied.ServerId != serverId || ControlJson.Serialize(applied) != ControlJson.Serialize(desired) || (revision.HasValue && applied.Revision != revision)) throw new InventoryFault(409, "StaleRevision");
        return applied;
    }
}
