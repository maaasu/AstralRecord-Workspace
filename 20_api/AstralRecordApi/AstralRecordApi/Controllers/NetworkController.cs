using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;
using System.Security.Cryptography;
using System.Text;

namespace AstralRecordApi.Controllers;

/// <summary>ロビー・Proxy間のネットワーク実行時状態APIです。</summary>
[ApiController]
[Route("api/network")]
public sealed class NetworkController(
    IUserRepository userRepository,
    INetworkRuntimeService runtimeService,
    TimeProvider timeProvider,
    IConfiguration configuration,
    INetworkManagementRepository management) : ControllerBase
{
    private const string AuthoritySyncHeader = "X-Authority-Sync-Key";
    /// <summary>選択中アカウントの有効VIPとチャンネル参加権限を都度判定します。</summary>
    [HttpGet("admissions/{uuid:guid}")]
    [ProducesResponseType<NetworkAdmissionResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAdmission(Guid uuid, [FromQuery] string? serverId = null)
    {
        var user = await userRepository.GetByUuidAsync(uuid);
        var settings = await management.GetSettingsAsync();
        var ban = await management.GetBanAsync(uuid);
        var access = await management.GetChannelAccessAsync(uuid, serverId ?? settings?.LobbyServerId ?? "lobby");
        var banned = ban?.IsActive ?? (user?.BanIndefinite == true || user?.BanDate > timeProvider.GetLocalNow().DateTime);
        var banDate = ban is null ? user?.BanDate : ban.ExpiresAtUtc is DateTimeOffset expiry
            ? TimeZoneInfo.ConvertTime(expiry, timeProvider.LocalTimeZone).DateTime : (DateTime?)null;
        var authorityPermission = runtimeService.IsAuthority(uuid) || user?.Permission == 99 ? 99 : 0;
        return Ok(new NetworkAdmissionResponse(uuid, user?.Mcid ?? ban?.Mcid ?? string.Empty, user is not null,
            !banned && (settings is null || access.Allowed), banned ? "banned" : settings is not null && !access.ChannelKnown ? "unknown_channel" : settings is not null && !access.Allowed ? (access.DonorOnly && !access.IsVip ? "vip_required" : "not_whitelisted") : null,
            settings is null ? authorityPermission : access.Permission, ban?.IsIndefinite ?? user?.BanIndefinite ?? false, banDate, user?.AccountId,
            timeProvider.GetUtcNow().UtcDateTime, ban?.Reason, ban?.ExpiresAtUtc,
            access.DebugUser, access.Whitelisted, access.ChannelKnown, settings is not null, access.VipTier, access.VipExpiresAt));
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

    /// <summary>サーバー人数と一般・寄付者・管理者の接続枠を更新します。</summary>
    [HttpPut("servers/{serverId}")]
    [ProducesResponseType<NetworkServerPresenceResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public IActionResult HeartbeatServer(string serverId, [FromBody] NetworkServerHeartbeatRequest request)
    {
        if (!string.Equals(serverId, request.ServerId, StringComparison.OrdinalIgnoreCase)
            || string.IsNullOrWhiteSpace(request.DisplayName)
            || string.IsNullOrWhiteSpace(request.State)
            || request.OnlineCount < 0 || request.Capacity < 0
            || request.DonorExtraPlayers < 0 || request.AdminExtraPlayers < 0)
            return BadRequest();

        return Ok(runtimeService.UpsertServer(request));
    }

    /// <summary>期限内のサーバー人数と権限別接続枠を一覧で返します。</summary>
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
            || request.Kind is not ("chat" or "lifecycle")
            || request.Action is not null
                && request.Action is not ("join" or "channel_connect" or "leave")
            || request.Kind == "chat" && request.Action is not null
            || request.Action is not null && request.Source != "minecraft"
            || request.Action is not null
                && (!request.AuthorPlayerId.HasValue || request.AuthorMinecraftName is null)
            || request.AuthorName.Length > 64 || request.Message.Length > 512
            || request.AuthorPlayerId == Guid.Empty
            || request.AuthorMinecraftName is not null
                && (string.IsNullOrWhiteSpace(request.AuthorMinecraftName)
                    || request.AuthorMinecraftName.Length > 64)
            || request.AuthorPlayerId.HasValue != (request.AuthorMinecraftName is not null))
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

    /// <summary>初期移行前の旧Proxy専用同期。ManagementDB初期化後は上書きを拒否します。</summary>
    [HttpPut("authorities")]
    [ProducesResponseType<IReadOnlyList<Guid>>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    public async Task<IActionResult> ReplaceAuthorities([FromBody] NetworkAuthorityUpdateRequest request)
    {
        if (!HasAuthoritySyncCredential())
            return Unauthorized();
        if (await management.GetSettingsAsync() is not null)
            return Conflict(new { message = "サーバー設定はManagementDBで管理されています。" });
        if (request.Uuids is null || request.Uuids.Any(uuid => uuid == Guid.Empty))
            return BadRequest();
        return Ok(runtimeService.ReplaceAuthorities(request));
    }

    /// <summary>現在のサーバー最高権限UUID一覧を返します。</summary>
    [HttpGet("authorities")]
    [ProducesResponseType<IReadOnlyList<Guid>>(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAuthorities() => Ok((await management.GetSettingsAsync())?.AuthorityUsers ?? runtimeService.GetAuthorities());

    /// <summary>ProxyとRPGへ永続設定を返します。移行前は404です。</summary>
    [HttpGet("settings")]
    public async Task<IActionResult> GetSettings() => await management.GetSettingsAsync() is { } settings ? Ok(settings) : NotFound();

    /// <summary>既存Proxy YAMLを一度だけ初期移行します。保存済みなら現在値を返すだけです。</summary>
    [HttpPost("settings/bootstrap")]
    public async Task<IActionResult> Bootstrap([FromBody] ManagedNetworkSettings request)
    {
        if (!HasAuthoritySyncCredential()) return Unauthorized();
        try
        {
            var result = await management.BootstrapAsync(request);
            return StatusCode(result.Created ? 201 : 200, result.Settings);
        }
        catch (ArgumentException ex) { return BadRequest(new { message = ex.Message }); }
        catch (NetworkManagementConflictException ex) { return Conflict(new { message = ex.Message }); }
    }

    /// <summary>チャンネル固有のdebug/whitelistと実効権限を返します。</summary>
    [HttpGet("channel-access/{uuid:guid}")]
    public async Task<IActionResult> GetChannelAccess(Guid uuid, [FromQuery] string serverId)
        => Ok(await management.GetChannelAccessAsync(uuid, serverId));

    /// <summary>Proxyが接続中プレイヤーのBANを反映するための有効一覧です。</summary>
    [HttpGet("bans/active")]
    public async Task<IActionResult> GetActiveBans() => Ok(await management.GetActiveBansAsync());

    /// <summary>信頼済みゲームサーバー向けにユーザーBANの現在版を返します。</summary>
    [HttpGet("bans/{uuid:guid}")]
    public async Task<IActionResult> GetRuntimeBan(Guid uuid) => await management.GetBanAsync(uuid) is { } ban ? Ok(ban) : NotFound();

    /// <summary>既存ゲーム内BANコマンドを共通BAN正本へ保存します。nil actorは従来のSystemUser(Console)です。</summary>
    [HttpPut("bans/{uuid:guid}")]
    public async Task<IActionResult> UpdateRuntimeBan(Guid uuid, [FromQuery(Name = "actor_user_uuid"), Microsoft.AspNetCore.Mvc.ModelBinding.BindRequired] Guid actor, [FromBody] NetworkBanUpdateRequest request)
    {
        if (!HasDedicatedCredential("Network:ModerationKey", "X-Network-Moderation-Key")) return Unauthorized();
        if (!await management.CanManageBanFromGameAsync(actor)) return StatusCode(403);
        try { return await management.UpdateBanAsync(uuid, request, actor) is { } ban ? Ok(ban) : NotFound(); }
        catch (ArgumentException ex) { return BadRequest(new { message = ex.Message }); }
        catch (NetworkManagementConflictException ex) { return Conflict(new { message = ex.Message }); }
    }

    private bool HasAuthoritySyncCredential() => HasDedicatedCredential("Network:AuthoritySyncKey", AuthoritySyncHeader);

    private bool HasDedicatedCredential(string configurationKey, string header)
    {
        var expected = configuration[configurationKey];
        var sharedApiKey = configuration["ApiKey:Key"];
        var provided = Request.Headers[header].FirstOrDefault();
        if (string.IsNullOrEmpty(expected) || string.IsNullOrEmpty(provided))
            return false;
        if (!string.IsNullOrEmpty(sharedApiKey)
            && string.Equals(expected, sharedApiKey, StringComparison.Ordinal))
            return false;
        var expectedBytes = Encoding.UTF8.GetBytes(expected);
        var providedBytes = Encoding.UTF8.GetBytes(provided);
        return expectedBytes.Length == providedBytes.Length
            && CryptographicOperations.FixedTimeEquals(expectedBytes, providedBytes);
    }
}
