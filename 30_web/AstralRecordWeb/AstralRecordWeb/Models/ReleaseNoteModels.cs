namespace AstralRecordWeb.Models;

public enum ReleaseNoteStatus
{
    Draft,
    Published,
}

public sealed class ReleaseNoteDocument
{
    public required string Slug { get; init; }
    public required string Version { get; init; }
    public required string Title { get; init; }
    public required string Summary { get; init; }
    public required DateTimeOffset PublishedAt { get; init; }
    public required ReleaseNoteStatus Status { get; init; }
    public required bool NotifyDiscord { get; init; }
    public required string SourcePath { get; init; }
    public required string ContentSha256 { get; init; }
    public required string ReleaseUrl { get; init; }
    public required string Html { get; init; }
}

public sealed class ReleaseNotePublishRequest
{
    public required string Slug { get; init; }
    public required string Version { get; init; }
    public required string Title { get; init; }
    public required string Summary { get; init; }
    public required DateTimeOffset PublishedAt { get; init; }
    public required string SourcePath { get; init; }
    public required string ContentSha256 { get; init; }
    public required string ReleaseUrl { get; init; }
    public required bool NotifyDiscord { get; init; }
}
