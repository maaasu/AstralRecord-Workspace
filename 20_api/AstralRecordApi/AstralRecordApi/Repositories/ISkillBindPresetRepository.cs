using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ISkillBindPresetRepository
{
    Task<IReadOnlyList<SkillBindPresetResponse>> GetByAccountIdAsync(Guid accountId);

    Task<SkillBindPresetResponse?> UpsertAsync(
        Guid accountId,
        int presetIndex,
        SkillBindPresetUpsertRequest request);

    Task<bool> SelectAsync(
        Guid accountId,
        int presetIndex,
        SkillBindPresetSelectionRequest request);
}
