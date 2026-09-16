using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ログイン中の本人だけが閲覧できる Mob 討伐記録を返します。</summary>
[ApiController]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[Route("api/web-bestiary")]
public sealed class WebBestiaryController(IWebBestiaryRepository repository) : ControllerBase
{
    /// <summary>本人の討伐済み Mob 一覧を返します。</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetList(
        [FromQuery(Name = "viewer_user_uuid")] Guid viewerUserUuid,
        [FromQuery(Name = "account_id")] Guid? accountId)
        => viewerUserUuid == Guid.Empty
            ? NotFound()
            : await repository.GetListAsync(viewerUserUuid, accountId) is { } result ? Ok(result) : NotFound();

    /// <summary>本人が討伐済みの指定 Mob 詳細を返します。</summary>
    [HttpGet("{mobId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetDetail(
        string mobId,
        [FromQuery(Name = "viewer_user_uuid")] Guid viewerUserUuid,
        [FromQuery(Name = "account_id")] Guid? accountId)
        => viewerUserUuid == Guid.Empty || string.IsNullOrWhiteSpace(mobId)
            ? NotFound()
            : await repository.GetDetailAsync(viewerUserUuid, accountId, mobId) is { } result ? Ok(result) : NotFound();
}
