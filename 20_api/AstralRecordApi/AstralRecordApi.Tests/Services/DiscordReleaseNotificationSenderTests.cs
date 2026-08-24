using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Options;
using AstralRecordApi.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public sealed class DiscordReleaseNotificationSenderTests
{
    [Fact]
    public async Task SendAsync_UsesStableNonceAndDiscordNonceEnforcement()
    {
        var handler = new RecordingHandler(() => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("{\"id\":\"message-id\"}", Encoding.UTF8, "application/json"),
        });
        var tokenPath = await CreateTokenFileAsync();
        try
        {
            var sender = CreateSender(handler, tokenPath);
            var outbox = CreateOutbox(Guid.Parse("01234567-89ab-cdef-0123-456789abcdef"));

            var result = await sender.SendAsync(outbox, CancellationToken.None);

            Assert.True(result.Succeeded);
            Assert.Equal("message-id", result.MessageId);
            Assert.NotNull(handler.Request);
            Assert.Equal("Bot", handler.Request!.Headers.Authorization?.Scheme);
            using var document = JsonDocument.Parse(handler.RequestBody!);
            var nonce = document.RootElement.GetProperty("nonce").GetString();
            Assert.NotNull(nonce);
            Assert.InRange(nonce!.Length, 1, 25);
            Assert.True(document.RootElement.GetProperty("enforce_nonce").GetBoolean());
        }
        finally
        {
            File.Delete(tokenPath);
        }
    }

    [Fact]
    public async Task SendAsync_UsesMaximumOf429HeaderAndBodyRetryAfter()
    {
        var response = new HttpResponseMessage((HttpStatusCode)429)
        {
            Content = new StringContent("{\"retry_after\":37.5}", Encoding.UTF8, "application/json"),
        };
        response.Headers.RetryAfter = new RetryConditionHeaderValue(TimeSpan.FromSeconds(12));
        var handler = new RecordingHandler(() => response);
        var tokenPath = await CreateTokenFileAsync();
        try
        {
            var result = await CreateSender(handler, tokenPath).SendAsync(
                CreateOutbox(Guid.NewGuid()),
                CancellationToken.None);

            Assert.False(result.Succeeded);
            Assert.True(result.Retryable);
            Assert.Equal(TimeSpan.FromSeconds(37.5), result.RetryAfter);
        }
        finally
        {
            File.Delete(tokenPath);
        }
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized)]
    [InlineData(HttpStatusCode.Forbidden)]
    public async Task SendAsync_TreatsAuthenticationAndPermissionFailuresAsPermanent(HttpStatusCode statusCode)
    {
        var handler = new RecordingHandler(() => new HttpResponseMessage(statusCode));
        var tokenPath = await CreateTokenFileAsync();
        try
        {
            var result = await CreateSender(handler, tokenPath).SendAsync(
                CreateOutbox(Guid.NewGuid()),
                CancellationToken.None);

            Assert.False(result.Succeeded);
            Assert.False(result.Retryable);
        }
        finally
        {
            File.Delete(tokenPath);
        }
    }

    [Fact]
    public async Task SendAsync_TreatsMissingTokenAsPermanentFailureWithoutSending()
    {
        var handler = new RecordingHandler(() => new HttpResponseMessage(HttpStatusCode.OK));
        var tokenPath = Path.Combine(Path.GetTempPath(), $"astralrecord-missing-token-{Guid.NewGuid():N}.txt");

        var result = await CreateSender(handler, tokenPath).SendAsync(
            CreateOutbox(Guid.NewGuid()),
            CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.False(result.Retryable);
        Assert.Null(handler.Request);
    }

    [Fact]
    public async Task SendAsync_TreatsEmptyTokenAsPermanentFailureWithoutSending()
    {
        var handler = new RecordingHandler(() => new HttpResponseMessage(HttpStatusCode.OK));
        var tokenPath = await CreateTokenFileAsync(string.Empty);
        try
        {
            var result = await CreateSender(handler, tokenPath).SendAsync(
                CreateOutbox(Guid.NewGuid()),
                CancellationToken.None);

            Assert.False(result.Succeeded);
            Assert.False(result.Retryable);
            Assert.Null(handler.Request);
        }
        finally
        {
            File.Delete(tokenPath);
        }
    }

    private static DiscordReleaseNotificationSender CreateSender(RecordingHandler handler, string tokenPath)
    {
        var client = new HttpClient(handler)
        {
            BaseAddress = new Uri("https://discord.test/api/v10/"),
        };
        return new DiscordReleaseNotificationSender(
            client,
            Microsoft.Extensions.Options.Options.Create(new DiscordReleaseNotificationOptions
            {
                TokenFilePath = tokenPath,
                ChannelId = "1261962785026343043",
            }),
            NullLogger<DiscordReleaseNotificationSender>.Instance);
    }

    private static ReleaseNotificationOutboxEntity CreateOutbox(Guid outboxId)
        => new()
        {
            OutboxId = outboxId,
            ReleaseNote = new ReleaseNoteEntity
            {
                Slug = "release-management",
                Version = "0.1.0",
                Title = "Release management",
                ReleaseUrl = "https://astralrecord.com/releases/release-management",
            },
        };

    private static async Task<string> CreateTokenFileAsync(string content = "test-token")
    {
        var path = Path.Combine(Path.GetTempPath(), $"astralrecord-token-{Guid.NewGuid():N}.txt");
        await File.WriteAllTextAsync(path, content);
        return path;
    }

    private sealed class RecordingHandler(Func<HttpResponseMessage> responseFactory) : HttpMessageHandler
    {
        public HttpRequestMessage? Request { get; private set; }
        public string? RequestBody { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Request = request;
            RequestBody = request.Content is null
                ? null
                : await request.Content.ReadAsStringAsync(cancellationToken);
            return responseFactory();
        }
    }
}
