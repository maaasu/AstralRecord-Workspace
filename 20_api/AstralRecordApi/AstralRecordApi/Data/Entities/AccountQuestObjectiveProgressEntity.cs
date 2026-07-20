namespace AstralRecordApi.Data.Entities;

public class AccountQuestObjectiveProgressEntity
{
    public Guid AccountQuestObjectiveProgressId { get; set; }
    public Guid AccountQuestActiveId { get; set; }
    public string ObjectiveId { get; set; } = string.Empty;
    public int Progress { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }

    public AccountQuestActiveEntity? ActiveQuest { get; set; }
}
