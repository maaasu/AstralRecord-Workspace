using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class ServerRecordsControllerTests
{
    [Theory]
    [InlineData(0)]
    [InlineData(101)]
    public async Task Get_RejectsInvalidLimitWithoutReadingDatabase(int limit)
    {
        var repository = new Stub();
        var response = await new ServerRecordsController(repository).Get(limit);
        Assert.IsType<BadRequestObjectResult>(response.Result);
        Assert.Equal(0, repository.Calls);
    }

    [Fact]
    public async Task Get_RequiresApiAuthenticationAndFetchesAllTypesOnce()
    {
        Assert.Single(typeof(ServerRecordsController).GetCustomAttributes(typeof(AuthorizeAttribute), true));
        var repository = new Stub();
        var response = await new ServerRecordsController(repository).Get();
        Assert.IsType<OkObjectResult>(response.Result);
        Assert.Equal(1, repository.Calls); Assert.Equal(10, repository.Limit);
    }

    private sealed class Stub : IServerRecordsRepository
    {
        public int Calls { get; private set; }
        public int Limit { get; private set; }
        public Task<ServerRecordsResponse> GetAsync(int limit, CancellationToken cancellationToken = default)
        { Calls++; Limit = limit; return Task.FromResult(new ServerRecordsResponse(DateTimeOffset.UtcNow, [])); }
    }
}
