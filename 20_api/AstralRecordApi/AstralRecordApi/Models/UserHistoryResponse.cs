namespace AstralRecordApi.Models;

public class UserHistoryResponse
{
    public long HistoryId { get; set; }
    public Guid? UserUuid { get; set; }
    public DateTime EventTime { get; set; }
    public string EventType { get; set; } = string.Empty;
    public string Source { get; set; } = string.Empty;
    public string Message { get; set; } = string.Empty;
    public string? PayloadJson { get; set; }
    public DateTime CreatedAt { get; set; }
}
