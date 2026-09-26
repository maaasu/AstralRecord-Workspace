namespace AstralRecordApi.Models;

public sealed record DonationDiscordLinkRequest(string AccessToken, string RefreshToken);

public sealed record DonationDiscordLinkResponse(
    string DiscordUserId, string DiscordName, bool IsGuildMember, DateTime VerifiedAtUtc);
