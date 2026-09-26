namespace AstralRecordWeb.Options;

public sealed class DonationOptions
{
    public const string SectionName = "Donations";
    public string WebKey { get; set; } = "";
    public string DiscordClientId { get; set; } = "";
    public string DiscordClientSecret { get; set; } = "";
    public string DiscordRedirectUri { get; set; } = "";
    public bool DiscordConfigured => !string.IsNullOrWhiteSpace(DiscordClientId)
        && !string.IsNullOrWhiteSpace(DiscordClientSecret)
        && Uri.TryCreate(DiscordRedirectUri, UriKind.Absolute, out var uri)
        && uri.Scheme == Uri.UriSchemeHttps && uri.AbsolutePath == "/Donations/Discord"
        && string.IsNullOrEmpty(uri.Query) && string.IsNullOrEmpty(uri.Fragment);
}
