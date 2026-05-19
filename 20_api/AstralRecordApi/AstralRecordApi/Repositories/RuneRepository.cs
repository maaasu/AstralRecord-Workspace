using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class RuneRepository(AstralRecordDbContext dbContext) : IRuneRepository
{
    public async Task AddAsync(RuneInstanceEntity instance, IReadOnlyList<RuneInstanceStatRollEntity> statRolls)
    {
        await dbContext.RuneInstances.AddAsync(instance);

        if (statRolls.Count > 0)
            await dbContext.RuneInstanceStatRolls.AddRangeAsync(statRolls);

        await dbContext.SaveChangesAsync();
    }

    public async Task<RuneInstanceEntity?> FindInstanceAsync(Guid instanceId)
        => await dbContext.RuneInstances
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.RuneInstanceId == instanceId && !x.IsDeleted);

    public async Task<IReadOnlyList<RuneInstanceStatRollEntity>> FindStatRollsAsync(Guid instanceId)
        => await dbContext.RuneInstanceStatRolls
            .AsNoTracking()
            .Where(x => x.RuneInstanceId == instanceId && !x.IsDeleted)
            .OrderBy(x => x.SortOrder)
            .ToListAsync();
}
