using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class MasterDataRepository(MasterDataDbContext dbContext) : IMasterDataRepository
{
    private const string StatusSucceeded = "SUCCEEDED";
    private const string StatusFailed = "FAILED";

    public async Task<IReadOnlyList<MasterDataSeedRunResponse>> GetSeedRunsAsync(int limit)
    {
        var runs = await dbContext.SeedRuns
            .AsNoTracking()
            .OrderByDescending(run => run.StartedAt)
            .Take(limit)
            .ToListAsync();

        return runs.Select(MapToResponse).ToArray();
    }

    public async Task<MasterDataHealthResponse> GetHealthAsync()
    {
        var activeEntryCount = await dbContext.Entries
            .AsNoTracking()
            .CountAsync(entry => !entry.IsDeleted);

        var lastRun = await dbContext.SeedRuns
            .AsNoTracking()
            .OrderByDescending(run => run.StartedAt)
            .FirstOrDefaultAsync();

        var lastSucceededAt = await dbContext.SeedRuns
            .AsNoTracking()
            .Where(run => run.Status == StatusSucceeded)
            .OrderByDescending(run => run.StartedAt)
            .Select(run => run.FinishedAt)
            .FirstOrDefaultAsync();

        var status = activeEntryCount == 0
            ? "empty"
            : lastRun?.Status == StatusFailed
                ? "degraded"
                : "ok";

        return new MasterDataHealthResponse
        {
            Status = status,
            ActiveEntryCount = activeEntryCount,
            LastSeedRunId = lastRun?.SeedRunId,
            LastSeedRunStatus = lastRun?.Status,
            LastSucceededAt = lastSucceededAt,
        };
    }

    private static MasterDataSeedRunResponse MapToResponse(MasterDataSeedRunEntity run) => new()
    {
        SeedRunId = run.SeedRunId,
        TriggerType = run.TriggerType,
        Status = run.Status,
        SourceRootPath = run.SourceRootPath,
        StartedAt = run.StartedAt,
        FinishedAt = run.FinishedAt,
        FileCount = run.FileCount,
        UpsertedCount = run.UpsertedCount,
        DeletedCount = run.DeletedCount,
        SkippedCount = run.SkippedCount,
        ErrorMessage = run.ErrorMessage,
    };
}
