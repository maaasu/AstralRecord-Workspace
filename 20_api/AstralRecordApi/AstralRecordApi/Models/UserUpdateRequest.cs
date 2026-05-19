namespace AstralRecordApi.Models;

public class UserUpdateRequest
{
    public string? Mcid { get; set; }
    public DateTime? LastJoinDate { get; set; }
    public string? GlobalIp { get; set; }
    public Guid? AccountId { get; set; }
    public bool? BanIndefinite { get; set; }
    public DateTime? BanDate { get; set; }
    public bool? KickIp { get; set; }
    public int? Permission { get; set; }
    public Guid UpdatedBy { get; set; }
}
