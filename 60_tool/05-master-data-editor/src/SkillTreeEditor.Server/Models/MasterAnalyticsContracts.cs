using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Models;

public sealed record AnalyticsMaster(string Path, JsonObject Content, int? Progression);
public sealed record AnalyticsDiagnostic(string Path, string Message);
public sealed record MasterAnalyticsCatalog(
    IReadOnlyList<AnalyticsMaster> Equipment,
    IReadOnlyList<AnalyticsMaster> Classes,
    IReadOnlyList<AnalyticsDiagnostic> Diagnostics);
public sealed record ClassGrowthValue(string Id, int Level, long TotalExperience, long NextLevelExperience);
public sealed record GrowthPoint(int PlayerLevel, long PlayerTotalExperience, IReadOnlyList<ClassGrowthValue> Classes);
public sealed record ClassGrowthDefinition(string Id, string Name, int MaxLevel, int ExpRate);
public sealed record GrowthSimulation(
    string AccountUuid,
    int StartPlayerLevel,
    int StartClassLevel,
    IReadOnlyList<ClassGrowthDefinition> Classes,
    IReadOnlyList<GrowthPoint> Points,
    IReadOnlyList<string> Sources,
    string Assumption);
