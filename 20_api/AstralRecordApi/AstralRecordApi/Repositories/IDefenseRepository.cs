using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>防衛戦の設定、開催、段階報酬の永続化境界です。</summary>
public interface IDefenseRepository
{
    Task<DefenseChannelResponse> GetChannelAsync(string serverId);
    Task<DefenseChannelResponse> UpdateChannelAsync(string serverId, DefenseChannelUpdateRequest request, Guid actor);
    Task<DefenseRunResponse> StartAsync(string serverId, DefenseStartRequest request);
    Task<DefenseRunResponse> StopAsync(string serverId, DefenseStopRequest request);
    Task<IReadOnlyList<DefenseRunResponse>> RunsAsync(string serverId, int limit);
    Task<DefenseRuntimeResponse> RuntimeAsync(string serverId, Guid instanceId);
    Task<DefenseChannelResponse> RegistrationAsync(string serverId, DefenseRegistrationReport request);
    Task<DefenseRunResponse> ReportAsync(string serverId, Guid runId, DefenseRunReportRequest request);
    Task<IReadOnlyList<DefenseRewardReceipt>> RewardsAsync(string serverId, Guid runId, DefenseRewardReportRequest request);
    Task<int> DeliverPendingAsync(CancellationToken cancellationToken);
    Task ReconcileScheduleAsync(CancellationToken cancellationToken);
}

public sealed class DefenseConflictException(string message) : Exception(message);
