using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class EquipmentRandomValidationTests
{
    private const string Valid = """
        {"equipment":{"stats":[{"status":"FIRE_DAMAGE_INCREASE","type":"FLAT","value":5,
          "random":{"offsetMin":-2,"offsetMax":3,"step":0.5,"zeroChance":0.1}}]}}
        """;

    [Fact]
    public void AcceptsExplicitRandomAndLegacyScalarOrRange()
    {
        var item = JsonNode.Parse(Valid)!.AsObject();
        var stats = item["equipment"]!["stats"]!.AsArray();
        stats.Add(JsonNode.Parse("""{"status":"ATTACK","value":10}"""));
        stats.Add(JsonNode.Parse("""{"status":"DEFENSE","value":{"min":1,"max":3}}"""));
        var issues = new List<MasterDataIssue>();

        EquipmentRandomValidation.Validate(item, issues);

        Assert.Empty(issues);
    }

    [Fact]
    public void AcceptsInsignificantTrailingZerosInJsonAndYaml()
    {
        var json = JsonNode.Parse("""{"equipment":{"stats":[{"value":1.00000,"random":{"offsetMin":0.00000,"offsetMax":1.00000,"step":0.50000}}]}}""")!.AsObject();
        var yaml = new MasterDataCodec().Parse("item.yml", "equipment:\n  stats:\n    - value: 1.00000\n      random:\n        offsetMin: 0.00000\n        offsetMax: 1.00000\n        step: 0.50000\n").Content!.AsObject();

        foreach (var item in new[] { json, yaml })
        {
            var issues = new List<MasterDataIssue>();
            EquipmentRandomValidation.Validate(item, issues);
            Assert.Empty(issues);
        }
    }

    [Theory]
    [InlineData("{\"equipment\":{\"stats\":[{\"value\":\"10\",\"random\":{\"offsetMin\":0,\"offsetMax\":1,\"step\":0.5}}]}}")]
    public void AcceptsQuotedNumericBase(string payload)
    {
        var item = JsonNode.Parse(payload)!.AsObject();
        var issues = new List<MasterDataIssue>();
        EquipmentRandomValidation.Validate(item, issues);
        Assert.Empty(issues);
    }

    [Theory]
    [InlineData("\"10~20\"", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":1}")]
    [InlineData("{\"min\":10,\"max\":20}", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":1}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":0.3}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":1000000,\"step\":1}")]
    [InlineData("10.00001", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":1}")]
    [InlineData("1e-100", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":1}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":0.00001}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":1e-100}")]
    [InlineData("10", "{\"offsetMin\":2,\"offsetMax\":0,\"step\":1}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":0}")]
    [InlineData("10", "{\"offsetMin\":0,\"step\":1}")]
    [InlineData("10", "{\"offsetMin\":-10,\"offsetMax\":2,\"step\":1,\"zeroChance\":0.1}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":2,\"step\":1,\"zeroChance\":1.1}")]
    [InlineData("10000000000000000", "{\"offsetMin\":-1,\"offsetMax\":1,\"step\":1}")]
    [InlineData("9007199254740993", "{\"offsetMin\":-1,\"offsetMax\":-1,\"step\":1}")]
    [InlineData("10", "{\"offsetMin\":0,\"offsetMax\":0,\"step\":9007199254740993}")]
    [InlineData("1000000000000000", "{\"offsetMin\":0,\"offsetMax\":1,\"step\":0.1}")]
    public void RejectsInvalidRandomContracts(string value, string random)
    {
        var item = new JsonObject
        {
            ["equipment"] = new JsonObject
            {
                ["stats"] = new JsonArray(new JsonObject
                {
                    ["value"] = JsonNode.Parse(value),
                    ["random"] = JsonNode.Parse(random)
                })
            }
        };
        var issues = new List<MasterDataIssue>();

        EquipmentRandomValidation.Validate(item, issues);

        Assert.Contains(issues, issue => issue.Code == "EQUIPMENT_STAT_RANDOM");
    }

    [Fact]
    public void RejectsRandomCombinedWithLegacyBounds()
    {
        var item = JsonNode.Parse("""{"equipment":{"stats":[{"value":10,"min":5,"max":15,"random":{"offsetMin":0,"offsetMax":2,"step":1}}]}}""")!.AsObject();
        var issues = new List<MasterDataIssue>();

        EquipmentRandomValidation.Validate(item, issues);

        Assert.Contains(issues, issue => issue.Path == "/equipment/stats/0/value");
    }

}
