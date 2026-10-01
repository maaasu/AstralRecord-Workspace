using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MasterAnalyticsTests
{
    [Theory]
    [InlineData(1, 707)]
    [InlineData(5, 4600)]
    [InlineData(10, 14942)]
    public void PlayerCurveUsesJavaSignedHashAndMilestone(int level, long expected)
        => Assert.Equal(expected, MasterAnalyticsService.PlayerExperienceForNextLevel(Guid.Parse("00000000-0000-0000-0000-000000000001"), level));

    [Theory]
    [InlineData(1, 100, 53)]
    [InlineData(10, 100, 1115)]
    [InlineData(1, 150, 80)]
    [InlineData(1, 0, 5)]
    public void ClassCurveUsesRateMinimumAndKotlinRounding(int level, int rate, long expected)
        => Assert.Equal(expected, MasterAnalyticsService.ClassExperienceForNextLevel(level, rate));

    [Fact]
    public async Task SimulationRespectsAnchorUpperLimitAndUuid()
    {
        var root = Path.Combine(Path.GetTempPath(), "ar-analytics-" + Guid.NewGuid().ToString("N"));
        var classes = Path.Combine(root, "40_filebase", "20.features.class");
        Directory.CreateDirectory(classes);
        try
        {
            await File.WriteAllTextAsync(Path.Combine(classes, "v1.0.example.yml"), "id: example\nname: Example\nmaxLevel: 3\nexpRate: 100\n");
            var service = new MasterAnalyticsService(new WorkspacePaths(root));
            var result = await service.SimulateAsync(null, 5, 3, 2, "example", CancellationToken.None);
            Assert.Equal(new[] { 3, 4, 5 }, result.Points.Select(point => point.PlayerLevel));
            Assert.Equal(2, result.Points[0].Classes[0].Level);
            Assert.Equal(53, result.Points[0].Classes[0].TotalExperience);
            Assert.Equal(3, result.Points[1].Classes[0].Level);
            Assert.Equal(0, result.Points[1].Classes[0].NextLevelExperience);
            await Assert.ThrowsAsync<ArgumentException>(() => service.SimulateAsync("invalid", 100, 1, 1, "example", CancellationToken.None));
        }
        finally { Directory.Delete(root, recursive: true); }
    }
}
