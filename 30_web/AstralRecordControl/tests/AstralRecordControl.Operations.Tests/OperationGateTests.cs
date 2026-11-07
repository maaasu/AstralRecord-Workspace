using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using AstralRecordControl.Web.Pages.Servers;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class OperationGateTests
{
    [Theory]
    [InlineData(ServerKind.Java, true)]
    [InlineData(ServerKind.Velocity, false)]
    public async Task GeyserUpdateRejectsUnsupportedServerOrAgentBeforeReserving(ServerKind kind, bool updateCapability)
    {
        await using var fixture = await Fixture.CreateAsync(enableFiles: true);
        var server = await fixture.ServerAsync("proxy");
        server.DesiredConfigJson = ControlJson.Serialize(ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { Kind = kind });
        var host = await fixture.Db.Hosts.SingleAsync(x => x.HostId == server.HostId);
        string[] capabilities = ["P1.Operations", "P1.LegacyGate", "P1.Velocity", "P2.Files"];
        host.CapabilitiesJson = ControlJson.Serialize(updateCapability ? [.. capabilities, "P3.GeyserUpdate"] : capabilities);
        await fixture.Db.SaveChangesAsync();
        await Fault("UnsupportedCapability", () => fixture.Operations.CreateAsync(server.ServerId,
            new() { Kind = OperationKind.UpdateGeyser, ConfigRevision = 1, ExpectedStateRevision = 1, Payload = ControlJson.Element(new UpdateGeyserPayload()) }, Guid.NewGuid()));
        Assert.Equal(0, fixture.Agent.PrepareCount);
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
    }

    [Fact]
    public async Task CombinedUpdateRejectsOldCapabilityBeforeReservation()
    {
        await using var fixture = await Fixture.CreateAsync(enableFiles: true);
        var server = await fixture.ServerAsync("proxy");
        server.DesiredConfigJson = ControlJson.Serialize(ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { Kind = ServerKind.Velocity });
        var host = await fixture.Db.Hosts.SingleAsync(x => x.HostId == server.HostId);
        host.CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate", "P1.Velocity", "P2.Files", "P3.GeyserUpdate" });
        await fixture.Db.SaveChangesAsync();
        await Fault("UnsupportedCapability", () => fixture.Operations.CreateAsync(server.ServerId,
            new() { Kind = OperationKind.UpdateGeyser, ConfigRevision = 1, ExpectedStateRevision = 1, Payload = ControlJson.Element(new UpdateGeyserPayload(UpdateVelocity: true)) }, Guid.NewGuid()));
        Assert.Equal(0, fixture.Agent.PrepareCount);
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task GeyserUpdateUsesDataOperationGateWhileStoppedOrRunning(bool running)
    {
        await using var fixture = await Fixture.CreateAsync(enableFiles: true);
        var server = await fixture.ServerAsync("proxy");
        server.DesiredConfigJson = ControlJson.Serialize(ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { Kind = ServerKind.Velocity });
        var host = await fixture.Db.Hosts.SingleAsync(x => x.HostId == server.HostId);
        host.CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate", "P1.Velocity", "P2.Files", "P3.GeyserUpdate" });
        var session = running ? Guid.NewGuid() : (Guid?)null;
        fixture.Agent.States[server.HostId] = fixture.Agent.States[server.HostId] with { ProcessState = running ? ProcessState.Running : ProcessState.Stopped, CurrentSessionId = session };
        await fixture.Db.SaveChangesAsync();
        var key = Guid.NewGuid();
        var operation = await fixture.Operations.CreateAsync(server.ServerId,
            new() { Kind = OperationKind.UpdateGeyser, ConfigRevision = 1, ExpectedStateRevision = 1, ExpectedServerSessionId = session, Payload = ControlJson.Element(new UpdateGeyserPayload()) }, key);
        Assert.Equal(key, operation.OperationId);
        Assert.Equal(OperationStatus.Accepted, operation.Status);
        Assert.Equal(1, fixture.Agent.PrepareCount);
        Assert.Equal(1, fixture.Agent.DispatchCount);
    }

    [Theory]
    [InlineData(OperationKind.Stop, ServerKind.Java)]
    [InlineData(OperationKind.Restart, ServerKind.Java)]
    [InlineData(OperationKind.ConsoleCommand, ServerKind.Java)]
    [InlineData(OperationKind.Kill, ServerKind.Java)]
    [InlineData(OperationKind.Stop, ServerKind.Velocity)]
    [InlineData(OperationKind.Restart, ServerKind.Velocity)]
    [InlineData(OperationKind.ConsoleCommand, ServerKind.Velocity)]
    [InlineData(OperationKind.Kill, ServerKind.Velocity)]
    public async Task ConsolePageDispatchesTheSelectedRunningOperation(OperationKind kind, ServerKind serverKind)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("console");
        var state = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        fixture.Agent.States[server.HostId] = state;
        var host = await fixture.Db.Hosts.SingleAsync(x => x.HostId == server.HostId);
        server.DesiredConfigJson = ControlJson.Serialize(ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { Kind = serverKind });
        host.CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate", ServerProcessSupport.VelocityCapability });
        host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { state });
        await fixture.Db.SaveChangesAsync();
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:EnableProcessControl"] = "true" }).Build();
        var page = new OperationsModel(fixture.Db, fixture.Operations, fixture.Reservations, config, TimeProvider.System)
        { PageContext = new PageContext { HttpContext = new DefaultHttpContext() } };

        var result = await page.OnPostOperateAsync(server.ServerId, Guid.NewGuid(), kind, "say fixture", "fixture force stop", null, 1, state.StateRevision, state.CurrentSessionId, confirmKill: true);

        Assert.IsType<RedirectToPageResult>(result);
        var operation = await fixture.Db.Operations.SingleAsync();
        Assert.Equal(kind, operation.Kind);
        Assert.Equal(kind, ControlJson.Deserialize<OperationCommand>(operation.CommandJson).Kind);
        Assert.Equal(1, fixture.Agent.DispatchCount);
    }

    [Fact]
    public async Task VelocityStartRequiresUpdatedAgentCapabilityBeforeTakingAnyHold()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("proxy");
        server.DesiredConfigJson = ControlJson.Serialize(ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { Kind = ServerKind.Velocity });
        await fixture.Db.SaveChangesAsync();
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        await Fault("UnsupportedCapability", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));
        Assert.Equal(0, fixture.Agent.PrepareCount);
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
        var host = await fixture.Db.Hosts.SingleAsync(x => x.HostId == server.HostId);
        host.CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate", ServerProcessSupport.VelocityCapability });
        await fixture.Db.SaveChangesAsync();
        Assert.Equal(OperationStatus.Accepted, (await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid())).Status);
        Assert.Equal(1, fixture.Agent.DispatchCount);
    }

    [Theory]
    [InlineData(null)]
    [InlineData((OperationKind)999)]
    public async Task ConsolePageNeverTreatsMissingOrUndefinedKindAsStart(OperationKind? kind)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("console");
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:EnableProcessControl"] = "true" }).Build();
        var page = new OperationsModel(fixture.Db, fixture.Operations, fixture.Reservations, config, TimeProvider.System)
        { PageContext = new PageContext { HttpContext = new DefaultHttpContext() } };

        var result = await page.OnPostOperateAsync(server.ServerId, Guid.NewGuid(), kind, null, null, null, 1, 1, null);

        Assert.IsType<PageResult>(result);
        Assert.Contains("操作種別", page.Error);
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
        Assert.Equal(0, fixture.Agent.PrepareCount);
        Assert.Equal(0, fixture.Agent.DispatchCount);
    }

    [Fact]
    public async Task ReservationTakesAllResourcesAndReusesTheSameLease()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Stopped)] };
        var first = await fixture.Reservations.AcquireAsync(request);
        Assert.Equal(ReservationStatus.Prepared, first.Status);
        Assert.Contains(first.ResourceKeys, x => x.StartsWith("directory:", StringComparison.Ordinal));
        Assert.Contains(first.ResourceKeys, x => x.StartsWith("environment:", StringComparison.Ordinal));
        Assert.Single(first.Fences);
        var replay = await fixture.Reservations.AcquireAsync(request);
        Assert.Equal(first.ReservationId, replay.ReservationId);
        Assert.Equal(1, fixture.Agent.PrepareCount);
        var competitor = request with { WorkflowId = Guid.NewGuid() };
        await Fault("ReservationConflict", () => fixture.Reservations.AcquireAsync(competitor));
    }

    [Fact]
    public async Task KnownPartialPrepareReleasesHoldsAndCentralReservation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.ServerAsync("one");
        var second = await fixture.ServerAsync("two");
        fixture.Agent.ChangeStateHost = new[] { first.HostId, second.HostId }.Max();
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(first.ServerId), ReservationService.ServerKey(second.ServerId)], ServerConditions = [new(first.ServerId, ProcessRequirement.Stopped), new(second.ServerId, ProcessRequirement.Stopped)] };
        await Fault("StateConflict", () => fixture.Reservations.AcquireAsync(request));
        var lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.Released, lease.Status);
        Assert.Empty(fixture.Agent.Holds);
        Assert.All(await fixture.Db.Reservations.ToArrayAsync(), x => Assert.Equal(ReservationStatus.Released, x.State));
        Assert.Equal(2, lease.Fences.Length);
    }

    [Fact]
    public async Task UnknownPartialPreparePersistsFenceAndCanResumeAfterInspection()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.ServerAsync("one");
        var second = await fixture.ServerAsync("two");
        fixture.Agent.FailHost = new[] { first.HostId, second.HostId }.Max();
        fixture.Agent.SnapshotUnavailableAfterFailure = true;
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "16", RunReference = "run-1", ResourceKeys = [ReservationService.ServerKey(first.ServerId), ReservationService.ServerKey(second.ServerId)] };
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.AcquireAsync(request));
        var lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.RecoveryRequired, lease.Status);
        Assert.Single(lease.Fences);
        fixture.Agent.SnapshotUnavailableAfterFailure = false;
        fixture.Agent.FailHost = null;
        await fixture.Reservations.RecoverMaintenancePreparationAsync(request.WorkflowId, WorkflowRecoveryAction.Resume);
        lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.Prepared, lease.Status);
        Assert.Equal(2, lease.Fences.Length);
    }

    [Fact]
    public async Task InspectedAbortTombstonesHostWithoutObservedHoldBeforeCentralRelease()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.ServerAsync("one");
        var second = await fixture.ServerAsync("two");
        var delayedHost = new[] { first.HostId, second.HostId }.Max();
        fixture.Agent.FailHost = delayedHost;
        fixture.Agent.SnapshotUnavailableAfterFailure = true;
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "16", RunReference = "run-delayed", ResourceKeys = [ReservationService.ServerKey(first.ServerId), ReservationService.ServerKey(second.ServerId)] };
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.AcquireAsync(request));
        fixture.Agent.FailHost = null;
        fixture.Agent.SnapshotUnavailableAfterFailure = false;
        fixture.Agent.FailReleaseHost = delayedHost;
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.RecoverMaintenancePreparationAsync(request.WorkflowId, WorkflowRecoveryAction.AbortAfterInspection));
        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(request.WorkflowId)).Status);
        fixture.Agent.FailReleaseHost = null;
        await fixture.Reservations.RecoverMaintenancePreparationAsync(request.WorkflowId, WorkflowRecoveryAction.AbortAfterInspection);
        var releasedLease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.Released, releasedLease.Status);
        Assert.Empty(fixture.Agent.Holds);
        Assert.Equal(2, fixture.Agent.ReleasedHolds.Count);
        var oldKeys = releasedLease.ResourceKeys.Where(x => x == ReservationService.ServerKey(delayedHost == first.HostId ? first.ServerId : second.ServerId) || x.StartsWith($"directory:{delayedHost:D}:", StringComparison.Ordinal)).ToArray();
        await Fault("ReservationConflict", () => fixture.Agent.PrepareAsync(delayedHost, new AcquireHoldRequest(releasedLease.ReservationId, request.WorkflowId, oldKeys, HoldMode.Maintenance), CancellationToken.None));
    }

    [Fact]
    public async Task ReleasedLeaseRemainsReadableAfterResourceIsReused()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)] };
        var first = await fixture.Reservations.AcquireAsync(request);
        await fixture.Reservations.ReleaseAsync(first.ReservationId, new GateReleaseRequest(first.WorkflowId, first.Fences, GateOutcome.Succeeded, ""));
        var second = await fixture.Reservations.AcquireAsync(request with { WorkflowId = Guid.NewGuid() });
        Assert.NotEqual(first.ReservationId, second.ReservationId);
        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(first.WorkflowId)).Status);
        Assert.Equal(first.ReservationId, (await fixture.Reservations.GetByReservationAsync(first.ReservationId)).ReservationId);
        await Fault("ReservationConflict", () => fixture.Reservations.AcquireAsync(request));
    }

    [Fact]
    public async Task CommandUsesPostHoldRevisionAndDuplicateRequestDoesNotDispatchAgain()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        var key = Guid.NewGuid();
        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, key);
        Assert.Equal(OperationStatus.Accepted, operation.Status);
        var command = ControlJson.Deserialize<OperationCommand>(operation.CommandJson);
        Assert.Equal(2, command.ExpectedStateRevision);
        Assert.Equal(operation.CommandSha256, ControlProtocol.Hash(operation.CommandJson));
        Assert.Equal(1, fixture.Agent.DispatchCount);
        Assert.Equal(operation.OperationId, (await fixture.Operations.CreateAsync(server.ServerId, request, key)).OperationId);
        Assert.Equal(1, fixture.Agent.DispatchCount);
        await Fault("OperationConflict", () => fixture.Operations.CreateAsync(server.ServerId, request with { Kind = OperationKind.Stop }, key));
    }

    [Fact]
    public async Task QueuedWebOperationRedispatchesTheSameCommandAfterTransportReturns()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        fixture.Agent.DispatchUnavailable = true;
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        var key = Guid.NewGuid();
        var queued = await fixture.Operations.CreateAsync(server.ServerId, request, key);
        Assert.Equal(OperationStatus.Queued, queued.Status);
        fixture.Agent.DispatchUnavailable = false;
        var accepted = await fixture.Operations.RefreshAsync(queued.OperationId);
        Assert.Equal(OperationStatus.Accepted, accepted.Status);
        Assert.Equal(queued.OperationId, accepted.OperationId);
        Assert.Equal(queued.CommandSha256, accepted.CommandSha256);
    }

    [Fact]
    public async Task DefinitiveDispatchConflictIsShownAsNeedsAttention()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        fixture.Agent.DispatchFaultCode = "OperationConflict";
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid());
        Assert.Equal(OperationStatus.NeedsAttention, operation.Status);
        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(operation.WorkflowId)).Status);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task WebTransportConflictCannotObscureAuthoritativeTerminalResult(bool authoritativeResultArrivesFirst)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("transport-conflict");
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid());
        var succeeded = new ExecutionSnapshot { OperationId = operation.OperationId, CommandSha256 = operation.CommandSha256,
            Status = OperationStatus.Succeeded, StatusVersion = operation.StatusVersion + 1, UpdatedAtUtc = DateTimeOffset.UtcNow };
        fixture.Agent.QueryFaultCode = "OperationConflict";
        if (authoritativeResultArrivesFirst)
            fixture.Agent.BeforeQueryResponse = () => fixture.Operations.ApplyExecutionAsync(operation.OperationId, succeeded);

        var result = await fixture.Operations.RefreshAsync(operation.OperationId);
        if (!authoritativeResultArrivesFirst)
        {
            Assert.Equal(OperationStatus.NeedsAttention, result.Status);
            Assert.Equal(succeeded.StatusVersion - 1, result.StatusVersion);
            result = await fixture.Operations.RefreshAsync(operation.OperationId);
            Assert.Equal(succeeded.StatusVersion - 1, result.StatusVersion);
            fixture.Agent.QueryFaultCode = null;
            fixture.Agent.QueryResult = succeeded;
            result = await fixture.Operations.RefreshAsync(operation.OperationId);
        }

        Assert.Equal(OperationStatus.Succeeded, result.Status);
        Assert.Equal(succeeded.StatusVersion, result.StatusVersion);
    }

    [Fact]
    public async Task MaintenanceStartRequiresPersistedStartupWaitAndNeverAcceptsAdapter17()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 2 };
        var maintenance = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "01", RunReference = "run-1", ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Stopped)] };
        var lease = await fixture.Reservations.AcquireAsync(maintenance);
        var inherited = request with { WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, inherited, Guid.NewGuid()));
        await fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "WaitForStartup", "run-1"));
        var state = fixture.Agent.States[server.HostId];
        inherited = inherited with { ExpectedStateRevision = state.StateRevision };
        var started = await fixture.Operations.CreateAsync(server.ServerId, inherited, Guid.NewGuid());
        Assert.Equal(OperationStatus.Accepted, started.Status);
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, inherited with { Kind = OperationKind.Stop }, Guid.NewGuid()));
    }

    [Fact]
    public async Task StopWithPendingSettingsUsesAppliedRevisionAndReservesBothDirectories()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var other = await fixture.ServerAsync("other");
        var extraEnvironments = Enumerable.Range(1, 128).Select(index => new InventoryEnvironment
        { EnvironmentId = Guid.NewGuid(), EnvironmentKey = $"extra-{index}", DisplayName = $"Extra {index}" }).ToArray();
        fixture.Db.Environments.AddRange(extraEnvironments);
        var applied = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { StopTimeoutSeconds = 210 };
        await fixture.SaveAppliedSettingsAsync(server, applied);
        var desired = applied with { Revision = 2, ServerDirectory = "servers/renamed", StopTimeoutSeconds = 310 };
        var state = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        fixture.Agent.States[server.HostId] = state;
        server.DesiredConfigJson = ControlJson.Serialize(desired);
        server.DesiredRevision = desired.Revision;
        await fixture.Db.SaveChangesAsync();

        var stop = new ServerOperationRequest { Kind = OperationKind.Stop, ConfigRevision = applied.Revision, ExpectedStateRevision = state.StateRevision, ExpectedServerSessionId = state.CurrentSessionId };
        await Fault("StaleRevision", () => fixture.Operations.CreateAsync(server.ServerId, stop with { ConfigRevision = desired.Revision }, Guid.NewGuid()));
        await Fault("StaleRevision", () => fixture.Operations.CreateAsync(server.ServerId, stop with { Kind = OperationKind.Start }, Guid.NewGuid()));
        await Fault("StaleRevision", () => fixture.Operations.CreateAsync(server.ServerId, stop with { Kind = OperationKind.Restart }, Guid.NewGuid()));
        await Fault("StateConflict", () => fixture.Operations.CreateAsync(server.ServerId, stop with { ExpectedServerSessionId = Guid.NewGuid() }, Guid.NewGuid()));

        var operation = await fixture.Operations.CreateAsync(server.ServerId, stop, Guid.NewGuid());
        var command = ControlJson.Deserialize<OperationCommand>(operation.CommandJson);
        Assert.Equal(applied.Revision, command.ConfigRevision);
        Assert.Equal(applied.StopTimeoutSeconds, command.ExecutionTimeoutSeconds);
        var lease = await fixture.Reservations.GetByReservationAsync(operation.ReservationId);
        Assert.Contains(ReservationService.DirectoryKey(server.HostId, applied.ManagedRootId, applied.ServerDirectory), lease.ResourceKeys);
        Assert.Contains(ReservationService.DirectoryKey(server.HostId, desired.ManagedRootId, desired.ServerDirectory), lease.ResourceKeys);
        Assert.Contains(ReservationService.EnvironmentKey(other.EnvironmentId!.Value), lease.ResourceKeys);
        Assert.All(extraEnvironments, environment => Assert.Contains(ReservationService.EnvironmentKey(environment.EnvironmentId), lease.ResourceKeys));
    }

    [Theory]
    [InlineData(null, 1500)]
    [InlineData(45, 45)]
    public async Task RestartDefaultAddsTheConfiguredStopWaitToTheStartupBudget(int? clientTimeout, int expectedTimeout)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var applied = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson) with { StopTimeoutSeconds = 600 };
        server.DesiredConfigJson = ControlJson.Serialize(applied);
        await fixture.Db.SaveChangesAsync();
        var state = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        fixture.Agent.States[server.HostId] = state;

        var request = new ServerOperationRequest { Kind = OperationKind.Restart, ConfigRevision = applied.Revision, ExpectedStateRevision = state.StateRevision, ExpectedServerSessionId = state.CurrentSessionId, ExecutionTimeoutSeconds = clientTimeout };
        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid());
        Assert.Equal(expectedTimeout, ControlJson.Deserialize<OperationCommand>(operation.CommandJson).ExecutionTimeoutSeconds);
    }

    [Fact]
    public async Task Adapter16CanStartAppliedSettingsOnlyWithinPreparedStartupScope()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var applied = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        await fixture.SaveAppliedSettingsAsync(server, applied);
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), AdapterId = "16", Mode = HoldMode.Maintenance, RunReference = "run-16",
            ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Stopped)]
        });
        var desired = applied with { Revision = 2, ServerDirectory = "servers/renamed" };
        server.DesiredConfigJson = ControlJson.Serialize(desired);
        server.DesiredRevision = desired.Revision;
        await fixture.Db.SaveChangesAsync();
        var state = fixture.Agent.States[server.HostId];
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = applied.Revision, ExpectedStateRevision = state.StateRevision, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));
        await fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "WaitForStartup", "run-16"));
        var hold = fixture.Agent.Holds[server.HostId];
        fixture.Agent.Holds[server.HostId] = hold with { ResourceKeys = [ReservationService.ServerKey(server.ServerId)] };
        await Fault("RecoveryRequired", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));
        fixture.Agent.Holds[server.HostId] = hold;

        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid());
        Assert.Equal(OperationStatus.Accepted, operation.Status);
        Assert.Equal(lease.ReservationId, operation.ReservationId);
        Assert.Equal(applied.Revision, ControlJson.Deserialize<OperationCommand>(operation.CommandJson).ConfigRevision);
    }

    [Theory]
    [InlineData("server")]
    [InlineData("old directory")]
    [InlineData("new directory")]
    [InlineData("old environment")]
    [InlineData("new environment")]
    public async Task SettingsEditCannotChangeEitherSideOfAnActiveReservation(string heldResource)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var other = await fixture.ServerAsync("other");
        var applied = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        var next = applied with { Revision = 2, ServerDirectory = "renamed/one" };
        await fixture.SaveAppliedSettingsAsync(server, applied);
        var key = heldResource switch
        {
            "server" => ReservationService.ServerKey(server.ServerId),
            "old directory" => ReservationService.DirectoryKey(server.HostId, applied.ManagedRootId, "servers"),
            "new directory" => ReservationService.DirectoryKey(server.HostId, applied.ManagedRootId, "renamed"),
            "old environment" => ReservationService.EnvironmentKey(server.EnvironmentId!.Value),
            _ => ReservationService.EnvironmentKey(other.EnvironmentId!.Value)
        };
        await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), Mode = HoldMode.Maintenance,
            ResourceKeys = [key, ReservationService.ServerKey(other.ServerId)]
        });
        var update = new ServerUpdateRequest(server.DisplayName, other.EnvironmentId, server.RuntimeServerId, next);

        await Fault("ReservationConflict", () => fixture.Store.UpdateServerAsync(server.ServerId, update, applied.Revision, ControlJson.Serialize(next)));
        Assert.Equal(applied.Revision, (await fixture.Store.ServerAsync(server.ServerId)).DesiredRevision);
    }

    [Fact]
    public async Task EnvironmentEditRequiresConfirmedStoppedSessionButOrdinaryEditsCanWait()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var other = await fixture.ServerAsync("other");
        var applied = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        await fixture.SaveAppliedSettingsAsync(server, applied);
        var host = await fixture.Store.HostAsync(server.HostId);
        var running = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { running });
        await fixture.Db.SaveChangesAsync();
        var next = applied with { Revision = 2, StopTimeoutSeconds = 210 };
        var update = new ServerUpdateRequest(server.DisplayName, other.EnvironmentId, server.RuntimeServerId, next);

        await Fault("StateConflict", () => fixture.Store.UpdateServerAsync(server.ServerId, update, applied.Revision, ControlJson.Serialize(next)));
        var saved = await fixture.Store.UpdateServerAsync(server.ServerId, update with { EnvironmentId = server.EnvironmentId }, applied.Revision, ControlJson.Serialize(next));
        Assert.Equal(next.Revision, saved.DesiredRevision);
        Assert.Equal(applied.Revision, saved.AppliedRevision);
    }

    [Fact]
    public async Task Adapter17CannotInheritProcessOperation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "17", RunReference = "run-17", ResourceKeys = [ReservationService.ServerKey(server.ServerId)] });
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = fixture.Agent.States[server.HostId].StateRevision, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));
    }

    [Fact]
    public async Task Adapter03StoppedCanStartOnlyDuringStartupWait()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "03", RunReference = "run-03", ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Stopped)] });
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = fixture.Agent.States[server.HostId].StateRevision, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));
        await fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "WaitForStartup", "run-03"));
        Assert.Equal(OperationStatus.Accepted, (await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid())).Status);
    }

    [Fact]
    public async Task Adapter03RunningCannotStopAndPublishValidationIsRepeatable()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var session = Guid.NewGuid();
        fixture.Agent.States[server.HostId] = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = session };
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "03", RunReference = "run-03", ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Running)] });
        var state = fixture.Agent.States[server.HostId];
        await Fault("StateConflict", () => fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "Publish", "run-03")));
        await fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "WaitForStartup", "run-03"));
        var stop = new ServerOperationRequest { Kind = OperationKind.Stop, ConfigRevision = 1, ExpectedStateRevision = state.StateRevision, ExpectedServerSessionId = session, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, stop, Guid.NewGuid()));
        var publish = new GateValidateRequest(lease.WorkflowId, lease.Fences, "Publish", "run-03");
        await fixture.Reservations.ValidateAsync(lease.ReservationId, publish);
        await fixture.Reservations.ValidateAsync(lease.ReservationId, publish);
        await Fault("ReservationConflict", () => fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "WaitForStartup", "run-03")));
    }

    [Theory]
    [InlineData("01")]
    [InlineData("02")]
    [InlineData("03")]
    public async Task DevLifecycleUsesOnlyTheOwnedDevServerAndReplaysOperations(string adapter)
    {
        await using var fixture = await Fixture.CreateAsync();
        var dev = await fixture.ServerAsync("dev");
        dev.RuntimeServerId = "Dev";
        var other = await fixture.ServerAsync("other");
        await fixture.Db.SaveChangesAsync();
        var firstSession = Guid.NewGuid();
        fixture.Agent.States[dev.HostId] = fixture.Agent.States[dev.HostId] with
        {
            ProcessState = ProcessState.Running, CurrentSessionId = firstSession
        };
        var request = new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), AdapterId = adapter, RunReference = "run-dev",
            DevLifecycleServerId = dev.ServerId,
            ResourceKeys = [ReservationService.ServerKey(dev.ServerId), ReservationService.ServerKey(other.ServerId)],
            ServerConditions = [new(dev.ServerId, ProcessRequirement.Stopped), new(other.ServerId, ProcessRequirement.Stopped)]
        };
        var lease = await fixture.Reservations.AcquireAsync(request);
        var scope = new GateDevScopeRequest(lease.WorkflowId, lease.Fences, "run-dev");
        Assert.Equal(dev.ServerId, await fixture.Reservations.GetDevLifecycleServerIdAsync(lease.ReservationId, scope));
        await Fault("ReservationConflict", () => fixture.Reservations.GetDevLifecycleServerIdAsync(lease.ReservationId,
            scope with { ExpectedFences = [new HostReservationFence(lease.Fences[0].HostId, lease.Fences[0].Fence + 1), .. lease.Fences.Skip(1)] }));
        await Fault("ReservationConflict", () => fixture.Reservations.GetDevLifecycleServerIdAsync(lease.ReservationId,
            scope with { RunReference = "another-run" }));
        var validate = (string step) => fixture.Reservations.ValidateAsync(lease.ReservationId,
            new GateValidateRequest(lease.WorkflowId, lease.Fences, step, "run-dev"));
        await Fault("ReservationConflict", () => validate("Deploy"));
        await validate("StopForDeploy");
        var running = fixture.Agent.States[dev.HostId];
        var stop = new ServerOperationRequest
        {
            Kind = OperationKind.Stop, ConfigRevision = 1, ExpectedStateRevision = running.StateRevision,
            ExpectedServerSessionId = firstSession, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId
        };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(other.ServerId,
            stop with { Kind = OperationKind.Start, ExpectedStateRevision = fixture.Agent.States[other.HostId].StateRevision,
                ExpectedServerSessionId = null }, Guid.NewGuid()));
        var otherStopped = fixture.Agent.States[other.HostId];
        fixture.Agent.States[other.HostId] = otherStopped with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        await Fault("StateConflict", () => fixture.Operations.CreateAsync(dev.ServerId, stop, Guid.NewGuid()));
        fixture.Agent.States[other.HostId] = otherStopped;
        var stopKey = Guid.NewGuid();
        var stopped = await fixture.Operations.CreateAsync(dev.ServerId, stop, stopKey);
        Assert.Equal(OperationStatus.Accepted, stopped.Status);
        Assert.Equal(stopped.OperationId, (await fixture.Operations.CreateAsync(dev.ServerId, stop, stopKey)).OperationId);
        Assert.Equal(1, fixture.Agent.DispatchCount);
        fixture.Agent.States[dev.HostId] = running with { ProcessState = ProcessState.Stopped, CurrentSessionId = null, StateRevision = running.StateRevision + 1 };
        await validate("Deploy");
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(dev.ServerId,
            stop with { ExpectedServerSessionId = null, ExpectedStateRevision = fixture.Agent.States[dev.HostId].StateRevision }, Guid.NewGuid()));
        await validate("Seed");
        await validate("WaitForStartup");
        var start = stop with { Kind = OperationKind.Start, ExpectedServerSessionId = null,
            ExpectedStateRevision = fixture.Agent.States[dev.HostId].StateRevision };
        fixture.Agent.States[other.HostId] = otherStopped with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        await Fault("StateConflict", () => fixture.Operations.CreateAsync(dev.ServerId, start, Guid.NewGuid()));
        fixture.Agent.States[other.HostId] = otherStopped;
        Assert.Equal(OperationStatus.Accepted, (await fixture.Operations.CreateAsync(dev.ServerId, start, Guid.NewGuid())).Status);
        Assert.Equal(2, fixture.Agent.DispatchCount);
        await Fault("StateConflict", () => validate("Publish"));
        fixture.Agent.States[dev.HostId] = fixture.Agent.States[dev.HostId] with
        {
            ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid(),
            StateRevision = fixture.Agent.States[dev.HostId].StateRevision + 1
        };
        await validate("Publish");
    }

    [Fact]
    public async Task DevOnlyDeploymentKeepsSharedDirectoriesReservedWhilePeersRemainRunning()
    {
        await using var fixture = await Fixture.CreateAsync();
        var dev = await fixture.ServerAsync("dev");
        dev.RuntimeServerId = "Dev";
        var peer = await fixture.ServerAsync("channel1");
        peer.EnvironmentId = dev.EnvironmentId;
        await fixture.Db.SaveChangesAsync();
        var peerSession = Guid.NewGuid();
        fixture.Agent.States[peer.HostId] = fixture.Agent.States[peer.HostId] with
        {
            ProcessState = ProcessState.Running, CurrentSessionId = peerSession
        };
        fixture.Agent.States[dev.HostId] = fixture.Agent.States[dev.HostId] with
        {
            ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid()
        };
        var directory = ReservationService.DirectoryKey(peer.HostId, Guid.NewGuid(), "api");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), AdapterId = "01", RunReference = "run-dev-only",
            DevLifecycleServerId = dev.ServerId,
            ResourceKeys = [ReservationService.ServerKey(dev.ServerId), directory],
            ServerConditions = [new(dev.ServerId, ProcessRequirement.Stopped)]
        });
        Assert.Equal(ReservationStatus.Prepared, lease.Status);
        Assert.Contains(ReservationService.EnvironmentKey(dev.EnvironmentId!.Value), lease.ResourceKeys);
        Assert.Contains(directory, fixture.Agent.Holds[peer.HostId].ResourceKeys);
        Assert.DoesNotContain(ReservationService.ServerKey(peer.ServerId), lease.ResourceKeys);
        var validate = (string step) => fixture.Reservations.ValidateAsync(lease.ReservationId,
            new GateValidateRequest(lease.WorkflowId, lease.Fences, step, "run-dev-only"));
        await validate("StopForDeploy");
        var running = fixture.Agent.States[dev.HostId];
        var stop = new ServerOperationRequest
        {
            Kind = OperationKind.Stop, ConfigRevision = 1, ExpectedStateRevision = running.StateRevision,
            ExpectedServerSessionId = running.CurrentSessionId, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId
        };
        Assert.Equal(OperationStatus.Accepted, (await fixture.Operations.CreateAsync(dev.ServerId, stop, Guid.NewGuid())).Status);
        await Fault("StateConflict", () => validate("Deploy"));
        fixture.Agent.States[dev.HostId] = running with
        {
            ProcessState = ProcessState.Stopped, CurrentSessionId = null, StateRevision = running.StateRevision + 1
        };
        await validate("Deploy");
        await validate("Seed");
        await validate("WaitForStartup");
        var start = stop with { Kind = OperationKind.Start, ExpectedServerSessionId = null,
            ExpectedStateRevision = fixture.Agent.States[dev.HostId].StateRevision };
        Assert.Equal(OperationStatus.Accepted, (await fixture.Operations.CreateAsync(dev.ServerId, start, Guid.NewGuid())).Status);
        fixture.Agent.States[dev.HostId] = fixture.Agent.States[dev.HostId] with
        {
            ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid(),
            StateRevision = fixture.Agent.States[dev.HostId].StateRevision + 1
        };
        await validate("Publish");
        Assert.Equal(ProcessState.Running, fixture.Agent.States[peer.HostId].ProcessState);
        Assert.Equal(peerSession, fixture.Agent.States[peer.HostId].CurrentSessionId);
        Assert.Contains(directory, fixture.Agent.Holds[peer.HostId].ResourceKeys);
        Assert.Equal(2, fixture.Agent.DispatchCount);
        Assert.All(await fixture.Db.Operations.ToArrayAsync(), operation => Assert.Equal(dev.ServerId, operation.ServerId));
    }

    [Fact]
    public async Task DevLifecycleRequiresExactStoppedConditionsAndRuntimeIdentity()
    {
        Assert.DoesNotContain("devLifecycleServerId", ControlJson.Serialize(new ReservationAcquireRequest()), StringComparison.Ordinal);
        await using var fixture = await Fixture.CreateAsync();
        var dev = await fixture.ServerAsync("dev");
        var other = await fixture.ServerAsync("other");
        var request = new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), AdapterId = "01", RunReference = "run-dev", DevLifecycleServerId = dev.ServerId,
            ResourceKeys = [ReservationService.ServerKey(dev.ServerId), ReservationService.ServerKey(other.ServerId)],
            ServerConditions = [new(dev.ServerId, ProcessRequirement.Stopped), new(other.ServerId, ProcessRequirement.Stopped)]
        };
        await Fault("InvalidInput", () => fixture.Reservations.AcquireAsync(request));
        dev.RuntimeServerId = "Dev";
        await fixture.Db.SaveChangesAsync();
        await Fault("InvalidInput", () => fixture.Reservations.AcquireAsync(request with { AdapterId = "17" }));
        await Fault("InvalidInput", () => fixture.Reservations.AcquireAsync(request with { Mode = HoldMode.Exclusive }));
        await Fault("InvalidInput", () => fixture.Reservations.AcquireAsync(request with { ServerConditions = [new(dev.ServerId, ProcessRequirement.Stopped)] }));
        await Fault("InvalidInput", () => fixture.Reservations.AcquireAsync(request with
        {
            ServerConditions = [new(dev.ServerId, ProcessRequirement.Stopped), new(other.ServerId, ProcessRequirement.Running)]
        }));
        Assert.Empty(await fixture.Db.ReservationLeases.ToArrayAsync());
    }

    [Fact]
    public async Task ReleaseRetriesSameOutcomeAfterPartialHostFailure()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.ServerAsync("one");
        var second = await fixture.ServerAsync("two");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "16", RunReference = "run-16", ResourceKeys = [ReservationService.ServerKey(first.ServerId), ReservationService.ServerKey(second.ServerId)] });
        fixture.Agent.FailReleaseHost = new[] { first.HostId, second.HostId }.Max();
        var release = new GateReleaseRequest(lease.WorkflowId, lease.Fences, GateOutcome.Succeeded, "run-16");
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.ReleaseAsync(lease.ReservationId, release));
        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(lease.WorkflowId)).Status);
        fixture.Agent.FailReleaseHost = null;
        await fixture.Reservations.ReleaseAsync(lease.ReservationId, release);
        await fixture.Reservations.ReleaseAsync(lease.ReservationId, release);
        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(lease.WorkflowId)).Status);
        await Fault("ReservationConflict", () => fixture.Reservations.ReleaseAsync(lease.ReservationId, release with { Outcome = GateOutcome.Failed }));
    }

    [Theory]
    [InlineData(OperationKind.Start, false)]
    [InlineData(OperationKind.ConsoleCommand, true)]
    public async Task InspectedTerminalOperationCanReleaseCentralRecovery(OperationKind kind, bool running)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var session = running ? Guid.NewGuid() : (Guid?)null;
        if (running) fixture.Agent.States[server.HostId] = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = session };
        var request = new ServerOperationRequest { Kind = kind, ConfigRevision = 1, ExpectedStateRevision = fixture.Agent.States[server.HostId].StateRevision, ExpectedServerSessionId = session, Payload = kind == OperationKind.ConsoleCommand ? ControlJson.Element(new { command = "say fixture" }) : ControlJson.Element(new { }) };
        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid());
        await fixture.Operations.ApplyExecutionAsync(operation.OperationId, new ExecutionSnapshot { OperationId = operation.OperationId, CommandSha256 = operation.CommandSha256, Status = OperationStatus.NeedsAttention, StatusVersion = 2, UpdatedAtUtc = DateTimeOffset.UtcNow });
        fixture.Agent.QueryResult = new ExecutionSnapshot { OperationId = operation.OperationId, CommandSha256 = operation.CommandSha256, Status = OperationStatus.Failed, StatusVersion = 3, UpdatedAtUtc = DateTimeOffset.UtcNow };
        var state = fixture.Agent.States[server.HostId];
        fixture.Agent.Inspection = new RecoveryInspection(state, running ? new ServerSessionSnapshot { ServerSessionId = session!.Value, ServerId = server.ServerId } : null, running, running, false);
        Assert.Null(await fixture.Operations.RecoverAsync(operation.WorkflowId, new WorkflowRecoveryRequest(WorkflowRecoveryAction.AbortAfterInspection, "inspected")));
        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(operation.WorkflowId)).Status);
        Assert.False((await fixture.Db.Workflows.AsNoTracking().SingleAsync()).RecoveryRequired);
    }

    [Theory]
    [InlineData(OperationKind.Stop, OperationStatus.Succeeded, null, WorkflowStatus.Succeeded)]
    [InlineData(OperationKind.Restart, OperationStatus.Failed, "Timeout", WorkflowStatus.Failed)]
    [InlineData(OperationKind.Restart, OperationStatus.Failed, "RecoveryRequired", WorkflowStatus.Failed)]
    public async Task LateShutdownReleasesOnlyAfterExactStoppedInspection(OperationKind kind, OperationStatus status, string? error, WorkflowStatus workflowStatus)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("late-exit");
        var running = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        fixture.Agent.States[server.HostId] = running;
        var operation = await fixture.Operations.CreateAsync(server.ServerId, new ServerOperationRequest
        {
            Kind = kind, ConfigRevision = running.ConfigRevision, ExpectedStateRevision = running.StateRevision,
            ExpectedServerSessionId = running.CurrentSessionId
        }, Guid.NewGuid());
        var attention = new ExecutionSnapshot { OperationId = operation.OperationId, CommandSha256 = operation.CommandSha256,
            Status = OperationStatus.NeedsAttention, StatusVersion = 2, ErrorCode = "Timeout", UpdatedAtUtc = DateTimeOffset.UtcNow };
        await fixture.Operations.ApplyExecutionAsync(operation.OperationId, attention);
        var stopped = running with { ProcessState = ProcessState.Stopped, CurrentSessionId = null };
        var inspection = new RecoveryInspection(stopped, null, false, false, false);
        fixture.Agent.Inspection = inspection;
        await fixture.Operations.ReconcileSingleOperationReleaseAsync(operation.WorkflowId);
        Assert.Single(fixture.Agent.Holds);

        await fixture.Operations.ApplyExecutionAsync(operation.OperationId, attention with { Status = status, StatusVersion = 3, ErrorCode = error });
        foreach (var unsafeInspection in new[]
        {
            inspection with { Runtime = stopped with { ServerId = Guid.NewGuid() } },
            inspection with { Runtime = running },
            inspection with { Runtime = stopped with { CurrentSessionId = running.CurrentSessionId } },
            inspection with { Session = new ServerSessionSnapshot { ServerId = server.ServerId, ServerSessionId = running.CurrentSessionId!.Value } },
            inspection with { RecoveryRequired = true },
            inspection with { ProcessAlive = true }
        })
        {
            fixture.Agent.Inspection = unsafeInspection;
            await fixture.Operations.ReconcileSingleOperationReleaseAsync(operation.WorkflowId);
            Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(operation.WorkflowId)).Status);
            Assert.Single(fixture.Agent.Holds);
        }

        fixture.Agent.Inspection = inspection;
        fixture.Agent.FailReleaseHost = server.HostId;
        await Assert.ThrowsAsync<IOException>(() => fixture.Operations.ReconcileSingleOperationReleaseAsync(operation.WorkflowId));
        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(operation.WorkflowId)).Status);
        Assert.Single(fixture.Agent.Holds);
        fixture.Agent.FailReleaseHost = null;
        fixture.Agent.Inspection = null; // The verified release intent survives a lost release response.
        await fixture.Operations.ReconcileSingleOperationReleaseAsync(operation.WorkflowId);

        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(operation.WorkflowId)).Status);
        Assert.Empty(fixture.Agent.Holds);
        Assert.Equal(operation.ReservationId, fixture.Agent.ReleasedHolds[server.HostId].ReservationId);
        var workflow = await fixture.Db.Workflows.AsNoTracking().SingleAsync();
        Assert.Equal(workflowStatus, workflow.Status);
        Assert.False(workflow.RecoveryRequired);
        Assert.Equal(1, fixture.Agent.DispatchCount);
    }

    [Fact]
    public async Task LateShutdownReconciliationKeepsExternalMaintenanceHold()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("maintenance-exit");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), AdapterId = "16", RunReference = "run-16", ResourceKeys = [ReservationService.ServerKey(server.ServerId)]
        });
        var child = new ControlOperation { OperationId = Guid.NewGuid(), IdempotencyKey = Guid.NewGuid(), HostId = server.HostId, ServerId = server.ServerId,
            WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId, Kind = OperationKind.Stop, Status = OperationStatus.Succeeded };
        fixture.Db.Operations.Add(child);
        await fixture.Db.SaveChangesAsync();
        await fixture.Reservations.MarkRecoveryAsync(lease.WorkflowId, lease.ReservationId);
        fixture.Agent.Inspection = new RecoveryInspection(fixture.Agent.States[server.HostId], null, false, false, false);

        await fixture.Operations.ReconcileSingleOperationReleaseAsync(lease.WorkflowId);

        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(lease.WorkflowId)).Status);
        Assert.Single(fixture.Agent.Holds);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RescueKillUsesOnlyTheOriginalShutdownSessionAndHold(bool shutdownCompletesDuringInspection)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("rescue-kill");
        var originalSession = Guid.NewGuid();
        var running = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Running, CurrentSessionId = originalSession };
        fixture.Agent.States[server.HostId] = running;
        var stop = await fixture.Operations.CreateAsync(server.ServerId, new ServerOperationRequest
        {
            Kind = OperationKind.Stop, ConfigRevision = running.ConfigRevision, ExpectedStateRevision = running.StateRevision,
            ExpectedServerSessionId = originalSession
        }, Guid.NewGuid());
        var attention = new ExecutionSnapshot { OperationId = stop.OperationId, CommandSha256 = stop.CommandSha256,
            Status = OperationStatus.NeedsAttention, StatusVersion = 2, ErrorCode = "Timeout", UpdatedAtUtc = DateTimeOffset.UtcNow };
        await fixture.Operations.ApplyExecutionAsync(stop.OperationId, attention);
        var newSession = Guid.NewGuid();
        var stopping = fixture.Agent.States[server.HostId] with { ProcessState = ProcessState.Stopping };
        var session = new ServerSessionSnapshot { ServerId = server.ServerId, ServerSessionId = originalSession, ProcessId = 42 };
        var request = new WorkflowRecoveryRequest(WorkflowRecoveryAction.KillAfterInspection, "fixture confirmation");
        fixture.Agent.Inspection = new RecoveryInspection(stopping with { CurrentSessionId = newSession }, session with { ServerSessionId = newSession }, true, true, true);
        await Fault("RecoveryRequired", () => fixture.Operations.RecoverAsync(stop.WorkflowId, request, inspectedSessionId: originalSession));
        fixture.Agent.Inspection = new RecoveryInspection(stopping, session, true, true, true);
        await Fault("StateConflict", () => fixture.Operations.RecoverAsync(stop.WorkflowId, request, inspectedSessionId: newSession));
        Assert.Equal(1, fixture.Agent.DispatchCount);

        if (shutdownCompletesDuringInspection)
        {
            fixture.Agent.BeforeInspectionResponse = () => fixture.Operations.ApplyExecutionAsync(stop.OperationId,
                attention with { Status = OperationStatus.Succeeded, StatusVersion = 3, ErrorCode = null });
            await Fault("StateConflict", () => fixture.Operations.RecoverAsync(stop.WorkflowId, request, inspectedSessionId: originalSession));
            Assert.Single(await fixture.Db.Operations.AsNoTracking().ToArrayAsync());
            Assert.Equal(1, fixture.Agent.DispatchCount);
            return;
        }

        var rescue = Assert.IsType<ControlOperation>(await fixture.Operations.RecoverAsync(stop.WorkflowId, request, inspectedSessionId: originalSession));
        var command = ControlJson.Deserialize<OperationCommand>(rescue.CommandJson);
        Assert.Equal(OperationKind.Kill, command.Kind);
        Assert.Equal(stop.WorkflowId, command.WorkflowId);
        Assert.Equal(stop.ReservationId, command.ReservationId);
        Assert.Equal(fixture.Agent.Holds[server.HostId].Fence, command.ReservationFence);
        Assert.Equal(originalSession, command.ExpectedServerSessionId);
        Assert.Equal(2, fixture.Agent.DispatchCount);

        await fixture.Operations.ApplyExecutionAsync(stop.OperationId, attention with { Status = OperationStatus.Succeeded, StatusVersion = 3, ErrorCode = null });
        await fixture.Operations.ApplyExecutionAsync(rescue.OperationId, new ExecutionSnapshot { OperationId = rescue.OperationId, CommandSha256 = rescue.CommandSha256,
            Status = OperationStatus.Cancelled, StatusVersion = rescue.StatusVersion + 1, UpdatedAtUtc = DateTimeOffset.UtcNow });
        await fixture.Operations.ReconcileSingleOperationReleaseAsync(stop.WorkflowId);
        Assert.Single(fixture.Agent.Holds);
        fixture.Agent.Inspection = new RecoveryInspection(stopping with { ProcessState = ProcessState.Stopped, CurrentSessionId = null }, null, false, false, false);
        await fixture.Operations.ReconcileSingleOperationReleaseAsync(stop.WorkflowId);
        Assert.Empty(fixture.Agent.Holds);
        Assert.Equal(WorkflowStatus.Succeeded, (await fixture.Db.Workflows.AsNoTracking().SingleAsync()).Status);
    }

    [Fact]
    public async Task InspectedAbortReleasesPreparedSingleOperationWithoutChild()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)], Mode = HoldMode.Exclusive
        });
        await fixture.Reservations.MarkRecoveryAsync(lease.WorkflowId, lease.ReservationId);
        var recovery = new WorkflowRecoveryRequest(WorkflowRecoveryAction.AbortAfterInspection, "inspected");
        var state = fixture.Agent.States[server.HostId];
        fixture.Agent.Inspection = new RecoveryInspection(state, null, false, false, true);
        await Fault("RecoveryRequired", () => fixture.Operations.RecoverAsync(lease.WorkflowId, recovery));
        Assert.Equal(lease.ReservationId, fixture.Agent.Holds[server.HostId].ReservationId);

        fixture.Agent.Inspection = new RecoveryInspection(state, null, false, false, false);
        fixture.Agent.FailReleaseHost = server.HostId;
        await Assert.ThrowsAsync<IOException>(() => fixture.Operations.RecoverAsync(lease.WorkflowId, recovery));
        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Reservations.GetAsync(lease.WorkflowId)).Status);
        fixture.Agent.FailReleaseHost = null;
        Assert.Null(await fixture.Operations.RecoverAsync(lease.WorkflowId, recovery));

        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(lease.WorkflowId)).Status);
        Assert.Equal(WorkflowStatus.Failed, (await fixture.Db.Workflows.SingleAsync()).Status);
        Assert.Empty(fixture.Agent.Holds);
        Assert.Equal(lease.ReservationId, fixture.Agent.ReleasedHolds[server.HostId].ReservationId);
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
        Assert.Equal(0, fixture.Agent.DispatchCount);
    }

    [Fact]
    public async Task InspectedAbortFencesDelayedSingleOperationPrepareWithoutChild()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)], Mode = HoldMode.Exclusive
        };
        fixture.Agent.FailHost = server.HostId;
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.AcquireAsync(request));
        var lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.RecoveryRequired, lease.Status);
        Assert.Empty(lease.Fences);
        fixture.Agent.FailHost = null;
        fixture.Agent.Inspection = new RecoveryInspection(fixture.Agent.States[server.HostId], null, false, false, false);
        await Fault("RecoveryRequired", () => fixture.Operations.RecoverAsync(lease.WorkflowId, new(WorkflowRecoveryAction.Resume, "inspected")));
        Assert.Null(await fixture.Operations.RecoverAsync(lease.WorkflowId, new(WorkflowRecoveryAction.AbortAfterInspection, "inspected")));

        var released = await fixture.Reservations.GetAsync(lease.WorkflowId);
        Assert.Equal(ReservationStatus.Released, released.Status);
        Assert.Single(released.Fences);
        var runnerKeys = released.ResourceKeys.Where(x => !x.StartsWith("environment:", StringComparison.Ordinal)).ToArray();
        await Fault("ReservationConflict", () => fixture.Agent.PrepareAsync(server.HostId, new(lease.ReservationId, lease.WorkflowId, runnerKeys, HoldMode.Exclusive), CancellationToken.None));
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
        Assert.Equal(0, fixture.Agent.DispatchCount);
    }

    [Fact]
    public async Task SingleOperationIsNotQueuedAfterItsPreparedLeaseEntersRecovery()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var snapshotCount = 0;
        fixture.Agent.BeforeSnapshotResponse = async () =>
        {
            if (++snapshotCount != 4) return;
            var hold = fixture.Agent.Holds[server.HostId];
            await fixture.Reservations.MarkRecoveryAsync(hold.OwnerWorkflowId, hold.ReservationId);
        };
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };

        await Fault("RecoveryRequired", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));

        Assert.Equal(ReservationStatus.RecoveryRequired, (await fixture.Db.ReservationLeases.SingleAsync()).Status);
        Assert.Empty(await fixture.Db.Operations.ToArrayAsync());
        Assert.Equal(0, fixture.Agent.DispatchCount);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task LatePrepareResponseDoesNotReviveInspectedSingleOperationAbort(bool lostResponse)
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)], Mode = HoldMode.Exclusive
        };
        var responseReady = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var resume = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Agent.BeforePrepareResponse = async () =>
        {
            responseReady.SetResult();
            await resume.Task;
            if (lostResponse) throw new IOException("Injected lost response.");
        };
        var pending = fixture.Reservations.AcquireAsync(request);
        try
        {
            await responseReady.Task.WaitAsync(TimeSpan.FromSeconds(10));
            fixture.Agent.FailHost = server.HostId;
            await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.AcquireAsync(request));
            fixture.Agent.FailHost = null;
            fixture.Agent.Inspection = new RecoveryInspection(fixture.Agent.States[server.HostId], null, false, false, false);
            Assert.Null(await fixture.Operations.RecoverAsync(request.WorkflowId, new(WorkflowRecoveryAction.AbortAfterInspection, "inspected")));
        }
        finally { resume.SetResult(); }

        if (lostResponse) await Assert.ThrowsAsync<IOException>(() => pending);
        else await Fault("RecoveryRequired", () => pending);
        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(request.WorkflowId)).Status);
        Assert.Equal(WorkflowStatus.Failed, (await fixture.Db.Workflows.SingleAsync()).Status);
        Assert.Empty(fixture.Agent.Holds);
    }

    [Fact]
    public async Task ReleaseWaitingForWriterDoesNotReopenCompletedReservation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest
        {
            WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)], Mode = HoldMode.Exclusive
        });
        var release = new GateReleaseRequest(lease.WorkflowId, lease.Fences, GateOutcome.Failed, "");
        await fixture.Agent.ReleaseAsync(server.HostId, new(lease.ReservationId, lease.WorkflowId, lease.Fences.Single().Fence), CancellationToken.None);
        fixture.Agent.FailReleaseHost = server.HostId;
        Task waiting = Task.CompletedTask;
        await fixture.Store.WriteAsync(async () =>
        {
            waiting = fixture.Reservations.ReleaseAsync(lease.ReservationId, release);
            var history = await fixture.Db.ReservationLeases.SingleAsync();
            history.Status = ReservationStatus.Released;
            history.ReleaseOutcome = GateOutcome.Failed;
            foreach (var row in await fixture.Db.Reservations.ToArrayAsync()) row.State = ReservationStatus.Released;
            (await fixture.Db.Workflows.SingleAsync()).Status = WorkflowStatus.Failed;
            return true;
        });

        await waiting;
        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(lease.WorkflowId)).Status);
        Assert.All(await fixture.Db.Reservations.ToArrayAsync(), row => Assert.Equal(ReservationStatus.Released, row.State));
    }

    [Fact]
    public async Task SchemaOneMigrationBacksUpAndPreservesInventory()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-migration-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "control.db");
        try
        {
            var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options;
            await using (var db = new ControlDbContext(options))
            {
                await ControlDatabase.InitializeAsync(db);
                db.Hosts.Add(new InventoryHost { HostId = Guid.NewGuid(), HostKey = "preserved", DisplayName = "Preserved" });
                await db.SaveChangesAsync();
                await db.Database.ExecuteSqlRawAsync("DROP TABLE NotificationOutbox; DROP TABLE NotificationEvents; DROP TABLE NotificationDestinations; DROP TABLE MetricBuckets; DROP TABLE ScheduleOccurrences; DROP TABLE ScheduleManualRequests; DROP TABLE TransferChunks; DROP TABLE Transfers; DROP TABLE Operations; DROP TABLE Reservations; DROP TABLE ReservationLeases; DROP TABLE Workflows; DROP TABLE CliCredentials; PRAGMA user_version=1;");
            }
            await using (var db = new ControlDbContext(options))
            {
                await ControlDatabase.InitializeAsync(db);
                Assert.Equal(1, await db.Hosts.CountAsync());
                Assert.Equal(0, await db.Operations.CountAsync());
                Assert.Equal(0, await db.CliCredentials.CountAsync());
                await db.Database.OpenConnectionAsync();
                await using var versionCommand = db.Database.GetDbConnection().CreateCommand();
                versionCommand.CommandText = "PRAGMA user_version;";
                Assert.Equal(5L, Convert.ToInt64(await versionCommand.ExecuteScalarAsync()));
                await db.Database.CloseConnectionAsync();
            }
            Assert.True(File.Exists(path + ".before-v2.bak"));
        }
        finally { Directory.Delete(root, true); }
    }

    private static async Task Fault(string code, Func<Task> action) => Assert.Equal(code, (await Assert.ThrowsAsync<InventoryFault>(action)).Code);

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        public ControlDbContext Db { get; private set; } = null!;
        public FakeAgent Agent { get; } = new();
        public InventoryStore Store { get; private set; } = null!;
        public ReservationService Reservations { get; private set; } = null!;
        public ControlOperationsService Operations { get; private set; } = null!;
        public static async Task<Fixture> CreateAsync(bool enableFiles = false)
        {
            var f = new Fixture(); await f.connection.OpenAsync();
            f.Db = new(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(f.connection).Options);
            await f.Db.Database.EnsureCreatedAsync();
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:EnableProcessControl"] = "true", ["Control:EnableFileControl"] = enableFiles.ToString() }).Build();
            f.Store = new InventoryStore(f.Db, new InventoryWriteGate(), TimeProvider.System, config);
            f.Reservations = new(f.Db, f.Store, f.Agent, config, TimeProvider.System);
            f.Operations = new(f.Db, f.Store, f.Reservations, f.Agent, TimeProvider.System, config);
            return f;
        }
        public async Task<InventoryServer> ServerAsync(string key)
        {
            var hostId = Guid.NewGuid(); var id = Guid.NewGuid(); var environmentId = Guid.NewGuid();
            var config = new ServerConfig { ServerId = id, HostId = hostId, Revision = 1, ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "servers/" + key, ListenPort = 25565 };
            Db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = key, DisplayName = key, Enabled = true, ConnectionState = HostConnectionState.Online, LastSeenAtUtc = DateTimeOffset.UtcNow, CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate" }) });
            Db.Environments.Add(new InventoryEnvironment { EnvironmentId = environmentId, EnvironmentKey = key, DisplayName = key });
            var server = new InventoryServer { ServerId = id, HostId = hostId, ServerKey = key, DisplayName = key, EnvironmentId = environmentId, DesiredRevision = 1, AppliedRevision = 1, DesiredConfigJson = ControlJson.Serialize(config) };
            Db.Servers.Add(server); await Db.SaveChangesAsync();
            Agent.States[hostId] = new ServerRuntimeSnapshot { ServerId = id, ConfigRevision = 1, StateRevision = 1, ProcessState = ProcessState.Stopped };
            return server;
        }
        public async Task SaveAppliedSettingsAsync(InventoryServer server, ServerConfig config)
        {
            var host = await Store.HostAsync(server.HostId);
            host.AppliedInventoryRevision = host.DesiredInventoryRevision = 1;
            host.ResourcesJson = ControlJson.Serialize(new HostResources
            {
                Roots = [new ManagedRoot { Id = config.ManagedRootId, Path = @"C:\fixture" }],
                Executables = [new ManagedExecutable { Id = config.ExecutableId, Path = @"C:\fixture\java.exe", Kind = ServerKind.Java }]
            });
            var json = ControlJson.Serialize(new InventorySnapshot { HostId = server.HostId, Revision = 1, Servers = [config] });
            Db.InventoryVersions.Add(new InventoryVersion { HostId = server.HostId, Revision = 1, SnapshotJson = json, Sha256 = ControlProtocol.Hash(json) });
            await Db.SaveChangesAsync();
        }
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await connection.DisposeAsync(); }
    }

    private sealed class FakeAgent : IAgentOperationTransport
    {
        public Dictionary<Guid, ServerRuntimeSnapshot> States { get; } = [];
        public Dictionary<Guid, ResourceHold> Holds { get; } = [];
        public Dictionary<Guid, ResourceHold> ReleasedHolds { get; } = [];
        public Guid? FailHost { get; set; }
        public Guid? ChangeStateHost { get; set; }
        public Guid? FailReleaseHost { get; set; }
        public bool SnapshotUnavailableAfterFailure { get; set; }
        public bool DispatchUnavailable { get; set; }
        public string? DispatchFaultCode { get; set; }
        public string? QueryFaultCode { get; set; }
        public ExecutionSnapshot? QueryResult { get; set; }
        public RecoveryInspection? Inspection { get; set; }
        public Func<Task>? BeforeSnapshotResponse { get; set; }
        public Func<Task>? BeforePrepareResponse { get; set; }
        public Func<Task>? BeforeInspectionResponse { get; set; }
        public Func<Task>? BeforeQueryResponse { get; set; }
        public int PrepareCount { get; private set; }
        public int DispatchCount { get; private set; }
        public async Task<RunnerSnapshot> SnapshotAsync(Guid hostId, CancellationToken token)
        {
            if (SnapshotUnavailableAfterFailure && hostId == FailHost && PrepareCount > 0) throw new IOException("Snapshot unavailable.");
            var snapshot = new RunnerSnapshot { RunnerEpoch = Guid.NewGuid(), Servers = [States[hostId]], Holds = Holds.TryGetValue(hostId, out var hold) ? [hold] : [] };
            if (BeforeSnapshotResponse is not null) await BeforeSnapshotResponse();
            return snapshot;
        }
        public async Task<PreparedHold> PrepareAsync(Guid hostId, AcquireHoldRequest request, CancellationToken token)
        {
            PrepareCount++;
            if (ReleasedHolds.TryGetValue(hostId, out var released) && released.ReservationId == request.ReservationId) throw new InventoryFault(409, "ReservationConflict");
            if (hostId == FailHost) throw new IOException("Injected lost response.");
            var hold = new ResourceHold { ReservationId = request.ReservationId, OwnerWorkflowId = request.OwnerWorkflowId, ResourceKeys = request.ResourceKeys, Mode = request.Mode, Fence = 1 };
            Holds[hostId] = hold;
            States[hostId] = States[hostId] with { StateRevision = States[hostId].StateRevision + 1 };
            if (hostId == ChangeStateHost) States[hostId] = States[hostId] with { ProcessState = ProcessState.Running };
            var prepared = new PreparedHold(hold, await SnapshotAsync(hostId, token));
            if (BeforePrepareResponse is not null) await BeforePrepareResponse();
            return prepared;
        }
        public Task ReleaseAsync(Guid hostId, ReleaseHoldRequest request, CancellationToken token)
        {
            if (hostId == FailReleaseHost) throw new IOException("Injected release timeout.");
            if (Holds.TryGetValue(hostId, out var hold) && hold.ReservationId == request.ReservationId && hold.Fence == request.ExpectedFence) { Holds.Remove(hostId); ReleasedHolds[hostId] = hold; return Task.CompletedTask; }
            if (ReleasedHolds.TryGetValue(hostId, out var released) && released.ReservationId == request.ReservationId && released.Fence == request.ExpectedFence) return Task.CompletedTask;
            throw new InventoryFault(409, "ReservationConflict");
        }
        public Task<ExecutionSnapshot> DispatchAsync(Guid hostId, Guid operationId, string json, string hash, CancellationToken token)
        {
            DispatchCount++;
            if (DispatchFaultCode != null) throw new InventoryFault(409, DispatchFaultCode);
            if (DispatchUnavailable) throw new IOException("Transport unavailable.");
            return Task.FromResult(new ExecutionSnapshot { OperationId = operationId, CommandSha256 = hash, Status = OperationStatus.Accepted, StatusVersion = 1, UpdatedAtUtc = DateTimeOffset.UtcNow });
        }
        public async Task<ExecutionSnapshot> QueryAsync(Guid hostId, Guid operationId, CancellationToken token)
        {
            if (BeforeQueryResponse is not null) await BeforeQueryResponse();
            if (QueryFaultCode != null) throw new InventoryFault(409, QueryFaultCode);
            return QueryResult ?? throw new IOException("Query unavailable.");
        }
        public Task<LogBatch> ReadLogsAsync(Guid hostId, ReadLogsRequest request, CancellationToken token) => Task.FromResult(new LogBatch { ServerId = request.ServerId, SessionId = States[hostId].CurrentSessionId });
        public async Task<RecoveryInspection> InspectRecoveryAsync(Guid hostId, Guid serverId, CancellationToken token)
        {
            var result = Inspection ?? throw new IOException("Inspection unavailable.");
            if (BeforeInspectionResponse is not null) await BeforeInspectionResponse();
            return result;
        }
    }
}
