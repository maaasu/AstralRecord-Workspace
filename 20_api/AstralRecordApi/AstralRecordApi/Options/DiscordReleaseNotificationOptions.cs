namespace AstralRecordApi.Options;

public sealed class DiscordReleaseNotificationOptions
{
    public const string SectionName = "DiscordReleaseNotification";

    public bool Enabled { get; set; } = true;
    public string TokenFilePath { get; set; } = "token.txt";
    public string ChannelId { get; set; } = "1261962785026343043";
    public int PollIntervalSeconds { get; set; } = 10;
}
