namespace AstralRecordApi.Data.Entities;

/// <summary>ゲームデータのリセットに依存せず長期保持するプレイヤー識別情報です。</summary>
public class ManagementPlayerEntity
{
    public Guid PlayerUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public bool WebAdmin { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public DateTime? FirstWebLoginAt { get; set; }
    public DateTime? LastWebLoginAt { get; set; }
}
