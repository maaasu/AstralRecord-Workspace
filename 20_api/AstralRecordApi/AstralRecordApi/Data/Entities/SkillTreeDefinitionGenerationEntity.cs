namespace AstralRecordApi.Data.Entities;

public sealed class SkillTreeDefinitionGenerationEntity
{
    public string DefinitionGenerationId { get; set; } = string.Empty;
    public string CanonicalSnapshotJson { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
}
