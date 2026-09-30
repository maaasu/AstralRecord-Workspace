namespace AstralRecordApi.Models;

/// <summary>起動確認済みの定義を通常ログインの移行先として公開します。</summary>
public sealed class SkillTreePatchPublishRequest
{
    public string DefinitionGenerationId { get; init; } = string.Empty;
}

public sealed class SkillTreePatchPublishResponse
{
    public string DefinitionGenerationId { get; init; } = string.Empty;
    public long PatchVersion { get; init; }
}

/// <summary>まだプレイを開始していない所有sessionで、保持移行を確認・適用します。</summary>
public sealed class SkillTreeLoginPatchRequest
{
    public Guid ServerSessionId { get; init; }
    public Guid AccountSessionId { get; init; }
    public string AccountLeaseToken { get; init; } = string.Empty;
    public string DefinitionGenerationId { get; init; } = string.Empty;
    public bool Apply { get; init; }
    public int ExpectedStateVersion { get; init; }
}

public static class SkillTreeLoginPatchStatuses
{
    public const string Current = "CURRENT";
    public const string UpdateRequired = "UPDATE_REQUIRED";
    public const string Applied = "APPLIED";
    public const string ChannelOutdated = "CHANNEL_OUTDATED";
    public const string Unpublished = "UNPUBLISHED";
    public const string Incompatible = "INCOMPATIBLE";
    public const string LegacyRequiresRepair = "LEGACY_REQUIRES_REPAIR";
}

public sealed class SkillTreeLoginPatchResponse
{
    public string Status { get; init; } = string.Empty;
    public string DefinitionGenerationId { get; init; } = string.Empty;
    public long? PatchVersion { get; init; }
    public int StateVersion { get; init; }
}
