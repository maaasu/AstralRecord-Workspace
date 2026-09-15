using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Webの本人・公開プレイヤープロフィールを取得します。</summary>
[ApiController]
[Route("api/web-profiles")]
public sealed class WebPlayerProfileController(IWebPlayerProfileRepository repository) : ControllerBase
{
    /// <summary>ログイン中の本人プロフィールを取得します。</summary>
    [HttpGet("me")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetMyProfile([FromQuery(Name = "viewer_user_uuid")] Guid viewerUserUuid)
        => viewerUserUuid == Guid.Empty ? BadRequest() : await ProfileOrNotFound(repository.GetMyProfileAsync(viewerUserUuid));

    /// <summary>本人の公開設定だけを更新します。</summary>
    [HttpPut("me/visibility")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> UpdateVisibility(
        [FromQuery(Name = "viewer_user_uuid")] Guid viewerUserUuid,
        [FromBody] WebPlayerProfileVisibilityUpdateRequest request)
        => viewerUserUuid == Guid.Empty ? BadRequest() : await ProfileOrNotFound(repository.UpdateVisibilityAsync(viewerUserUuid, request.IsPublic));

    /// <summary>公開済みプレイヤー、またはWeb管理者が許可された非公開プロフィールを取得します。</summary>
    [HttpGet("{userUuid:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetProfile(
        Guid userUuid,
        [FromQuery(Name = "viewer_user_uuid")] Guid viewerUserUuid,
        [FromQuery(Name = "include_private")] bool includePrivate = false)
        => await ProfileOrNotFound(repository.GetProfileAsync(userUuid, viewerUserUuid, includePrivate));

    /// <summary>公開プレイヤーを検索し、Web管理者だけは非公開・Web未ログイン登録者を含められます。</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Search(
        [FromQuery(Name = "viewer_user_uuid")] Guid viewerUserUuid,
        [FromQuery] string? mcid,
        [FromQuery(Name = "class_id")] string? classId,
        [FromQuery] string? sort = "level_desc",
        [FromQuery] int page = 1,
        [FromQuery(Name = "page_size")] int pageSize = 20,
        [FromQuery(Name = "include_private")] bool includePrivate = false)
    {
        if (page < 1 || pageSize is < 1 or > 100
            || sort is not ("level_desc" or "level_asc"))
            return BadRequest();
        return Ok(await repository.SearchAsync(viewerUserUuid, mcid, classId, sort, page, pageSize, includePrivate));
    }

    private async Task<IActionResult> ProfileOrNotFound(Task<WebPlayerProfileResponse?> task)
        => await task is { } profile ? Ok(profile) : NotFound();
}
