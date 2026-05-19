namespace AstralRecordApi.Models;

public class UserCreateRequest
{
    public Guid Uuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public DateTime JoinDate { get; set; }
    public DateTime LastJoinDate { get; set; }
    public string GlobalIp { get; set; } = string.Empty;
    public Guid CreatedBy { get; set; }
}
