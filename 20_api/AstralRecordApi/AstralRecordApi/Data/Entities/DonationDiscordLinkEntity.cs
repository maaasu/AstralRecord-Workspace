namespace AstralRecordApi.Data.Entities;

public sealed class DonationDiscordLinkEntity
{
    public Guid UserUuid { get; set; }
    public string DiscordUserId { get; set; } = string.Empty;
    public string DiscordName { get; set; } = string.Empty;
    public string ProtectedAccessToken { get; set; } = string.Empty;
    public string ProtectedRefreshToken { get; set; } = string.Empty;
    public bool IsGuildMember { get; set; }
    public DateTime VerifiedAtUtc { get; set; }
    public int Revision { get; set; }
}
