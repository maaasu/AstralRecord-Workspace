using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>プレイヤー設定 API</summary>
[ApiController]
[Route("api/[controller]")]
public class PlayerSettingController(IPlayerSettingRepository playerSettingRepository) : ControllerBase
{
    /// <summary>ユーザ単位のプレイヤー設定一覧を取得</summary>
    /// <param name="userId">ユーザ ID（query: user_id）</param>
    /// <response code="200">プレイヤー設定一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetByUserId([FromQuery(Name = "user_id")] Guid userId)
    {
        var settings = await playerSettingRepository.GetByUserIdAsync(userId);
        return Ok(settings);
    }

    /// <summary>プレイヤー設定を取得</summary>
    /// <param name="userSettingId">プレイヤー設定 ID</param>
    /// <response code="200">プレイヤー設定取得成功</response>
    /// <response code="404">指定 ID のプレイヤー設定が存在しない</response>
    [HttpGet("{userSettingId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetById(Guid userSettingId)
    {
        var setting = await playerSettingRepository.GetByIdAsync(userSettingId);
        if (setting is null)
            return NotFound();

        return Ok(setting);
    }

    /// <summary>プレイヤー設定を登録</summary>
    /// <param name="request">登録内容</param>
    /// <response code="201">プレイヤー設定登録成功</response>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<IActionResult> Create([FromBody] PlayerSettingCreateRequest request)
    {
        var created = await playerSettingRepository.CreateAsync(request);
        return CreatedAtAction(nameof(GetById), new { userSettingId = created.UserSettingId }, created);
    }

    /// <summary>プレイヤー設定を更新</summary>
    /// <param name="userSettingId">更新対象のプレイヤー設定 ID</param>
    /// <param name="request">更新内容（expectedVersion 必須）</param>
    /// <response code="200">更新成功</response>
    /// <response code="404">指定 ID のプレイヤー設定が存在しない</response>
    /// <response code="409">楽観ロック競合（version 不一致）</response>
    [HttpPut("{userSettingId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> Update(Guid userSettingId, [FromBody] PlayerSettingUpdateRequest request)
    {
        var result = await playerSettingRepository.UpdateAsync(userSettingId, request);
        if (result is null)
            return NotFound();

        if (result.IsVersionConflict)
            return Conflict(result.Current);

        return Ok(result.Updated);
    }
}
