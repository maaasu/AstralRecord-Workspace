namespace AstralRecordApi.Models;

public class AccountUpdateRequest
{
    public string? AccountName { get; set; }
    public bool? IsActive { get; set; }
    public byte? Mode { get; set; }
    public string? MenuShortcutsJson { get; set; }
    public int? Level { get; set; }
    public long? TotalExperience { get; set; }
    public string? ClassId { get; set; }
    public int? ClassLevel { get; set; }
    public long? ClassExperience { get; set; }
    public IReadOnlyList<AccountClassProgressUpdateRequest>? ClassProgresses { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class AccountClassProgressUpdateRequest
{
    public string ClassId { get; set; } = string.Empty;
    public int Level { get; set; }
    public long Experience { get; set; }
}
