namespace AstralRecordApi.Data.Entities;

/// <summary>Web 固定ログインIDとパスワードをゲームデータから独立して保持します。</summary>
public sealed class WebCredentialEntity
{
    public Guid PlayerUuid { get; set; }
    public string? LoginId { get; set; }
    public string? PasswordHash { get; set; }
    public bool Enabled { get; set; }
    public Guid SessionVersion { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }
}

/// <summary>ログインID単位で失敗回数を永続化する楽観ロック付きの試行記録です。</summary>
public sealed class WebCredentialLoginAttemptEntity
{
    public string LoginId { get; set; } = string.Empty;
    public int FailedAttempts { get; set; }
    public DateTime WindowStartedAtUtc { get; set; }
    public DateTime? LockedUntilUtc { get; set; }
    public int Revision { get; set; }
}
