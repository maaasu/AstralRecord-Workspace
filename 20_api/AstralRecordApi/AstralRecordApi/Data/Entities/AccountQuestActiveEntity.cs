namespace AstralRecordApi.Data.Entities;

public class AccountQuestActiveEntity
{
    public Guid AccountQuestActiveId { get; set; }
    public Guid AccountQuestStateId { get; set; }
    public string QuestId { get; set; } = string.Empty;
    public DateTime AcceptedAt { get; set; }
    public string? AcceptedNpcId { get; set; }
    public bool ReadyToTurnIn { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }

    public AccountQuestStateEntity? State { get; set; }
    public List<AccountQuestObjectiveProgressEntity> ObjectiveProgress { get; set; } = [];
}
