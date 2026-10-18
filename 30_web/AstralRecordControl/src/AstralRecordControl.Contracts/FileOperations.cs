using System.Text;
using System.Text.Json;

namespace AstralRecordControl.Contracts;

public sealed record FileTarget(Guid ManagedRootId, string RelativePath);
public sealed record Artifact(Guid TransferId, string Sha256, long SizeBytes);
public enum FileMutationAction { CreateDirectory, Copy, Move, Delete }
public enum ArtifactImportMode { File, ExtractZip }
public sealed record ListFilesPayload(FileTarget Target, string? Cursor = null, int Limit = 200);
public sealed record ReadFilePayload(FileTarget Target);
public sealed record WriteFilePayload(FileTarget Target, string ExpectedEtag, string ContentUtf8);
public sealed record FileMutationPayload(FileMutationAction Action, FileTarget Source, FileTarget? Destination = null, string ExpectedEtag = "absent");
public sealed record ExportFilesPayload(FileTarget? Target = null, Guid? BackupId = null, Guid TransferId = default);
public sealed record ImportArtifactPayload(Artifact Artifact, FileTarget Target, ArtifactImportMode Mode, string ExpectedEtag);
public sealed record ProvisionServerPayload(Artifact Artifact, string TemplateId, bool EulaAccepted);
public sealed record FileEntry(string Name, bool IsDirectory, long Bytes, string? Etag);
public sealed record FileListResult(FileEntry[] Entries, string? NextCursor);
public sealed record FileReadResult(string ContentUtf8, string Encoding, string Newline, string Etag);
public sealed record FileWriteResult(string Etag, string? TrashPath);
public sealed record FileMutationResult(string? Etag, string? TrashPath);
public sealed record ExportedArtifact(string Path, long SizeBytes, string Sha256);

public static class FileOperationValidation
{
    public static void ValidateTarget(FileTarget? target)
    {
        if (target is null || target.ManagedRootId == Guid.Empty || !ControlProtocol.IsRelativePath(target.RelativePath))
            throw new ArgumentException("Invalid file target.");
    }

    public static void ValidateArtifact(Artifact? artifact)
    {
        if (artifact is null || artifact.TransferId == Guid.Empty || artifact.SizeBytes < 0 || !ControlProtocol.IsHash(artifact.Sha256))
            throw new ArgumentException("Invalid artifact.");
    }

    public static void ValidatePayload(OperationKind kind, JsonElement payload)
    {
        if (payload.ValueKind != JsonValueKind.Object) throw new ArgumentException("Invalid file payload.");
        switch (kind)
        {
            case OperationKind.ListFiles:
                var list = Read<ListFilesPayload>(payload); ValidateTarget(list.Target);
                if (list.Limit is < 1 or > 1000 || (list.Cursor is not null && (list.Cursor.Length > 512 || !ControlProtocol.IsRelativePath(list.Cursor)))) throw new ArgumentException("Invalid list limit/cursor.");
                break;
            case OperationKind.ReadFile:
                ValidateTarget(Read<ReadFilePayload>(payload).Target); break;
            case OperationKind.WriteFile:
                var write = Read<WriteFilePayload>(payload); ValidateTarget(write.Target); ValidateEtag(write.ExpectedEtag);
                if (write.ContentUtf8 is null || Encoding.UTF8.GetByteCount(write.ContentUtf8) > 64 * 1024) throw new ArgumentException("File content too large.");
                break;
            case OperationKind.FileMutation:
                if (!payload.TryGetProperty("expectedEtag", out _)) throw new ArgumentException("Missing expected etag.");
                var mutation = Read<FileMutationPayload>(payload); ValidateTarget(mutation.Source); ValidateEtag(mutation.ExpectedEtag);
                if (!Enum.IsDefined(mutation.Action) || (mutation.Action is FileMutationAction.Copy or FileMutationAction.Move) != (mutation.Destination is not null)) throw new ArgumentException("Invalid file mutation.");
                if (mutation.Destination is not null) ValidateTarget(mutation.Destination);
                break;
            case OperationKind.ExportFiles:
                var export = Read<ExportFilesPayload>(payload);
                if (export.TransferId == Guid.Empty || (export.Target is null) == (export.BackupId is null) || export.BackupId == Guid.Empty) throw new ArgumentException("Invalid export target.");
                if (export.Target is not null) ValidateTarget(export.Target);
                break;
            case OperationKind.ImportArtifact:
                var import = Read<ImportArtifactPayload>(payload); ValidateArtifact(import.Artifact); ValidateTarget(import.Target); ValidateEtag(import.ExpectedEtag);
                if (!Enum.IsDefined(import.Mode)) throw new ArgumentException("Invalid import mode.");
                break;
            case OperationKind.ProvisionServer:
                var provision = Read<ProvisionServerPayload>(payload); ValidateArtifact(provision.Artifact);
                if (string.IsNullOrWhiteSpace(provision.TemplateId) || provision.TemplateId.Length > 128 || provision.TemplateId.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_')) || !provision.EulaAccepted) throw new ArgumentException("Invalid provision request.");
                break;
            case OperationKind.CreateBackup or OperationKind.RestoreBackup or OperationKind.PruneBackups or OperationKind.SetBackupProtection or OperationKind.DeleteBackup:
                BackupValidation.ValidatePayload(kind, payload);
                break;
        }
    }

    public static T Read<T>(JsonElement payload) => ControlJson.Deserialize<T>(payload.GetRawText());
    public static void ValidateEtag(string? value)
    {
        if (value != "absent" && !ControlProtocol.IsHash(value)) throw new ArgumentException("Invalid expected etag.");
    }
}
