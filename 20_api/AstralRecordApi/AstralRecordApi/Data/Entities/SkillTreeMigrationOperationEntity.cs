namespace AstralRecordApi.Data.Entities;
public sealed class SkillTreeMigrationOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public int ExpectedStateVersion { get; set; }
    public string? FromGenerationId { get; set; }
    public string ToGenerationId { get; set; } = string.Empty;
    public string BaselineNodeIdsJson { get; set; } = "[]";
    public string RemovedNodeIdsJson { get; set; } = "[]";
    public string Status { get; set; } = string.Empty;
    public DateTime CompletedAtUtc { get; set; }
}
