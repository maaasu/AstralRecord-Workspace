using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ロビー・Proxy間のネットワーク実行時状態APIです。</summary>
[ApiController]
[Route("api/network")]
public sealed class NetworkController(
    IUserRepository userRepository,
    INetworkRuntimeService runtimeService,
    TimeProvider timeProvider) : ControllerBase
{
    [HttpGet("admissions/{uuid:guid}")]
    [ProducesResponseType<NetworkAdmissionResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAdmission(Guid uuid)
    {
        var user = await userRepository.GetByUuidAsync(uuid);
        var nowLocal = timeProvider.GetLocalNow().DateTime;
        if (user is null)
        {
            return Ok(new NetworkAdmissionResponse(
                uuid, string.Empty, false, true, null, 0, false, null, null,
                timeProvider.GetUtcNow().UtcDateTime));
        }

        var banned = user.BanIndefinite || user.BanDate is not null && user.BanDate > nowLocal;
        return Ok(new NetworkAdmissionResponse(
            user.Uuid,
            user.Mcid,
            true,
            !banned,
            banned ? "banned" : null,
            user.Permission,
            user.BanIndefinite,
            user.BanDate,
            user.AccountId,
            timeProvider.GetUtcNow().UtcDateTime));
    }

    [HttpPut("players/{uuid:guid}")]
    [ProducesResponseType<NetworkPlayerPresenceResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public IActionResult HeartbeatPlayer(Guid uuid, [FromBody] NetworkPlayerHeartbeatRequest request)
    {
        if (uuid != request.Uuid || string.IsNullOrWhiteSpace(request.Mcid)
            || string.IsNullOrWhiteSpace(request.ServerId) || string.IsNullOrWhiteSpace(request.Channel)
            || string.IsNullOrWhiteSpace(request.DisplayName))
            return BadRequest();

        return Ok(runtimeService.UpsertPlayer(request));
    }

    [HttpDelete("players/{uuid:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public IActionResult RemovePlayer(Guid uuid)
    {
        runtimeService.RemovePlayer(uuid);
        return NoContent();
    }

    [HttpGet("players")]
    [ProducesResponseType<IReadOnlyList<NetworkPlayerPresenceResponse>>(StatusCodes.Status200OK)]
    public IActionResult GetPlayers() => Ok(runtimeService.GetPlayers());

    [HttpPut("servers/{serverId}")]
    [ProducesResponseType<NetworkServerPresenceResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public IActionResult HeartbeatServer(string serverId, [FromBody] NetworkServerHeartbeatRequest request)
    {
        if (!string.Equals(serverId, request.ServerId, StringComparison.OrdinalIgnoreCase)
            || string.IsNullOrWhiteSpace(request.DisplayName)
            || string.IsNullOrWhiteSpace(request.State)
            || request.OnlineCount < 0 || request.Capacity < 0)
            return BadRequest();

        return Ok(runtimeService.UpsertServer(request));
    }

    [HttpGet("servers")]
    [ProducesResponseType<IReadOnlyList<NetworkServerPresenceResponse>>(StatusCodes.Status200OK)]
    public IActionResult GetServers() => Ok(runtimeService.GetServers());

    [HttpPost("chat")]
    [ProducesResponseType<NetworkChatMessageResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public IActionResult PublishChat([FromBody] NetworkChatPublishRequest request)
    {
        if (request.MessageId == Guid.Empty || string.IsNullOrWhiteSpace(request.Source)
            || request.Source is not ("minecraft" or "discord")
            || string.IsNullOrWhiteSpace(request.SourceServerId)
            || string.IsNullOrWhiteSpace(request.AuthorName)
            || string.IsNullOrWhiteSpace(request.Message)
            || request.AuthorName.Length > 64 || request.Message.Length > 512)
            return BadRequest();

        return Ok(runtimeService.PublishChat(request));
    }

    [HttpGet("chat")]
    [ProducesResponseType<NetworkChatBatchResponse>(StatusCodes.Status200OK)]
    public IActionResult GetChat([FromQuery] long afterSequence = 0, [FromQuery] string? source = null)
    {
        if (afterSequence < 0 || source is not null && source is not ("minecraft" or "discord"))
            return BadRequest();
        return Ok(runtimeService.GetChatAfter(afterSequence, source));
    }
}
