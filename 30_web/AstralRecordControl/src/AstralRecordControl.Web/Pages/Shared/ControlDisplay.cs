using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;

namespace AstralRecordControl.Web.Pages.Shared;

public static class ControlDisplay
{
    public sealed record RuntimeStatus(string Label, string CssClass, string Detail, ProcessState? State);

    public static RuntimeStatus ServerState(InventoryHost? host, ServerRuntimeSnapshot? runtime, DateTimeOffset now)
    {
        if (host == null || !host.Enabled || host.ConnectionState != HostConnectionState.Online ||
            !host.LastSeenAtUtc.HasValue || now - host.LastSeenAtUtc.Value > TimeSpan.FromSeconds(30))
            return new("確認不能", "is-warning", "PCと通信できないため現在の状態は不明です", null);
        if (host.RecoveryRequired) return new("要確認", "is-warning", "PCの状態の照合が必要です", null);
        return runtime?.ProcessState switch
        {
            ProcessState.Running => new("稼働中", runtime.Readiness == GameReadiness.Failed ? "is-warning" : "is-online", runtime.Readiness switch
            {
                GameReadiness.Ready => "ゲーム受付可能", GameReadiness.Preparing => "ゲーム受付準備中",
                GameReadiness.Failed => "ゲーム受付に失敗・要確認", _ => "ゲーム受付状況は未確認"
            }, ProcessState.Running),
            ProcessState.Stopped => new("停止中", "is-offline", "プロセスは停止しています", ProcessState.Stopped),
            ProcessState.Starting => new("起動中", "is-pending", "起動処理を進めています", ProcessState.Starting),
            ProcessState.Stopping => new("停止処理中", "is-pending", "終了を待っています", ProcessState.Stopping),
            ProcessState.Unmanaged => new("要確認", "is-warning", "Runnerが管理できないプロセスがあります", ProcessState.Unmanaged),
            _ => new("確認不能", "is-warning", "プロセスの報告を待っています", null)
        };
    }

    public static string WorkflowState(WorkflowStatus state) => state switch
    {
        WorkflowStatus.Succeeded => "完了", WorkflowStatus.Failed => "失敗", WorkflowStatus.Cancelled => "中断済み",
        WorkflowStatus.RecoveryRequired => "要復旧", WorkflowStatus.WaitingForStartup => "起動待ち",
        WorkflowStatus.Planned => "受付済み", WorkflowStatus.Reserving => "予約中", WorkflowStatus.Running => "実行中", _ => "不明"
    };

    public static string OperationName(OperationKind kind) => kind switch
    {
        OperationKind.Start => "起動", OperationKind.Stop => "正常停止", OperationKind.Restart => "再起動",
        OperationKind.Kill => "強制終了", OperationKind.ConsoleCommand => "コマンド送信",
        OperationKind.CreateBackup => "バックアップ取得", OperationKind.RestoreBackup => "バックアップ復元", OperationKind.PruneBackups => "バックアップ整理",
        _ => kind.ToString()
    };

    public static string OperationState(OperationStatus state) => state switch
    {
        OperationStatus.Queued => "待機中", OperationStatus.Accepted => "受付済み", OperationStatus.Running => "実行中",
        OperationStatus.Succeeded => "完了", OperationStatus.Failed => "失敗", OperationStatus.Rejected => "受付拒否",
        OperationStatus.Expired => "期限切れ", OperationStatus.Cancelled => "中断済み", OperationStatus.NeedsAttention => "要確認", _ => "不明"
    };

    public static string WorkflowStateClass(WorkflowStatus state) => state switch
    {
        WorkflowStatus.Succeeded => "is-online", WorkflowStatus.Failed or WorkflowStatus.RecoveryRequired => "is-warning",
        WorkflowStatus.Cancelled => "is-offline", _ => "is-pending"
    };

    public static string HostState(HostConnectionState state) => state switch
    {
        HostConnectionState.Online => "接続中",
        HostConnectionState.Reconciling => "設定を照合中",
        HostConnectionState.Offline => "未接続",
        HostConnectionState.Disabled => "無効",
        HostConnectionState.Incompatible => "互換性を確認",
        _ => "状態不明"
    };

    public static string HostStateClass(HostConnectionState state) => state switch
    {
        HostConnectionState.Online => "is-online",
        HostConnectionState.Reconciling => "is-pending",
        HostConnectionState.Offline or HostConnectionState.Disabled => "is-offline",
        _ => "is-warning"
    };

    public static string Kind(ServerKind kind) => kind switch
    {
        ServerKind.Java => "Java",
        ServerKind.Bedrock => "Bedrock",
        ServerKind.Velocity => "Velocity",
        _ => "不明"
    };

    public static string LastSeen(DateTimeOffset? utc) => utc.HasValue
        ? utc.Value.ToOffset(TimeSpan.FromHours(9)).ToString("yyyy/MM/dd HH:mm 'JST'")
        : "まだ報告なし";
}
