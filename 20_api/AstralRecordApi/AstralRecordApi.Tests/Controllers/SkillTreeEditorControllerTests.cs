using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class SkillTreeEditorControllerTests
{
    [Fact]
    public async Task MigrationEndpointsRequireDedicatedCredential()
    {
        await using var fixture = await Repositories.SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var controller = CreateController(fixture);

        Assert.IsType<UnauthorizedResult>(await controller.GetRuntime(fixture.Server));
        controller.Request.Headers["X-SkillTree-Runtime-Key"] = "runtime-secret";
        Assert.IsType<UnauthorizedResult>(await controller.GetRuntime(fixture.Server));
        controller.Request.Headers["X-SkillTree-Migration-Key"] = "migration-secret";
        Assert.IsType<OkObjectResult>(await controller.GetRuntime(fixture.Server));
    }

    [Fact]
    public async Task MigrationEndpointsSeparateBadRequestNotFoundAndConflict()
    {
        await using var fixture = await Repositories.SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var controller = CreateController(fixture);
        controller.Request.Headers["X-SkillTree-Migration-Key"] = "migration-secret";

        Assert.IsType<NotFoundResult>(await controller.GetRuntime("unknown"));
        Assert.IsType<BadRequestResult>(await controller.GetMigrationCandidates(
            fixture.Server, fixture.Boot, fixture.Generation, 0, 100));
        Assert.IsType<BadRequestResult>(await controller.GetMigrationCandidates(
            $" {fixture.Server}", fixture.Boot, fixture.Generation, 1, 100));
        Assert.IsType<BadRequestResult>(await controller.GetMigrationCandidates(
            new string('s', 65), fixture.Boot, fixture.Generation, 1, 100));
        Assert.IsType<ConflictResult>(await controller.GetMigrationCandidates(
            fixture.Server, Guid.NewGuid(), fixture.Generation, 1, 100));
        Assert.IsType<BadRequestResult>(await controller.MigrateBatch(
            fixture.Server,
            fixture.Boot,
            new SkillTreeMigrationBatchRequest { Mode = "INVALID", Items = [] }));
        Assert.IsType<BadRequestResult>(await controller.Migrate(
            fixture.Server,
            fixture.Account,
            fixture.Boot,
            new SkillTreeMigrationRequest
            {
                OperationId = Guid.NewGuid(),
                ExpectedStateVersion = 1,
                FromGenerationId = fixture.Generation,
                ToGenerationId = fixture.Generation,
                LegacyBaselineNodeIds = ["root"],
                RemoveNodeIds = [],
                ConsumedClassAssignments = [null!],
            }));
    }

    [Fact]
    public async Task MigrationCredentialCannotReuseRuntimeOrSharedApiKey()
    {
        await using var fixture = await Repositories.SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var controller = CreateController(fixture, "runtime-secret");
        controller.Request.Headers["X-SkillTree-Migration-Key"] = "runtime-secret";
        Assert.IsType<UnauthorizedResult>(await controller.GetRuntime(fixture.Server));

        controller = CreateController(fixture, "shared-secret");
        controller.Request.Headers["X-SkillTree-Migration-Key"] = "shared-secret";
        Assert.IsType<UnauthorizedResult>(await controller.GetRuntime(fixture.Server));
    }

    private static SkillTreeEditorController CreateController(
        Repositories.SkillTreeOperationRepositoryTests.Fixture fixture,
        string migrationKey = "migration-secret")
    {
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ApiKey:Key"] = "shared-secret",
            ["SkillTreeRuntime:Key"] = "runtime-secret",
            ["SkillTreeRuntime:MigrationKey"] = migrationKey,
        }).Build();
        return new(fixture.Repository, configuration)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };
    }
}
