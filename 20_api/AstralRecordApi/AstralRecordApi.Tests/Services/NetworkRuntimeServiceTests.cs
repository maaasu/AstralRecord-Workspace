using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public sealed class NetworkRuntimeServiceTests
{
    [Fact]
    public void PlayerPresenceExpiresAfterThirtySeconds()
    {
        var time = new MutableTimeProvider(new DateTimeOffset(2026, 9, 4, 0, 0, 0, TimeSpan.Zero));
        var service = new NetworkRuntimeService(time);
        var uuid = Guid.NewGuid();

        service.UpsertPlayer(new NetworkPlayerHeartbeatRequest(
            uuid, "AstralRecord", "ch1", "ch1", "AstralRecord#1", 12, "剣士", false));
        Assert.Single(service.GetPlayers());

        time.Advance(TimeSpan.FromSeconds(31));

        Assert.Empty(service.GetPlayers());
    }

    [Fact]
    public void ServerPresenceKeepsRoleSpecificCapacity()
    {
        var time = new MutableTimeProvider(new DateTimeOffset(2026, 9, 4, 0, 0, 0, TimeSpan.Zero));
        var service = new NetworkRuntimeService(time);

        service.UpsertServer(new NetworkServerHeartbeatRequest(
            "ch1", "ch1", "online", 32, 40, 5, 1));

        var server = Assert.Single(service.GetServers());
        Assert.Equal(40, server.Capacity);
        Assert.Equal(5, server.DonorExtraPlayers);
        Assert.Equal(1, server.AdminExtraPlayers);
    }

    [Fact]
    public void DuplicateChatMessageIdReturnsSameSequence()
    {
        var time = new MutableTimeProvider(new DateTimeOffset(2026, 9, 4, 0, 0, 0, TimeSpan.Zero));
        var service = new NetworkRuntimeService(time);
        var messageId = Guid.NewGuid();
        var request = new NetworkChatPublishRequest(messageId, "minecraft", "ch1", "AstralRecord", "こんにちは");

        var first = service.PublishChat(request);
        var duplicate = service.PublishChat(request);

        Assert.Equal(first.Sequence, duplicate.Sequence);
        Assert.Single(service.GetChatAfter(0, "minecraft").Messages);
    }

    [Fact]
    public void ChatCursorAheadOfRestartedSequenceReadsNewMessages()
    {
        var time = new MutableTimeProvider(new DateTimeOffset(2026, 9, 4, 0, 0, 0, TimeSpan.Zero));
        var service = new NetworkRuntimeService(time);
        service.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "discord", "lobby", "DiscordUser", "再起動後のメッセージ"));

        var messages = service.GetChatAfter(500, "discord").Messages;

        Assert.Single(messages);
        Assert.Equal(1, messages[0].Sequence);
    }

    [Fact]
    public void ChatBatchGenerationChangesBetweenRuntimeInstances()
    {
        var time = new MutableTimeProvider(new DateTimeOffset(2026, 9, 4, 0, 0, 0, TimeSpan.Zero));
        var first = new NetworkRuntimeService(time).GetChatAfter(0, null);
        var restarted = new NetworkRuntimeService(time).GetChatAfter(0, null);

        Assert.NotEqual(first.GenerationId, restarted.GenerationId);
    }

    private sealed class MutableTimeProvider(DateTimeOffset value) : TimeProvider
    {
        private DateTimeOffset current = value;
        public override DateTimeOffset GetUtcNow() => current;
        public void Advance(TimeSpan duration) => current += duration;
    }
}
