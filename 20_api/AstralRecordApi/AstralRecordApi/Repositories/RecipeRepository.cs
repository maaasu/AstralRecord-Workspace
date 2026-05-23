using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = recipe</c>）から
/// レシピマスタを取得する。
/// </summary>
public class RecipeRepository(MasterDataDbContext dbContext) : IRecipeRepository
{
    private const string MasterType = "recipe";

    public IReadOnlyList<RecipeSummaryResponse> GetAllSummaries()
        => dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.Category)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => new RecipeSummaryResponse
            {
                Id = entry.MasterId,
                Type = entry.Type ?? string.Empty,
                Category = entry.Category ?? string.Empty,
                Name = entry.DisplayName,
            })
            .ToArray();

    public RecipeResponse? GetById(string recipeId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == recipeId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<RecipeResponse>(payload);
    }
}
