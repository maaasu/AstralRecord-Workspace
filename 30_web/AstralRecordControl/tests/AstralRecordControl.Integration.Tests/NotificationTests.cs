using System.Net;
using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Notifications;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed class NotificationTests
{
    [Fact]
    public async Task EventAndDeliverySurviveReopenAndRetryWithSameId()
    {
        var path = Path.Combine(Path.GetTempPath(), "control-notification-test-" + Guid.NewGuid().ToString("N") + ".db");
        try
        {
            var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options;
            var clock = new ChangeableClock(DateTimeOffset.Parse("2026-10-18T00:00:00Z"));
            var protection = DataProtectionProvider.Create("notification-fixture");
            var gate = new InventoryWriteGate();
            Guid id;
            await using (var db = new ControlDbContext(options))
            {
                await db.Database.EnsureCreatedAsync();
                var store = new InventoryStore(db, gate, clock);
                var service = new NotificationService(db, protection, clock, store);
                id = Guid.NewGuid();
                await service.SaveDestinationAsync(id, 0, "fixture", "https://hooks.example.com/secret-token", true);
                var row = await db.NotificationDestinations.SingleAsync();
                Assert.DoesNotContain("secret-token", row.ProtectedUrl);
                await store.WriteAsync(async () => { await service.StageWebEventAsync("backup:one", NotificationEventTypes.BackupSucceeded, clock.GetUtcNow()); return true; });
                await store.WriteAsync(async () => { await service.StageWebEventAsync("backup:one", NotificationEventTypes.BackupSucceeded, clock.GetUtcNow()); return true; });
                Assert.Single(await db.NotificationEvents.ToArrayAsync());
                Assert.Single(await db.NotificationOutbox.ToArrayAsync());
            }
            var handler = new FixtureHandler();
            using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(5) };
            await using (var db = new ControlDbContext(options))
            {
                var store = new InventoryStore(db, gate, clock);
                var service = new NotificationService(db, protection, clock, store);
                var delivery = new NotificationDeliveryService(db, service, store, client, clock);
                Assert.Equal(1, await delivery.SendDueAsync());
                Assert.Equal(0, await delivery.SendDueAsync());
                clock.Advance(TimeSpan.FromMinutes(1));
                Assert.Equal(1, await delivery.SendDueAsync());
                var row = await db.NotificationOutbox.SingleAsync();
                Assert.Equal(2, row.Attempt);
                Assert.NotNull(row.SentAtUtc);
                Assert.Null(row.DeadLetteredAtUtc);
                Assert.Equal(2, handler.EventIds.Count);
                Assert.Equal(handler.EventIds[0], handler.EventIds[1]);
                Assert.Equal(row.EventId.ToString("D"), handler.EventIds[0]);
                clock.Advance(TimeSpan.FromDays(91));
                await new NotificationRetentionService(db, store, clock).RunAsync();
                Assert.Empty(await db.NotificationOutbox.ToArrayAsync());
                Assert.Empty(await db.NotificationEvents.ToArrayAsync());
            }
        }
        finally { if (File.Exists(path)) File.Delete(path); }
    }

    [Theory]
    [InlineData("http://hooks.example.com/a")]
    [InlineData("https://localhost/a")]
    [InlineData("https://user:pass@hooks.example.com/a")]
    [InlineData("https://hooks.example.com:444/a")]
    public void UnsafeDestinationsRejected(string url) => Assert.Throws<ArgumentException>(() => NotificationHttpHandler.ValidateUrl(url));

    [Fact]
    public async Task RuntimeEventReplayIsDeduplicatedAndFailureDeadLettersAfterThreeRetries()
    {
        var path = Path.Combine(Path.GetTempPath(), "control-notification-test-" + Guid.NewGuid().ToString("N") + ".db");
        try
        {
            var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options;
            var clock = new ChangeableClock(DateTimeOffset.Parse("2026-10-18T00:00:00Z"));
            var protection = DataProtectionProvider.Create("notification-fixture");
            var gate = new InventoryWriteGate();
            Guid eventId;
            await using (var db = new ControlDbContext(options))
            {
                await db.Database.EnsureCreatedAsync();
                var hostId = Guid.NewGuid(); var serverId = Guid.NewGuid();
                db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "fixture-host" });
                db.Servers.Add(new InventoryServer { ServerId = serverId, HostId = hostId, ServerKey = "fixture-server" });
                await db.SaveChangesAsync();
                var store = new InventoryStore(db, gate, clock);
                var service = new NotificationService(db, protection, clock, store);
                await service.SaveDestinationAsync(Guid.NewGuid(), 0, "fixture", "https://hooks.example.com/secret", true);
                var current = new ServerRuntimeSnapshot { ServerId = serverId, StateRevision = 1, ProcessState = ProcessState.Running };
                var body = JsonDocument.Parse(ControlJson.Serialize(new RuntimeChangedBody(null, current))).RootElement.Clone();
                var item = new AgentEvent(1, "RuntimeChanged", clock.GetUtcNow(), body);
                await store.WriteAsync(async () => { await service.StageAgentEventAsync(hostId, item); return true; });
                await store.WriteAsync(async () => { await service.StageAgentEventAsync(hostId, item); return true; });
                eventId = Assert.Single(await db.NotificationEvents.ToArrayAsync()).EventId;
                Assert.Equal(NotificationEventTypes.Started, (await db.NotificationEvents.SingleAsync()).EventType);
            }
            using var client = new HttpClient(new AlwaysFailsHandler()) { Timeout = TimeSpan.FromSeconds(5) };
            await using (var db = new ControlDbContext(options))
            {
                var store = new InventoryStore(db, gate, clock);
                var service = new NotificationService(db, protection, clock, store);
                var delivery = new NotificationDeliveryService(db, service, store, client, clock);
                clock.Advance(TimeSpan.FromDays(91));
                await new NotificationRetentionService(db, store, clock).RunAsync();
                Assert.Single(await db.NotificationOutbox.ToArrayAsync());
                foreach (var delay in new[] { TimeSpan.Zero, TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(30) })
                {
                    clock.Advance(delay);
                    Assert.Equal(1, await delivery.SendDueAsync());
                }
                var row = await db.NotificationOutbox.SingleAsync();
                Assert.Equal(eventId, row.EventId);
                Assert.Equal(4, row.Attempt);
                Assert.Equal("Http5xx", row.LastErrorCode);
                Assert.NotNull(row.DeadLetteredAtUtc);
                Assert.Equal(0, await delivery.SendDueAsync());
            }
        }
        finally { if (File.Exists(path)) File.Delete(path); }
    }

    [Fact]
    public async Task ScheduleUpdatesNotifyOncePerOccurrenceStatusWithStepSummary()
    {
        var path = Path.Combine(Path.GetTempPath(), "control-notification-test-" + Guid.NewGuid().ToString("N") + ".db");
        try
        {
            var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options;
            var clock = new ChangeableClock(DateTimeOffset.Parse("2026-10-18T00:00:00Z"));
            await using var db = new ControlDbContext(options);
            await db.Database.EnsureCreatedAsync();
            var hostId = Guid.NewGuid(); var serverId = Guid.NewGuid(); var scheduleId = Guid.NewGuid(); var runId = Guid.NewGuid();
            db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "fixture-host" });
            db.Servers.Add(new InventoryServer { ServerId = serverId, HostId = hostId, ServerKey = "fixture-server" });
            await db.SaveChangesAsync();
            var store = new InventoryStore(db, new InventoryWriteGate(), clock);
            var notifications = new NotificationService(db, DataProtectionProvider.Create("notification-fixture"), clock, store);
            await notifications.SaveDestinationAsync(Guid.NewGuid(), 0, "fixture", "https://hooks.example.com/secret", true);
            var running = new ScheduleOccurrenceReport { HostId = hostId, ServerId = serverId, ScheduleId = scheduleId,
                OperationId = runId, Status = ScheduleOccurrenceStatus.Running, UpdatedAtUtc = clock.GetUtcNow(),
                Steps = [new ScheduleStepReport("backup", 1, Guid.NewGuid(), OperationStatus.Running, null)] };
            await Stage(1, running);
            await Stage(2, running);
            Assert.Single(await db.NotificationEvents.ToArrayAsync());
            await Stage(3, running with { Status = ScheduleOccurrenceStatus.Succeeded, Steps =
                [new ScheduleStepReport("backup", 1, running.Steps[0].OperationId, OperationStatus.Succeeded, null)] });
            Assert.Equal(2, await db.NotificationEvents.CountAsync());
            var payloads = (await db.NotificationOutbox.ToArrayAsync()).Select(x => ControlJson.Deserialize<NotificationPayload>(x.PayloadJson)).ToArray();
            Assert.Contains(payloads, x => x.EventType == NotificationEventTypes.ScheduleStarted);
            var completed = Assert.Single(payloads, x => x.EventType == NotificationEventTypes.ScheduleCompleted);
            Assert.Equal(scheduleId, completed.ScheduleId);
            Assert.Equal(runId, completed.ScheduleRunId);
            Assert.Equal("Succeeded", Assert.Single(completed.ScheduleSteps!).Status);

            async Task Stage(long sequence, ScheduleOccurrenceReport report)
            {
                var body = JsonDocument.Parse(ControlJson.Serialize(report)).RootElement.Clone();
                await store.WriteAsync(async () => { await notifications.StageAgentEventAsync(hostId,
                    new AgentEvent(sequence, "ScheduleOccurrenceChanged", clock.GetUtcNow(), body)); return true; });
            }
        }
        finally { if (File.Exists(path)) File.Delete(path); }
    }

    private sealed class FixtureHandler : HttpMessageHandler
    {
        public List<string> EventIds { get; } = [];
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            EventIds.Add(request.Headers.GetValues("X-Control-Event-Id").Single());
            return Task.FromResult(new HttpResponseMessage(EventIds.Count == 1 ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK));
        }
    }

    private sealed class AlwaysFailsHandler : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(new HttpResponseMessage(HttpStatusCode.ServiceUnavailable));
    }

    private sealed class ChangeableClock(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;
        public override DateTimeOffset GetUtcNow() => current;
        public void Advance(TimeSpan duration) => current += duration;
    }
}
