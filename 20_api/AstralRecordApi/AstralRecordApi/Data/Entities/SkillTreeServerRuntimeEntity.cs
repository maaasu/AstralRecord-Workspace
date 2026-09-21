namespace AstralRecordApi.Data.Entities;

public sealed class SkillTreeServerRuntimeEntity
{
    public string ServerId { get; set; } = string.Empty;
    public Guid ServerSessionId { get; set; }
    public long PublicationRevision { get; set; } = 1;
    public DateTime ServerStartedAtUtc { get; set; }
    public string PluginVersion { get; set; } = string.Empty;
    public string CompatibilityVersion { get; set; } = string.Empty;
    public string DefinitionGenerationId { get; set; } = string.Empty;
    public bool Ready { get; set; }
    public DateTime LastSeenUtc { get; set; }
}
