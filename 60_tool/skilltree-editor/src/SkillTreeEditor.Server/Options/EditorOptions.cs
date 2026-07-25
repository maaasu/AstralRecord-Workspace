namespace SkillTreeEditor.Server.Options;

public sealed class EditorOptions
{
    public const string SectionName = "SkillTreeEditor";

    public string? WorkspaceRoot { get; set; }

    public string MinecraftIconsBaseUrl { get; set; } = "https://mc-icons.com";
}
