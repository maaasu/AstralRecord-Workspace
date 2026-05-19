using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>レシピ API</summary>
[ApiController]
[Route("api/[controller]")]
public class RecipeController(IRecipeRepository recipeRepository) : ControllerBase
{
    /// <summary>レシピ一覧取得（最小項目）</summary>
    /// <response code="200">レシピ一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        var recipes = recipeRepository.GetAllSummaries();
        return Ok(recipes);
    }

    /// <summary>レシピ取得</summary>
    /// <param name="recipeId">レシピ ID</param>
    /// <response code="200">レシピ取得成功</response>
    /// <response code="404">指定 ID のレシピが存在しない</response>
    [HttpGet("{recipeId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string recipeId)
    {
        var recipe = recipeRepository.GetById(recipeId);
        if (recipe is null)
            return NotFound();

        return Ok(recipe);
    }
}
