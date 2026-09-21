using System.Text.Json;

namespace AstralRecordWeb.Models;

/// <summary>Web の公開プロフィールと本人プロフィールで共通に返す値です。</summary>
public sealed class WebPlayerProfileResponse
{
    public required Guid UserUuid { get; init; }
    public required string Mcid { get; init; }
    public int? Permission { get; init; }
    public bool IsPublic { get; init; }
    public WebPlayerAccountProfileResponse? CurrentAccount { get; init; }
    public required IReadOnlyList<WebPlayerAccountSummaryResponse> Accounts { get; init; }
}

/// <summary>現在選択中のアカウントだけを表すプロフィール進行です。</summary>
public sealed class WebPlayerAccountProfileResponse
{
    public required Guid AccountId { get; init; }
    public required string AccountName { get; init; }
    public required int SlotIndex { get; init; }
    public required int PlayerLevel { get; init; }
    public required string ClassId { get; init; }
    public required string ClassName { get; init; }
    public required int ClassLevel { get; init; }
    public required IReadOnlyList<WebPlayerClassProgressResponse> ClassProgresses { get; init; }
    public required long Gold { get; init; }
    public long TotalMobDefeats { get; init; }
    public required DateTime UpdatedAt { get; init; }
    public required WebSkillTreeProfileResponse SkillTree { get; init; }
}

public sealed class WebPlayerClassProgressResponse
{
    public required string ClassId { get; init; }
    public required string ClassName { get; init; }
    public required int Level { get; init; }
}

public sealed class WebSkillTreeProfileResponse
{
    public required string StructureId { get; init; }
    public required string Name { get; init; }
    public required string RootNodeId { get; init; }
    public required IReadOnlyList<WebSkillTreeNodeProfileResponse> Nodes { get; init; }
    public required IReadOnlyList<WebSkillTreeEdgeResponse> Edges { get; init; }
}

public sealed class WebSkillTreeNodeProfileResponse
{
    public required string NodeId { get; init; }
    public required string Name { get; init; }
    public required string Icon { get; init; }
    public IReadOnlyList<string> Lore { get; init; } = [];
    public IReadOnlyList<string> Tags { get; init; } = [];
    public required string PointType { get; init; }
    public required int PointCost { get; init; }
    public JsonElement? UnlockCondition { get; init; }
    public IReadOnlyList<JsonElement> Effects { get; init; } = [];
    public IReadOnlyList<string> DisplayEffects { get; init; } = [];
    public required double X { get; init; }
    public required double Y { get; init; }
    public required double Z { get; init; }
    public bool IsUnlocked { get; init; }
    public bool IsConditionMet { get; init; } = true;
    public string RequirementText { get; init; } = string.Empty;
    public string? ConsumedClassId { get; init; }
    public string? ConsumedClassName { get; init; }
    public string? CostText { get; init; }
    public string? StateText { get; init; }
    public string? BlockedReason { get; init; }
    public bool CanUnlock { get; init; }
    public bool CanRelock { get; init; }
    public bool RequiresCpSourceSelection { get; init; }
    public IReadOnlyList<WebSkillTreeCpSource> CpSources { get; init; } = [];
}

public sealed class WebSkillTreeCpSource
{
    public string ClassId { get; init; } = "";
    public string ClassName { get; init; } = "";
    public int AvailableCp { get; init; }
}

public sealed class WebSkillTreeEdgeResponse
{
    public required string SourceNodeId { get; init; }
    public required string TargetNodeId { get; init; }
}

public sealed class WebPlayerProfileVisibilityUpdateRequest
{
    public bool IsPublic { get; init; }
}

public sealed class WebPlayerProfileSearchResponse
{
    public required IReadOnlyList<WebPlayerProfileSummaryResponse> Profiles { get; init; }
    public required IReadOnlyList<WebPlayerProfileClassFilterResponse> Classes { get; init; }
    public required int Page { get; init; }
    public required int PageSize { get; init; }
    public required int TotalCount { get; init; }
}

public sealed class WebPlayerProfileClassFilterResponse
{
    public required string Id { get; init; }
    public required string Name { get; init; }
}

/// <summary>一覧用の概要。通貨・クラス進行・スキルツリーは詳細GETで取得する。</summary>
public sealed class WebPlayerProfileSummaryResponse
{
    public required Guid UserUuid { get; init; }
    public required string Mcid { get; init; }
    public bool IsPublic { get; init; }
    public required WebPlayerAccountSummaryResponse Account { get; init; }
}

public sealed class WebPlayerAccountSummaryResponse
{
    public required Guid AccountId { get; init; }
    public required string AccountName { get; init; }
    public required int SlotIndex { get; init; }
    public required int PlayerLevel { get; init; }
    public required string ClassId { get; init; }
    public required string ClassName { get; init; }
}
