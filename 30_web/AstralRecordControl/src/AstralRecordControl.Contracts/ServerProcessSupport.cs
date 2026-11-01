namespace AstralRecordControl.Contracts;

public static class ServerProcessSupport
{
    public const string VelocityCapability = "P1.Velocity";
    public const string GeyserUpdateCapability = "P3.GeyserUpdate";
    public const string GeyserDisplayEntityUpdateCapability = "P3.GeyserDisplayEntityUpdate";
    public const string ViaVersionUpdateCapability = "P3.ViaVersionUpdate";

    public const string ProxyMaintenanceCapability = "P3.ProxyMaintenance";
    public const string VelocityUpdateCapability = "P3.VelocityUpdate";

    public static bool HasGeyserUpdateCapabilities(UpdateGeyserPayload payload, string[] capabilities) =>
        capabilities.Contains(GeyserUpdateCapability) && capabilities.Contains(VelocityCapability)
        && (!payload.UpdateVelocity || capabilities.Contains(VelocityUpdateCapability))
        && (!payload.UpdateGeyserDisplayEntity || capabilities.Contains(GeyserDisplayEntityUpdateCapability))
        && (!payload.UpdateViaVersion || capabilities.Contains(ViaVersionUpdateCapability))
        && (!payload.RestartEvenIfUnchanged || capabilities.Contains(ProxyMaintenanceCapability));

    public static bool IsSupported(ServerKind kind) => kind is ServerKind.Java or ServerKind.Velocity;

    public static bool HasKindCapability(ServerKind kind, string[] capabilities) => IsSupported(kind)
        && (kind != ServerKind.Velocity || capabilities.Contains(VelocityCapability));

    public static bool IsExecutableCompatible(ServerKind serverKind, ServerKind executableKind) =>
        serverKind == executableKind || (serverKind == ServerKind.Velocity && executableKind == ServerKind.Java);

    public static string DefaultStopCommand(ServerKind kind) => kind == ServerKind.Velocity ? "shutdown" : "stop";

    // Existing Velocity registrations may still contain the old Java default.
    public static string EffectiveStopCommand(ServerConfig config) =>
        config.Kind == ServerKind.Velocity && config.StopCommand == "stop" ? "shutdown" : config.StopCommand;
}
