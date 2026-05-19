using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IRecipeRepository
{
    IReadOnlyList<RecipeSummaryResponse> GetAllSummaries();

    RecipeResponse? GetById(string recipeId);
}
