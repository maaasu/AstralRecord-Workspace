using System.Reflection;
using System.Text.Json.Nodes;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class MasterDataSeederItemIdTests
{
    /**
     * 設計入力: 00_docs/50_Filebase設計書/feature/10-item.md
     * 章・見出し: # Item 設計 > ## IDとファイル名
     * 検証契約: 採番済みカテゴリディレクトリは論理カテゴリへ正規化され、v<schemaVersion>.<id>.<slug>.yml のIDとYAML categoryが一致するファイルを受け付ける。
     */
    [Fact]
    public void ValidateItemFileContract_AcceptsNumberedDirectoryAndFileName()
    {
        var root = new JsonObject { ["category"] = "equipment" };
        var seedFile = CreateSeedFile("10.features.item/20.equipment/v1.20a00002.nox_sword.yml", "equipment");

        InvokeStatic("ValidateItemFileContract", root, "20a00002", 1, seedFile);
        Assert.Equal("equipment", InvokeStatic("NormalizeItemCategory", "20.equipment", "10.features.item"));
    }

    /**
     * 設計入力: 00_docs/50_Filebase設計書/feature/10-item.md
     * 章・見出し: # Item 設計 > ## IDとファイル名
     * 検証契約: 番号なしカテゴリディレクトリやYAML IDと不一致のファイル名はSeederが拒否する。
     */
    [Fact]
    public void ValidateItemFileContract_RejectsLegacyDirectoryAndMismatchedFileName()
    {
        var normalizeError = Assert.Throws<TargetInvocationException>(() =>
            InvokeStatic("NormalizeItemCategory", "equipment", "10.features.item"));
        Assert.IsType<InvalidOperationException>(normalizeError.InnerException);

        var root = new JsonObject { ["category"] = "equipment" };
        var seedFile = CreateSeedFile("10.features.item/20.equipment/v1.20a00003.nox_sword.yml", "equipment");
        var fileError = Assert.Throws<TargetInvocationException>(() =>
            InvokeStatic("ValidateItemFileContract", root, "20a00002", 1, seedFile));
        Assert.IsType<InvalidOperationException>(fileError.InnerException);
    }

    /**
     * 設計入力: 00_docs/50_Filebase設計書/feature/10-item.md
     * 章・見出し: # Item 設計 > ## IDとファイル名
     * 検証契約: z は debug_ slug 専用で、通常アイテムの採番には使用できない。
     */
    [Fact]
    public void ValidateItemFileContract_ReservesZForDebugItems()
    {
        var root = new JsonObject { ["category"] = "equipment" };
        var debugFile = CreateSeedFile("10.features.item/20.equipment/v1.20z00001.debug_bow.yml", "equipment");
        InvokeStatic("ValidateItemFileContract", root, "20z00001", 1, debugFile);

        var normalFile = CreateSeedFile("10.features.item/20.equipment/v1.20z00002.nox_sword.yml", "equipment");
        var normalError = Assert.Throws<TargetInvocationException>(() =>
            InvokeStatic("ValidateItemFileContract", root, "20z00002", 1, normalFile));
        Assert.IsType<InvalidOperationException>(normalError.InnerException);
    }

    private static object CreateSeedFile(string relativePath, string category)
    {
        var type = typeof(MasterDataSeeder).GetNestedType(
            "SeedFile",
            BindingFlags.NonPublic
        ) ?? throw new MissingMemberException(typeof(MasterDataSeeder).FullName, "SeedFile");
        var constructor = type.GetConstructor(
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            binder: null,
            [typeof(string), typeof(string), typeof(string)],
            modifiers: null
        ) ?? throw new MissingMethodException(type.FullName, ".ctor");
        return constructor.Invoke(["root", relativePath, category]);
    }

    private static object? InvokeStatic(string methodName, params object[] arguments)
    {
        var method = typeof(MasterDataSeeder).GetMethod(
            methodName,
            BindingFlags.Static | BindingFlags.NonPublic
        ) ?? throw new MissingMethodException(typeof(MasterDataSeeder).FullName, methodName);
        return method.Invoke(null, arguments);
    }
}
