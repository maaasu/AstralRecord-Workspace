using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>スキル API</summary>
[ApiController]
[Route("api/[controller]")]
public class SkillController(ISkillRepository skillRepository) : ControllerBase
{
    /// <summary>スキル一覧取得（最小項目）</summary>
    /// <response code="200">スキル一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        var skills = skillRepository.GetAllSummaries();
        return Ok(skills);
    }

    /// <summary>スキル取得</summary>
    /// <param name="skillId">スキル ID</param>
    /// <response code="200">スキル取得成功</response>
    /// <response code="404">指定 ID のスキルが存在しない</response>
    [HttpGet("{skillId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string skillId)
    {
        var skill = skillRepository.GetById(skillId);
        if (skill is null)
            return NotFound();

        return Ok(skill);
    }
}
