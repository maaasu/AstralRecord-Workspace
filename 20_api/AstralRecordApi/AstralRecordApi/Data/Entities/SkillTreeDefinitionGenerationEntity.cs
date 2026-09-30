namespace AstralRecordApi.Data.Entities;

public sealed class SkillTreeDefinitionGenerationEntity
{
    public string DefinitionGenerationId { get; set; } = string.Empty;
    public string CanonicalSnapshotJson { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
    /// <summary>明示公開の順番。0は導入前の既知定義、nullは未公開です。</summary>
    public long? PatchVersion { get; set; }
    /// <summary>初回登録時に既に公開されていた最大番号。後から登録順を読み替えません。</summary>
    public long IntroducedAfterPatchVersion { get; set; }
}
