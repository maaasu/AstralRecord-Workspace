using System.Text.Json;

namespace AstralRecordControl.Contracts;

public enum BackupFormat { Directory, Zip }
public enum BackupState { Creating, Completed, Failed, Restoring, RecoveryRequired, Deleted }
public enum RestoreMode { Replace, Merge }
public sealed record BackupProfile
{
    public Guid BackupProfileId { get; init; }
    public Guid ServerId { get; init; }
    public long Revision { get; init; }
    public Guid DestinationRootId { get; init; }
    public string[] ExcludePatterns { get; init; } = [];
    public BackupFormat Format { get; init; }
    public int RetentionCount { get; init; } = 7;
    public int RetentionDays { get; init; } = 30;
    public bool RestartIfPreviouslyRunning { get; init; }
}
public sealed record CreateBackupPayload(Guid BackupId, Guid BackupProfileId, long ProfileRevision);
public sealed record RestoreBackupPayload(Guid BackupId, RestoreMode Mode, bool RestartAfterRestore = false);
public sealed record PruneBackupsPayload(Guid BackupProfileId);
public sealed record SetBackupProtectionPayload(Guid BackupId, bool Protected);
public sealed record DeleteBackupPayload(Guid BackupId, string Reason);
public sealed record BackupEntry(string RelativePath, long SizeBytes, string Sha256, bool IsDirectory = false);
public sealed record BackupSummary(Guid BackupId, Guid ServerId, Guid BackupProfileId, BackupState State, DateTimeOffset CreatedAtUtc, long SizeBytes, bool Protected, BackupFormat Format, string Scope);
public sealed record BackupManifest
{
    public int SchemaVersion { get; init; } = 1;
    public Guid BackupId { get; init; }
    public Guid HostId { get; init; }
    public Guid ServerId { get; init; }
    public long ConfigRevision { get; init; }
    public ServerKind ServerKind { get; init; }
    public Guid BackupProfileId { get; init; }
    public long ProfileRevision { get; init; }
    public DateTimeOffset CreatedAtUtc { get; init; }
    public BackupFormat Format { get; init; }
    public BackupState State { get; init; }
    public bool Protected { get; init; }
    public string Scope { get; init; } = "ServerFiles";
    public string Consistency { get; init; } = "Stopped";
    public Guid? EnvironmentId { get; init; }
    public string? RuntimeServerId { get; init; }
    public string? DefinitionRevision { get; init; }
    public string? PatchRevision { get; init; }
    public BackupEntry[] Entries { get; init; } = [];
    public long SizeBytes { get; init; }
    public string ManifestSha256 { get; init; } = "";
}
public sealed record RestoreResult(Guid BackupId, RestoreMode Mode, int RestoredFiles, string PreRestorePath);
public sealed record RestoreJournal(Guid OperationId, Guid ServerId, Guid BackupId, RestoreMode Mode, string Phase, string StagePath, string PreRestorePath, DateTimeOffset UpdatedAtUtc);

public static class BackupValidation
{
    public static void Validate(BackupProfile? profile)
    {
        if (profile is null || profile.BackupProfileId == Guid.Empty || profile.ServerId == Guid.Empty || profile.Revision < 1 || profile.DestinationRootId == Guid.Empty
            || !Enum.IsDefined(profile.Format) || profile.RetentionCount is < 1 or > 1000 || profile.RetentionDays is < 1 or > 3650
            || profile.ExcludePatterns is null || profile.ExcludePatterns.Length > 128 || profile.ExcludePatterns.Any(x => !IsSafeExclude(x)))
            throw new ArgumentException("Invalid backup profile.");
    }
    private static bool IsSafeExclude(string? pattern) => pattern is { Length: > 0 and <= 512 } && !pattern.StartsWith('/') && !pattern.Contains('\\') && !pattern.Contains(':')
        && pattern.Split('/').All(part => part is not "" and not "." and not ".." && !part.Any(c => c < 32));

    public static void ValidatePayload(OperationKind kind, JsonElement payload)
    {
        switch (kind)
        {
            case OperationKind.CreateBackup:
                var create = FileOperationValidation.Read<CreateBackupPayload>(payload);
                if (create.BackupId == Guid.Empty || create.BackupProfileId == Guid.Empty || create.ProfileRevision < 1) throw new ArgumentException("Invalid backup request.");
                break;
            case OperationKind.RestoreBackup:
                var restore = FileOperationValidation.Read<RestoreBackupPayload>(payload);
                if (restore.BackupId == Guid.Empty || !Enum.IsDefined(restore.Mode)) throw new ArgumentException("Invalid restore request.");
                break;
            case OperationKind.PruneBackups:
                if (FileOperationValidation.Read<PruneBackupsPayload>(payload).BackupProfileId == Guid.Empty) throw new ArgumentException("Invalid profile ID.");
                break;
            case OperationKind.SetBackupProtection:
                if (FileOperationValidation.Read<SetBackupProtectionPayload>(payload).BackupId == Guid.Empty) throw new ArgumentException("Invalid backup ID.");
                break;
            case OperationKind.DeleteBackup:
                var deletion = FileOperationValidation.Read<DeleteBackupPayload>(payload);
                if (deletion.BackupId == Guid.Empty || string.IsNullOrWhiteSpace(deletion.Reason) || deletion.Reason.Length > 1024 || deletion.Reason.Any(c => c is '\r' or '\n' or '\0')) throw new ArgumentException("Invalid backup deletion.");
                break;
        }
    }
}
