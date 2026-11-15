using System.Globalization;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Endpoints;
using Xunit;

namespace SkillTreeEditor.Server.Tests;

public sealed class WorldPlacementRadiusTests
{
    [Theory]
    [InlineData("{}", 16)]
    [InlineData("{\"radiusMeters\":null}", 16)]
    [InlineData("{\"radiusMeters\":64}", 64)]
    [InlineData("{\"radiusMeters\":32.5}", 32.5)]
    [InlineData("{\"radiusMeters\":0}", 1)]
    [InlineData("{\"radiusMeters\":-2}", 1)]
    [InlineData("{\"radiusMeters\":0.5}", 1)]
    public void RadiusMatchesPluginDefaultMinimumAndDecimal(string document, double expected)
    {
        var previous = CultureInfo.CurrentCulture;
        try
        {
            CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("fr-FR");
            Assert.Equal(expected, WorldPlacementEndpoints.ReadSpawnerRadius(JsonNode.Parse(document)));
        }
        finally { CultureInfo.CurrentCulture = previous; }
    }

    [Theory]
    [InlineData("null")]
    [InlineData("[]")]
    [InlineData("{\"radiusMeters\":\"32.5\"}")]
    [InlineData("{\"radiusMeters\":\"invalid\"}")]
    [InlineData("{\"radiusMeters\":true}")]
    [InlineData("{\"radiusMeters\":{}}")]
    [InlineData("{\"radiusMeters\":1e999}")]
    public void MalformedOrNonFiniteRadiusRemainsUnknown(string document) =>
        Assert.Null(WorldPlacementEndpoints.ReadSpawnerRadius(JsonNode.Parse(document)));
}
