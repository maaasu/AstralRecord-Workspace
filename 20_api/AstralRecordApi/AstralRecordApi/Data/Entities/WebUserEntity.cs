namespace AstralRecordApi.Data.Entities;

/// <summary>Web サイト用に管理するプレイヤー情報です。</summary>
public class WebUserEntity
{
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public bool WebAdmin { get; set; }
    public DateTime FirstLoginAt { get; set; }
    public DateTime LastLoginAt { get; set; }
}
