namespace AstralRecordApi.Data.Entities;

public class AccountQuestStateEntity
{
    public Guid AccountQuestStateId { get; set; }
    public Guid AccountId { get; set; }
    public int Version { get; set; } = 1;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }

    public List<AccountQuestActiveEntity> ActiveQuests { get; set; } = [];
    public List<AccountQuestCompletionEntity> Completions { get; set; } = [];
    public List<AccountQuestCooldownEntity> Cooldowns { get; set; } = [];
}
