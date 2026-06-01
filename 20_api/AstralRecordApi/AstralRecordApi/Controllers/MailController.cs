using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>メール API</summary>
[ApiController]
[Route("api/mail")]
public class MailController(IMailRepository mailRepository) : ControllerBase
{
    /// <summary>期限内かつ未削除のメール一覧を取得</summary>
    /// <param name="userId">ユーザー ID（query: user_id）</param>
    /// <param name="filter">all / unread / read</param>
    /// <response code="200">メール一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetByUserId(
        [FromQuery(Name = "user_id")] Guid userId,
        [FromQuery] string? filter)
    {
        return Ok(await mailRepository.GetAvailableByUserIdAsync(userId, filter));
    }

    /// <summary>メールを既読にする</summary>
    /// <param name="mailId">メール ID</param>
    /// <param name="request">更新者情報</param>
    /// <response code="200">既読更新成功</response>
    /// <response code="404">指定メールが存在しない</response>
    [HttpPut("{mailId}/read")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> MarkRead(string mailId, [FromBody] MailActionRequest request)
    {
        var mail = await mailRepository.MarkReadAsync(mailId, request);
        return mail is null ? NotFound() : Ok(mail);
    }

    /// <summary>プレイヤー単位でメールを一覧から削除する</summary>
    /// <param name="mailId">メール ID</param>
    /// <param name="request">更新者情報</param>
    /// <response code="204">削除状態更新成功</response>
    /// <response code="404">指定メールが存在しない</response>
    [HttpPut("{mailId}/delete")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(string mailId, [FromBody] MailActionRequest request)
    {
        return await mailRepository.DeleteAsync(mailId, request) ? NoContent() : NotFound();
    }
}
