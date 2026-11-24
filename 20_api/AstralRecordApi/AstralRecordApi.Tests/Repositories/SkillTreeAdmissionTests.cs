using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeAdmissionTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task OldPluginCannotReadOrReplaceEnrolledState_EvenBeforeFirstBinding(bool legacy)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        if (legacy) { (await f.Db.AccountSkillTreeStates.SingleAsync()).DefinitionGenerationId = null; await f.Db.SaveChangesAsync(); }
        var states = new AccountSkillTreeStateRepository(f.Db);
        var controller = new AccountSkillTreeController(states, f.Repository) { ControllerContext = new() { HttpContext = new DefaultHttpContext() } };
        Assert.IsType<ConflictObjectResult>(await controller.GetByAccountId(f.Account));
        await Assert.ThrowsAsync<InvalidOperationException>(() => states.UpsertAsync(f.Account, new() { UpdatedBy = f.User, UnlockedNodes = [] }));
        var headers = controller.Request.Headers;
        headers["X-SkillTree-Generation"] = f.Generation;
        headers["X-SkillTree-Server"] = f.Server;
        headers["X-SkillTree-Boot"] = f.Boot.ToString();
        headers["X-SkillTree-Account-Session"] = f.Session.ToString();
        headers["X-SkillTree-Account-Token"] = f.Token;
        Assert.IsType<OkObjectResult>(await controller.GetByAccountId(f.Account));
        headers["X-SkillTree-Generation"] = new string('a', 64);
        Assert.IsType<ConflictObjectResult>(await controller.GetByAccountId(f.Account));
        f.Db.ChangeTracker.Clear();
        Assert.Single(await f.Db.AccountSkillTreeUnlockedNodes.ToListAsync());
    }

    [Fact]
    public async Task RepairInvalidState_RequiresCurrentGenerationAndSessionOnRetry()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var states = new AccountSkillTreeStateRepository(f.Db);
        var controller = new AccountSkillTreeController(states, f.Repository);
        var initial = await states.GetByAccountIdAsync(f.Account);
        Assert.Single(initial.UnlockedNodes);

        AccountSkillTreeInvalidStateRepairRequest CreateRequest(string generation) => new()
        {
            UserId = f.User,
            UpdatedBy = f.User,
            ExpectedVersion = initial.Version,
            ExpectedDefinitionGenerationId = generation,
            ServerId = f.Server,
            ServerSessionId = f.Boot,
            AccountSessionId = f.Session,
            AccountLeaseToken = f.Token,
        };

        var request = CreateRequest(f.Generation);
        var repaired = Assert.IsType<OkObjectResult>(await controller.RepairInvalidState(f.Account, request));
        var repairedState = Assert.IsType<AccountSkillTreeStateResponse>(repaired.Value);
        Assert.Empty(repairedState.UnlockedNodes);
        Assert.Equal(initial.Version + 1, repairedState.Version);
        Assert.Equal(f.Generation, repairedState.DefinitionGenerationId);
        Assert.IsType<OkObjectResult>(await controller.RepairInvalidState(f.Account, request));

        var wrongGeneration = SkillTreeOperationRepositoryTests.Hash("other-generation");
        Assert.IsType<ConflictResult>(await controller.RepairInvalidState(f.Account, CreateRequest(wrongGeneration)));
        await f.CloseAsync();
        Assert.IsType<ConflictResult>(await controller.RepairInvalidState(f.Account, request));
        Assert.Equal(repairedState.Version, (await states.GetByAccountIdAsync(f.Account)).Version);
    }
}
