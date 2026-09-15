namespace AstralRecordApi.Options;

/// <summary>Webプロフィールで表示する、Plugin設定と同じスキルツリー構造です。</summary>
public sealed class WebPlayerProfileOptions
{
    public const string SectionName = "WebPlayerProfile";
    public string SkillTreeStructureId { get; set; } = "starter";
}
