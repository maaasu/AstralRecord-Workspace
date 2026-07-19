namespace AstralRecordApi.Data.Entities;

public class AccountClassProgressEntity
{
    public Guid AccountId { get; set; }
    public string ClassId { get; set; } = string.Empty;
    public int Level { get; set; } = 1;
    public long Experience { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid UpdatedBy { get; set; }

    public AccountEntity Account { get; set; } = null!;
}
