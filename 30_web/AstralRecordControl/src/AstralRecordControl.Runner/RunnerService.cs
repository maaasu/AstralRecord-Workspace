using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using AstralRecordControl.Contracts;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace AstralRecordControl.Runner;

public sealed class RunnerService(RunnerConfiguration configuration, RunnerDispatcher dispatcher, ILogger<RunnerService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            using var pipe = CreatePipe(configuration);
            try
            {
                await pipe.WaitForConnectionAsync(stoppingToken);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                timeout.CancelAfter(TimeSpan.FromSeconds(5));
                var json = await PipeProtocol.ReadAsync(pipe, timeout.Token);
                var request = ControlJson.Deserialize<IpcRequest>(json);
                var administrator = false;
                if (request.Method == "Recover")
                    pipe.RunAsClient(() => administrator = new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator));
                await PipeProtocol.WriteAsync(pipe, dispatcher.Dispatch(request, administrator), timeout.Token);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception exception) when (exception is IOException or OperationCanceledException or ArgumentException or System.Text.Json.JsonException or RunnerFault)
            {
                logger.LogWarning("Runner IPC request rejected ({ErrorCode}).", ErrorCode.ProtocolMismatch);
            }
        }
    }

    public static NamedPipeServerStream CreatePipe(RunnerConfiguration configuration)
    {
        if (string.IsNullOrWhiteSpace(configuration.PipeName) || configuration.PipeName.Contains('\\') || configuration.PipeName.Contains('/')) throw new ArgumentException("Invalid pipe name.");
        var agentSid = new SecurityIdentifier(configuration.AgentSid);
        var security = new PipeSecurity();
        security.SetAccessRuleProtection(true, false);
        // A network logon can never use this endpoint, even when its user is a local administrator.
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.NetworkSid, null), PipeAccessRights.FullControl, AccessControlType.Deny));
        security.AddAccessRule(new PipeAccessRule(agentSid, PipeAccessRights.ReadWrite | PipeAccessRights.Synchronize, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        return NamedPipeServerStreamAcl.Create(configuration.PipeName, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.FirstPipeInstance, 4096, 4096, security);
    }
}
