using System.Net;
using System.Net.Http.Headers;
using System.Security.Principal;
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
    [Fact]
    public async Task P3ManualAcceptanceOfflineScheduleMetricsAndEventsCrossHttpsAndNamedPipe()
    {
        await using var f = await LiveFixture.StartAsync(enableProcessControl: true, enableSchedules: true);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(2));
        using var owner = f.CreateClient(f.OwnerCookies); await f.LoginOwnerAsync(owner);
        var host = await f.OwnerApiAsync<InventoryHost>(owner, HttpMethod.Post, "/api/control/v1/hosts", new HostCreateRequest("p3-pc", "P3 fixture"), create: true);
        var credential = await f.OwnerApiAsync<CredentialIssued>(owner, HttpMethod.Post, $"/api/control/v1/hosts/{host.HostId}/credentials", new { });
        using var identity = WindowsIdentity.GetCurrent();
        var runnerConfig = new RunnerConfiguration { HostId = host.HostId, AgentSid = identity.User!.Value, PipeName = "control-p3-" + Guid.NewGuid().ToString("N"), LocalResources = f.Resources, EnableProcessControl = true };
        using var runnerStore = new RunnerStore(Path.Combine(f.Root, "Runner"), runnerConfig);
        using var runnerService = new RunnerService(runnerConfig, new RunnerDispatcher(runnerStore), NullLogger<RunnerService>.Instance);
        await runnerService.StartAsync(timeout.Token);
        var config = new AgentConfiguration { HostId = host.HostId, ServerUrl = f.Origin.ToString(), RunnerPipeName = runnerConfig.PipeName, LocalResources = f.Resources, EnableSchedules = true };
        using var store = new AgentStore(Path.Combine(f.Root, "Agent"), host.HostId);
        var runner = new RunnerClient(config); var handler = new AgentOperationHandler(config, store, runner);
        var marker = Path.Combine(f.Root, "test-only-marker"); await File.WriteAllTextAsync(marker, "fixture", timeout.Token);
        var clock = new AutomationClock(DateTimeOffset.UtcNow);
        using var schedules = new AgentScheduleService(config, store, runner, handler, new AgentProcessControlGate(marker), clock);
        var applier = new InventoryApplier(config, store, runner);
        var epoch = Guid.Empty;
        var rejectManual = false;
        await using var hub = f.CreateHub(ControlProtocol.AgentHubPath, HttpTransportType.WebSockets, credential.Token);
        hub.On<RunScheduleNowRequest, RunScheduleNowResponse>("RunScheduleNow", async r =>
        {
            if (rejectManual) return new(null, ErrorCode.StaleRevision);
            try { return new(await schedules.RunNowAsync(r, epoch, timeout.Token), null); }
            catch (AgentFault fault) { return new(null, fault.Code); }
        });
        using var agent = f.CreateClient(); agent.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential.Token);
        try
        {
            await Connect();
            var serverId = Guid.NewGuid();
            var server = new ServerConfig { HostId = host.HostId, ServerId = serverId, Revision = 1, ManagedRootId = f.Resources.Roots.Single().Id, ServerDirectory = "dev", ExecutableId = f.Resources.Executables.Single().Id, ArgumentList = [typeof(TestProcess.Marker).Assembly.Location] };
            await f.OwnerApiAsync<InventoryServer>(owner, HttpMethod.Post, "/api/control/v1/servers", new { hostId = host.HostId, serverKey = "p3-dev", displayName = "P3 Dev", config = server }, create: true);
            await Apply();
            var definition = new ScheduleDefinition { HostId = host.HostId, ServerId = serverId, ScheduleId = Guid.NewGuid(), Revision = 1, Enabled = true,
                Trigger = new() { Kind = ScheduleTriggerKind.Once, AtUtc = clock.GetUtcNow().AddHours(1) },
                Template = new() { ActionType = ScheduleActionType.Operation, Steps = [new() { StepKey = "start", Kind = OperationKind.Start }] } };
            await f.OwnerApiAsync<ScheduleDefinition>(owner, HttpMethod.Post, "/api/control/v1/schedules", definition, create: true);
            await Apply();
            var manualId = Guid.NewGuid();
            await f.OwnerApiAsync<System.Text.Json.JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/schedules/{definition.ScheduleId}/run-now", new { }, create: true, requestId: manualId, expectedStatus: HttpStatusCode.Accepted);
            Assert.Equal(ScheduleOccurrenceStatus.Pending, store.GetScheduleReport(manualId)!.Status);
            await Complete(manualId);
            Assert.Equal(ProcessState.Running, (await runner.GetSnapshotAsync(timeout.Token)).Servers.Single().ProcessState);
            await Publish();
            var sampler = new AgentMetricsSampler(runner, config, store: store);
            var snapshot = await runner.GetSnapshotAsync(timeout.Token);
            var metrics = await sampler.SampleAsync(snapshot, true, timeout.Token);
            await hub.InvokeAsync("Heartbeat", new AgentHeartbeat { ConnectionEpoch = epoch, RunnerEpoch = snapshot.RunnerEpoch, ObservedAtUtc = DateTimeOffset.UtcNow, RunnerAvailable = true, ServerSnapshots = snapshot.Servers, Metrics = metrics }, timeout.Token);
            using (var response = await owner.GetAsync($"/api/control/v1/servers/{serverId}/metrics?fromUtc={Uri.EscapeDataString(DateTimeOffset.UtcNow.AddMinutes(-2).ToString("O"))}&toUtc={Uri.EscapeDataString(DateTimeOffset.UtcNow.ToString("O"))}&granularity=minute", timeout.Token)) Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            foreach (var page in new[] { $"/Servers/{serverId}/Schedules", $"/Servers/{serverId}/Metrics", "/Notifications" })
                using (var response = await owner.GetAsync(page, timeout.Token)) Assert.Equal(HttpStatusCode.OK, response.StatusCode);

            var offline = definition with { Revision = 2, Trigger = new() { Kind = ScheduleTriggerKind.Once, AtUtc = clock.GetUtcNow().AddSeconds(30) }, Template = new() { Steps = [new() { StepKey = "stop", Kind = OperationKind.Stop }] } };
            await f.OwnerApiAsync<ScheduleDefinition>(owner, HttpMethod.Put, $"/api/control/v1/schedules/{definition.ScheduleId}", offline, match: 1);
            await Apply();
            await hub.StopAsync(timeout.Token);
            clock.Now = clock.GetUtcNow().AddSeconds(31);
            var offlineId = ScheduleIdentity.Occurrence(offline.ScheduleId, 2, offline.Trigger.AtUtc!.Value);
            await Complete(offlineId);
            Assert.Equal(ProcessState.Stopped, (await runner.GetSnapshotAsync(timeout.Token)).Servers.Single().ProcessState);
            Assert.NotEmpty(store.ReadEvents());
            await Connect(); await Publish();
            using (var response = await owner.GetAsync($"/api/control/v1/schedules/{definition.ScheduleId}", timeout.Token))
            {
                Assert.Equal(HttpStatusCode.OK, response.StatusCode);
                Assert.Contains(offlineId.ToString(), await response.Content.ReadAsStringAsync(timeout.Token));
            }
            Assert.Empty(store.ReadEvents());
            // Revision changes must not turn the same accepted manual request into a new operation.
            var replay = await f.OwnerApiAsync<System.Text.Json.JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/schedules/{definition.ScheduleId}/run-now", new { }, create: true, requestId: manualId, expectedStatus: HttpStatusCode.Accepted);
            Assert.Equal(manualId, replay.GetProperty("operationId").GetGuid());
            rejectManual = true;
            var rejectedId = Guid.NewGuid();
            var rejected = await f.OwnerApiAsync<System.Text.Json.JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/schedules/{definition.ScheduleId}/run-now", new { }, create: true, requestId: rejectedId, expectedStatus: HttpStatusCode.Accepted);
            Assert.Equal("Rejected", rejected.GetProperty("status").GetString());
            using var rejectedQuery = await owner.GetAsync($"/api/control/v1/schedules/{definition.ScheduleId}/requests/{rejectedId}", timeout.Token);
            Assert.Contains("Rejected", await rejectedQuery.Content.ReadAsStringAsync(timeout.Token));
            Assert.Null(store.GetScheduleReport(rejectedId));
        }
        finally
        {
            await hub.StopAsync();
            foreach (var state in runnerStore.GetSnapshot().Servers.Where(s => s.CurrentSessionId.HasValue))
            {
                var inspection = runnerStore.InspectRecovery(new InspectRecoveryRequest(state.ServerId));
                if (inspection.Session?.ProcessId is int pid)
                {
                    try
                    {
                        using var process = System.Diagnostics.Process.GetProcessById(pid);
                        if (process.StartTime.ToUniversalTime() == inspection.Session.ProcessCreatedAtUtc?.UtcDateTime) { process.Kill(true); await process.WaitForExitAsync(); }
                    }
                    catch (ArgumentException) { }
                }
            }
            await runnerService.StopAsync(CancellationToken.None);
        }

        async Task Connect()
        {
            await hub.StartAsync(timeout.Token);
            var snapshot = await runner.GetSnapshotAsync(timeout.Token); var state = store.GetState();
            var hello = await Invoke<HelloAck>(hub, "Hello", new AgentHello { AgentVersion = "p3-test", AgentStoreId = state.StoreId, MaxLocalEventSeq = state.MaxEventSequence, AppliedInventoryRevision = state.AppliedInventoryRevision, RunnerEpoch = snapshot.RunnerEpoch, LocalResources = f.Resources, Capabilities = ["P0.Inventory", "P1.Operations", "P1.LegacyGate", "P3.Schedules", "P3.Metrics"] }, timeout.Token);
            epoch = hello.ConnectionEpoch; store.Acknowledge(hello.ControllerAckEventSeq);
            await Invoke<ReconcileAck>(hub, "Reconcile", new ReconcileRequest { ConnectionEpoch = epoch, RunnerEpoch = snapshot.RunnerEpoch, AppliedInventoryRevision = state.AppliedInventoryRevision, Servers = snapshot.Servers, Holds = snapshot.Holds, ScheduleOccurrences = store.ReadScheduleReports(true).Select(r => r.Report).ToArray() }, timeout.Token);
            agent.DefaultRequestHeaders.Remove("X-Control-Connection-Epoch"); agent.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", epoch.ToString());
        }
        async Task Apply()
        {
            var envelope = ControlJson.Deserialize<InventoryEnvelope>(await agent.GetStringAsync("/api/agent/v1/inventory", timeout.Token));
            await applier.ApplyAsync(envelope, timeout.Token);
            await hub.InvokeAsync("InventoryApplied", new InventoryApplied(epoch, envelope.Revision, envelope.Sha256, true), timeout.Token);
            store.MarkScheduleInventoryAcknowledged(envelope.Revision);
        }
        async Task Publish()
        {
            var snapshot = await runner.GetSnapshotAsync(timeout.Token); store.ObserveRuntime(snapshot, DateTimeOffset.UtcNow);
            while (store.ReadEvents() is { Length: > 0 } events)
            {
                var ack = await Invoke<EventAck>(hub, "PublishEvents", new EventBatch(epoch, events), timeout.Token); store.Acknowledge(ack.AckEventSeq);
            }
        }
        async Task Complete(Guid id)
        {
            for (var attempt = 0; attempt < 100; attempt++)
            {
                await schedules.TickAsync(timeout.Token);
                if (store.GetScheduleReport(id)?.Status == ScheduleOccurrenceStatus.Succeeded) return;
                await Task.Delay(50, timeout.Token);
            }
            Assert.Fail("Scheduled fixture operation did not complete: " + ControlJson.Serialize(store.GetScheduleReport(id)));
        }
    }
    private sealed class AutomationClock(DateTimeOffset now) : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = now;
        public override DateTimeOffset GetUtcNow() => Now;
    }
}
