namespace AstralRecordWeb.Options;

public sealed class ReleaseNoteOptions
{
    public const string SectionName = "ReleaseNotes";

    public string ContentRootRelativePath { get; set; } = "release-notes";
    public string PublicBaseUrl { get; set; } = "https://astralrecord.com";
    public bool SyncOnStartup { get; set; } = true;
    public int SyncIntervalMinutes { get; set; } = 15;
}
