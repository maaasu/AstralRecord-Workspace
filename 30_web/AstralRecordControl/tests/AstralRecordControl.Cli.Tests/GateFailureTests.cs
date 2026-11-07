using System.Net;
using System.Text;
using AstralRecordControl.Cli;
using Xunit;

namespace AstralRecordControl.Cli.Tests;

public sealed class GateFailureTests
{
    private const string GatePath = "/api/control/v1/reservations";

    [Theory]
    [InlineData(409, "ReservationConflict", "履歴・復旧")]
    [InlineData(409, "StateConflict", "稼働状態")]
    [InlineData(409, "StaleRevision", "希望版と適用版")]
    [InlineData(409, "RecoveryRequired", "点検")]
    [InlineData(503, "HostOffline", "Agent の状態")]
    public async Task GateFailuresUseKnownCodesAndLocalAdviceOnly(int status, string code, string advice)
    {
        var body = $$"""{"errorCode":"{{code}}","detail":"fixture-secret-detail","traceId":"fixture-secret-trace"}""";
        var exception = await ReadFailureAsync(status, body);
        Assert.Equal((HttpStatusCode)status, exception.StatusCode);
        Assert.Equal($"HTTP_{status} {code}", ControlCli.GetSafeFailureCode(exception));
        var message = ControlCli.GetSafeFailureMessage(exception);
        Assert.Contains(advice, message);
        Assert.DoesNotContain("fixture-secret", message);
    }

    [Theory]
    [InlineData("""{"errorCode":"fixture-secret-unknown"}""")]
    [InlineData("""{"errorCode":"reservationConflict"}""")]
    [InlineData("""{"errorCode":"ReservationConflict","errorCode":"StateConflict"}""")]
    [InlineData("""{"errorCode":5}""")]
    [InlineData("""{"title":"ReservationConflict"}""")]
    [InlineData("{\"errorCode\":\"ReservationConflict\"")]
    public async Task InvalidOrUnknownProblemDetailsFallBackToHttpStatus(string body)
    {
        var exception = await ReadFailureAsync(409, body);
        Assert.Equal("HTTP_409", ControlCli.GetSafeFailureCode(exception));
        Assert.DoesNotContain("fixture-secret", ControlCli.GetSafeFailureMessage(exception));
    }

    [Fact]
    public async Task NonGateWrongMediaTypeWrongStatusAndOversizedResponsesKeepGenericDiagnostics()
    {
        const string body = """{"errorCode":"ReservationConflict"}""";
        Assert.Equal("HTTP_409", ControlCli.GetSafeFailureCode(await ReadFailureAsync(409, body, path: "/api/control/v1/auth/cli-token")));
        Assert.Equal("HTTP_409", ControlCli.GetSafeFailureCode(await ReadFailureAsync(409, body, mediaType: "application/json")));
        Assert.Equal("HTTP_400", ControlCli.GetSafeFailureCode(await ReadFailureAsync(400, body)));
        Assert.Equal("HTTP_409", ControlCli.GetSafeFailureCode(await ReadFailureAsync(409, body + new string(' ', 4096))));
    }

    private static async Task<HttpRequestException> ReadFailureAsync(int status, string body, string mediaType = "application/problem+json", string path = GatePath)
    {
        using var client = new HttpClient(new ResponseHandler((HttpStatusCode)status, body, mediaType)) { BaseAddress = new Uri("https://control.example/") };
        return await Assert.ThrowsAnyAsync<HttpRequestException>(() => ControlCli.SendResponseAsync(client, HttpMethod.Post, path, null, null));
    }

    private sealed class ResponseHandler(HttpStatusCode status, string body, string mediaType) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, mediaType) });
    }
}
