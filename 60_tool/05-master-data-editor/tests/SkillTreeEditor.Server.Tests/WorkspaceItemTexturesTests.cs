using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class WorkspaceItemTexturesTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "skilltree-pack-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void ResolvesTheExactMaterialAndModelAndRefreshesAfterRegistryEdits()
    {
        Write("item-models.json", """
            { "items": [ { "base_item": "minecraft:fern", "custom_model_data": 230003, "id": "windleaf_sprig" } ] }
            """);
        var png = Write("bedrock/textures/items/windleaf_sprig.png", "fixture");
        var paths = new WorkspacePaths(root);
        var textures = new WorkspaceItemTextures(new MasterDataPaths(paths));

        Assert.Equal(png, textures.FindPath(" Minecraft:FERN ", 230003));
        Assert.Null(textures.FindPath("stone", 230003));
        Assert.Null(textures.FindPath("fern", 230004));

        Write("item-models.json", """
            { "items": [
              { "base_item": "minecraft:fern", "custom_model_data": 230004, "id": "windleaf_sprig" },
              { "base_item": "minecraft:fern", "custom_model_data": 230005, "id": "../outside" }
            ] }
            """);
        Assert.Null(textures.FindPath("fern", 230003));
        Assert.Equal(png, textures.FindPath("fern", 230004));
        Assert.Null(textures.FindPath("fern", 230005));
        File.Delete(png);
        Assert.Null(textures.FindPath("fern", 230004));
    }

    [Fact]
    public void ResolvesOnlyRegisteredPaperMobHeadImagesFromTheGuiPack()
    {
        Write("gui-icons.json", """
            { "icons": [
              { "role": "mob_head_sunbird", "base_item": "minecraft:paper" },
              { "role": "mob_head_wolf", "base_item": "minecraft:stone" },
              { "role": "mob_head_missing", "base_item": "minecraft:paper" },
              { "role": "close", "base_item": "minecraft:paper" },
              { "role": "mob_head_../outer", "base_item": "minecraft:paper" }
            ] }
            """);
        var png = Write("assets/astralrecord/textures/item/gui/gui_mob_head_sunbird.png", "fixture");
        var textures = new WorkspaceItemTextures(new MasterDataPaths(new WorkspacePaths(root)));

        Assert.Equal(png, textures.FindMobHeadPath("mob_head_sunbird"));
        Assert.Null(textures.FindMobHeadPath("mob_head_missing"));
        Assert.Null(textures.FindMobHeadPath("mob_head_wolf"));
        Assert.Null(textures.FindMobHeadPath("close"));
        Assert.Null(textures.FindMobHeadPath("mob_head_../outer"));

        Write("gui-icons.json", """{ "icons": [] }""");
        Assert.Null(textures.FindMobHeadPath("mob_head_sunbird"));
    }

    private string Write(string relative, string text)
    {
        var path = Path.GetFullPath(Path.Combine(root, "50_resourcepack", relative));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        return path;
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
