namespace SkillTreeEditor.Server.Services;

public sealed class WorkspaceMutationGate(WorkspacePaths paths)
{
    public ValueTask<IAsyncDisposable> EnterAsync(CancellationToken cancellationToken)
    {
        new MasterDataPaths(paths).RequireNoLinks(paths.WorkspaceMutationLock);
        return ExclusiveFileLock.AcquireAsync(paths.WorkspaceMutationLock, cancellationToken);
    }
}
