namespace AstralRecordApi.Options;

public sealed class DonationDiscordOptions
{
    public const string SectionName = "Donations";

    public string DiscordClientId { get; set; } = string.Empty;
    public string DiscordClientSecret { get; set; } = string.Empty;
    public string DiscordGuildId { get; set; } = string.Empty;
}
