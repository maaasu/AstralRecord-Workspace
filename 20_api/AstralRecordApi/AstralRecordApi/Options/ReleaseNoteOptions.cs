namespace AstralRecordApi.Options;

public sealed class ReleaseNoteOptions
{
    public const string SectionName = "ReleaseNotes";

    public string PublicBaseUrl { get; set; } = "https://astralrecord.com";
    public string NotificationChannel { get; set; } = "discord-release";
}
