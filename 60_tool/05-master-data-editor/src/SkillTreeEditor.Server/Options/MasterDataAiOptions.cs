namespace SkillTreeEditor.Server.Options;

public sealed class MasterDataAiOptions
{
    public const string SectionName = "SkillTreeEditor:Ai";
    public const string DefaultModel = "gpt-6.1-terra";
    public string Model { get; set; } = DefaultModel;
    public int TimeoutSeconds { get; set; } = 120;
}
