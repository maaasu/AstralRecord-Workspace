using System.Net;
using System.Text.RegularExpressions;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed partial class LiveTransportTests
{
    [Fact]
    public async Task HistoryFormsRequireCsrfAndConfirmationThenRemoveMultipleResultFiles()
    {
        await using var fixture = await LiveFixture.StartAsync();
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var ids = new[] { Guid.NewGuid(), Guid.NewGuid() };
        var operationIds = new[] { Guid.NewGuid(), Guid.NewGuid() };
        using (var scope = fixture.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
            var hostId = Guid.NewGuid();
            db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "history-fixture", DisplayName = "History fixture" });
            for (var i = 0; i < ids.Length; i++)
            {
                var reservationId = Guid.NewGuid();
                db.Workflows.Add(new ControlWorkflow { WorkflowId = ids[i], CreatedAtUtc = DateTimeOffset.UtcNow.AddMinutes(-i), Status = WorkflowStatus.Succeeded });
                db.ReservationLeases.Add(new ControlReservationLease { ReservationId = reservationId, WorkflowId = ids[i], Status = ReservationStatus.Released });
                db.Operations.Add(new ControlOperation { OperationId = operationIds[i], IdempotencyKey = Guid.NewGuid(), HostId = hostId, WorkflowId = ids[i], ReservationId = reservationId, Kind = OperationKind.Start, Status = OperationStatus.Succeeded, FinishedAtUtc = DateTimeOffset.UtcNow, ResultJson = "{\"message\":\"history-detail-fixture\"}" });
            }
            await db.SaveChangesAsync();
        }
        var resultRoot = Path.Combine(fixture.Root, "Web", "operation-results");
        Directory.CreateDirectory(resultRoot);
        foreach (var id in operationIds) await File.WriteAllTextAsync(Path.Combine(resultRoot, id.ToString("N") + ".json"), "{}");
        var detail = await owner.GetStringAsync("/Workflows?workflowId=" + ids[0]);
        Assert.Contains("history-detail-fixture", detail);
        Assert.Contains(operationIds[0].ToString(), detail);

        using (var missingCsrf = await Post("Delete", false, null)) Assert.Equal(HttpStatusCode.BadRequest, missingCsrf.StatusCode);
        var list = await owner.GetStringAsync("/Workflows");
        var csrf = WebUtility.HtmlDecode(Regex.Match(list, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
        using (var confirmation = await Post("ConfirmDelete", false, csrf))
        {
            Assert.Equal(HttpStatusCode.OK, confirmation.StatusCode);
            Assert.Contains("name=\"confirmDelete\"", await confirmation.Content.ReadAsStringAsync());
        }
        using (var unconfirmed = await Post("Delete", false, csrf)) Assert.Equal(HttpStatusCode.OK, unconfirmed.StatusCode);
        Assert.All(operationIds, id => Assert.True(File.Exists(Path.Combine(resultRoot, id.ToString("N") + ".json"))));
        using (var deleted = await Post("Delete", true, csrf)) Assert.Equal(HttpStatusCode.Redirect, deleted.StatusCode);
        Assert.All(operationIds, id => Assert.False(File.Exists(Path.Combine(resultRoot, id.ToString("N") + ".json"))));
        using (var hidden = await owner.GetAsync("/Workflows?workflowId=" + ids[0])) Assert.Equal(HttpStatusCode.NotFound, hidden.StatusCode);
        using (var hiddenOperation = await owner.GetAsync("/api/control/v1/operations/" + operationIds[0])) Assert.Equal(HttpStatusCode.NotFound, hiddenOperation.StatusCode);
        using var verify = fixture.Services.CreateScope();
        var verificationDb = verify.ServiceProvider.GetRequiredService<ControlDbContext>();
        Assert.Equal(2, await verificationDb.Workflows.CountAsync(x => x.HiddenAtUtc != null));
        Assert.Equal(2, await verificationDb.Operations.CountAsync());

        async Task<HttpResponseMessage> Post(string handler, bool confirmed, string? token)
        {
            var values = ids.Select(id => new KeyValuePair<string, string>("workflowIds", id.ToString())).ToList();
            if (token != null) values.Add(new("__RequestVerificationToken", token));
            if (confirmed) values.Add(new("confirmDelete", "true"));
            using var request = new HttpRequestMessage(HttpMethod.Post, "/Workflows?handler=" + handler) { Content = new FormUrlEncodedContent(values) };
            request.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority));
            return await owner.SendAsync(request);
        }
    }
}
