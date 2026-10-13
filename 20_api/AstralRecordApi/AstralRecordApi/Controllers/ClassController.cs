using AstralRecordApi.Repositories;
using AstralRecordApi.Models;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>クラス API</summary>
[ApiController]
[Route("api/[controller]")]
public class ClassController(IClassRepository classRepository, IOptions<JsonOptions>? jsonOptions = null) : ControllerBase
{
    /// <summary>クラス一覧取得（最小項目）</summary>
    /// <response code="200">クラス一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAll(CancellationToken cancellationToken = default)
    {
        var classes = await classRepository.GetAllSummariesAsync(cancellationToken);
        return Ok(classes);
    }

    /// <summary>クラス取得</summary>
    /// <param name="cancellationToken">要求のキャンセル</param>
    /// <param name="classId">クラス ID</param>
    /// <response code="200">クラス取得成功</response>
    /// <response code="404">指定 ID のクラスが存在しない</response>
    [HttpGet("{classId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetById(string classId, CancellationToken cancellationToken = default)
    {
        var cls = await classRepository.GetByIdAsync(classId, cancellationToken);
        if (cls is null)
            return NotFound();

        return Ok(cls);
    }
    /// <summary>有効なClass詳細を同一世代から分割取得する（最大100件）。世代変更時は409。</summary>
    [HttpGet("details")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> GetDetails([FromQuery] int offset = 0, [FromQuery] int limit = 100,
        [FromQuery] string? generation = null, CancellationToken cancellationToken = default)
    {
        if (offset < 0 || limit is < 1 or > 100) return BadRequest();
        var page = await classRepository.GetDetailsAsync(offset, limit, cancellationToken);
        if (generation is not null && generation != page.Generation) return Conflict();
        return Ok(new MasterDataPageResponse<MasterDataDetailResponse<ClassResponse>>(
            page.Generation, page.Total, page.Items.Select(master =>
                MasterDataDetailResponse<ClassResponse>.Create(master, (jsonOptions?.Value ?? new JsonOptions()).JsonSerializerOptions)).ToArray()));
    }
}
