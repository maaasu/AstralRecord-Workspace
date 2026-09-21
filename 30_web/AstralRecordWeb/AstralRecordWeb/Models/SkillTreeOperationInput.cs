using System.ComponentModel.DataAnnotations;

namespace AstralRecordWeb.Models;

/// <summary>変更要求の条件だけを受け取る。費用・残高・所有者・条件判定は受け取らない。</summary>
public sealed class SkillTreeOperationInput
{
    public Guid OperationId { get; set; }
    [StringLength(100)] public string? TargetServerId { get; set; }
    [Required, StringLength(128)] public string ExpectedDefinitionGenerationId { get; set; } = "";
    [Range(0, int.MaxValue)] public int ExpectedPlayerStateVersion { get; set; }
    [Required, RegularExpression("UNLOCK|RELOCK")] public string Action { get; set; } = "";
    [Required, StringLength(128)] public string NodeId { get; set; } = "";
    [StringLength(128)] public string? SourceClassId { get; set; }
}
