using System.Runtime.CompilerServices;
using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class EnchantRepositoryTests
{
    [Fact]
    public async Task GetById_LoadsCommonMaster_WithEquipmentSpecificUniqueEffects()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new MasterDataDbContext(options);
        await MasterDataTestSeed.CreateSchemaAsync(dbContext);
        await MasterDataTestSeed.SeedEntryAsync(
            dbContext,
            Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "12.features.enchant", "v1.enchant001.yml"),
            masterType: "enchant",
            category: null);

        var repository = new EnchantRepository(dbContext);
        var master = repository.GetById("enchant:enchant001");

        Assert.NotNull(master);
        Assert.Equal(1, master!.SchemaVersion);
        Assert.Equal("enchant001", master.Id);
        Assert.Equal(["ACCESSORY", "ARMOR", "WEAPON"], master.Targets.Select(target => target.EquipmentType).OrderBy(type => type).ToArray());
        Assert.All(master.Targets, target =>
        {
            Assert.NotEmpty(target.Entries);
            Assert.Equal(
                target.Entries.Count,
                target.Entries.Select(entry => entry.EffectId).Distinct(StringComparer.OrdinalIgnoreCase).Count());
            Assert.All(target.Entries, entry => Assert.True(entry.Weight > 0));
        });
        Assert.Contains(
            master.Targets.Single(target => target.EquipmentType == "WEAPON").Entries,
            entry => entry.EffectId == "weapon_attack_scalar_130" && entry.Weight == 30);
    }

    private static string ResolveWorkspaceRoot([CallerFilePath] string currentFile = "")
    {
        var current = new FileInfo(currentFile).Directory;
        while (current is not null)
        {
            if (Directory.Exists(Path.Combine(current.FullName, "40_filebase"))
                && Directory.Exists(Path.Combine(current.FullName, "20_api")))
                return current.FullName;
            current = current.Parent;
        }

        throw new InvalidOperationException("workspace root could not be resolved from the test source path.");
    }
}
