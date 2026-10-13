using System.Text.Json;
using AstralRecordApi.Models;
using Xunit;

namespace AstralRecordApi.Tests.Models;

public sealed class EquipmentEnchantLockContractTests
{
    private static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);

    [Fact]
    public void LegacyEnchantJson_DefaultsToUnlocked()
    {
        const string json = """
            {"enchantMasterId":"enchant001","effectId":"attack","status":"ATTACK","type":"FLAT","value":5}
            """;
        Assert.False(JsonSerializer.Deserialize<EquipmentInstanceEnchantResponse>(json, Options)!.IsLocked);
        Assert.False(JsonSerializer.Deserialize<PlayerStateEquipmentEnchantSnapshot>(json, Options)!.IsLocked);
    }

    [Fact]
    public void LockedResponseAndSnapshot_UseCamelCaseFlag()
    {
        var response = JsonSerializer.Serialize(new EquipmentInstanceEnchantResponse { IsLocked = true }, Options);
        Assert.True(JsonDocument.Parse(response).RootElement.GetProperty("isLocked").GetBoolean());
        var snapshot = JsonSerializer.Deserialize<PlayerStateEquipmentEnchantSnapshot>("""
            {"enchantMasterId":"enchant003","effectId":"attack","status":"ATTACK","type":"FLAT","value":5,"isLocked":true}
            """, Options)!;
        Assert.True(snapshot.IsLocked);
        Assert.True(JsonDocument.Parse(JsonSerializer.Serialize(snapshot, Options))
            .RootElement.GetProperty("isLocked").GetBoolean());
    }
}
