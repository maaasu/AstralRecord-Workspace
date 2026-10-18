using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using AstralRecordControl.Agent.Transfers;
using System.Threading.Channels;
using AstralRecordControl.Contracts;
using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.SignalR.Client;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace AstralRecordControl.Agent;

public static class ReconnectBackoff
{
    private static readonly int[] Seconds = [1, 2, 5, 10, 30, 60];
    public static TimeSpan Delay(int failureCount, double jitter) => TimeSpan.FromSeconds(Seconds[Math.Clamp(failureCount, 0, Seconds.Length - 1)] * (1 + Math.Clamp(jitter, 0, 1) * .2));
}

public sealed class AgentConnectionService(AgentConfiguration configuration, AgentStore store,
    InventoryApplier applier, AgentOperationHandler operations, AgentProcessControlGate processGate, ProtectedTokenFile tokenFile, ILogger<AgentConnectionService> logger,
    AgentScheduleService schedules, AgentMetricsSampler metrics, TimeProvider clock) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var failures = 0;
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await RunSessionAsync(() => failures = 0, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (AgentFault fault) when (fault.Code == ErrorCode.RecoveryRequired)
            {
                logger.LogCritical("Agent recovery is required. Automatic connection has stopped ({ErrorCode}).", fault.Code);
                await Task.Delay(Timeout.InfiniteTimeSpan, stoppingToken);
            }
            catch (Exception exception) when (exception is IOException or HttpRequestException or HubException or OperationCanceledException or AgentFault or JsonException or ArgumentException or InvalidOperationException)
            {
                // Never log remote exception text, Authorization headers, token values or snapshot arguments.
                logger.LogWarning("Agent connection interrupted; retrying ({ErrorCode}).", exception is AgentFault fault ? fault.Code : ErrorCode.HostOffline);
            }
            finally { operations.SetDataSession(null, null); }
            await Task.Delay(ReconnectBackoff.Delay(failures++, Random.Shared.NextDouble()), stoppingToken);
        }
    }

    private async Task RunSessionAsync(Action reconciled, CancellationToken stoppingToken)
    {
        var origin = configuration.Validate();
        var epoch = Guid.Empty;
        var changes = Channel.CreateBounded<InventoryChanged>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
        var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var token = tokenFile.Read();
        await using var hub = new HubConnectionBuilder()
            .WithUrl(new Uri(origin, ControlProtocol.AgentHubPath), options =>
            {
                options.AccessTokenProvider = () => Task.FromResult<string?>(token);
                options.Headers["Origin"] = origin.GetLeftPart(UriPartial.Authority);
                options.HttpMessageHandlerFactory = handler =>
                {
                    if (handler is HttpClientHandler clientHandler) clientHandler.AllowAutoRedirect = false;
                    return handler;
                };
                options.ApplicationMaxBufferSize = ControlProtocol.MaxMessageBytes;
                options.TransportMaxBufferSize = ControlProtocol.MaxMessageBytes;
            })
            .AddJsonProtocol(options => options.PayloadSerializerOptions = ControlJson.CreateOptions())
            .Build();
        hub.Closed += _ => { closed.TrySetResult(); return Task.CompletedTask; };
        hub.On<DispatchOperationRequest, ExecutionSnapshot>("DispatchOperation", async request =>
        {
            try { return await operations.DispatchAsync(request, epoch, stoppingToken); }
            catch (AgentFault fault) { throw new HubException(fault.Code.ToString()); }
        });
        hub.On<PrepareHoldRequest, PreparedHold>("PrepareHold", request => operations.PrepareHoldAsync(request, epoch, stoppingToken));
        hub.On<ReleasePreparedHoldRequest, ReleasePreparedHoldResponse>("ReleaseHold", request => operations.ReleaseHoldAsync(request, epoch, stoppingToken));
        hub.On<AgentExecutionRequest, ExecutionSnapshot>("GetExecution", async request =>
        {
            try { return await operations.QueryAsync(request, epoch, stoppingToken); }
            catch (AgentFault fault) { throw new HubException(fault.Code.ToString()); }
        });
        hub.On<AgentReadLogsRequest, LogBatch>("ReadLogs", request => operations.ReadLogsAsync(request, epoch, stoppingToken));
        hub.On<AgentInspectRecoveryRequest, RecoveryInspection>("InspectRecovery", request => operations.InspectRecoveryAsync(request, epoch, stoppingToken));
        hub.On<AgentListBackupsRequest, BackupPage>("ListBackups", request => operations.ListBackups(request, epoch));
        hub.On<AgentResumeDataOperationRequest, ExecutionSnapshot>("ResumeDataOperation", request => operations.ResumeDataAsync(request, epoch, stoppingToken));
        hub.On<AgentSnapshotRequest, RunnerSnapshot>("GetSnapshot", request => operations.SnapshotAsync(request, epoch, stoppingToken));
        hub.On<RunScheduleNowRequest, RunScheduleNowResponse>("RunScheduleNow", async request =>
        {
            try { return new(await schedules.RunNowAsync(request, epoch, stoppingToken), null); }
            catch (AgentFault fault) { return new(null, fault.Code); }
        });
        hub.On<JsonElement>("InventoryChanged", message =>
        {
            var change = ControlJson.Deserialize<InventoryChanged>(message.GetRawText());
            if (epoch != Guid.Empty && change.ConnectionEpoch == epoch) changes.Writer.TryWrite(change);
        });
        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
        using var http = new HttpClient(handler) { BaseAddress = origin, Timeout = TimeSpan.FromSeconds(30) };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        await schedules.RefreshHeldReportsAsync(stoppingToken);
        var snapshot = await operations.GetSnapshotAsync(stoppingToken);
        await hub.StartAsync(stoppingToken);
        var state = store.GetState();
        var hello = await InvokeAsync<HelloAck>(hub, "Hello", new AgentHello
        {
            AgentVersion = typeof(AgentConnectionService).Assembly.GetName().Version?.ToString() ?? "1.0.0",
            AgentStoreId = state.StoreId, MaxLocalEventSeq = state.MaxEventSequence,
            RunnerEpoch = snapshot.RunnerEpoch, AppliedInventoryRevision = state.AppliedInventoryRevision,
            LocalResources = configuration.LocalResources,
            Capabilities = (processGate.Enabled
                ? configuration.EnableFileControl
                    ? ["P0.Inventory", "P1.Operations", "P1.LegacyGate", "P2.Files", "P2.Backups"]
                    : ["P0.Inventory", "P1.Operations", "P1.LegacyGate"]
                : new[] { "P0.Inventory" }).Concat(processGate.Enabled && configuration.EnableSchedules ? ["P3.Schedules"] : Array.Empty<string>()).Append("P3.Metrics").ToArray()
        }, stoppingToken);
        if (hello.ProtocolVersion != ControlProtocol.Version || hello.ConnectionEpoch == Guid.Empty || hello.HeartbeatSeconds is < 1 or > 60) throw new AgentFault(ErrorCode.ProtocolMismatch);
        store.Acknowledge(hello.ControllerAckEventSeq);
        epoch = hello.ConnectionEpoch;
        http.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", epoch.ToString());
        operations.SetDataSession(new AgentTransferClient(http, configuration.HostId, Path.Combine(store.DataRoot, "transfers")),
            (operationId, commandHash, resultJson, cancellationToken) => PublishResultAsync(http, operationId, commandHash, resultJson, cancellationToken));
        if (snapshot.AppliedInventoryRevision < state.AppliedInventoryRevision) throw new AgentFault(ErrorCode.RecoveryRequired);
        var reconciliation = await InvokeAsync<ReconcileAck>(hub, "Reconcile", new ReconcileRequest
        {
            ConnectionEpoch = epoch, RunnerEpoch = snapshot.RunnerEpoch, AppliedInventoryRevision = state.AppliedInventoryRevision,
            Servers = snapshot.Servers, Holds = snapshot.Holds,
            Operations = store.ReadNonTerminalOperations().Where(x => !store.OwnsScheduledOperation(x.OperationId)).Select(x => ControlJson.Element(new { operationId = x.OperationId, commandSha256 = x.CommandSha256, status = x.Status })).ToArray(),
            ScheduleOccurrences = store.ReadScheduleReports(nonterminalOnly: true).Select(x => x.Report).ToArray()
        }, stoppingToken);
        store.Acknowledge(reconciliation.AckEventSeq);
        await operations.RefreshOutstandingAsync(stoppingToken);
        // Always fetch after reconciliation: a previously applied ACK or InventoryChanged may have been lost.
        await FetchAndApplyAsync(http, hub, epoch, stoppingToken);
        reconciled();
        var ticks = 0;
        while (!stoppingToken.IsCancellationRequested && !closed.Task.IsCompleted)
        {
            var changed = false;
            while (changes.Reader.TryRead(out var change))
                if (change.ConnectionEpoch == epoch) changed = true;
            if (changed || ++ticks % 6 == 0) await FetchAndApplyAsync(http, hub, epoch, stoppingToken);
            var events = store.ReadEvents();
            if (events.Length > 0)
            {
                var ack = await InvokeAsync<EventAck>(hub, "PublishEvents", new EventBatch(epoch, events), stoppingToken);
                store.Acknowledge(ack.AckEventSeq);
            }
            var runnerAvailable = true;
            try { snapshot = await operations.GetSnapshotAsync(stoppingToken); }
            catch (Exception exception) when (exception is IOException or OperationCanceledException) { runnerAvailable = false; }
            var samples = await metrics.SampleAsync(snapshot, runnerAvailable, stoppingToken);
            await hub.InvokeAsync("Heartbeat", new AgentHeartbeat
            {
                ConnectionEpoch = epoch, RunnerEpoch = snapshot.RunnerEpoch, ObservedAtUtc = clock.GetUtcNow(),
                RunnerAvailable = runnerAvailable, ServerSnapshots = runnerAvailable ? snapshot.Servers : [], Metrics = samples
            }, stoppingToken);
            using var wait = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
            var timer = Task.Delay(TimeSpan.FromSeconds(hello.HeartbeatSeconds), wait.Token);
            var notification = changes.Reader.WaitToReadAsync(wait.Token).AsTask();
            await Task.WhenAny(timer, notification, closed.Task);
            await wait.CancelAsync();
        }
    }

    private async Task FetchAndApplyAsync(HttpClient http, HubConnection hub, Guid epoch, CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync("/api/agent/v1/inventory", HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength > ControlProtocol.MaxMessageBytes) throw new AgentFault(ErrorCode.ProtocolMismatch);
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        int bytes;
        while ((bytes = await input.ReadAsync(buffer, cancellationToken)) != 0)
        {
            if (output.Length + bytes > ControlProtocol.MaxMessageBytes) throw new AgentFault(ErrorCode.ProtocolMismatch);
            output.Write(buffer, 0, bytes);
        }
        var inventory = ControlJson.Deserialize<InventoryEnvelope>(new UTF8Encoding(false, true).GetString(output.ToArray()));
        try
        {
            await applier.ApplyAsync(inventory, cancellationToken);
            await hub.InvokeAsync("InventoryApplied", new InventoryApplied(epoch, inventory.Revision, inventory.Sha256, true), cancellationToken);
            store.MarkScheduleInventoryAcknowledged(inventory.Revision);
        }
        catch (Exception exception) when (exception is AgentFault or ArgumentException or JsonException)
        {
            var error = exception is AgentFault fault ? fault.Code : ErrorCode.ProtocolMismatch;
            await hub.InvokeAsync("InventoryApplied", new InventoryApplied(epoch, inventory.Revision, inventory.Sha256, false, error.ToString()), cancellationToken);
            if (error == ErrorCode.RecoveryRequired) throw;
            logger.LogWarning("Inventory was rejected; the previous applied revision remains active ({ErrorCode}).", error);
        }
    }

    private static async Task<T> InvokeAsync<T>(HubConnection hub, string method, object request, CancellationToken cancellationToken)
    {
        var response = await hub.InvokeAsync<JsonElement>(method, request, cancellationToken);
        return ControlJson.Deserialize<T>(response.GetRawText());
    }

    private static async Task PublishResultAsync(HttpClient http, Guid operationId, string commandHash, string resultJson, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(resultJson);
        if (bytes.Length > 16 * 1024 * 1024) throw new AgentFault(ErrorCode.ProtocolMismatch);
        using var request = new HttpRequestMessage(HttpMethod.Post, $"/api/agent/v1/operations/{operationId:D}/result");
        request.Headers.TryAddWithoutValidation("X-Command-Sha256", commandHash);
        request.Headers.TryAddWithoutValidation("X-Result-Sha256", ControlProtocol.Hash(resultJson));
        request.Content = new ByteArrayContent(bytes);
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (!response.IsSuccessStatusCode) throw new AgentFault(ErrorCode.HostOffline);
    }
}
