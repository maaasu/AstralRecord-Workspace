using System.ComponentModel.DataAnnotations;

namespace AstralRecordWeb.Models;

/// <summary>変更要求の条件だけを受け取る。費用・残高・所有者・条件判定は受け取らない。</summary>
public sealed class SkillTreeOperationInput : IValidatableObject
{
    public Guid OperationId { get; set; }
    [StringLength(100)] public string? TargetServerId { get; set; }
    [Required, StringLength(128)] public string ExpectedDefinitionGenerationId { get; set; } = "";
    [Range(0, int.MaxValue)] public int ExpectedPlayerStateVersion { get; set; }
    [Required, RegularExpression("UNLOCK|RELOCK|BATCH")] public string Action { get; set; } = "";
    [Required, StringLength(128)] public string NodeId { get; set; } = "";
    [StringLength(128)] public string? SourceClassId { get; set; }
    [MaxLength(512)] public IReadOnlyList<SkillTreeOperationChangeInput>? Changes { get; set; }

    public IEnumerable<ValidationResult> Validate(ValidationContext validationContext)
    {
        if (Action == "BATCH" && (NodeId != "batch" || Changes is not { Count: >= 1 and <= 512 }
            || Changes.Any(change => change is null || string.IsNullOrWhiteSpace(change.NodeId))
            || Changes.Select(change => change.NodeId.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).Count() != Changes.Count))
            yield return new ValidationResult("変更案を確認してください。", [nameof(Changes)]);
        if (Action != "BATCH" && Changes is not null)
            yield return new ValidationResult("変更形式を確認してください。", [nameof(Changes)]);
    }
}

public sealed class SkillTreeOperationChangeInput
{
    [Required, RegularExpression("UNLOCK|RELOCK")] public string Action { get; set; } = "";
    [Required, StringLength(128)] public string NodeId { get; set; } = "";
    [StringLength(128)] public string? SourceClassId { get; set; }
}
