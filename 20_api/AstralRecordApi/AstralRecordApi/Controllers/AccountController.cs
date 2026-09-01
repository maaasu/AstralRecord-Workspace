using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>アカウント API</summary>
[ApiController]
[Route("api/[controller]")]
public class AccountController(IAccountRepository accountRepository) : ControllerBase
{
    /// <summary>アカウント登録</summary>
    /// <param name="request">登録するアカウント情報</param>
    /// <response code="201">アカウント登録成功</response>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Create([FromBody] AccountCreateRequest request)
    {
        if (!AccessControlContract.IsValidAccountMode(request.Mode))
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Account mode is invalid.");

        var created = await accountRepository.CreateAsync(request);
        return CreatedAtAction(nameof(GetByUuid), new { uuid = created.Uuid }, created);
    }

    /// <summary>アカウント情報更新</summary>
    /// <param name="uuid">更新対象のアカウント UUID</param>
    /// <param name="request">更新内容</param>
    /// <response code="200">アカウント更新成功</response>
    /// <response code="404">指定 UUID のアカウントが存在しない、または論理削除済み</response>
    [HttpPut("{uuid:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> Update(Guid uuid, [FromBody] AccountUpdateRequest request)
    {
        if (request.Mode.HasValue && !AccessControlContract.IsValidAccountMode(request.Mode.Value))
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Account mode is invalid.");

        try
        {
            var updated = await accountRepository.UpdateAsync(uuid, request);
            if (updated is null)
                return NotFound();

            return Ok(updated);
        }
        catch (AccountNameConflictException ex)
        {
            return Conflict(new { message = ex.Message });
        }
        catch (ArgumentException ex)
        {
            return BadRequest(new { message = ex.Message });
        }
    }

    /// <summary>user_idに紐づくアカウント一覧取得</summary>
    /// <param name="userId">ユーザー ID（クエリ: user_id）</param>
    /// <response code="200">アカウント一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetByUserId([FromQuery(Name = "user_id")] Guid userId)
    {
        var accounts = await accountRepository.GetByUserIdAsync(userId);
        return Ok(accounts);
    }

    /// <summary>アカウント情報取得</summary>
    /// <param name="uuid">アカウント UUID</param>
    /// <response code="200">アカウント取得成功</response>
    /// <response code="404">指定 UUID のアカウントが存在しない、または論理削除済み</response>
    [HttpGet("{uuid:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByUuid(Guid uuid)
    {
        var account = await accountRepository.GetByUuidAsync(uuid);
        if (account is null)
            return NotFound();

        return Ok(account);
    }

    /// <summary>アカウントとアカウント専用データを論理削除する</summary>
    /// <param name="uuid">削除対象のアカウント UUID</param>
    /// <param name="request">削除実行者</param>
    /// <response code="200">削除成功</response>
    /// <response code="404">指定 UUID のアカウントが存在しない、または論理削除済み</response>
    [HttpDelete("{uuid:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid uuid, [FromBody] AccountDeleteRequest request)
    {
        if (request.DeletedBy == Guid.Empty)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "deletedBy is required.");
        var deleted = await accountRepository.DeleteAsync(uuid, request);
        return deleted is null ? NotFound() : Ok(deleted);
    }
}
