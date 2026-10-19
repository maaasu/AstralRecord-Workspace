using AstralRecordControl.Contracts;

namespace AstralRecordControl.Web.Pages.Shared;

public static class ControlDisplay
{
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
