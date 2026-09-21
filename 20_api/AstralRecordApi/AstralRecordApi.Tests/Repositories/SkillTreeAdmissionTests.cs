using AstralRecordApi.Controllers;
using AstralRecordApi.Data;
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
        await using var masters = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(f.Connection!).Options);
        var states = new AccountSkillTreeStateRepository(f.Db, masters);
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
}
