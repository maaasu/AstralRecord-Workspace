using System.Net;
using System.Net.Http.Headers;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using AstralRecordControl.Agent;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Agent.Transfers;
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
    public async Task P2FilesLargeReadTransfersAndInterruptedRestoreCrossRealHttpsAndRunnerGate()
    {
        await using var fixture = await LiveFixture.StartAsync(enableProcessControl: true, enableFileControl: true);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(5));
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var host = await fixture.OwnerApiAsync<InventoryHost>(owner, HttpMethod.Post, "/api/control/v1/hosts", new HostCreateRequest("data-pc", "Data fixture"), create: true);
        var credential = await fixture.OwnerApiAsync<CredentialIssued>(owner, HttpMethod.Post, $"/api/control/v1/hosts/{host.HostId}/credentials", new { });
        using var identity = WindowsIdentity.GetCurrent();
        var runnerConfiguration = new RunnerConfiguration { HostId = host.HostId, AgentSid = identity.User!.Value, PipeName = "control-p2-live-" + Guid.NewGuid().ToString("N"), LocalResources = fixture.Resources, EnableProcessControl = true, EnableFileControl = true };
        using var runnerStore = new RunnerStore(Path.Combine(fixture.Root, "Runner"), runnerConfiguration);
        using var runner = new RunnerService(runnerConfiguration, new RunnerDispatcher(runnerStore), NullLogger<RunnerService>.Instance);
        await runner.StartAsync(timeout.Token);
        var configuration = new AgentConfiguration { HostId = host.HostId, ServerUrl = fixture.Origin.ToString(), RunnerPipeName = runnerConfiguration.PipeName, LocalResources = fixture.Resources, EnableFileControl = true };
        using var agentStore = new AgentStore(Path.Combine(fixture.Root, "Agent"), host.HostId);
        var runnerClient = new RunnerClient(configuration);
        var storage = new P2StorageService(Path.Combine(fixture.Root, "Storage"), fixture.Resources);
        var handler = new AgentOperationHandler(configuration, agentStore, runnerClient, storage: storage);
        var applier = new InventoryApplier(configuration, agentStore, runnerClient);
        var epoch = Guid.Empty;
        await using var hub = fixture.CreateHub(ControlProtocol.AgentHubPath, HttpTransportType.WebSockets, credential.Token);
        hub.On<DispatchOperationRequest, ExecutionSnapshot>("DispatchOperation", r => handler.DispatchAsync(r, epoch, timeout.Token));
        hub.On<PrepareHoldRequest, PreparedHold>("PrepareHold", r => handler.PrepareHoldAsync(r, epoch, timeout.Token));
        hub.On<ReleasePreparedHoldRequest, ReleasePreparedHoldResponse>("ReleaseHold", r => handler.ReleaseHoldAsync(r, epoch, timeout.Token));
        hub.On<AgentExecutionRequest, ExecutionSnapshot>("GetExecution", r => handler.QueryAsync(r, epoch, timeout.Token));
        hub.On<AgentSnapshotRequest, RunnerSnapshot>("GetSnapshot", r => handler.SnapshotAsync(r, epoch, timeout.Token));
        hub.On<AgentInspectRecoveryRequest, RecoveryInspection>("InspectRecovery", r => handler.InspectRecoveryAsync(r, epoch, timeout.Token));
        hub.On<AgentListBackupsRequest, BackupPage>("ListBackups", r => Task.FromResult(handler.ListBackups(r, epoch)));
        hub.On<AgentResumeDataOperationRequest, ExecutionSnapshot>("ResumeDataOperation", r => handler.ResumeDataAsync(r, epoch, timeout.Token));
        using var agent = fixture.CreateClient();
        agent.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential.Token);
        try
        {
            await hub.StartAsync(timeout.Token);
            var initial = await runnerClient.GetSnapshotAsync(timeout.Token);
            var hello = await Invoke<HelloAck>(hub, "Hello", new AgentHello { AgentVersion = "p2-fixture", AgentStoreId = agentStore.GetState().StoreId, RunnerEpoch = initial.RunnerEpoch, LocalResources = fixture.Resources, Capabilities = ["P0.Inventory", "P1.Operations", "P1.LegacyGate", "P2.Files", "P2.Backups"] }, timeout.Token);
            epoch = hello.ConnectionEpoch;
            await Invoke<ReconcileAck>(hub, "Reconcile", new ReconcileRequest { ConnectionEpoch = epoch, RunnerEpoch = initial.RunnerEpoch }, timeout.Token);
            agent.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", epoch.ToString());
            handler.SetDataSession(new AgentTransferClient(agent, host.HostId, Path.Combine(fixture.Root, "transfer-stage")), async (id, commandHash, resultJson, token) =>
            {
                using var request = new HttpRequestMessage(HttpMethod.Post, $"/api/agent/v1/operations/{id}/result");
                request.Headers.Add("X-Command-Sha256", commandHash); request.Headers.Add("X-Result-Sha256", ControlProtocol.Hash(resultJson));
                request.Content = new ByteArrayContent(Encoding.UTF8.GetBytes(resultJson)); request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                using var response = await agent.SendAsync(request, token); response.EnsureSuccessStatusCode();
            });
            var serverId = Guid.NewGuid();
            var root = fixture.Resources.Roots.Single(r => !r.BackupOnly);
            var live = Path.Combine(root.Path, "dev");
            var config = new ServerConfig { ServerId = serverId, HostId = host.HostId, Revision = 1, ManagedRootId = root.Id, ExecutableId = fixture.Resources.Executables.Single().Id, ServerDirectory = "dev", Kind = ServerKind.Java, ArgumentList = [typeof(TestProcess.Marker).Assembly.Location] };
            await fixture.OwnerApiAsync<InventoryServer>(owner, HttpMethod.Post, "/api/control/v1/servers", new { hostId = host.HostId, serverKey = "data-dev", displayName = "Data Dev", config }, create: true);
            await ApplyAndAck(agent, hub, epoch, applier, agentStore, runnerClient, timeout.Token);
            await Heartbeat(hub, epoch, await runnerClient.GetSnapshotAsync(timeout.Token), timeout.Token);
            foreach (var page in new[] { $"/Servers/{serverId}/Files", $"/Servers/{serverId}/Backups", "/Transfers" })
                using (var response = await owner.GetAsync(page, timeout.Token)) Assert.Equal(HttpStatusCode.OK, response.StatusCode);

            var target = new FileTarget(root.Id, "dev/settings.properties");
            await Run(OperationKind.WriteFile, new WriteFilePayload(target, "absent", "value=alpha\r\n"));
            var read = await Run(OperationKind.ReadFile, new ReadFilePayload(target));
            Assert.Equal("value=alpha\r\n", read.Result.GetProperty("contentUtf8").GetString());
            var largeText = new string('x', 2 * 1024 * 1024);
            await File.WriteAllTextAsync(Path.Combine(live, "large.txt"), largeText, new UTF8Encoding(false), timeout.Token);
            var large = await Run(OperationKind.ReadFile, new ReadFilePayload(new(root.Id, "dev/large.txt")));
            Assert.Equal(largeText, large.Result.GetProperty("contentUtf8").GetString());
            Assert.Equal(HttpStatusCode.Unauthorized, (await agent.GetAsync($"/api/control/v1/operations/{large.Id}/result", timeout.Token)).StatusCode);

            var importId = Guid.NewGuid(); var bytes = Encoding.UTF8.GetBytes("binary transfer fixture"); var hash = ControlProtocol.Hash("binary transfer fixture");
            var transfer = await fixture.OwnerApiAsync<TransferInfo>(owner, HttpMethod.Post, "/api/control/v1/transfers", new TransferCreateRequest { DestinationHostId = host.HostId, OperationId = importId, ServerId = serverId, SizeBytes = bytes.Length, Sha256 = hash }, create: true);
            using (var upload = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/transfers/{transfer.TransferId}/chunks/0"))
            {
                upload.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority)); upload.Headers.Add("X-CSRF-TOKEN", await GetCsrfAsync(owner)); upload.Headers.Add("X-Chunk-Sha256", hash);
                upload.Content = new ByteArrayContent(bytes); upload.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                using var response = await owner.SendAsync(upload, timeout.Token); Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            }
            await fixture.OwnerApiAsync<TransferInfo>(owner, HttpMethod.Post, $"/api/control/v1/transfers/{transfer.TransferId}/complete", new TransferCompleteRequest(bytes.Length, hash), expectedStatus: HttpStatusCode.OK);
            await Run(OperationKind.ImportArtifact, new ImportArtifactPayload(new(transfer.TransferId, hash, bytes.Length), new(root.Id, "dev/import.bin"), ArtifactImportMode.File, "absent"), importId);
            Assert.Equal(bytes, await File.ReadAllBytesAsync(Path.Combine(live, "import.bin"), timeout.Token));
            Assert.Equal(TransferStatus.Consumed, ControlJson.Deserialize<TransferInfo>(await owner.GetStringAsync($"/api/control/v1/transfers/{transfer.TransferId}", timeout.Token)).Status);
            var exportId = Guid.NewGuid();
            var export = await fixture.OwnerApiAsync<TransferInfo>(owner, HttpMethod.Post, "/api/control/v1/transfers", new TransferCreateRequest { SourceHostId = host.HostId, OperationId = exportId, ServerId = serverId }, create: true);
            var exportState = Assert.Single((await runnerClient.GetSnapshotAsync(timeout.Token)).Servers);
            await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/servers/{serverId}/operations", new ServerOperationRequest { Kind = OperationKind.ExportFiles, Payload = ControlJson.Element(new ExportFilesPayload(new(root.Id, "dev/import.bin"), TransferId: export.TransferId)), ConfigRevision = 1, ExpectedStateRevision = exportState.StateRevision }, create: true, expectedStatus: HttpStatusCode.Conflict);
            Assert.Empty((await runnerClient.GetSnapshotAsync(timeout.Token)).Holds);
            await Run(OperationKind.ExportFiles, new ExportFilesPayload(new(root.Id, "dev/import.bin"), TransferId: export.TransferId), exportId);
            var exported = ControlJson.Deserialize<TransferInfo>(await owner.GetStringAsync($"/api/control/v1/transfers/{export.TransferId}", timeout.Token));
            Assert.Equal(hash, exported.Sha256); Assert.Equal(TransferStatus.Verified, exported.Status);

            var profile = new BackupProfile { BackupProfileId = Guid.NewGuid(), ServerId = serverId, Revision = 1, DestinationRootId = fixture.Resources.Roots.Single(r => r.BackupOnly).Id, Format = BackupFormat.Zip };
            await fixture.OwnerApiAsync<BackupProfile>(owner, HttpMethod.Post, "/api/control/v1/backup-profiles", profile, create: true);
            await ApplyAndAck(agent, hub, epoch, applier, agentStore, runnerClient, timeout.Token);
            await Run(OperationKind.Start, new { });
            var backupId = Guid.NewGuid(); await Run(OperationKind.CreateBackup, new CreateBackupPayload(backupId, profile.BackupProfileId, 1));
            Assert.Equal(ProcessState.Stopped, Assert.Single((await runnerClient.GetSnapshotAsync(timeout.Token)).Servers).ProcessState);
            await File.WriteAllTextAsync(Path.Combine(live, "settings.properties"), "value=beta\r\n", timeout.Token);
            storage.JournalBoundary = journal => { if (journal.Phase == "OriginalMoved") throw new IOException("fixture restore interruption"); };
            var interrupted = await Run(OperationKind.RestoreBackup, new RestoreBackupPayload(backupId, RestoreMode.Replace), expectAttention: true);
            Assert.Equal(OperationStatus.NeedsAttention, agentStore.GetOperation(interrupted.Id)!.Status);
            var blocked = await runnerClient.InspectRecoveryAsync(serverId, timeout.Token); Assert.True(blocked.RecoveryRequired);
            storage.JournalBoundary = null;
            using var workflow = JsonDocument.Parse(await owner.GetStringAsync($"/api/control/v1/operations/{interrupted.Id}", timeout.Token));
            var workflowId = workflow.RootElement.GetProperty("workflowId").GetGuid();
            await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/workflows/{workflowId}/recover", new WorkflowRecoveryRequest(WorkflowRecoveryAction.Resume, "fixture verified same restore journal"), create: true, expectedStatus: HttpStatusCode.Accepted);
            await Await(interrupted.Id);
            Assert.Equal("value=alpha\r\n", await File.ReadAllTextAsync(Path.Combine(live, "settings.properties"), timeout.Token));
            Assert.Contains(Directory.EnumerateDirectories(root.Path, ".control-prerestore-*"), p => File.ReadAllText(Path.Combine(p, "settings.properties")) == "value=beta\r\n");
            Assert.False((await runnerClient.InspectRecoveryAsync(serverId, timeout.Token)).RecoveryRequired);

            async Task<(Guid Id, JsonElement Result)> Run(OperationKind kind, object payload, Guid? id = null, bool expectAttention = false)
            {
                var snapshot = await runnerClient.GetSnapshotAsync(timeout.Token); await Heartbeat(hub, epoch, snapshot, timeout.Token);
                var state = Assert.Single(snapshot.Servers); var key = id ?? Guid.NewGuid();
                var request = new ServerOperationRequest { Kind = kind, Payload = ControlJson.Element(payload), ConfigRevision = 1, ExpectedStateRevision = state.StateRevision, ExpectedServerSessionId = state.CurrentSessionId };
                var accepted = await fixture.OwnerApiAsync<JsonElement>(owner, HttpMethod.Post, $"/api/control/v1/servers/{serverId}/operations", request, create: true, expectedStatus: HttpStatusCode.Accepted, requestId: key);
                var operationId = accepted.GetProperty("operationId").GetGuid();
                var current = await Await(operationId, expectAttention);
                if (expectAttention) return (operationId, current);
                using var result = JsonDocument.Parse(await owner.GetStringAsync($"/api/control/v1/operations/{operationId}/result", timeout.Token));
                var workflowId = current.GetProperty("workflowId").GetGuid();
                using var detail = JsonDocument.Parse(await owner.GetStringAsync($"/api/control/v1/workflows/{workflowId}", timeout.Token));
                var lease = JsonSerializer.Deserialize<GateLease>(detail.RootElement.GetProperty("lease").GetRawText(), ControlJson.Options)!;
                if (kind is OperationKind.CreateBackup or OperationKind.RestoreBackup)
                    Assert.Contains($"directory:{host.HostId:D}:{fixture.Resources.Roots.Single(r => r.BackupOnly).Id:D}:control-backups/{serverId:N}", lease.ResourceKeys);
                await fixture.OwnerApiAsync<object>(owner, HttpMethod.Post, $"/api/control/v1/reservations/{lease.ReservationId}/release", new GateReleaseRequest(lease.WorkflowId, lease.Fences, GateOutcome.Succeeded, ""), expectedStatus: HttpStatusCode.NoContent);
                return (operationId, result.RootElement.Clone());
            }
            async Task<JsonElement> Await(Guid operationId, bool expectAttention = false)
            {
                while (true)
                {
                    using var current = JsonDocument.Parse(await owner.GetStringAsync($"/api/control/v1/operations/{operationId}", timeout.Token));
                    var state = Enum.Parse<OperationStatus>(current.RootElement.GetProperty("status").GetString()!);
                    if (OperationValidation.IsTerminal(state) || state == OperationStatus.NeedsAttention) { Assert.Equal(expectAttention ? OperationStatus.NeedsAttention : OperationStatus.Succeeded, state); return current.RootElement.Clone(); }
                    await Task.Delay(50, timeout.Token);
                }
            }
        }
        finally
        {
            foreach (var server in runnerStore.GetSnapshot().Servers.Where(s => s.CurrentSessionId != null))
            {
                var inspected = runnerStore.InspectRecovery(new(server.ServerId));
                if (inspected.Session?.ProcessId is not int pid) continue;
                try { using var process = System.Diagnostics.Process.GetProcessById(pid); if (process.StartTime.ToUniversalTime() == inspected.Session.ProcessCreatedAtUtc?.UtcDateTime) { process.Kill(true); await process.WaitForExitAsync(); } } catch (ArgumentException) { }
            }
            await runner.StopAsync(CancellationToken.None);
        }
    }
}
