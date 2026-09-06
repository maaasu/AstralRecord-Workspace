namespace AstralRecordApi.Data.Entities;

/// <summary>同一 snapshotId の再送で、保存時点の ACK を固定再生する台帳です。</summary>
public sealed class PlayerStateSnapshotEntity
{
    public Guid SnapshotId { get; set; }
    public Guid AccountId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public string AckPayloadJson { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; }
    public DateTime CompletedAt { get; set; }
    public Guid CreatedBy { get; set; }
}
