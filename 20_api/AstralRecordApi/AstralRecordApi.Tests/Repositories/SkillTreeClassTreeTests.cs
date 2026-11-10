using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;
using static AstralRecordApi.Tests.Repositories.SkillTreeOperationRepositoryTests;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeClassTreeTests
{
    // Pluginの正規化JSONと同じ形。クラス別閲覧に使う項目だけを持つ。
    private const string Canonical = """
        {"rootNodeId":"root","nodes":[
        {"nodeId":"root","name":"起点","icon":"CAMPFIRE","pointType":"PASSIVE_POINT","pointCost":0,"unlockCondition":{"classId":null,"playerLevel":0},"effects":[]},
        {"nodeId":"s1","name":"剣の道","icon":"IRON_SWORD","pointType":"CLASS_POINT","pointCost":1,"unlockCondition":{"classId":"swordsman","classDisplayName":"ソードマン","playerLevel":0},
         "effects":[{"type":"status","status":"ATTACK","modifierType":"FLAT","value":1500,"displayName":"攻撃力","suffix":"","decimalPlaces":0},
                    {"type":"skill","skillId":"smash","displayName":"スマッシュ"},
                    {"type":"status","status":"CRITICAL_RATE","modifierType":"FLAT","value":0.5,"displayName":"会心率","suffix":"%","decimalPlaces":1}]},
        {"nodeId":"s2","name":"剣の奥義","pointType":"CLASS_POINT","pointCost":2,"unlockCondition":{"classId":"Swordsman","classDisplayName":"ソードマン","playerLevel":20},
         "effects":[{"type":"status","status":"MAX_HEALTH","modifierType":"SCALAR","value":0.1,"displayName":"最大HP","suffix":"","decimalPlaces":0},{"type":"status","status":"MP_REGEN","modifierType":"FLAT","value":1.005,"displayName":"MP回復","suffix":"","decimalPlaces":2},{"type":"status","status":"DEFENSE","modifierType":"FLAT","value":-3,"displayName":"防御力","suffix":"","decimalPlaces":0}]},
        {"nodeId":"h1","name":"狩人の目","icon":"BOW","pointType":"CLASS_POINT","pointCost":1,"unlockCondition":{"classId":"hunter","classDisplayName":"ハンター","playerLevel":0},"effects":[]}],
        "positions":[{"nodeId":"root","x":0,"y":0,"z":0},{"nodeId":"s1","x":1.5,"y":0,"z":-2},{"nodeId":"s2","x":3,"y":0,"z":-2},{"nodeId":"h1","x":-1,"y":0,"z":1}],
        "edges":["root->s1","s1->s2","root->h1"],"classes":{}}
        """;

    [Fact]
    public async Task ClassTrees_LegacyGenerationListsOnlySavedClassesAndProjectsReadOnlyNodes()
    {
        await using var f = await Fixture.CreateAsync();
        var generation = await AddGenerationAsync(f);
        AddClassProgress(f, " SwordSman ");
        var state = await f.Db.AccountSkillTreeStates.SingleAsync(x => x.AccountId == f.Account);
        f.Db.AccountSkillTreeUnlockedNodes.Add(new() { AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId,
            NodeId = "s1", CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = f.User, UpdatedBy = f.User });
        await f.Db.SaveChangesAsync();

        var index = await f.Repository.GetClassTreesAsync(f.Account, f.User, generation, null);
        Assert.NotNull(index);
        Assert.Empty(index.Nodes);
        var summary = Assert.Single(index.Classes);
        Assert.Equal("swordsman", summary.ClassId);
        Assert.Equal("ソードマン", summary.ClassName);
        Assert.Equal(["swordsman"], summary.ScopeClassIds);
        Assert.Equal(2, summary.NodeCount);
        Assert.Equal(1, summary.UnlockedCount);

        var sword = await f.Repository.GetClassTreesAsync(f.Account, f.User, generation, "SWORDSMAN");
        Assert.NotNull(sword);
        Assert.Equal("swordsman", sword.ClassId);
        var s1 = Assert.Single(sword.Nodes, n => n.NodeId == "s1");
        Assert.True(s1.IsUnlocked);
        Assert.Equal("IRON_SWORD", s1.Icon);
        Assert.Equal("CP", s1.PointType);
        Assert.Equal("ソードマン · 1 CP", s1.CostText);
        Assert.Equal(["攻撃力 +1,500", "会心率 +0.5%", "- スマッシュ"], s1.DisplayEffects);
        Assert.Equal("必要クラス: ソードマン", s1.RequirementText);
        var s2 = Assert.Single(sword.Nodes, n => n.NodeId == "s2");
        Assert.False(s2.IsUnlocked);
        Assert.Null(s2.Icon);
        // Java の %.2f と同じく 1.005 は 1.01 に丸める。負値に + は付けない。
        Assert.Equal(["最大HP +10%", "MP回復 +1.01", "防御力 -3"], s2.DisplayEffects);
        Assert.Equal("必要レベル: 20\n必要クラス: ソードマン", s2.RequirementText);
        Assert.DoesNotContain(sword.Nodes, n => n.NodeId is "root" or "h1");
        Assert.Equal(["root->s1", "s1->s2"], sword.Edges.Select(e => $"{e.SourceNodeId}->{e.TargetNodeId}").Order());

        var unknownClass = await f.Repository.GetClassTreesAsync(f.Account, f.User, generation, "paladin");
        Assert.NotNull(unknownClass);
        Assert.Null(unknownClass.ClassId);
        Assert.Empty(unknownClass.Nodes);
        var unownedClass = await f.Repository.GetClassTreesAsync(f.Account, f.User, generation, "hunter");
        Assert.NotNull(unownedClass);
        Assert.Null(unownedClass.ClassId);
        Assert.Empty(unownedClass.Nodes);
        Assert.Empty(unownedClass.Edges);
    }

    [Fact]
    public async Task ClassTrees_TraversesAllPrerequisitesForSavedClassWithoutOwnNodes()
    {
        await using var f = await Fixture.CreateAsync();
        const string classMetadata = """
            "classes":{
                "vanguard":{"name":"&a先鋒","unlockClassLevel":[{"classId":" Guard ","level":50},{"classId":"swordsman","level":50}]},
                "guard":{"name":"護衛","unlockClassLevel":[{"classId":"hunter","level":50}]},
                "swordsman":{"unlockClassLevel":[{"classId":"vanguard","level":50}]},
                "hunter":{}
            }
            """;
        var generation = await AddGenerationAsync(f, Canonical.Replace("\"classes\":{}", classMetadata, StringComparison.Ordinal));
        var account = await f.Db.Accounts.SingleAsync(x => x.Uuid == f.Account);
        account.ClassId = "Vanguard";
        AddClassProgress(f, "swordsman");
        await f.Db.SaveChangesAsync();

        var tree = await f.Repository.GetClassTreesAsync(f.Account, f.User, generation, "vanguard");
        Assert.NotNull(tree);
        Assert.Equal("vanguard", tree.ClassId);
        Assert.Equal(["swordsman", "vanguard"], tree.Classes.Select(entry => entry.ClassId));
        var summary = Assert.Single(tree.Classes, entry => entry.ClassId == "vanguard");
        Assert.Equal("先鋒", summary.ClassName);
        Assert.Equal(0, summary.NodeCount);
        Assert.Equal(0, summary.UnlockedCount);
        Assert.Equal(["vanguard", "guard", "swordsman", "hunter"], summary.ScopeClassIds);
        Assert.Equal(["h1", "s1", "s2"], tree.Nodes.Select(node => node.NodeId));
        Assert.Equal(["root->h1", "root->s1", "s1->s2"], tree.Edges.Select(e => $"{e.SourceNodeId}->{e.TargetNodeId}").Order());
    }

    [Fact]
    public async Task ClassTrees_RejectOtherOwnersUnknownGenerationsAndBadInput()
    {
        await using var f = await Fixture.CreateAsync();
        var generation = await AddGenerationAsync(f);
        Assert.Null(await f.Repository.GetClassTreesAsync(f.Account, Guid.NewGuid(), generation, null));
        Assert.Null(await f.Repository.GetClassTreesAsync(Guid.NewGuid(), f.User, generation, null));
        Assert.Null(await f.Repository.GetClassTreesAsync(f.Account, f.User, Hash("missing"), null));
        // 旧形式の最小定義（座標なし）は解釈できないため見つからない扱いにする。
        Assert.Null(await f.Repository.GetClassTreesAsync(f.Account, f.User, f.Generation, "swordsman"));

        var controller = new SkillTreeEditorController(f.Repository, new ConfigurationBuilder().Build());
        Assert.IsType<BadRequestResult>(await controller.GetClassTrees(f.Account, Guid.Empty, generation));
        Assert.IsType<BadRequestResult>(await controller.GetClassTrees(f.Account, f.User, "not-a-hash"));
        Assert.IsType<BadRequestResult>(await controller.GetClassTrees(f.Account, f.User, generation, "../hunter"));
        Assert.IsType<OkObjectResult>(await controller.GetClassTrees(f.Account, f.User, generation, "hunter"));
        // Plugin のクラスID規則（英数字・_・-）に合わせる。
        Assert.IsType<OkObjectResult>(await controller.GetClassTrees(f.Account, f.User, generation, "dark-knight"));
        Assert.IsType<BadRequestResult>(await controller.GetClassTrees(f.Account, f.User, generation, "-knight"));
        Assert.IsType<NotFoundResult>(await controller.GetClassTrees(f.Account, Guid.NewGuid(), generation));
    }

    private static void AddClassProgress(Fixture f, string classId) =>
        f.Db.AccountClassProgresses.Add(new()
        {
            AccountId = f.Account, ClassId = classId, Level = 1, Experience = 0,
            UpdatedAt = DateTime.UtcNow, UpdatedBy = f.User,
        });

    private static async Task<string> AddGenerationAsync(Fixture f, string canonical = Canonical)
    {
        var generation = Hash(canonical);
        f.Db.SkillTreeDefinitionGenerations.Add(new() { DefinitionGenerationId = generation, CanonicalSnapshotJson = canonical, CreatedAtUtc = DateTime.UtcNow });
        await f.Db.SaveChangesAsync();
        return generation;
    }
}
