using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Web管理者による永続ネットワーク設定・ユーザー単位BAN管理。</summary>
[ApiController]
[Route("api/network-management")]
public sealed class NetworkManagementController(INetworkManagementRepository repository, IWebAuthRepository authorization) : ControllerBase
{
    /// <summary>管理設定とMCID表示情報を取得します。</summary>
    [HttpGet("settings")]
    public async Task<IActionResult> GetSettings([FromQuery(Name = "actor_user_uuid")] Guid actor)
    {
        if (!await IsAdmin(actor)) return StatusCode(403);
        return await repository.GetSettingsAsync(includePlayers: true) is { } settings ? Ok(settings) : NotFound();
    }

    /// <summary>期待revisionが一致する場合だけ設定を保存します。</summary>
    [HttpPut("settings")]
    public async Task<IActionResult> UpdateSettings([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] ManagedNetworkSettings request)
    {
        if (!await IsAdmin(actor)) return StatusCode(403);
        return await Execute(async () => await repository.UpdateSettingsAsync(request, actor));
    }

    /// <summary>AstralRecord DBの登録済みMCIDからUUID付き候補を検索します。</summary>
    [HttpGet("players")]
    public async Task<IActionResult> SearchPlayers([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] string? query)
    {
        if (!await IsAdmin(actor)) return StatusCode(403);
        return await Execute(async () => await repository.SearchPlayersAsync(query));
    }

    /// <summary>対象ユーザーの現在のBAN状態を取得します。</summary>
    [HttpGet("bans/{userUuid:guid}")]
    public async Task<IActionResult> GetBan(Guid userUuid, [FromQuery(Name = "actor_user_uuid")] Guid actor)
    {
        if (!await IsAdmin(actor)) return StatusCode(403);
        return await repository.GetBanAsync(userUuid) is { } ban ? Ok(ban) : NotFound();
    }

    /// <summary>無期限/期限付きBAN、または解除を対象UUIDと期待revisionで保存します。</summary>
    [HttpPut("bans/{userUuid:guid}")]
    public async Task<IActionResult> UpdateBan(Guid userUuid, [FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] NetworkBanUpdateRequest request)
    {
        if (!await IsAdmin(actor)) return StatusCode(403);
        return await Execute(async () => await repository.UpdateBanAsync(userUuid, request, actor));
    }

    private Task<bool> IsAdmin(Guid actor) => actor == Guid.Empty ? Task.FromResult(false) : authorization.IsWebAdminAsync(actor);
    private async Task<IActionResult> Execute(Func<Task<object?>> operation)
    {
        try { return await operation() is { } value ? Ok(value) : NotFound(); }
        catch (NetworkManagementConflictException ex) { return Conflict(new { message = ex.Message }); }
        catch (ArgumentException ex) { return BadRequest(new { message = ex.Message }); }
    }
}
