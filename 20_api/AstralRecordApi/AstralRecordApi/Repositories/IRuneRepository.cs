using AstralRecordApi.Data.Entities;

namespace AstralRecordApi.Repositories;

public interface IRuneRepository
{
    Task AddAsync(RuneInstanceEntity instance, IReadOnlyList<RuneInstanceStatRollEntity> statRolls);

    Task<RuneInstanceEntity?> FindInstanceAsync(Guid instanceId);

    Task<IReadOnlyList<RuneInstanceStatRollEntity>> FindStatRollsAsync(Guid instanceId);
}
