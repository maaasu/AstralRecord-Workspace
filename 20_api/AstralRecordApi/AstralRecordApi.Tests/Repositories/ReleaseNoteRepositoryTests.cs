using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class ReleaseNoteRepositoryTests
{
    [Fact]
    public async Task PublishAsync_IsIdempotentForSameSlugAndChannel()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
        }

        var request = new ReleaseNotePublishRequest
        {
            Slug = "release-management",
            Version = "0.1.0",
            Title = "Release management",
            Summary = "Initial release.",
            PublishedAt = DateTimeOffset.UtcNow,
            ReleaseUrl = "https://astralrecord.com/releases/release-management",
            SourcePath = "00_docs/70_リリースノート/2026-08-24-v0.1.0.md",
            ContentSha256 = new string('A', 64),
            NotifyDiscord = true,
        };

        await using (var firstContext = new AstralRecordDbContext(options))
        {
            var repository = new ReleaseNoteRepository(firstContext);
            var result = await repository.PublishAsync(request, "discord-release", CancellationToken.None);

            Assert.True(result.Created);
            Assert.True(result.NotificationQueued);
        }

        await using (var secondContext = new AstralRecordDbContext(options))
        {
            var repository = new ReleaseNoteRepository(secondContext);
            var result = await repository.PublishAsync(request, "discord-release", CancellationToken.None);

            Assert.False(result.Created);
            Assert.False(result.NotificationQueued);
            Assert.Equal(1, await secondContext.ReleaseNotes.CountAsync());
            Assert.Equal(1, await secondContext.ReleaseNotificationOutboxes.CountAsync());
        }
    }

    [Fact]
    public async Task PublishAsync_NormalizesNullSummaryAndWaitsUntilFuturePublication()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new AstralRecordDbContext(options))
            await CreateSchemaAsync(setupContext);

        var publishedAt = DateTimeOffset.UtcNow.AddMinutes(5);
        var request = CreateRequest(summary: null, publishedAt);

        await using var context = new AstralRecordDbContext(options);
        var repository = new ReleaseNoteRepository(context);
        await repository.PublishAsync(request, "discord-release", CancellationToken.None);

        var note = await context.ReleaseNotes.SingleAsync();
        var outbox = await context.ReleaseNotificationOutboxes.SingleAsync();
        Assert.Equal(string.Empty, note.Summary);
        Assert.True(outbox.NextAttemptAtUtc >= publishedAt.UtcDateTime);
        Assert.Null(await repository.ClaimNextNotificationAsync(
            "discord-release",
            publishedAt.UtcDateTime.AddMinutes(-1),
            CancellationToken.None));
    }

    [Fact]
    public async Task RetryNotificationAsync_DoesNotMakeFuturePublicationImmediatelyDue()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new AstralRecordDbContext(options))
            await CreateSchemaAsync(setupContext);

        var publishedAt = DateTimeOffset.UtcNow.AddMinutes(5);
        await using var context = new AstralRecordDbContext(options);
        var repository = new ReleaseNoteRepository(context);
        await repository.PublishAsync(CreateRequest(summary: "summary", publishedAt), "discord-release", CancellationToken.None);

        Assert.True(await repository.RetryNotificationAsync(
            "release-management",
            "discord-release",
            DateTime.UtcNow,
            CancellationToken.None));

        var outbox = await context.ReleaseNotificationOutboxes.SingleAsync();
        Assert.True(outbox.NextAttemptAtUtc >= publishedAt.UtcDateTime);
    }

    [Fact]
    public async Task ClaimNextNotificationAsync_AllowsOnlyOneLeaseOwner()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new AstralRecordDbContext(options))
            await CreateSchemaAsync(setupContext);

        await using (var publishContext = new AstralRecordDbContext(options))
        {
            var repository = new ReleaseNoteRepository(publishContext);
            await repository.PublishAsync(
                CreateRequest("summary", DateTimeOffset.UtcNow.AddMinutes(-1)),
                "discord-release",
                CancellationToken.None);
        }

        await using var firstContext = new AstralRecordDbContext(options);
        await using var secondContext = new AstralRecordDbContext(options);
        var first = await new ReleaseNoteRepository(firstContext).ClaimNextNotificationAsync(
            "discord-release", DateTime.UtcNow, CancellationToken.None);
        var second = await new ReleaseNoteRepository(secondContext).ClaimNextNotificationAsync(
            "discord-release", DateTime.UtcNow, CancellationToken.None);

        Assert.NotNull(first);
        Assert.Null(second);
    }

    private static ReleaseNotePublishRequest CreateRequest(string? summary, DateTimeOffset publishedAt)
        => new()
        {
            Slug = "release-management",
            Version = "0.1.0",
            Title = "Release management",
            Summary = summary,
            PublishedAt = publishedAt,
            ReleaseUrl = "https://astralrecord.com/releases/release-management",
            SourcePath = "00_docs/70_リリースノート/2026-08-24-v0.1.0.md",
            ContentSha256 = new string('A', 64),
            NotifyDiscord = true,
        };

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE release_note (
                release_note_id TEXT NOT NULL PRIMARY KEY,
                slug TEXT NOT NULL,
                version TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                release_url TEXT NOT NULL,
                source_path TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                published_at_utc TEXT NOT NULL,
                is_published INTEGER NOT NULL,
                notify_discord INTEGER NOT NULL,
                created_at_utc TEXT NOT NULL,
                updated_at_utc TEXT NOT NULL
            );
            CREATE UNIQUE INDEX UX_release_note_slug ON release_note (slug);
            CREATE TABLE release_notification_outbox (
                outbox_id TEXT NOT NULL PRIMARY KEY,
                release_note_id TEXT NOT NULL,
                channel TEXT NOT NULL,
                status INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL,
                next_attempt_at_utc TEXT NOT NULL,
                lease_until_utc TEXT NULL,
                lease_token TEXT NULL,
                sent_at_utc TEXT NULL,
                discord_message_id TEXT NULL,
                last_error TEXT NULL,
                created_at_utc TEXT NOT NULL,
                updated_at_utc TEXT NOT NULL
            );
            CREATE UNIQUE INDEX UX_release_notification_outbox_note_channel
                ON release_notification_outbox (release_note_id, channel);
        ");
    }
}
