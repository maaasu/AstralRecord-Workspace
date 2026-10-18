using System.Text.Json;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Contracts.Tests;

public sealed class ProtocolTests
{
    private static readonly Guid Host = Guid.NewGuid();
    private static ServerConfig Server() => new() { HostId = Host, ServerId = Guid.NewGuid(), ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "dev", Revision = 1 };

    [Theory]
    [InlineData("{\"schemaVersion\":1,\"schemaVersion\":2}")]
    [InlineData("{\"servers\":[{\"kind\":\"Java\",\"kind\":\"Bedrock\"}]}")]
    [InlineData("{\"futureField\":true}")]
    [InlineData("{\"SchemaVersion\":1}")]
    [InlineData("{\"servers\":[{\"kind\":0}]}")]
    [InlineData("{\"servers\":[{\"kind\":\"java\"}]}")]
    [InlineData("{\"servers\":[{\"kind\":\"0\"}]}")]
    public void UntrustedJsonRejectsAmbiguousOrUnknownContract(string json) => Assert.Throws<JsonException>(() => ControlJson.Deserialize<InventorySnapshot>(json));

    [Fact]
    public void StrictEnumRoundTrips() => Assert.Equal(ServerKind.Velocity, ControlJson.Deserialize<ServerConfig>(ControlJson.Serialize(Server() with { Kind = ServerKind.Velocity })).Kind);

    [Fact]
    public void MissingRequiredConstructorFieldsRejectInsteadOfChangingHostState()
    {
        Assert.Throws<JsonException>(() => ControlJson.Deserialize<HostUpdateRequest>("{\"displayName\":\"new name\"}"));
        Assert.True(ControlJson.Deserialize<HostUpdateRequest>("{\"displayName\":\"new name\",\"enabled\":true}").Enabled);
        var ack = new InventoryApplied(Guid.NewGuid(), 1, new string('a', 64), true);
        Assert.Null(ControlJson.Deserialize<InventoryApplied>(ControlJson.Serialize(ack)).ErrorCode);
    }

    [Fact]
    public void OptionalServerAndEventFieldsMayBeOmittedWhileMandatoryFieldsStillReject()
    {
        var config = ControlJson.Serialize(Server());
        var create = ControlJson.Deserialize<ServerCreateRequest>("{\"hostId\":\"" + Host + "\",\"serverKey\":\"dev\",\"displayName\":\"Dev\",\"config\":" + config + "}");
        Assert.Null(create.EnvironmentId); Assert.Null(create.RuntimeServerId);
        var update = ControlJson.Deserialize<ServerUpdateRequest>("{\"displayName\":\"Dev\",\"config\":" + config + "}");
        Assert.Null(update.EnvironmentId); Assert.Null(update.RuntimeServerId);
        Assert.Throws<JsonException>(() => ControlJson.Deserialize<ServerUpdateRequest>("{\"displayName\":\"Dev\"}"));
        var item = ControlJson.Deserialize<AgentEvent>("{\"eventSeq\":1,\"eventType\":\"Diagnostic\",\"occurredAtUtc\":\"2026-10-18T00:00:00Z\",\"body\":{}}");
        Assert.Null(item.OperationId); Assert.Null(item.StatusVersion);
    }

    [Theory]
    [InlineData("../server")][InlineData("/server")][InlineData("C:/server")][InlineData("a\\b")]
    [InlineData("a/./b")][InlineData("a//b")][InlineData("a/CON.txt")][InlineData("a/b.")][InlineData("a/b ")][InlineData("a/x:stream")]
    public void WindowsUnsafeRelativePathsReject(string path) => Assert.False(ControlProtocol.IsRelativePath(path));

    [Fact]
    public void EnvelopePinsExactBytesHostAndRevision()
    {
        var snapshot = new InventorySnapshot { HostId = Host, Revision = 2, Servers = [Server()] };
        var json = ControlJson.Serialize(snapshot);
        var envelope = new InventoryEnvelope(1, Host, 2, json, ControlProtocol.Hash(json));
        Assert.Equal(2, InventoryValidation.ReadEnvelope(envelope, Host).Revision);
        Assert.Throws<ArgumentException>(() => InventoryValidation.ReadEnvelope(envelope with { SnapshotJson = json + " " }, Host));
        Assert.Throws<ArgumentException>(() => InventoryValidation.ReadEnvelope(envelope, Guid.NewGuid()));
        Assert.Throws<ArgumentException>(() => InventoryValidation.ReadEnvelope(envelope with { Revision = 3 }, Host));
    }

    [Fact]
    public void InvalidInventoryRejectsBeforeSideEffects()
    {
        var first = Server();
        var second = Server() with { ListenPort = 25565 };
        var snapshot = new InventorySnapshot { HostId = Host, Revision = 1, Servers = [first with { ListenPort = 25565 }, second] };
        Assert.Throws<ArgumentException>(() => InventoryValidation.Validate(snapshot, Host));
        Assert.Throws<ArgumentException>(() => InventoryValidation.Validate(snapshot with { Servers = [first with { DependsOnServerIds = [second.ServerId] }, second with { DependsOnServerIds = [first.ServerId] }] }, Host));
        Assert.Throws<ArgumentException>(() => InventoryValidation.Validate(snapshot with { Servers = null! }, Host));
        Assert.Throws<ArgumentException>(() => InventoryValidation.Validate(first with { ArgumentList = null! }, Host));
        Assert.Throws<ArgumentException>(() => InventoryValidation.Validate(first with { SchemaVersion = 2 }, Host));
    }
}
