using AstralRecordApi.Repositories;
using AstralRecordApi.Models;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>スキル API</summary>
[ApiController]
[Route("api/[controller]")]
public class SkillController(ISkillRepository skillRepository, IOptions<JsonOptions>? jsonOptions = null) : ControllerBase
{
    /// <summary>スキル一覧取得（最小項目）</summary>
    /// <response code="200">スキル一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAll(CancellationToken cancellationToken = default)
    {
        var skills = await skillRepository.GetAllSummariesAsync(cancellationToken);
        return Ok(skills);
    }

    /// <summary>スキル取得</summary>
    /// <param name="cancellationToken">要求のキャンセル</param>
    /// <param name="skillId">スキル ID</param>
    /// <response code="200">スキル取得成功</response>
    /// <response code="404">指定 ID のスキルが存在しない</response>
    [HttpGet("{skillId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetById(string skillId, CancellationToken cancellationToken = default)
    {
        var skill = await skillRepository.GetByIdAsync(skillId, cancellationToken);
        if (skill is null)
            return NotFound();

        return Ok(skill);
    }
    /// <summary>有効なSkill詳細を同一世代から分割取得する（最大100件）。世代変更時は409。</summary>
    [HttpGet("details")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> GetDetails([FromQuery] int offset = 0, [FromQuery] int limit = 100,
        [FromQuery] string? generation = null, CancellationToken cancellationToken = default)
    {
        if (offset < 0 || limit is < 1 or > 100) return BadRequest();
        var page = await skillRepository.GetDetailsAsync(offset, limit, cancellationToken);
        if (generation is not null && generation != page.Generation) return Conflict();
        return Ok(new MasterDataPageResponse<MasterDataDetailResponse<SkillResponse>>(
            page.Generation, page.Total, page.Items.Select(master =>
                MasterDataDetailResponse<SkillResponse>.Create(master, (jsonOptions?.Value ?? new JsonOptions()).JsonSerializerOptions)).ToArray()));
    }
}
