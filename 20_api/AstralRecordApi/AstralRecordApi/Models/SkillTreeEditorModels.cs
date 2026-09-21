using System.Text.Json;

namespace AstralRecordApi.Models;

public static class SkillTreeOperationStatuses
{
    public const string PendingOnline = "PENDING_ONLINE";
    public const string PendingOffline = "PENDING_OFFLINE";
    public const string Claimed = "CLAIMED";
    public const string Applied = "APPLIED";
    public const string ReconfirmationRequired = "RECONFIRMATION_REQUIRED";
    public const string Failed = "FAILED";
    public const string Canceled = "CANCELED";
    public const string Expired = "EXPIRED";

    public static bool IsActive(string status) => status is PendingOnline or PendingOffline or Claimed;
}

public sealed class SkillTreeServerRegistrationRequest
{
    public Guid ServerSessionId { get; init; }
    /// <summary>同一serverIdで単調増加するPlugin起動時刻。旧bootの遅延registerを拒否するfenceです。</summary>
    public DateTime ServerStartedAtUtc { get; init; }
    public required string PluginVersion { get; init; }
    public required string CompatibilityVersion { get; init; }
    public required string DefinitionGenerationId { get; init; }
    /// <summary>Plugin が正規化済みUTF-8 JSONとしてhash化した、表示・判定に必要な完全snapshot。</summary>
    public required string CanonicalSnapshotJson { get; init; }
    public bool Ready { get; init; }
}

public sealed class SkillTreeServerHeartbeatRequest
{
    public Guid ServerSessionId { get; init; }
    public required string DefinitionGenerationId { get; init; }
    public bool Ready { get; init; }
}

public sealed class SkillTreeServerRuntimeResponse
{
    public required string ServerId { get; init; }
    public Guid ServerSessionId { get; init; }
    public required string DefinitionGenerationId { get; init; }
    public bool Ready { get; init; }
    public DateTime LastSeenUtc { get; init; }
}

public sealed class SkillTreeOperationCreateRequest
{
    public Guid OperationId { get; init; }
    public Guid ActorUserId { get; init; }
    public required string TargetServerId { get; init; }
    public required string ExpectedDefinitionGenerationId { get; init; }
    public int ExpectedPlayerStateVersion { get; init; }
    /// <summary>Gold、PP/CP、class、条件を含むPlugin評価入力の正規化fingerprintです。</summary>
    /// <summary>後方互換用。APIはPlugin登録済みviewから期待fingerprintを固定し、Web入力を信頼しません。</summary>
    public string? ExpectedEvaluationFingerprint { get; init; }
    public required string Action { get; init; }
    public required string NodeId { get; init; }
    public string? SourceClassId { get; init; }
}

/// <summary>
/// 実際にロード済みの Plugin が評価した表示情報。Web はこの値から条件・費用を再計算しません。
/// </summary>
public sealed class SkillTreePlayerViewRegistrationRequest
{
    public Guid ServerSessionId { get; init; }
    public required string DefinitionGenerationId { get; init; }
    public int PlayerStateVersion { get; init; }
    public required string EvaluationFingerprint { get; init; }
    /// <summary>拠点またはスキルツリーワールドでのみ true。位置判定は Plugin が正本です。</summary>
    public bool EditEligible { get; init; }
    /// <summary>Pluginが明示logoutを確認したcached viewだけoffline案に利用できます。</summary>
    public bool OfflineConfirmed { get; init; }
    /// <summary>tree, points, relockGoldCost, connection を含む Plugin 評価済み JSON。</summary>
    public JsonElement View { get; init; }
}

public sealed class SkillTreeOperationResponse
{
    public Guid OperationId { get; init; }
    public Guid AccountId { get; init; }
    public required string TargetServerId { get; init; }
    public required string ExpectedDefinitionGenerationId { get; init; }
    public int ExpectedPlayerStateVersion { get; init; }
    public required string ExpectedEvaluationFingerprint { get; init; }
    public required string Action { get; init; }
    public required string NodeId { get; init; }
    public string? SourceClassId { get; init; }
    public required string Status { get; init; }
    public string? Reason { get; init; }
    public DateTime ExpiresAtUtc { get; init; }
    public DateTime CreatedAtUtc { get; init; }
    public DateTime? CompletedAtUtc { get; init; }
}

