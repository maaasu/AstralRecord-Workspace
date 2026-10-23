using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceLabTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "ar-balance-lab-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void DeathAndTimeoutRemainInAggregateDenominator()
    {
        var trials = JsonNode.Parse("""
            [{"outcome":"victory","elapsedTicks":20,"dps":100,"damageDealt":100,"damageTaken":0},
             {"outcome":"death","elapsedTicks":40,"dps":0,"damageDealt":0,"damageTaken":50},
             {"outcome":"timeout","elapsedTicks":60,"dps":20,"damageDealt":60,"damageTaken":20}]
            """)!.AsArray();
        var summary = BalanceLabRunner.Summarize(trials);
        Assert.Equal(3, summary["validTrials"]!.GetValue<int>());
        Assert.Equal(40, summary["meanDps"]!.GetValue<double>());
        Assert.Equal(2, summary["meanElapsedSeconds"]!.GetValue<double>());
        Assert.Equal(1.0 / 3, summary["successRate"]!.GetValue<double>());
        Assert.Null(summary["medianKillSeconds"]);
        Assert.Null(summary["p90KillSeconds"]);
    }

    [Fact]
    public void InvalidAndUnsupportedAreNotNormalTrialsAndEvenMedianUsesBothMiddleValues()
    {
        var trials = JsonNode.Parse("""
            [{"outcome":"victory","elapsedTicks":20,"dps":10},
             {"outcome":"death","elapsedTicks":40,"dps":30},
             {"outcome":"invalid"},{"outcome":"unsupported"}]
            """)!.AsArray();
        var summary = BalanceLabRunner.Summarize(trials);
        Assert.Equal(4, summary["totalTrials"]!.GetValue<int>());
        Assert.Equal(2, summary["validTrials"]!.GetValue<int>());
        Assert.Equal(20, summary["medianDps"]!.GetValue<double>());
        Assert.Equal(.5, summary["successRate"]!.GetValue<double>());
        Assert.Equal(1, summary["invalid"]!.GetValue<int>());
        var unavailable = BalanceLabRunner.Summarize(new JsonArray(new JsonObject { ["outcome"] = "unsupported" }));
        Assert.Null(unavailable["meanDps"]);
        Assert.Null(unavailable["successRate"]);
    }

    [Fact]
    public void ProposalIsAnIsolatedCopyWithTraceableDifferences()
    {
        var catalog = JsonNode.Parse("""
            {"masterHash":"original","masters":[{"path":"items/sword.yml","kind":"item","content":{"id":"sword","equipment":{"stats":[{"value":10}]}}}]}
            """)!.AsObject();
        var before = catalog.ToJsonString();
        var overrides = JsonNode.Parse("""
            [{"path":"items/sword.yml","content":{"id":"sword","equipment":{"stats":[{"value":20}]}}}]
            """)!.AsArray();
        var copy = BalanceLabCatalog.ApplyProposal(catalog, overrides, out var changes);
        Assert.Equal(before, catalog.ToJsonString());
        Assert.NotEqual(catalog["masterHash"]!.ToString(), copy["masterHash"]!.ToString());
        Assert.Equal("/equipment/stats/0/value", Assert.Single(changes)!["pointer"]!.ToString());
        overrides[0]!["content"]!["id"] = "other";
        Assert.Throws<ArgumentException>(() => BalanceLabCatalog.ApplyProposal(catalog, overrides, out _));
        overrides[0]!["path"] = "../outside.yml";
        Assert.Throws<ArgumentException>(() => BalanceLabCatalog.ApplyProposal(catalog, overrides, out _));
    }

    [Fact]
    public void WorkAndNumericBoundsRejectUnboundedExperiments()
    {
        var request = Scenario();
        BalanceLabRunner.Validate(request);
        request["trials"] = 501;
        Assert.Throws<ArgumentException>(() => BalanceLabRunner.Validate(request));
        request["trials"] = 500; request["maxSeconds"] = 600;
        Assert.Throws<ArgumentException>(() => BalanceLabRunner.Validate(request));
        request["trials"] = 1; request["seed"] = 1.5;
        Assert.Throws<ArgumentException>(() => BalanceLabRunner.Validate(request));
        request["seed"] = 1; request["builds"]![0]!["playerLevel"] = "NaN";
        Assert.Throws<ArgumentException>(() => BalanceLabRunner.Validate(request));
        Assert.Equal(-100, BalanceLabRunner.TrialSeed(-100, 0));
        Assert.Equal(BalanceLabRunner.TrialSeed(-100, 123), BalanceLabRunner.TrialSeed(-100, 123));
        request = Scenario();
        request["economy"] = JsonNode.Parse("""{"fromEnhanceLevel":"oops","dropRatePercent":"oops"}""");
        Assert.Throws<ArgumentException>(() => BalanceLabRunner.Validate(request));
    }

    [Fact]
    public async Task ExistingReaderCreatesStableFingerprintAndNeverWritesMasters()
    {
        var file = Path.Combine(_root, "40_filebase", "20.features.class", "v1.test.yml");
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        var source = Path.Combine(_root, "10_plugin", "AstralRecord", "src", "main", "java", "Sample.java");
        Directory.CreateDirectory(Path.GetDirectoryName(source)!);
        await File.WriteAllTextAsync(file, "# preserve\nid: test\nname: 試験職\n");
        await File.WriteAllTextAsync(source, "class Sample {}\n");
        var service = CreateCatalog();
        var bytes = await File.ReadAllBytesAsync(file);
        var first = await service.ReadAsync(CancellationToken.None);
        var second = await service.ReadAsync(CancellationToken.None);
        Assert.Equal(first.ToJsonString(), second.ToJsonString());
        Assert.Equal("class", first["masters"]![0]!["kind"]!.ToString());
        await File.AppendAllTextAsync(source, "// changed\n");
        var third = await service.ReadAsync(CancellationToken.None);
        Assert.NotEqual(first["sourceHash"]!.ToString(), third["sourceHash"]!.ToString());
        await File.AppendAllTextAsync(file, "expRate: 120\n");
        var fourth = await service.ReadAsync(CancellationToken.None);
        Assert.NotEqual(third["masterHash"]!.ToString(), fourth["masterHash"]!.ToString());
        Assert.Equal(bytes, (await File.ReadAllBytesAsync(file))[..bytes.Length]);
        Assert.False(Directory.Exists(Path.Combine(_root, "60_tool", "skilltree-editor", ".backups")));
    }

    [Fact]
    public async Task ProposalPassesExistingMasterTypeValidationWithoutWriting()
    {
        var directory = Path.Combine(_root, "40_filebase", "40.features.mob");
        Directory.CreateDirectory(directory);
        await File.WriteAllTextAsync(Path.Combine(directory, "docs.mob.YAMLスキーマ定義.md"), "| キー | 型 | 必須 | 説明 |\n|---|---|---|---|\n| `level` | number | yes | レベル |\n");
        var file = Path.Combine(directory, "v1.boar.yml");
        await File.WriteAllTextAsync(file, "id: boar\nlevel: 1\n");
        var service = CreateCatalog();
        var catalog = await service.ReadAsync(CancellationToken.None);
        var request = Scenario();
        request["overrides"] = JsonNode.Parse("""[{"path":"40.features.mob/v1.boar.yml","content":{"id":"boar","level":"bad"}}]""");
        await Assert.ThrowsAsync<ArgumentException>(() => service.ValidateProposalAsync(catalog, request, CancellationToken.None));
        Assert.Equal("id: boar\nlevel: 1\n", await File.ReadAllTextAsync(file));
    }

    private BalanceLabCatalog CreateCatalog()
    {
        var workspace = new WorkspacePaths(_root);
        var paths = new MasterDataPaths(workspace);
        var backups = new BackupService(workspace);
        var codec = new MasterDataCodec();
        var skillValidation = new ValidationService(workspace, new SchemaCatalog(workspace), new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        var validation = new MasterDataValidation(paths, codec, new MasterDataCatalog(paths), skillValidation);
        var masters = new MasterDataService(paths, workspace, codec, validation, new WorkspaceMutationGate(workspace), backups);
        return new BalanceLabCatalog(paths, masters, workspace, validation);
    }

    [Fact]
    public async Task CatalogReportsSkillSupportFromTheActualCombatResolver()
    {
        var directory = Path.Combine(_root, "40_filebase", "30.features.skill");
        Directory.CreateDirectory(directory);
        await File.WriteAllTextAsync(Path.Combine(directory, "v1.shot.yml"),
            "id: adventurer_quick_shot\nimplementationId: adventurer_quick_shot\nresourceType: ENERGY\n");
        await File.WriteAllTextAsync(Path.Combine(directory, "v1.other.yml"),
            "id: other\nimplementationId: other\nresourceType: ENERGY\n");
        var catalog = await CreateCatalog().ReadAsync(CancellationToken.None);
        Assert.Equal("adventurer_quick_shot", Assert.Single(catalog["supportedSkillIds"]!.AsArray())!.ToString());
    }

    [Fact]
    public async Task RunningJobRejectsAnotherStartAndCancellationHasNoNormalResult()
    {
        var directory = Path.Combine(_root, "40_filebase", "20.features.class");
        Directory.CreateDirectory(directory);
        for (var index = 0; index < 50; index++)
            await File.WriteAllTextAsync(Path.Combine(directory, $"v1.test{index}.yml"), $"id: test{index}\nbaseStats: []\n");
        using var jobs = new BalanceLabJobs(CreateCatalog());
        var job = jobs.Start(Scenario());
        var id = job["id"]!.ToString();
        Assert.Throws<InvalidOperationException>(() => jobs.Start(Scenario()));
        jobs.Cancel(id);
        var deadline = DateTime.UtcNow.AddSeconds(5);
        JsonObject state;
        do { await Task.Delay(10); state = jobs.Read(id); }
        while (state["status"]!.ToString() == "running" && DateTime.UtcNow < deadline);
        Assert.Equal("cancelled", state["status"]!.ToString());
        Assert.Null(state["result"]);
        Assert.Throws<FileNotFoundException>(() => jobs.Read("unknown"));
    }

    public static JsonObject Scenario() => JsonNode.Parse("""
        {"schemaVersion":1,"seed":123,"trials":1,"maxSeconds":60,"mode":"strict","builds":[{"id":"a","classId":"adventurer","playerLevel":1,"classLevel":1,"equipment":[],"skillIds":[]}],"enemy":{"mobId":"grassboar","level":1},"overrides":[]}
        """)!.AsObject();

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
