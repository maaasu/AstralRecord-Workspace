using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IRuneService
{
    Task<RuneInstanceResponse?> CreateAsync(RuneCreateRequest request);

    Task<RuneInstanceResponse?> GetByInstanceIdAsync(Guid instanceId);
}
