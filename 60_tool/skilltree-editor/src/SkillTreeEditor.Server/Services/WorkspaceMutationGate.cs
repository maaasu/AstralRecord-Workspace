namespace SkillTreeEditor.Server.Services;

public sealed class WorkspaceMutationGate(WorkspacePaths paths)
{
    public ValueTask<IAsyncDisposable> EnterAsync(CancellationToken cancellationToken)
        => ExclusiveFileLock.AcquireAsync(paths.WorkspaceMutationLock, cancellationToken);
}
