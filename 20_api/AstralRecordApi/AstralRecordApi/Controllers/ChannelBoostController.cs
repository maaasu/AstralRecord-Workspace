using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>チャンネル別の経験値・ドロップブーストと発動イベント。</summary>
[ApiController]
[Route("api/channel-boosts")]
public sealed class ChannelBoostController(IChannelBoostRepository repository) : ControllerBase
{
    /// <summary>全ゲームチャンネルの現在状態・表示名・ネットワーク対象設定と、イベント購読開始カーソルを返します。</summary>
    [HttpGet]
    public async Task<IActionResult> Get() => Ok(await repository.GetSnapshotAsync());

    /// <summary>カーソル以後の発動イベントを古い順に返します。</summary>
    [HttpGet("events")]
    public async Task<IActionResult> Events([FromQuery] long after) => after < 0 ? BadRequest() : Ok(await repository.GetEventsAsync(after));

    /// <summary>アイテム消費とチャンネルブースト発動を原子的に確定します。</summary>
    [HttpPost("{channelId}/activate")]
    public async Task<IActionResult> Activate(string channelId, ChannelBoostActivateRequest request)
    {
        var result = await repository.ActivateAsync(channelId, request);
        return result.Status == "COMPLETED" ? Ok(result) : Conflict(result);
    }
}
