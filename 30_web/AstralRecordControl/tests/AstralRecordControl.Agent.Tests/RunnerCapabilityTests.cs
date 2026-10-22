using System.Buffers.Binary;
using System.IO.Pipes;
using System.Text;
using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class RunnerCapabilityTests
{
    [Theory]
    [InlineData(null)]
    [InlineData(ErrorCode.UnsupportedCapability)]
    [InlineData(ErrorCode.RecoveryRequired)]
    public async Task CapabilityProbeFallsBackOnlyForOlderRunner(ErrorCode? error)
    {
        var name = "control-capabilities-" + Guid.NewGuid().ToString("N");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        var reply = Task.Run(async () =>
        {
            await server.WaitForConnectionAsync(timeout.Token);
            var header = new byte[4];
            await server.ReadExactlyAsync(header, timeout.Token);
            var bytes = new byte[checked((int)BinaryPrimitives.ReadUInt32LittleEndian(header))];
            await server.ReadExactlyAsync(bytes, timeout.Token);
            var request = ControlJson.Deserialize<IpcRequest>(Encoding.UTF8.GetString(bytes));
            Assert.Equal("GetCapabilities", request.Method);
            Assert.Empty(request.Body.EnumerateObject());
            var response = new IpcResponse(request.RequestId, !error.HasValue,
                error.HasValue ? ControlJson.Element(new { }) : ControlJson.Element(new[] { ServerProcessSupport.VelocityCapability }), error?.ToString());
            bytes = Encoding.UTF8.GetBytes(ControlJson.Serialize(response));
            BinaryPrimitives.WriteUInt32LittleEndian(header, (uint)bytes.Length);
            await server.WriteAsync(header, timeout.Token);
            await server.WriteAsync(bytes, timeout.Token);
            await server.FlushAsync(timeout.Token);
        }, timeout.Token);
        var client = new RunnerClient(new AgentConfiguration { RunnerPipeName = name });
        if (error == ErrorCode.RecoveryRequired)
            Assert.Equal(error, (await Assert.ThrowsAsync<AgentFault>(() => client.GetCapabilitiesAsync(timeout.Token))).Code);
        else if (error == ErrorCode.UnsupportedCapability)
            Assert.Empty(await client.GetCapabilitiesAsync(timeout.Token));
        else
            Assert.Contains(ServerProcessSupport.VelocityCapability, await client.GetCapabilitiesAsync(timeout.Token));
        await reply;
    }
}
