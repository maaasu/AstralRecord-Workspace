using Xunit;

namespace AstralRecordControl.Contracts.Tests;

public sealed class MetricsCompatibilityTests
{
    [Fact]
    public void EmptyLegacyMetricsArrayRemainsCompatibleWithProtocolOne()
    {
        Assert.Null(ControlJson.Deserialize<AgentHeartbeat>("{\"metrics\":[]}").Metrics);
        Assert.Throws<System.Text.Json.JsonException>(() => ControlJson.Deserialize<AgentHeartbeat>("{\"metrics\":[{}]}"));
        var batch = new AgentHeartbeat { Metrics = new() { ObservedAtUtc = DateTimeOffset.UtcNow, Host = new() } };
        Assert.NotNull(ControlJson.Deserialize<AgentHeartbeat>(ControlJson.Serialize(batch)).Metrics);
    }
}
