namespace AstralRecordApi.Models;

public class UserHistoryCreateRequest
{
    public Guid? UserUuid { get; set; }
    public DateTime EventTime { get; set; }
    public string EventType { get; set; } = string.Empty;
    public string Source { get; set; } = "PLUGIN";
    public string Message { get; set; } = string.Empty;
    public string? PayloadJson { get; set; }
}
