namespace AstralRecordControl.Agent.Storage;

// The expected file version failed before replacing or moving a managed target.
public sealed class FileConflictException(string message) : InvalidOperationException(message);
