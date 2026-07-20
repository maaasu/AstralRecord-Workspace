namespace AstralRecordApi.Models;

public class AccountQuestStateResponse
{
    public Guid? AccountQuestStateId { get; init; }
    public Guid AccountId { get; init; }
    public required IReadOnlyList<AccountQuestActiveResponse> ActiveQuests { get; init; }
    public required IReadOnlyList<AccountQuestCompletionResponse> Completions { get; init; }
    public required IReadOnlyList<AccountQuestCooldownResponse> Cooldowns { get; init; }
    public bool IsSaved { get; init; }
    public int Version { get; init; }
    public DateTime? CreatedAt { get; init; }
    public DateTime? UpdatedAt { get; init; }
    public Guid? CreatedBy { get; init; }
    public Guid? UpdatedBy { get; init; }
}

public class AccountQuestActiveResponse
{
    public required string QuestId { get; init; }
    public long AcceptedAtEpochMillis { get; init; }
    public string? AcceptedNpcId { get; init; }
    public bool ReadyToTurnIn { get; init; }
    public required IReadOnlyList<AccountQuestObjectiveProgressResponse> ObjectiveProgress { get; init; }
}

public class AccountQuestObjectiveProgressResponse
{
    public required string ObjectiveId { get; init; }
    public int Progress { get; init; }
}

public class AccountQuestCompletionResponse
{
    public required string QuestId { get; init; }
    public long CompletedAtEpochMillis { get; init; }
}

public class AccountQuestCooldownResponse
{
    public required string QuestId { get; init; }
    public long CooldownUntilEpochMillis { get; init; }
}

public class AccountQuestStateUpsertRequest
{
    public required IReadOnlyList<AccountQuestActiveRequest> ActiveQuests { get; init; }
    public required IReadOnlyList<AccountQuestCompletionRequest> Completions { get; init; }
    public required IReadOnlyList<AccountQuestCooldownRequest> Cooldowns { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountQuestActiveRequest
{
    public required string QuestId { get; init; }
    public long AcceptedAtEpochMillis { get; init; }
    public string? AcceptedNpcId { get; init; }
    public bool ReadyToTurnIn { get; init; }
    public required IReadOnlyList<AccountQuestObjectiveProgressRequest> ObjectiveProgress { get; init; }
}

public class AccountQuestObjectiveProgressRequest
{
    public required string ObjectiveId { get; init; }
    public int Progress { get; init; }
}

public class AccountQuestCompletionRequest
{
    public required string QuestId { get; init; }
    public long CompletedAtEpochMillis { get; init; }
}

public class AccountQuestCooldownRequest
{
    public required string QuestId { get; init; }
    public long CooldownUntilEpochMillis { get; init; }
}
