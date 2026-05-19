namespace AstralRecordApi.Data.Entities;

public class UserEntity
{
    public Guid Uuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public DateTime JoinDate { get; set; }
    public DateTime LastJoinDate { get; set; }
    public string GlobalIp { get; set; } = string.Empty;
    public Guid? AccountId { get; set; }
    public bool BanIndefinite { get; set; }
    public DateTime? BanDate { get; set; }
    public bool KickIp { get; set; }
    public int Permission { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
