using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>釣りマスタ API。</summary>
[ApiController]
[Route("api/fishing")]
public sealed class FishingController(IFishingRepository repository) : ControllerBase
{
    /// <summary>釣れる魚の一覧を取得します。</summary>
    [HttpGet("fish")]
    public IActionResult GetFish() => Ok(repository.GetFish());

    /// <summary>指定 ID の魚を取得します。</summary>
    [HttpGet("fish/{id}")]
    public IActionResult GetFish(string id) => repository.GetFish(id) is { } value ? Ok(value) : NotFound();

    /// <summary>釣り場の一覧を取得します。</summary>
    [HttpGet("areas")]
    public IActionResult GetAreas() => Ok(repository.GetAreas());

    /// <summary>指定 ID の釣り場を取得します。</summary>
    [HttpGet("areas/{id}")]
    public IActionResult GetArea(string id) => repository.GetArea(id) is { } value ? Ok(value) : NotFound();

    /// <summary>釣りルールの一覧を取得します。</summary>
    [HttpGet("rules")]
    public IActionResult GetRules() => Ok(repository.GetRules());

    /// <summary>指定 ID の釣りルールを取得します。</summary>
    [HttpGet("rules/{id}")]
    public IActionResult GetRules(string id) => repository.GetRules(id) is { } value ? Ok(value) : NotFound();
}
