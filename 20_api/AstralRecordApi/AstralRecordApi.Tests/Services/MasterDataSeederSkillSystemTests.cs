using System.Reflection;
using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Options;
using AstralRecordApi.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class MasterDataSeederSkillSystemTests
{
    [Fact]
    public void ParseYamlObject_PreservesYamlStructureAndAmpersandScalars()
    {
        const string rawText = """
            schemaVersion: 1
            id: market_expansion_token_alpha
            category: currency
            name: &aマーケット拡張トークンα
            lore:
              - "&eこのトークンの反映上限: &f+6枠"
            description: |
              key: &ablock
              quote: "x # y"
            "foo # key": &aquoted
            flow: [ &aflow, "&gflow" ]
            """;

        var root = ParseYamlObject(rawText);
        var lore = root["lore"]?.AsArray();
        var flow = root["flow"]?.AsArray();

        Assert.NotNull(lore);
        Assert.NotNull(flow);
        Assert.Equal("&aマーケット拡張トークンα", root["name"]?.GetValue<string>());
        Assert.Equal(
            "&eこのトークンの反映上限: &f+6枠",
            lore[0]?.GetValue<string>());
        Assert.Equal("key: &ablock\nquote: \"x # y\"\n", root["description"]?.GetValue<string>());
        Assert.Equal("&aquoted", root["foo # key"]?.GetValue<string>());
        Assert.Equal("&aflow", flow[0]?.GetValue<string>());
        Assert.Equal("&gflow", flow[1]?.GetValue<string>());
    }

    /// <summary>
    /// 設計入力: 00_docs/50_Filebase設計書/feature/20-class.md
    /// 検証契約: class の shortName は色・装飾コードを除いて ASCII 英大文字3文字だけを受け付ける。
    /// </summary>
    [Theory]
    [InlineData("&aMAG")]
    [InlineData("&bSWD")]
    public void ValidateClassShortName_AcceptsUppercaseEnglishThreeLetters(string shortName)
    {
        InvokeValidateClassShortName(shortName);
    }

    /// <summary>
    /// 設計入力: 00_docs/50_Filebase設計書/feature/20-class.md
    /// 検証契約: class の shortName は小文字・数字・記号を含む値を拒否する。
    /// </summary>
    [Theory]
    [InlineData("&aMag")]
    [InlineData("&aMA1")]
    public void ValidateClassShortName_RejectsNonUppercaseEnglishThreeLetters(string shortName)
    {
        var error = Assert.Throws<TargetInvocationException>(() => InvokeValidateClassShortName(shortName));

        Assert.IsType<InvalidOperationException>(error.InnerException);
    }

    /// <summary>
    /// 設計入力: 00_docs/50_Filebase設計書/feature/30-skill.md
    /// 検証契約: 有効skillから自動生成される仮想gem IDは、loot/quest等のitem必須参照として解決できる。
    /// </summary>
    [Fact]
    public async Task ValidateReferencesAsync_ResolvesGeneratedSkillGemItemReference()
    {
        await using var fixture = await Fixture.CreateAsync();
        var skill = fixture.AddEntry("skill", "adventurer_smash", null, "{}");
        fixture.Db.References.Add(new MasterDataReferenceEntity
        {
            ReferenceId = Guid.NewGuid(),
            FromEntryId = skill.EntryId,
            FromMasterType = "loot",
            FromMasterId = "gem_drop",
            ReferenceType = "item",
            ReferenceIdValue = "00_skill_gem_adventurer_smash",
            ReferencePath = "$.entries[0].ref",
            IsRequired = true,
            CreatedAt = fixture.Now,
            UpdatedAt = fixture.Now,
            CreatedBy = fixture.SystemUser,
            UpdatedBy = fixture.SystemUser,
        });
        await fixture.Db.SaveChangesAsync();

        await InvokeAsync(fixture.Seeder, "ValidateReferencesAsync", new List<string>(), CancellationToken.None);
    }

    /// <summary>
    /// 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_0-概要.md
    /// 検証契約: Plugin組み込み通貨astraldはFilebase item定義なしでも必須参照として解決できる。
    /// </summary>
    [Fact]
    public async Task ValidateReferencesAsync_ResolvesBuiltInAstraldCurrencyItemReference()
    {
        await using var fixture = await Fixture.CreateAsync();
        var shop = fixture.AddEntry("shop", "astrald_shop", null, "{}");
        fixture.Db.References.Add(new MasterDataReferenceEntity
        {
            ReferenceId = Guid.NewGuid(),
            FromEntryId = shop.EntryId,
            FromMasterType = "shop",
            FromMasterId = "astrald_shop",
            ReferenceType = "item",
            ReferenceIdValue = "astrald",
            ReferencePath = "$.items[0].requiredItems[0].itemId",
            IsRequired = true,
            CreatedAt = fixture.Now,
            UpdatedAt = fixture.Now,
            CreatedBy = fixture.SystemUser,
            UpdatedBy = fixture.SystemUser,
        });
        await fixture.Db.SaveChangesAsync();

        await InvokeAsync(fixture.Seeder, "ValidateReferencesAsync", new List<string>(), CancellationToken.None);
    }

    /// <summary>
    /// 設計入力: 40_filebase/10.features.item/orb/docs.orb.YAMLスキーマ定義.md
    /// 検証契約: オーブが参照する共通エンチャントマスタが存在しない場合、Seeder は必須参照エラーにする。
    /// </summary>
    [Fact]
    public async Task ValidateReferencesAsync_RejectsMissingOrbEnchantMasterReference()
    {
        await using var fixture = await Fixture.CreateAsync();
        var orb = fixture.AddEntry("item", "broken_enchant_orb", "orb", "{}");
        fixture.Db.References.Add(new MasterDataReferenceEntity
        {
            ReferenceId = Guid.NewGuid(),
            FromEntryId = orb.EntryId,
            FromMasterType = "item",
            FromMasterId = "broken_enchant_orb",
            ReferenceType = "enchant",
            ReferenceIdValue = "missing_enchant",
            ReferencePath = "$.orb.effect.enchantMasterId",
            IsRequired = true,
            CreatedAt = fixture.Now,
            UpdatedAt = fixture.Now,
            CreatedBy = fixture.SystemUser,
            UpdatedBy = fixture.SystemUser,
        });
        await fixture.Db.SaveChangesAsync();

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            InvokeAsync(fixture.Seeder, "ValidateReferencesAsync", new List<string>(), CancellationToken.None));

        Assert.Contains(
            "item:broken_enchant_orb -> enchant:missing_enchant",
            error.Message,
            StringComparison.Ordinal);
    }

    /// <summary>
    /// 設計入力: 40_filebase/60.features.world/docs.world.YAMLスキーマ定義.md
    /// 検証契約: world.requiredItemId が参照する currency item を許可する。
    /// </summary>
    [Fact]
    public async Task ValidateWorldRequiredItemsAsync_AllowsCurrencyItemReference()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.AddEntry("item", "eriva_waystone", "currency", """
            {
              "schemaVersion": 1,
              "id": "eriva_waystone",
              "category": "currency",
              "name": "Eriva Waystone",
              "icon": "LODESTONE",
              "rarity": "COMMON"
            }
            """);
        fixture.AddEntry("world", "eriva_supercontinent", null, """
            {
              "schemaVersion": 1,
              "id": "eriva_supercontinent",
              "displayName": "エリヴァ超大陸",
              "worldType": "OVERWORLD",
              "baseWorldPath": "plugins/AstralRecord/worlds/overworld/eriva_supercontinent",
              "instanceRootPath": "plugins/AstralRecord/_world_instances/eriva_supercontinent",
              "spawnLocation": { "x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 },
              "description": "Eriva",
              "requiredItemId": { "ref": "item:eriva_waystone" }
            }
            """);
        await fixture.Db.SaveChangesAsync();

        await InvokeAsync(fixture.Seeder, "ValidateWorldRequiredItemsAsync", CancellationToken.None);
    }

    /// <summary>
    /// 設計入力: 40_filebase/60.features.world/docs.world.YAMLスキーマ定義.md
    /// 検証契約: world.requiredItemId が currency 以外の item を参照した場合、Seeder は拒否する。
    /// </summary>
    [Fact]
    public async Task ValidateWorldRequiredItemsAsync_RejectsNonCurrencyItemReference()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.AddEntry("item", "eriva_waystone", "material", """
            {
              "schemaVersion": 1,
              "id": "eriva_waystone",
              "category": "material",
              "name": "Eriva Waystone",
              "icon": "LODESTONE",
              "rarity": "COMMON"
            }
            """);
        fixture.AddEntry("world", "eriva_supercontinent", null, """
            {
              "schemaVersion": 1,
              "id": "eriva_supercontinent",
              "displayName": "エリヴァ超大陸",
              "worldType": "OVERWORLD",
              "baseWorldPath": "plugins/AstralRecord/worlds/overworld/eriva_supercontinent",
              "instanceRootPath": "plugins/AstralRecord/_world_instances/eriva_supercontinent",
              "spawnLocation": { "x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 },
              "description": "Eriva",
              "requiredItemId": { "ref": "item:eriva_waystone" }
            }
            """);
        await fixture.Db.SaveChangesAsync();

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            InvokeAsync(fixture.Seeder, "ValidateWorldRequiredItemsAsync", CancellationToken.None));

        Assert.Contains("currency item", error.Message, StringComparison.Ordinal);
    }

    /// <summary>
    /// 設計入力: 40_filebase/10.features.item/docs.item.YAMLスキーマ定義.md
    /// 検証契約: シジルmodifierは共有カタログに存在するstatus IDだけを許可する。
    /// </summary>
    [Fact]
    public async Task ValidateSkillSystemMastersAsync_RejectsUnknownSigilStatus()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.AddEntry("item", "bad_sigil", "sigil", """
            {
              "schemaVersion": 1,
              "id": "bad_sigil",
              "category": "sigil",
              "name": "Bad Sigil",
              "icon": "STONE",
              "rarity": "COMMON",
              "sigil": {
                "equipGroupId": "bad_group",
                "modifiers": [{ "status": "UNKNOWN_STATUS", "value": 1.0 }]
              }
            }
            """);
        await fixture.Db.SaveChangesAsync();

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            InvokeAsync(fixture.Seeder, "ValidateSkillSystemMastersAsync", CancellationToken.None));
    }

    /// <summary>
    /// 設計入力: 00_docs/50_Filebase設計書/feature/30-skill.md
    /// 検証契約: DTOの既定値へ暗黙fallbackせず、各skill masterがgem rarityを明示する。
    /// </summary>
    [Fact]
    public async Task ValidateSkillSystemMastersAsync_RejectsMissingGemDefinition()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.AddEntry("skill", "missing_gem", null, """
            {
              "schemaVersion": 1,
              "id": "missing_gem",
              "type": "SKILL",
              "implementationId": "missing_gem",
              "name": "Missing Gem",
              "maxLevel": 1
            }
            """);
        await fixture.Db.SaveChangesAsync();

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            InvokeAsync(fixture.Seeder, "ValidateSkillSystemMastersAsync", CancellationToken.None));
    }

    private static async Task InvokeAsync(object target, string methodName, params object[] arguments)
    {
        MethodInfo method = target.GetType().GetMethod(
            methodName,
            BindingFlags.Instance | BindingFlags.NonPublic
        ) ?? throw new MissingMethodException(target.GetType().FullName, methodName);
        await ((Task?)method.Invoke(target, arguments)
            ?? throw new InvalidOperationException($"{methodName} did not return Task"));
    }

    private static JsonObject ParseYamlObject(string rawText)
    {
        MethodInfo method = typeof(MasterDataSeeder).GetMethod(
            "ParseYamlObject",
            BindingFlags.Static | BindingFlags.NonPublic
        ) ?? throw new MissingMethodException(typeof(MasterDataSeeder).FullName, "ParseYamlObject");

        return (JsonObject)(method.Invoke(null, [rawText, "test.yml"])
            ?? throw new InvalidOperationException("ParseYamlObject returned null"));
    }

    private static void InvokeValidateClassShortName(string shortName)
    {
        MethodInfo method = typeof(MasterDataSeeder).GetMethod(
            "ValidateClassShortName",
            BindingFlags.Static | BindingFlags.NonPublic
        ) ?? throw new MissingMethodException(typeof(MasterDataSeeder).FullName, "ValidateClassShortName");

        var root = ParseYamlObject($"shortName: \"{shortName}\"");
        method.Invoke(
            null,
            [root, "test_class", "test.yml", new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)]);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection;
        public MasterDataDbContext Db { get; }
        public MasterDataSeeder Seeder { get; }
        public Guid SystemUser { get; } = Guid.NewGuid();
        public DateTime Now { get; } = DateTime.UtcNow;
        private Guid SourceId { get; } = Guid.NewGuid();

        private Fixture(SqliteConnection connection, MasterDataDbContext db)
        {
            this.connection = connection;
            Db = db;
            Seeder = new MasterDataSeeder(
                db,
                Microsoft.Extensions.Options.Options.Create(new FileDatabaseOptions()),
                Microsoft.Extensions.Options.Options.Create(new MasterDataOptions { SystemUserId = SystemUser }),
                NullLogger<MasterDataSeeder>.Instance
            );
        }

        public static async Task<Fixture> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<MasterDataDbContext>()
                .UseSqlite(connection)
                .Options;
            var db = new MasterDataDbContext(options);
            await db.Database.EnsureCreatedAsync();
            var fixture = new Fixture(connection, db);
            db.Sources.Add(new MasterDataSourceEntity
            {
                SourceId = fixture.SourceId,
                SourceKey = "test",
                SourcePath = "test",
                SourceKind = "yaml",
                IsEnabled = true,
                CreatedAt = fixture.Now,
                UpdatedAt = fixture.Now,
                CreatedBy = fixture.SystemUser,
                UpdatedBy = fixture.SystemUser,
            });
            await db.SaveChangesAsync();
            return fixture;
        }

        public MasterDataEntryEntity AddEntry(
            string masterType,
            string masterId,
            string? category,
            string payloadJson)
        {
            var entry = new MasterDataEntryEntity
            {
                EntryId = Guid.NewGuid(),
                SourceId = SourceId,
                MasterType = masterType,
                MasterId = masterId,
                Category = category,
                SchemaVersion = 1,
                SourceFilePath = $"test/{masterId}.yml",
                SourceFileHash = new string('0', 64),
                PayloadJson = payloadJson,
                PayloadVersion = 1,
                EffectiveFrom = Now,
                CreatedAt = Now,
                UpdatedAt = Now,
                CreatedBy = SystemUser,
                UpdatedBy = SystemUser,
            };
            Db.Entries.Add(entry);
            return entry;
        }

        public async ValueTask DisposeAsync()
        {
            await Db.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
