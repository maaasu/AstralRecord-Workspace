using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>スキルバインドプリセット API</summary>
[ApiController]
[Route("api/skill-bind-presets")]
public class SkillBindPresetController(ISkillBindPresetRepository repository) : ControllerBase
{
    /// <summary>アカウントのスキルバインドプリセット一覧を取得します。</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetByAccountId([FromQuery(Name = "account_id")] Guid accountId)
    {
        var presets = await repository.GetByAccountIdAsync(accountId);
        return Ok(presets);
    }

    /// <summary>指定プリセットへスキルバインドを保存します。</summary>
    [HttpPut("{accountId:guid}/{presetIndex:int}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Upsert(
        Guid accountId,
        int presetIndex,
        [FromBody] SkillBindPresetUpsertRequest request)
    {
        var updated = await repository.UpsertAsync(accountId, presetIndex, request);
        if (updated is null)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Preset index is invalid.");

        return Ok(updated);
    }
}
