using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>クラス API</summary>
[ApiController]
[Route("api/[controller]")]
public class ClassController(IClassRepository classRepository) : ControllerBase
{
    /// <summary>クラス一覧取得（最小項目）</summary>
    /// <response code="200">クラス一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        var classes = classRepository.GetAllSummaries();
        return Ok(classes);
    }

    /// <summary>クラス取得</summary>
    /// <param name="classId">クラス ID</param>
    /// <response code="200">クラス取得成功</response>
    /// <response code="404">指定 ID のクラスが存在しない</response>
    [HttpGet("{classId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string classId)
    {
        var cls = classRepository.GetById(classId);
        if (cls is null)
            return NotFound();

        return Ok(cls);
    }
}