public sealed class SkillTreeOperationClaimRequest
{
    public Guid ServerSessionId { get; init; }
    public Guid AccountId { get; init; }
}

public sealed class SkillTreeOperationClaimResponse
{
    public required string LeaseToken { get; init; }
    public DateTime LeaseExpiresAtUtc { get; init; }
    public required SkillTreeOperationResponse Operation { get; init; }
}

/// <summary>nonempty legacy採用または廃止node除去だけを許可する明示移行です。</summary>
public sealed class SkillTreeMigrationRequest
{
    public Guid OperationId { get; init; }
    public int ExpectedStateVersion { get; init; }
    public string? FromGenerationId { get; init; }
    public required string ToGenerationId { get; init; }
    public required IReadOnlyList<string> LegacyBaselineNodeIds { get; init; }
    public required IReadOnlyList<string> RemoveNodeIds { get; init; }
    /// <summary>Pluginが旧新snapshotの保持node互換性・graph連結性を検証した証明。APIはゲーム条件を再実装しない。</summary>
    public required string CompatibilityProofHash { get; init; }
}
public sealed class SkillTreeMigrationResponse { public Guid OperationId { get; init; } public required string Status { get; init; } public int StateVersion { get; init; } public required IReadOnlyList<string> RemovedNodeIds { get; init; } }

/// <summary>Plugin の既存player-state snapshotと同一transactionで確定する操作結果です。</summary>
public sealed class PlayerStateSkillTreeOperationSection
{
    public Guid OperationId { get; init; }
    public required string ServerId { get; init; }
    public Guid ServerSessionId { get; init; }
    public required string LeaseToken { get; init; }
    public required string FinalStatus { get; init; }
    public string? FailureReason { get; init; }
    public required string DefinitionGenerationId { get; init; }
    public int FinalPlayerStateVersion { get; init; }
    public required string FinalEvaluationFingerprint { get; init; }
    /// <summary>legacy state を Plugin が同一世代で検証した場合だけ明示移行を許可します。</summary>
    public bool MigrateLegacyState { get; init; }
    public JsonElement? EvaluatedView { get; init; }
}

public sealed class SkillTreeEditorConnectionResponse
{
    public required string Status { get; init; }
    public string? ServerId { get; init; }
    public string? ChannelName { get; init; }
    public string? WorldName { get; init; }
    public double? X { get; init; }
    public double? Y { get; init; }
    public double? Z { get; init; }
    public DateTime? ObservedAtUtc { get; init; }
    public bool CanEdit { get; init; }
}

/// <summary>Web editor用。tree/pointsはPlugin登録済みのプレイヤー別評価結果です。</summary>
public sealed class SkillTreeEditorResponse
{
    public Guid AccountId { get; init; }
    public required string AccountName { get; init; }
    public required SkillTreeEditorConnectionResponse Connection { get; init; }
    public string? GenerationId { get; init; }
    public int StateRevision { get; init; }
    public bool CanEdit { get; init; }
    /// <summary>現在接続先Pluginで確認済みの残高・条件を返している場合だけtrueです。</summary>
    public bool HasFreshState { get; init; }
    /// <summary>LIVE、SAVED、UNKNOWN。SAVEDはoffline編集案の基準であり確定残高ではありません。</summary>
    public required string BalanceKind { get; init; }
    public string? Reason { get; init; }
    public JsonElement? Tree { get; init; }
    public JsonElement? Points { get; init; }
    public decimal? RelockGoldCost { get; init; }
    public SkillTreeOperationResponse? PendingOperation { get; init; }
    public IReadOnlyList<SkillTreeOperationResponse> PendingOperations { get; init; } = [];
}
