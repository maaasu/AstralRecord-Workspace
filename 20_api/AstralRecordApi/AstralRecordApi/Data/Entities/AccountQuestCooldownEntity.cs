namespace AstralRecordApi.Data.Entities;

public class AccountQuestCooldownEntity
{
    public Guid AccountQuestCooldownId { get; set; }
    public Guid AccountQuestStateId { get; set; }
    public string QuestId { get; set; } = string.Empty;
    public DateTime CooldownUntil { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }

    public AccountQuestStateEntity? State { get; set; }
}
