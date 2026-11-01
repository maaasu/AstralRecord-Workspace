using System.Net;
using System.Text.RegularExpressions;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed partial class LiveTransportTests
{
    [Fact]
    public async Task ScheduleCardsKeepHistoryDiagnosticsAndManualRequestIdentitySeparate()
    {
        await using var fixture = await LiveFixture.StartAsync(enableProcessControl: true, enableSchedules: true);
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var serverId = Guid.NewGuid();
        var appliedId = Guid.Parse("10000000-0000-4000-8000-000000000001");
        var disabledId = Guid.Parse("20000000-0000-4000-8000-000000000002");
        var pendingId = Guid.Parse("30000000-0000-4000-8000-000000000003");
        var requestId = Guid.NewGuid();
        var rejectedId = Guid.NewGuid();
        var at = new DateTimeOffset(2026, 11, 1, 19, 0, 0, TimeSpan.Zero);
        using (var scope = fixture.Services.CreateScope())
        {
            var store = scope.ServiceProvider.GetRequiredService<InventoryStore>();
            var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
            var host = await store.CreateHostAsync(new("schedule-presentation", "Schedule presentation"), Guid.NewGuid(), "schedule-presentation");
            host.ResourcesJson = ControlJson.Serialize(fixture.Resources);
            host.CapabilitiesJson = ControlJson.Serialize(new[] { "P0.Inventory", "P1.Operations", "P1.LegacyGate", "P1.Velocity", "P3.Schedules" });
            await db.SaveChangesAsync();
            var config = new ServerConfig { ServerId = serverId, HostId = host.HostId, Revision = 1, Kind = ServerKind.Velocity,
                ManagedRootId = fixture.Resources.Roots.Single().Id, ExecutableId = fixture.Resources.Executables.Single().Id, ServerDirectory = "dev" };
            var create = new ServerCreateRequest(host.HostId, "dev", "Proxy", null, null, config);
            await store.CreateServerAsync(create, Guid.NewGuid(), ControlJson.Serialize(create), ControlJson.Serialize(config));
            ScheduleDefinition Definition(Guid id, bool enabled) => new() { ScheduleId = id, HostId = host.HostId, ServerId = serverId,
                Revision = 1, Enabled = enabled, Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = "0 4 * * *", TimeZoneId = "Asia/Tokyo" },
                Template = new() { ActionType = ScheduleActionType.Operation, Steps = [new() { StepKey = "restart", Kind = OperationKind.Restart }] } };
            await store.SaveScheduleAsync(Definition(appliedId, true), null, Guid.NewGuid());
            await store.SaveScheduleAsync(Definition(disabledId, false), null, Guid.NewGuid());
            host.AppliedInventoryRevision = host.DesiredInventoryRevision;
            await db.SaveChangesAsync();
            await store.SaveScheduleAsync(Definition(pendingId, true), null, Guid.NewGuid());
            var statuses = Enum.GetValues<ScheduleOccurrenceStatus>();
            for (var index = 0; index < statuses.Length; index++)
            {
                var report = new ScheduleOccurrenceReport { ScheduleId = appliedId, HostId = host.HostId, ServerId = serverId, Revision = 1,
                    OperationId = Guid.NewGuid(), ScheduledAtUtc = at.AddDays(-index), UpdatedAtUtc = at.AddDays(-index), Status = statuses[index],
                    Manual = index == 0, ErrorCode = statuses[index] == ScheduleOccurrenceStatus.Skipped ? "StateConflict" : null };
                db.ScheduleOccurrences.Add(new() { OperationId = report.OperationId, HostId = host.HostId, ScheduleId = appliedId,
                    ReportJson = ControlJson.Serialize(report), UpdatedAtUtc = report.UpdatedAtUtc });
            }
            db.ScheduleManualRequests.Add(new() { OperationId = requestId, HostId = host.HostId, ScheduleId = appliedId, Revision = 1, CreatedAtUtc = at, DefinitionJson = ControlJson.Serialize(Definition(appliedId, true)) });
            db.ScheduleManualRequests.Add(new() { OperationId = rejectedId, HostId = host.HostId, ScheduleId = appliedId, Revision = 1, CreatedAtUtc = at, RejectionCode = "StateConflict", DefinitionJson = ControlJson.Serialize(Definition(appliedId, true)) });
            await db.SaveChangesAsync();
        }

        using var response = await owner.GetAsync($"/Servers/{serverId}/Schedules");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var html = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
        Assert.Contains("2026/11/02 04:00:00 JST", html);
        Assert.Contains("実行日時（日本時間）", html);
        Assert.Contains("実行履歴はまだありません。", html);
        Assert.Contains("適用済み", html);
        Assert.Contains("反映待ち", html);
        Assert.Contains("無効・履歴を表示", html);
        Assert.Matches($"<details[^>]*id=\"schedule-{disabledId}\"[^>]*>", html);
        var disabledCard = Regex.Match(html, $"<details[^>]*id=\"schedule-{disabledId}\"[^>]*>").Value;
        Assert.DoesNotContain("open", disabledCard);
        Assert.Matches($"name=\"RequestKey\" value=\"{requestId}\"", html);
        Assert.Matches($"name=\"RequestKey\" value=\"{rejectedId}\"", html);
        Assert.Contains("同じ要求を照合", html);
        Assert.Contains("予約の診断情報", html);
        Assert.Contains("ViaVersion 更新に未対応です。対応した Agent / Runner を配置するか、ViaVersion 更新のチェックを外してください。", html);
        var history = Regex.Match(html, "<table[^>]*class=\"data-table schedule-history\"[\\s\\S]*?</table>").Value;
        Assert.NotEmpty(history);
        Assert.Equal(6, Regex.Matches(history, "<tr><td>").Count);
        Assert.Contains("<span>—</span>", history);
        var visibleHistory = Regex.Replace(history, "<details[\\s\\S]*?</details>", "");
        foreach (var status in Enum.GetNames<ScheduleOccurrenceStatus>()) Assert.DoesNotContain(status, visibleHistory);
        Assert.DoesNotContain("StateConflict", visibleHistory);
        Assert.DoesNotContain(" / ", visibleHistory);
        var runButtons = Regex.Matches(html, "<button[^>]*>今すぐ実行</button>").Select(match => match.Value).ToArray();
        Assert.Equal(3, runButtons.Length);
        Assert.DoesNotContain("disabled", runButtons[0]);
        Assert.Contains("disabled", runButtons[1]);
        Assert.Contains("disabled", runButtons[2]);

        // Optional review artifact from the real Razor response; no production state is used.
        var snapshotDirectory = Environment.GetEnvironmentVariable("CONTROL_UI_SNAPSHOT_DIR");
        if (!string.IsNullOrWhiteSpace(snapshotDirectory))
        {
            Directory.CreateDirectory(snapshotDirectory);
            var raw = await response.Content.ReadAsStringAsync();
            raw = Regex.Replace(raw, "href=\"/css/control.css[^\"]*\"", "href=\"control.css\"");
            raw = Regex.Replace(raw, "<script[\\s\\S]*?</script>", "");
            await File.WriteAllTextAsync(Path.Combine(snapshotDirectory, "schedules.html"), raw);
            var stylesheet = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../src/AstralRecordControl.Web/wwwroot/css/control.css"));
            File.Copy(stylesheet, Path.Combine(snapshotDirectory, "control.css"), overwrite: true);
        }
    }
}
