namespace AstralRecordApi.Models;

public sealed record ResourcePackManifest(JavaResourcePack Java, BedrockResourcePack Bedrock);
public sealed record JavaResourcePack(string Sha1, string? ActionRingPanel = null, string? GuiTheme = null);
public sealed record BedrockResourcePack(string Sha256, Guid Uuid);
