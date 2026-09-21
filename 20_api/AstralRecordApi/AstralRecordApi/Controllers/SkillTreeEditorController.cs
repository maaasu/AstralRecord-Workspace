using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Web要求とPlugin確定処理を分離するスキルツリー安全編集APIです。</summary>
[ApiController]
[Route("api/skilltree")]
public sealed class SkillTreeEditorController(ISkillTreeOperationRepository repository, IConfiguration configuration) : ControllerBase
{
    [HttpGet("editor/{accountId:guid}")]
    [ProducesResponseType<SkillTreeEditorResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetEditor(Guid accountId, [FromQuery(Name = "actor_user_id")] Guid actorUserId, [FromQuery(Name = "target_server_id")] string? targetServerId = null)
        => actorUserId == Guid.Empty ? BadRequest() : await repository.GetEditorAsync(accountId, actorUserId, targetServerId) is { } result ? Ok(result) : NotFound();

    [HttpPost("editor/{accountId:guid}/operations")]
    [ProducesResponseType<SkillTreeOperationResponse>(StatusCodes.Status201Created)]
    public async Task<IActionResult> Create(Guid accountId, [FromBody] SkillTreeOperationCreateRequest request)
    {
        var result = await repository.CreateAsync(accountId, request);
        return result is null ? Conflict(new { message = "最新の状態を再取得してください。" }) : Created($"/api/skilltree/editor/{accountId}/operations/{result.OperationId}", result);
    }

    [HttpGet("editor/{accountId:guid}/operations/{operationId:guid}")]
    public async Task<IActionResult> GetOperation(Guid accountId, Guid operationId, [FromQuery(Name = "actor_user_id")] Guid actorUserId)
        => actorUserId == Guid.Empty ? BadRequest() : await repository.FindAsync(accountId, operationId, actorUserId) is { } result ? Ok(result) : NotFound();

    [HttpDelete("editor/{accountId:guid}/operations/{operationId:guid}")]
    public async Task<IActionResult> Cancel(Guid accountId, Guid operationId, [FromQuery(Name = "actor_user_id")] Guid actorUserId)
        => actorUserId == Guid.Empty ? BadRequest() : await repository.CancelAsync(accountId, operationId, actorUserId) is { } result ? Ok(result) : NotFound();

    [HttpPut("runtime/servers/{serverId}")]
    public async Task<IActionResult> Register(string serverId, [FromBody] SkillTreeServerRegistrationRequest request)
        => !HasRuntimeCredential() ? Unauthorized() : await repository.RegisterServerAsync(serverId, request) is { } result ? Ok(result) : BadRequest();

    [HttpPost("runtime/servers/{serverId}/heartbeat")]
    public async Task<IActionResult> Heartbeat(string serverId, [FromBody] SkillTreeServerHeartbeatRequest request)
        => !HasRuntimeCredential() ? Unauthorized() : await repository.HeartbeatServerAsync(serverId, request) is { } result ? Ok(result) : Conflict();

    /// <summary>実ロード定義でPluginが評価したプレイヤー別ツリー表示・編集可否を更新します。</summary>
    [HttpPut("runtime/servers/{serverId}/accounts/{accountId:guid}/view")]
    public async Task<IActionResult> RegisterPlayerView(string serverId, Guid accountId, [FromBody] SkillTreePlayerViewRegistrationRequest request)
        => !HasRuntimeCredential() ? Unauthorized() : await repository.RegisterPlayerViewAsync(serverId, accountId, request) is { } result ? Ok(result) : Conflict();

    [HttpGet("runtime/servers/{serverId}/operations")]
    public async Task<IActionResult> GetClaimable(string serverId, [FromQuery(Name = "server_session_id")] Guid serverSessionId, [FromQuery(Name = "account_id")] Guid accountId)
    {
        if (!HasRuntimeCredential()) return Unauthorized();
        return await repository.GetClaimableAsync(serverId, serverSessionId, accountId) is { } result ? Ok(result) : Conflict();
    }

    [HttpPost("runtime/servers/{serverId}/operations/{operationId:guid}/claim")]
    public async Task<IActionResult> Claim(string serverId, Guid operationId, [FromBody] SkillTreeOperationClaimRequest request)
    {
        if (!HasRuntimeCredential()) return Unauthorized();
        return await repository.ClaimAsync(serverId, operationId, request) is { } result ? Ok(result) : Conflict();
    }

    private bool HasRuntimeCredential()
    {
        var expected = configuration["SkillTreeRuntime:Key"];
        var sharedApiKey = configuration["ApiKey:Key"];
        var provided = Request.Headers["X-SkillTree-Runtime-Key"].FirstOrDefault();
        if (string.IsNullOrWhiteSpace(expected) || string.IsNullOrWhiteSpace(provided)) return false;
        if (!string.IsNullOrEmpty(sharedApiKey) && string.Equals(expected, sharedApiKey, StringComparison.Ordinal)) return false;
        var left = Encoding.UTF8.GetBytes(expected); var right = Encoding.UTF8.GetBytes(provided);
        return left.Length == right.Length && CryptographicOperations.FixedTimeEquals(left, right);
    }
}
