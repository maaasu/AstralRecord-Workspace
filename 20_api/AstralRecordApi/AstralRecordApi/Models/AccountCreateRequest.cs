namespace AstralRecordApi.Models;

public class AccountCreateRequest
{
    public Guid UserId { get; set; }
    public string AccountName { get; set; } = string.Empty;
    public int SlotIndex { get; set; }
    public byte Mode { get; set; }
    public Guid CreatedBy { get; set; }
}
