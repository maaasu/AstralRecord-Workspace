using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IChannelBoostRepository
{
    Task<ChannelBoostSnapshotResponse> GetSnapshotAsync();
    Task<ChannelBoostEventsResponse> GetEventsAsync(long after);
    Task<ChannelBoostActivateResponse> ActivateAsync(string channelId, ChannelBoostActivateRequest request);
}
