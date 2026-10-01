namespace SkillTreeEditor.Server.Options;

public sealed class EditorOptions
{
    public const string SectionName = "SkillTreeEditor";

    public string? WorkspaceRoot { get; set; }

    public string MinecraftIconsBaseUrl { get; set; } = "https://mc-icons.com";

    public string MinecraftIconsFallbackBaseUrl { get; set; } =
        "https://raw.githubusercontent.com/Owen1212055/mc-assets/551e4a68f23a59eecc84a310902f7e10864945f9/item-assets/";
}
