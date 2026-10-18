using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public sealed record RunnerConfiguration
{
    public Guid HostId { get; init; }
    public string AgentSid { get; init; } = "";
    public string PipeName { get; init; } = ControlProtocol.RunnerPipeName;
    public HostResources LocalResources { get; init; } = new();
    public static string DefaultDataRoot => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AstralRecordControl", "Runner");
}

public sealed class RunnerFault(ErrorCode code) : Exception(code.ToString())
{
    public ErrorCode Code { get; } = code;
}
