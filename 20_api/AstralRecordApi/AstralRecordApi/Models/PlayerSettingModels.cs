namespace AstralRecordApi.Models;

public class PlayerSettingCreateRequest
{
    public Guid UserId { get; set; }
    public required string SettingKey { get; set; }
    public required string SettingValueJson { get; set; }
    public Guid CreatedBy { get; set; }
}

public class PlayerSettingUpdateRequest
{
    public required string SettingValueJson { get; set; }
    public int ExpectedVersion { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class PlayerSettingResponse
{
    public Guid UserSettingId { get; init; }
    public Guid UserId { get; init; }
    public string SettingKey { get; init; } = string.Empty;
    public string SettingValueJson { get; init; } = "{}";
    public int Version { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
}

public class PlayerSettingUpdateResult
{
    public bool IsVersionConflict { get; init; }
    public PlayerSettingResponse? Current { get; init; }
    public PlayerSettingResponse? Updated { get; init; }
}
