using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Text;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class GeyserHeadRepositoryTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.01-取得系.md
    /// 章・見出し: # 99_3.01-取得系 > ### Geyser ヘッド起動データ取得
    /// 検証契約: item/class/skill/mob の PLAYER_HEAD iconTexture と、Mob level profile の有効上書きを重複なく返し、NPC skin.texture と論理削除ユーザーを除外する。
    /// </summary>
    [Fact]
    public async Task GetHeadsAsync_ReturnsPlayerHeadTexturesAndActivePlayerUuidsOnly()
    {
        await using var masterConnection = new SqliteConnection("Data Source=:memory:");
        await masterConnection.OpenAsync();
        await using var userConnection = new SqliteConnection("Data Source=:memory:");
        await userConnection.OpenAsync();

        var masterOptions = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(masterConnection)
            .Options;
        var userOptions = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(userConnection)
            .Options;

        await using (var masterSetup = new MasterDataDbContext(masterOptions))
        {
            await MasterDataTestSeed.CreateSchemaAsync(masterSetup);
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, ItemPayload, "item", "material");
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, ClassPayload, "class", null);
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, SkillPayload, "skill", null);
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, MobPayload, "mob.npc", "NPC");
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, NonHeadPayload, "item", "material");
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, InvalidHeadPayload, "item", "material");
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup, InvalidUrlHeadPayload, "item", "material");
            foreach (var invalid in new[] { "[]", "null", "123" })
            {
                var value = Convert.ToBase64String(Encoding.UTF8.GetBytes(invalid));
                await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup,
                    JsonSerializer.Serialize(new { schemaVersion = 1, id = "invalid_" + value, icon = "PLAYER_HEAD", iconTexture = value }), "item", "material");
            }
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup,
                JsonSerializer.Serialize(new { schemaVersion = 1, id = "spaced", icon = " player_head ", iconTexture = "  " + ItemTexture + "  " }), "item", "material");
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup,
                JsonSerializer.Serialize(new { schemaVersion = 1, id = "internal_whitespace", icon = "PLAYER_HEAD", iconTexture = ItemTexture.Insert(10, " ") }), "item", "material");
            await MasterDataTestSeed.SeedInlinePayloadAsync(masterSetup,
                JsonSerializer.Serialize(new { schemaVersion = 1, id = "cleared_level", icon = "STONE", iconTexture = CreateTexture("abc"),
                    levels = new[] { new { level = 1, icon = "PLAYER_HEAD", iconTexture = (string?)null } } }), "mob.enemy", "ENEMY");
        }

        var activeUuid = Guid.Parse("11111111-1111-1111-1111-111111111111");
        await using (var userSetup = new AstralRecordDbContext(userOptions))
        {
            await userSetup.Database.EnsureCreatedAsync();
            userSetup.Users.AddRange(CreateUser(activeUuid, false), CreateUser(Guid.NewGuid(), true));
            await userSetup.SaveChangesAsync();
        }

        await using var masterDataDbContext = new MasterDataDbContext(masterOptions);
        await using var astralRecordDbContext = new AstralRecordDbContext(userOptions);
        var repository = new GeyserHeadRepository(masterDataDbContext, astralRecordDbContext);

        var result = await repository.GetHeadsAsync();

        Assert.Equal(
            new[] { ItemTexture, ClassTexture, SkillTexture, MobBaseTexture, MobLevelTexture }
                .Order(StringComparer.Ordinal),
            result.Textures);
        Assert.Equal([activeUuid], result.PlayerUuids);
        Assert.Equal(SkillTexture, new SkillRepository(masterDataDbContext).GetAllSummaries().Single().IconTexture);
        Assert.Equal(MobBaseTexture, new MobRepository(masterDataDbContext).GetAllSummaries("NPC").Single().IconTexture);
    }

    private static UserEntity CreateUser(Guid uuid, bool isDeleted) => new()
    {
        Uuid = uuid,
        Mcid = uuid.ToString("N"),
        JoinDate = DateTime.UtcNow,
        LastJoinDate = DateTime.UtcNow,
        GlobalIp = "127.0.0.1",
        CreatedAt = DateTime.UtcNow,
        UpdatedAt = DateTime.UtcNow,
        CreatedBy = Guid.Empty,
        UpdatedBy = Guid.Empty,
        IsDeleted = isDeleted,
    };

    private static readonly string ItemTexture = CreateTexture("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static readonly string ClassTexture = CreateTexture("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static readonly string SkillTexture = CreateTexture("cccccccccccccccccccccccccccccccc");
    private static readonly string MobBaseTexture = CreateTexture("dddddddddddddddddddddddddddddddd");
    private static readonly string MobLevelTexture = CreateTexture("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

    private static string ItemPayload => $$"""
        { "schemaVersion": 1, "id": "head_item", "name": "head", "icon": "PLAYER_HEAD", "iconTexture": "{{ItemTexture}}", "rarity": "COMMON" }
        """;

    private static string ClassPayload => $$"""
        { "schemaVersion": 1, "id": "head_class", "type": "CLASS", "name": "head", "order": 1, "shortName": "HED", "icon": "PLAYER_HEAD", "iconTexture": "{{ClassTexture}}", "role": "SUPPORT", "baseStats": [] }
        """;

    private static string SkillPayload => $$"""
        { "schemaVersion": 1, "id": "head_skill", "type": "SKILL", "implementationId": "head_skill", "name": "head", "icon": "PLAYER_HEAD", "iconTexture": "{{SkillTexture}}" }
        """;

    private static string MobPayload => $$"""
        {
          "schemaVersion": 1, "id": "head_mob", "type": "MOB", "category": "NPC", "name": "head", "level": 1,
          "entityType": "PLAYER", "icon": "PLAYER_HEAD", "iconTexture": "{{MobBaseTexture}}",
          "skin": { "texture": "npc-skin-texture" }, "baseStats": [],
          "levels": [
            { "level": 1, "iconTexture": "{{MobLevelTexture}}" },
            { "level": 2, "icon": "STONE", "iconTexture": "excluded-level-texture" }
          ]
        }
        """;

    private const string NonHeadPayload = """
        { "schemaVersion": 1, "id": "non_head", "category": "material", "name": "non head", "icon": "STONE", "iconTexture": "excluded-item-texture", "rarity": "COMMON" }
        """;

    private const string InvalidHeadPayload = """
        { "schemaVersion": 1, "id": "invalid_head", "category": "material", "name": "invalid", "icon": "PLAYER_HEAD", "iconTexture": "not-base64", "rarity": "COMMON" }
        """;

    private static string InvalidUrlHeadPayload => $$"""
        { "schemaVersion": 1, "id": "invalid_url_head", "category": "material", "name": "invalid url", "icon": "PLAYER_HEAD", "iconTexture": "{{CreateTexture("ffffffffffffffffffffffffffffffff?query=1")}}", "rarity": "COMMON" }
        """;

    private static string CreateTexture(string textureId)
        => Convert.ToBase64String(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
        {
            textures = new
            {
                SKIN = new { url = $"https://textures.minecraft.net/texture/{textureId}" },
            },
        })));
}
