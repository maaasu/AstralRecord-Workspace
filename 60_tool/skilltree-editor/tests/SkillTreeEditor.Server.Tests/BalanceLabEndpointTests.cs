using System.Net;
using System.Net.Http.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using SkillTreeEditor.Server.Endpoints;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceLabEndpointTests : IDisposable
{
    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"balance-lab-endpoint-{Guid.NewGuid():N}");

    [Theory]
    [InlineData("GET")]
    [InlineData("DELETE")]
    public async Task MissingJobReturnsActionable404WithoutInvokingExceptionHandler(string method)
    {
        Directory.CreateDirectory(_workspace);
        var paths = new WorkspacePaths(_workspace);
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ContentRootPath = _workspace });
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Services.AddProblemDetails();
        builder.Services.AddSingleton(paths);
        builder.Services.AddSingleton<MasterDataPaths>();
        builder.Services.AddSingleton<BackupService>();
        builder.Services.AddSingleton<MasterDataCodec>();
        builder.Services.AddSingleton<MasterDataCatalog>();
        builder.Services.AddSingleton<SchemaCatalog>();
        builder.Services.AddSingleton<PluginConfigService>();
        builder.Services.AddSingleton<MasterTagCatalog>();
        builder.Services.AddSingleton<ValidationService>();
        builder.Services.AddSingleton<MasterDataValidation>();
        builder.Services.AddSingleton<WorkspaceMutationGate>();
        builder.Services.AddSingleton<MasterDataService>();
        builder.Services.AddSingleton<BalanceLabCatalog>();
        builder.Services.AddSingleton<BalanceLabJobs>();
        await using var app = builder.Build();
        var exceptionCount = 0;
        app.UseMiddleware<LocalEditorRequestBoundaryMiddleware>();
        app.UseExceptionHandler(handler => handler.Run(context =>
        {
            Interlocked.Increment(ref exceptionCount);
            return Results.Problem(statusCode: 500).ExecuteAsync(context);
        }));
        app.MapBalanceLabEndpoints();
        await app.StartAsync();
        try
        {
            var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
            using var client = new HttpClient { BaseAddress = new Uri(address) };
            using var request = new HttpRequestMessage(new HttpMethod(method), "/api/balance-lab/jobs/expired-result");
            using var response = await client.SendAsync(request);

            Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
            Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
            var problem = await response.Content.ReadFromJsonAsync<JsonObject>();
            Assert.Equal(404, problem!["status"]!.GetValue<int>());
            Assert.Contains("再起動または保存上限", problem["title"]!.GetValue<string>());
            Assert.Contains("再実行", problem["title"]!.GetValue<string>());
            Assert.Equal(0, Volatile.Read(ref exceptionCount));
        }
        finally { await app.StopAsync(); }
    }

    public void Dispose()
    {
        if (Directory.Exists(_workspace)) Directory.Delete(_workspace, recursive: true);
    }
}
