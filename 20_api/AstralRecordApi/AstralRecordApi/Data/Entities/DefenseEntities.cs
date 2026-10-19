namespace AstralRecordApi.Data.Entities;

/// <summary>チャンネルの運営設定。設定JSONは開催作成時に固定します。</summary>
public sealed class DefenseChannelEntity
{
    public string ServerId { get; set; } = string.Empty;
    public int Revision { get; set; }
    public bool DailyEnabled { get; set; }
    public string? DailyStartTimeJst { get; set; }
    public string ConfigurationJson { get; set; } = "{}";
    public DateTime UpdatedAtUtc { get; set; }
    public Guid UpdatedBy { get; set; }
    public DateTime? LastRuntimePollAtUtc { get; set; }
    public Guid? LastRuntimeInstanceId { get; set; }
    public string? RegistrationStatus { get; set; }
    public int? RegisteredCount { get; set; }
    public DateTime? RegistrationReportedAtUtc { get; set; }
}

/// <summary>受理された開催または予定のスキップ記録。</summary>
public sealed class DefenseRunEntity
{
    public Guid RunId { get; set; }
    public string ServerId { get; set; } = string.Empty;
    public string Source { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public Guid OperationId { get; set; }
    public DateTime? ScheduledAtUtc { get; set; }
    public DateTime? ScheduledDateJst { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime? StartedAtUtc { get; set; }
    public DateTime? EndedAtUtc { get; set; }
    public Guid? InstanceId { get; set; }
    public string ConfigurationJson { get; set; } = "{}";
    public int? DefenseLevel { get; set; }
    public int? ParticipantCount { get; set; }
    public string? Reason { get; set; }
    public DateTime? LastHeartbeatAtUtc { get; set; }
}

/// <summary>開始時に確定した参加者です。</summary>
public sealed class DefenseParticipantEntity
{
    public Guid RunId { get; set; }
    public Guid AccountId { get; set; }
    public Guid UserUuid { get; set; }
    public int ActiveSeconds { get; set; }
    public bool IsAfk { get; set; }
    public bool Eliminated { get; set; }
}

/// <summary>段階ごとの確定報酬と配送状態です。</summary>
public sealed class DefenseRewardEntity
{
    public Guid RunId { get; set; }
    public Guid AccountId { get; set; }
    public int DefenseLevel { get; set; }
    public Guid UserUuid { get; set; }
    public string OrbItemId { get; set; } = string.Empty;
    public int OrbQuantity { get; set; }
    public string MailId { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
    public DateTime? DeliveredAtUtc { get; set; }
}

/// <summary>runtime報告の再送識別子です。</summary>
public sealed class DefenseReportEntity
{
    public Guid OperationId { get; set; }
    public Guid RunId { get; set; }
    public string Kind { get; set; } = string.Empty;
    public string RequestHash { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
}
