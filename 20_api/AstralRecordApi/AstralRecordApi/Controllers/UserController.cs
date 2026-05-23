using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ユーザー API</summary>
[ApiController]
[Route("api/[controller]")]
public class UserController(IUserRepository userRepository) : ControllerBase
{
    /// <summary>ユーザー情報取得</summary>
    /// <param name="uuid">Minecraft プレイヤーの UUID</param>
    /// <response code="200">ユーザー取得成功</response>
    /// <response code="404">指定 UUID のユーザーが存在しない、または論理削除済み</response>
    [HttpGet("{uuid:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByUuid(Guid uuid)
    {
        var user = await userRepository.GetByUuidAsync(uuid);
        if (user is null)
            return NotFound();

        return Ok(user);
    }

    /// <summary>ユーザー登録</summary>
    /// <param name="request">登録するユーザー情報</param>
    /// <response code="201">ユーザー登録成功</response>
    /// <response code="409">指定 UUID のユーザーが既に存在する</response>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> Create([FromBody] UserCreateRequest request)
    {
        var existing = await userRepository.GetByUuidAsync(request.Uuid);
        if (existing is not null)
            return Conflict();

        var created = await userRepository.CreateAsync(request);
        return CreatedAtAction(nameof(GetByUuid), new { uuid = created.Uuid }, created);
    }

    /// <summary>ユーザー履歴登録</summary>
    /// <param name="request">登録する履歴情報</param>
    /// <response code="201">履歴登録成功</response>
    /// <response code="400">必須項目不足</response>
    /// <response code="404">指定ユーザーが存在しない</response>
    [HttpPost("history")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> CreateHistory([FromBody] UserHistoryCreateRequest request)
    {
        if (request.EventTime == default
            || string.IsNullOrWhiteSpace(request.EventType)
            || string.IsNullOrWhiteSpace(request.Message))
            return BadRequest();

        if (request.UserUuid.HasValue)
        {
            var existingUser = await userRepository.GetByUuidAsync(request.UserUuid.Value);
            if (existingUser is null)
                return NotFound();
        }

        var created = await userRepository.CreateHistoryAsync(request);
        return StatusCode(StatusCodes.Status201Created, created);
    }

    /// <summary>ユーザー情報更新</summary>
    /// <param name="uuid">更新対象のプレイヤー UUID</param>
    /// <param name="request">更新内容</param>
    /// <response code="200">ユーザー更新成功</response>
    /// <response code="404">指定 UUID のユーザーが存在しない、または論理削除済み</response>
    [HttpPut("{uuid:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Update(Guid uuid, [FromBody] UserUpdateRequest request)
    {
        var updated = await userRepository.UpdateAsync(uuid, request);
        if (updated is null)
            return NotFound();

        return Ok(updated);
    }
}
