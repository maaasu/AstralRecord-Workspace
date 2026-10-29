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
    [Theory]
    [InlineData(ServerKind.Java)]
    [InlineData(ServerKind.Velocity)]
    public async Task DailyScheduleFormSavesWithEmptyAdvancedFieldsAndKeepsItsPreset(ServerKind kind)
    {
        await using var fixture = await LiveFixture.StartAsync(enableProcessControl: true, enableFileControl: true, enableSchedules: true);
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var serverId = Guid.NewGuid();
        using (var scope = fixture.Services.CreateScope())
        {
            var store = scope.ServiceProvider.GetRequiredService<InventoryStore>();
            var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
            var host = await store.CreateHostAsync(new("schedule-ui", "Schedule UI"), Guid.NewGuid(), "schedule-ui");
            host.ResourcesJson = ControlJson.Serialize(fixture.Resources);
            host.CapabilitiesJson = ControlJson.Serialize(new[] { "P0.Inventory", "P1.Operations", "P1.LegacyGate", "P1.Velocity", "P2.Files", "P3.Schedules", "P3.GeyserUpdate" });
            await db.SaveChangesAsync();
            var config = new ServerConfig { ServerId = serverId, HostId = host.HostId, Revision = 1, Kind = kind,
                ManagedRootId = fixture.Resources.Roots.Single(r => !r.BackupOnly).Id, ExecutableId = fixture.Resources.Executables.Single().Id, ServerDirectory = "dev" };
            var create = new ServerCreateRequest(host.HostId, "dev", "Dev", null, null, config);
            await store.CreateServerAsync(create, Guid.NewGuid(), ControlJson.Serialize(create), ControlJson.Serialize(config));
        }
        var path = $"/Servers/{serverId}/Schedules";
        using (var settingsPage = await owner.GetAsync($"/Servers/{serverId}"))
            Assert.Contains(path, await settingsPage.Content.ReadAsStringAsync());
        using var page = await owner.GetAsync(path);
        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
        var html = await page.Content.ReadAsStringAsync();
        Assert.Equal(kind == ServerKind.Velocity, html.Contains("value=\"DailyGeyserUpdate\"", StringComparison.Ordinal));
        var preset = kind == ServerKind.Velocity ? "DailyGeyserUpdate" : "DailyRestart";
        var values = new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = Input("__RequestVerificationToken"),
            ["RequestKey"] = Input("RequestKey"), ["ScheduleId"] = "", ["Revision"] = "",
            ["Enabled"] = "true", ["Preset"] = preset, ["DailyTime"] = "05:23", ["TimeZoneId"] = "Asia/Tokyo",
            ["TriggerKind"] = "Once", ["AtUtc"] = "", ["Seconds"] = "3600", ["Expression"] = "", ["StepsJson"] = ""
        };
        using var post = new HttpRequestMessage(HttpMethod.Post, path + "?handler=Save") { Content = new FormUrlEncodedContent(values) };
        post.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority));
        using var saved = await owner.SendAsync(post);
        Assert.Equal(HttpStatusCode.Redirect, saved.StatusCode);
        using var readScope = fixture.Services.CreateScope();
        var readStore = readScope.ServiceProvider.GetRequiredService<InventoryStore>();
        var definition = Assert.Single(await readStore.SchedulesAsync(serverId)).Definition;
        Assert.Equal("23 5 * * *", definition.Trigger.Expression);
        Assert.Equal(kind == ServerKind.Velocity ? OperationKind.UpdateGeyser : OperationKind.Restart, Assert.Single(definition.Template.Steps).Kind);
        using var edit = await owner.GetAsync(path + "?edit=" + definition.ScheduleId);
        var editHtml = await edit.Content.ReadAsStringAsync();
        Assert.Matches($"<option(?=[^>]*value=\"{preset}\")(?=[^>]*selected=\"selected\")[^>]*>", editHtml);

        string Input(string name)
        {
            var match = Regex.Match(html, $"<input[^>]*name=\"{name}\"[^>]*value=\"([^\"]*)\"");
            Assert.True(match.Success, name);
            return WebUtility.HtmlDecode(match.Groups[1].Value);
        }
    }
}
