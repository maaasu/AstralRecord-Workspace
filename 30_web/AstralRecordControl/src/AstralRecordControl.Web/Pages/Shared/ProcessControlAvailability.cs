using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Operations;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Shared;

public sealed record ProcessControlAvailability(bool Enabled, ServerRuntimeSnapshot? Runtime, bool OperationPending,
    bool Reserved, bool AppliedSettingsAvailable, string? Reason, Guid? WorkflowId)
{
    public bool Pending => OperationPending || Reserved;
    public bool CanStartWithinReservation => !OperationPending && Runtime?.ProcessState == ProcessState.Stopped && Runtime.CurrentSessionId is null;
    public bool CanStart => Enabled && CanStartWithinReservation && !Reserved;
    public bool CanStop => (Enabled || AppliedSettingsAvailable) && !Pending && IsRunning;
    public bool CanRun => Enabled && !Pending && IsRunning;
    private bool IsRunning => Runtime?.ProcessState == ProcessState.Running && Runtime.CurrentSessionId is not null;

    public static bool HasPendingAppliedSettings(InventoryServer server) => server.AppliedRevision > 0 && server.AppliedRevision != server.DesiredRevision;

    public static bool HasCurrentReport(InventoryHost? host, DateTimeOffset now) =>
        host is { Enabled: true, ConnectionState: HostConnectionState.Online, LastSeenAtUtc: not null }
        && now - host.LastSeenAtUtc.Value <= TimeSpan.FromSeconds(30);

    public static IQueryable<ControlOperation> PendingOperations(ControlDbContext db) => db.Operations.AsNoTracking()
        .Where(x => x.ServerId != null && (x.Status == OperationStatus.Queued || x.Status == OperationStatus.Accepted
            || x.Status == OperationStatus.Running || x.Status == OperationStatus.NeedsAttention))
        .Select(x => new ControlOperation { ServerId = x.ServerId, WorkflowId = x.WorkflowId, Status = x.Status });

    public static IQueryable<ControlReservation> ActiveReservations(ControlDbContext db) => db.Reservations.AsNoTracking()
        .Where(x => x.State != ReservationStatus.Released)
        .Select(x => new ControlReservation { ResourceKey = x.ResourceKey, ReservationId = x.ReservationId, OwnerWorkflowId = x.OwnerWorkflowId, State = x.State });

    public static IEnumerable<ControlReservation> ConflictingReservations(InventoryServer server, ServerConfig config, IEnumerable<ControlReservation> reservations,
        ServerConfig? appliedConfig = null)
    {
        var serverKey = ReservationService.ServerKey(server.ServerId);
        var directoryKey = ReservationService.DirectoryKey(server.HostId, config.ManagedRootId, config.ServerDirectory);
        var environmentKey = server.EnvironmentId is Guid id ? ReservationService.EnvironmentKey(id) : null;
        var appliedDirectory = appliedConfig is null ? null : ReservationService.DirectoryKey(server.HostId, appliedConfig.ManagedRootId, appliedConfig.ServerDirectory);
        return reservations.Where(x => x.ResourceKey == serverKey || x.ResourceKey == environmentKey || ReservationService.Conflicts(x.ResourceKey, directoryKey)
                || (appliedDirectory is not null && (x.ResourceKey.StartsWith("environment:", StringComparison.Ordinal) || ReservationService.Conflicts(x.ResourceKey, appliedDirectory))))
            .OrderByDescending(x => x.State == ReservationStatus.RecoveryRequired)
            .ThenByDescending(x => x.ResourceKey == serverKey)
            .ThenBy(x => x.ResourceKey, StringComparer.Ordinal);
    }

    public static ProcessControlAvailability For(InventoryServer server, ServerConfig config, InventoryHost? host, ServerRuntimeSnapshot? runtime,
        DateTimeOffset now, bool processControlEnabled, ControlOperation? operation, ControlReservation? reservation, ServerConfig? appliedConfig = null)
    {
        if (!HasCurrentReport(host, now)) runtime = null;
        var hostIssue = HostBlock(host, now, processControlEnabled);
        var capabilities = hostIssue is null ? ControlJson.Deserialize<string[]>(host!.CapabilitiesJson) : [];
        var configIssue = hostIssue ?? ConfigBlock(config, capabilities);
        var appliedSettingsAvailable = hostIssue is null && appliedConfig is not null && HasPendingAppliedSettings(server)
            && runtime?.ConfigRevision == server.AppliedRevision && ConfigBlock(appliedConfig, capabilities) is null;
        var unavailable = configIssue ?? (server.AppliedRevision < 1 || server.AppliedRevision != server.DesiredRevision
            ? "設定の適用を待っています。設定・詳細で適用状況を確認してください。"
            : runtime is null ? "Runner からサーバーの状態を取得できていません。配置 PC を確認してください。"
            : runtime.ConfigRevision != server.AppliedRevision ? "適用済み設定の状態報告を待っています。" : null);
        var enabled = unavailable is null;
        if (!enabled && !appliedSettingsAvailable) return new(false, runtime, operation is not null, reservation is not null, false, unavailable,
            operation?.WorkflowId ?? reservation?.OwnerWorkflowId);

        if (operation is not null)
        {
            var reason = operation.Status == OperationStatus.NeedsAttention
                ? "前回の操作結果に確認が必要です。履歴・復旧を確認してください。"
                : $"「{ControlDisplay.OperationState(operation.Status)}」の操作があります。完了を待ってください。";
            return new(enabled, runtime, true, reservation is not null, appliedSettingsAvailable, reason, operation.WorkflowId);
        }
        if (reservation is not null)
        {
            var reason = reservation.State == ReservationStatus.RecoveryRequired
                ? "保守予約の復旧が必要です。履歴・復旧を確認してください。"
                : "別の操作または保守が対象を予約しています。履歴・復旧で進行状況を確認してください。";
            return new(enabled, runtime, false, true, appliedSettingsAvailable, reason, reservation.OwnerWorkflowId);
        }
        var processReason = runtime!.ProcessState switch
        {
            ProcessState.Stopped when runtime.CurrentSessionId is null => unavailable,
            ProcessState.Running when runtime.CurrentSessionId is not null => appliedSettingsAvailable
                ? "新しい設定は未適用です。正常停止後に適用を待ってください。" : null,
            ProcessState.Starting => "起動処理中です。完了を待ってください。",
            ProcessState.Stopping => "停止処理中です。完了を待ってください。",
            ProcessState.Unmanaged => "Runner が管理できないプロセスを検出しました。配置 PC で状態を確認してください。",
            _ => "プロセスとセッションの状態を照合できません。次の状態報告を待ってください。"
        };
        return new(enabled, runtime, false, false, appliedSettingsAvailable, processReason, null);
    }

    private static string? HostBlock(InventoryHost? host, DateTimeOffset now, bool enabled)
    {
        if (!enabled) return "管理 Web でサーバー操作が無効です。運用設定を確認してください。";
        if (host is null) return "配置 PC の登録を確認できません。接続 PC を確認してください。";
        if (!host.Enabled || host.ConnectionState == HostConnectionState.Disabled) return "配置 PC の登録が無効です。接続 PC の設定を確認してください。";
        if (host.RecoveryRequired) return "配置 PC の状態の照合が必要です。接続 PC を確認してください。";
        if (host.ConnectionState == HostConnectionState.Reconciling) return "配置 PC と設定を照合中です。接続の完了を待ってください。";
        if (host.ConnectionState == HostConnectionState.Incompatible) return "配置 PC の Agent と Runner のバージョンを確認してください。";
        if (host.ConnectionState != HostConnectionState.Online) return "配置 PC に接続していません。PC と Agent の状態を確認してください。";
        if (!HasCurrentReport(host, now)) return "配置 PC の状態報告が途絶えています。Agent の接続を確認してください。";
        return null;
    }

    private static string? ConfigBlock(ServerConfig config, string[] capabilities)
    {
        if (!ServerProcessSupport.IsSupported(config.Kind)) return "このサーバーの種類は起動・停止に対応していません。";
        if (!capabilities.Contains("P1.Operations") || !capabilities.Contains("P1.LegacyGate"))
            return "配置 PC で運転操作と共通ゲートが利用できません。Agent と Runner の導入状態を確認してください。";
        if (!ServerProcessSupport.HasKindCapability(config.Kind, capabilities))
            return "配置 PC が Velocity の運転に対応していません。Agent と Runner のバージョンを確認してください。";
        if (config.DependencyReadiness != DependencyReadiness.Running || config.DependsOnServerIds.Length != 0)
            return "現在の依存サーバー設定では単独で運転できません。サーバー設定を確認してください。";
        return null;
    }
}
