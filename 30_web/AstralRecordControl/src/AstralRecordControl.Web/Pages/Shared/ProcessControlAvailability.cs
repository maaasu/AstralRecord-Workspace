using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;

namespace AstralRecordControl.Web.Pages.Shared;

public sealed record ProcessControlAvailability(bool Enabled, ServerRuntimeSnapshot? Runtime, bool Pending)
{
    public bool CanStart => Enabled && !Pending && Runtime?.ProcessState == ProcessState.Stopped && Runtime.CurrentSessionId is null;
    public bool CanStop => Enabled && !Pending && Runtime?.ProcessState == ProcessState.Running && Runtime.CurrentSessionId is not null;

    public static ProcessControlAvailability For(InventoryServer server, InventoryHost? host, ServerRuntimeSnapshot? runtime,
        DateTimeOffset now, bool processControlEnabled, bool pending = false)
    {
        var fresh = host is { Enabled: true, ConnectionState: HostConnectionState.Online, LastSeenAtUtc: not null }
            && now - host.LastSeenAtUtc.Value <= TimeSpan.FromSeconds(30);
        if (!fresh) return new(false, null, pending);
        var config = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        var capabilities = ControlJson.Deserialize<string[]>(host!.CapabilitiesJson);
        var enabled = processControlEnabled && !host.RecoveryRequired
            && capabilities.Contains("P1.Operations") && capabilities.Contains("P1.LegacyGate")
            && ServerProcessSupport.HasKindCapability(config.Kind, capabilities)
            && config.DependencyReadiness == DependencyReadiness.Running && config.DependsOnServerIds.Length == 0
            && runtime is not null && server.AppliedRevision > 0
            && runtime.ConfigRevision == server.AppliedRevision && server.AppliedRevision == server.DesiredRevision;
        return new(enabled, runtime, pending);
    }
}
