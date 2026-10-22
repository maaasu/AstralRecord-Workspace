using System.Net;
using System.Net.Http.Headers;
using System.Security.Principal;
using System.Text.Json;
using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using AstralRecordControl.Runner;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Http.Connections;
using Microsoft.AspNetCore.SignalR.Client;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed partial class LiveTransportTests
{
    [Theory]
    [InlineData(ServerKind.Java)]
    [InlineData(ServerKind.Velocity)]
    public async Task OwnerOperationsCrossHttpsAgentAndRunnerAndMaintenanceKeepsItsHold(ServerKind kind)
    {
        await using var fixture = await LiveFixture.StartAsync(enableProcessControl: true);
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        using var cli = fixture.CreateClient();
        var cliToken = await fixture.IssueCliAsync(cli);
        cli.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", cliToken.Token);
        using (var denied = await cli.GetAsync("/api/control/v1/overview")) Assert.Equal(HttpStatusCode.Unauthorized, denied.StatusCode);
        using (var catalog = await cli.GetAsync("/api/control/v1/gate/catalog")) Assert.Equal(HttpStatusCode.OK, catalog.StatusCode);
        var host = await fixture.OwnerApiAsync<InventoryHost>(owner, HttpMethod.Post, "/api/control/v1/hosts", new HostCreateRequest("operation-pc", "Operation fixture"), create: true);
        var credential = await fixture.OwnerApiAsync<CredentialIssued>(owner, HttpMethod.Post, $"/api/control/v1/hosts/{host.HostId}/credentials", new { });
        using var identity = WindowsIdentity.GetCurrent();
        var pipeName = "control-p1-live-" + Guid.NewGuid().ToString("N");
        var runnerConfiguration = new RunnerConfiguration
        { HostId = host.HostId, AgentSid = identity.User!.Value, PipeName = pipeName, LocalResources = fixture.Resources, EnableProcessControl = true };
        using var runnerStore = new RunnerStore(Path.Combine(fixture.Root, "Runner"), runnerConfiguration);
        using var runner = new RunnerService(runnerConfiguration, new RunnerDispatcher(runnerStore), NullLogger<RunnerService>.Instance);
        await runner.StartAsync(CancellationToken.None);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        var configuration = new AgentConfiguration { HostId = host.HostId, ServerUrl = fixture.Origin.ToString(), RunnerPipeName = pipeName, LocalResources = fixture.Resources };
        using var agentStore = new AgentStore(Path.Combine(fixture.Root, "Agent"), host.HostId);
        var runnerClient = new RunnerClient(configuration);
        var handler = new AgentOperationHandler(configuration, agentStore, runnerClient);
        var applier = new InventoryApplier(configuration, agentStore, runnerClient);
        var epoch = Guid.Empty;
        await using var hub = fixture.CreateHub(ControlProtocol.AgentHubPath, HttpTransportType.WebSockets, credential.Token);
        hub.On<DispatchOperationRequest, ExecutionSnapshot>("DispatchOperation", request => handler.DispatchAsync(request, epoch, timeout.Token));
        hub.On<PrepareHoldRequest, PreparedHold>("PrepareHold", request => handler.PrepareHoldAsync(request, epoch, timeout.Token));
        hub.On<ReleasePreparedHoldRequest, ReleasePreparedHoldResponse>("ReleaseHold", request => handler.ReleaseHoldAsync(request, epoch, timeout.Token));
        hub.On<AgentExecutionRequest, ExecutionSnapshot>("GetExecution", request => handler.QueryAsync(request, epoch, timeout.Token));
        hub.On<AgentReadLogsRequest, LogBatch>("ReadLogs", request => handler.ReadLogsAsync(request, epoch, timeout.Token));
        hub.On<AgentSnapshotRequest, RunnerSnapshot>("GetSnapshot", request => request.ConnectionEpoch == epoch ? runnerClient.GetSnapshotAsync(timeout.Token) : throw new InvalidOperationException());
        try
        {
            await hub.StartAsync(timeout.Token);
            var initial = await runnerClient.GetSnapshotAsync(timeout.Token);
            var runnerCapabilities = await runnerClient.GetCapabilitiesAsync(timeout.Token);
            Assert.Contains(ServerProcessSupport.VelocityCapability, runnerCapabilities);
            var hello = await Invoke<HelloAck>(hub, "Hello", new AgentHello
            { AgentVersion = "p1-fixture", AgentStoreId = agentStore.GetState().StoreId, RunnerEpoch = initial.RunnerEpoch, LocalResources = fixture.Resources, Capabilities = ["P0.Inventory", "P1.Operations", "P1.LegacyGate", .. runnerCapabilities] }, timeout.Token);
            epoch = hello.ConnectionEpoch;
            await Invoke<ReconcileAck>(hub, "Reconcile", new ReconcileRequest { ConnectionEpoch = epoch, RunnerEpoch = initial.RunnerEpoch }, timeout.Token);
            using var agent = fixture.CreateClient();
            agent.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential.Token);
            agent.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", epoch.ToString());
            var serverId = Guid.NewGuid();
            var config = new ServerConfig
            { ServerId = serverId, HostId = host.HostId, Revision = 1, Kind = kind, ManagedRootId = fixture.Resources.Roots.Single().Id, ExecutableId = fixture.Resources.Executables.Single().Id, ServerDirectory = "dev", ArgumentList = kind == ServerKind.Velocity ? [typeof(TestProcess.Marker).Assembly.Location, "velocity"] : [typeof(TestProcess.Marker).Assembly.Location] };
            await fixture.OwnerApiAsync<InventoryServer>(owner, HttpMethod.Post, "/api/control/v1/servers", new { hostId = host.HostId, serverKey = "dev", displayName = "Dev", config }, create: true);
            await ApplyAndAck(agent, hub, epoch, applier, agentStore, runnerClient, timeout.Token);
            await Heartbeat(hub, epoch, await runnerClient.GetSnapshotAsync(timeout.Token), timeout.Token);

            using (var serversPage = await owner.GetAsync("/Servers", timeout.Token))
                Assert.Contains($"/Servers/{serverId}/Operations", await serversPage.Content.ReadAsStringAsync(timeout.Token));
            using (var settingsPage = await owner.GetAsync($"/Servers/{serverId}", timeout.Token))
            {
                var html = await settingsPage.Content.ReadAsStringAsync(timeout.Token);
                Assert.Contains($"value=\"{config.ExecutableId}\"", html);
                Assert.Contains($"value=\"{ServerProcessSupport.DefaultStopCommand(kind)}\"", html);
            }

            // A maintenance workflow remains reserved while the UI starts/stops its child process.
            var workflowId = Guid.NewGuid();
            var lease = await fixture.OwnerApiAsync<GateLease>(owner, HttpMethod.Post, "/api/control/v1/reservations", new ReservationAcquireRequest
            { WorkflowId = workflowId, ResourceKeys = ["server:" + serverId], AdapterId = "01", RunReference = "fixture-run", ServerConditions = [new GateServerCondition(serverId, ProcessRequirement.Stopped)] }, create: true);
            Assert.Equal(ReservationStatus.Prepared, lease.Status);
            await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/reservations/{lease.ReservationId}/validate", new GateValidateRequest(workflowId, lease.Fences, "Deploy", "fixture-run"), expectedStatus: HttpStatusCode.OK);
            await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/reservations/{lease.ReservationId}/validate", new GateValidateRequest(workflowId, lease.Fences, "WaitForStartup", "fixture-run"), expectedStatus: HttpStatusCode.OK);
            var start = await SendOperation(OperationKind.Start, new { }, workflowId, lease.ReservationId);
            Assert.Equal(OperationStatus.Succeeded, await AwaitOperation(start));
            var running = Assert.Single((await runnerClient.GetSnapshotAsync(timeout.Token)).Servers);
            Assert.Equal(ProcessState.Running, running.ProcessState);
            Assert.NotNull(running.CurrentSessionId);
            await Heartbeat(hub, epoch, await runnerClient.GetSnapshotAsync(timeout.Token), timeout.Token);
            using (var operationsPage = await owner.GetAsync($"/Servers/{serverId}/Operations", timeout.Token)) Assert.Equal(HttpStatusCode.OK, operationsPage.StatusCode);
            using (var workflowsPage = await owner.GetAsync("/Workflows", timeout.Token)) Assert.Equal(HttpStatusCode.OK, workflowsPage.StatusCode);
            await Task.Delay(100, timeout.Token);
            var held = ControlJson.Deserialize<GateLease>(await owner.GetStringAsync($"/api/control/v1/reservations/{lease.ReservationId}", timeout.Token));
            Assert.Equal(ReservationStatus.Prepared, held.Status);
            await fixture.OwnerApiAsync<object>(owner, HttpMethod.Post, $"/api/control/v1/reservations/{lease.ReservationId}/release", new GateReleaseRequest(workflowId, lease.Fences, GateOutcome.Succeeded, "fixture-run"), expectedStatus: HttpStatusCode.NoContent);
            var stop = await SendOperation(OperationKind.Stop, new { });
            Assert.Equal(OperationStatus.Succeeded, await AwaitOperation(stop));
            await WaitForNoHolds();

            var secondStart = await SendOperation(OperationKind.Start, new { });
            Assert.Equal(OperationStatus.Succeeded, await AwaitOperation(secondStart));
            await WaitForNoHolds();
            var session = Assert.Single((await runnerClient.GetSnapshotAsync(timeout.Token)).Servers).CurrentSessionId;
            Assert.NotEqual(running.CurrentSessionId, session);
            var command = await SendOperation(OperationKind.ConsoleCommand, new { command = "say fixture-once" });
            Assert.Equal(OperationStatus.Succeeded, await AwaitOperation(command));
            await WaitForNoHolds();
            LogBatch logs;
            do
            {
                logs = ControlJson.Deserialize<LogBatch>(await owner.GetStringAsync($"/api/control/v1/servers/{serverId}/logs?sessionId={session}&limit=200", timeout.Token));
                if (!logs.Rows.Any(x => x.Text.Contains("echo:say fixture-once", StringComparison.Ordinal))) await Task.Delay(50, timeout.Token);
            } while (!logs.Rows.Any(x => x.Text.Contains("echo:say fixture-once", StringComparison.Ordinal)));
            Assert.Contains(logs.Rows, x => x.Stream == "stderr" && x.Text.Contains("fixture-stderr", StringComparison.Ordinal));
            Assert.Single(logs.Rows, x => x.Text.Contains("echo:say fixture-once", StringComparison.Ordinal));
            using (var revoked = await cli.PostAsync("/api/control/v1/auth/cli-token/revoke", null, timeout.Token)) Assert.Equal(HttpStatusCode.NoContent, revoked.StatusCode);
            using (var denied = await cli.GetAsync("/api/control/v1/gate/catalog", timeout.Token)) Assert.Equal(HttpStatusCode.Unauthorized, denied.StatusCode);
            // Revoking the Agent connection does not own or terminate the running process.
            await fixture.OwnerApiAsync<object>(owner, HttpMethod.Delete, $"/api/control/v1/hosts/{host.HostId}/credentials/{credential.CredentialId}", null);
            Assert.Equal(ProcessState.Running, Assert.Single((await runnerClient.GetSnapshotAsync(timeout.Token)).Servers).ProcessState);

            async Task<Guid> SendOperation(OperationKind kind, object payload, Guid? workflow = null, Guid? reservation = null)
            {
                var snapshot = await runnerClient.GetSnapshotAsync(timeout.Token);
                await Heartbeat(hub, epoch, snapshot, timeout.Token);
                var state = Assert.Single(snapshot.Servers);
                var request = new ServerOperationRequest { Kind = kind, Payload = ControlJson.Element(payload), ConfigRevision = 1, ExpectedStateRevision = state.StateRevision, ExpectedServerSessionId = state.CurrentSessionId, WorkflowId = workflow, ReservationId = reservation };
                var requestId = Guid.NewGuid();
                var result = await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/servers/{serverId}/operations", request, create: true, expectedStatus: HttpStatusCode.Accepted, requestId: requestId);
                var replay = await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/servers/{serverId}/operations", request, create: true, expectedStatus: HttpStatusCode.Accepted, requestId: requestId);
                Assert.Equal(result.GetProperty("operationId").GetGuid(), replay.GetProperty("operationId").GetGuid());
                return result.GetProperty("operationId").GetGuid();
            }
            async Task<OperationStatus> AwaitOperation(Guid operation)
            {
                while (true)
                {
                    using var current = JsonDocument.Parse(await owner.GetStringAsync($"/api/control/v1/operations/{operation}", timeout.Token));
                    var status = Enum.Parse<OperationStatus>(current.RootElement.GetProperty("status").GetString()!);
                    if (OperationValidation.IsTerminal(status) || status == OperationStatus.NeedsAttention) return status;
                    await Task.Delay(100, timeout.Token);
                }
            }
            async Task WaitForNoHolds()
            {
                while (true)
                {
                    var state = await runnerClient.GetSnapshotAsync(timeout.Token);
                    if (state.Holds.Length == 0) return;
                    await Heartbeat(hub, epoch, state, timeout.Token);
                    await Task.Delay(500, timeout.Token);
                }
            }
        }
        finally
        {
            // Only the disposable fixture process can be killed by test cleanup.
            var snapshot = runnerStore.GetSnapshot();
            foreach (var state in snapshot.Servers.Where(x => x.CurrentSessionId != null))
            {
                var inspection = runnerStore.InspectRecovery(new InspectRecoveryRequest(state.ServerId));
                if (inspection.Session?.ProcessId is int pid)
                {
                    try { using var process = System.Diagnostics.Process.GetProcessById(pid); if (process.StartTime.ToUniversalTime() == inspection.Session.ProcessCreatedAtUtc?.UtcDateTime) { process.Kill(true); await process.WaitForExitAsync(); } }
                    catch (ArgumentException) { }
                }
            }
            await runner.StopAsync(CancellationToken.None);
        }
    }
}
