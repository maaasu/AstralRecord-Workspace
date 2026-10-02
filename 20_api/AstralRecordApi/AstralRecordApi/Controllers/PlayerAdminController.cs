using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>管理者によるプレイヤーの安全なオフライン編集とサーバー退避を扱います。</summary>
[ApiController]
[Route("api/player-admin")]
public sealed class PlayerAdminController(
    IPlayerAdminEditRepository edits,
    IWebAuthRepository webAuth,
    IConfiguration configuration) : ControllerBase
{
    /// <summary>ユーザーの全ゲーム接続を止める編集セッションを開始します。</summary>
    [HttpPost("accounts/{accountId:guid}/edit-sessions")]
    public async Task<IActionResult> Start(Guid accountId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminEditStartRequest request)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.StartAsync(accountId, actor, request));

    /// <summary>指定アカウントの有効な編集セッションを返します。</summary>
    [HttpGet("accounts/{accountId:guid}/edit-session")]
    public async Task<IActionResult> GetActive(Guid accountId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.GetActiveAsync(accountId, actor));

    /// <summary>完了済みも含めて編集セッションの結果を返します。</summary>
    [HttpGet("edit-sessions/{editSessionId:guid}")]
    public async Task<IActionResult> GetById(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.GetByIdAsync(editSessionId, actor));

    /// <summary>保存済みのサーバー退避証明を確認して編集権を更新します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/refresh")]
    public async Task<IActionResult> Refresh(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.RefreshAsync(editSessionId, actor));

    /// <summary>安全に退避したアカウントの編集用スナップショットを返します。</summary>
    [HttpGet("edit-sessions/{editSessionId:guid}/editor")]
    public async Task<IActionResult> GetEditor(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.GetEditorAsync(editSessionId, actor));

    /// <summary>期待版を検証して変更と監査用領収書を一つのトランザクションで確定します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/apply")]
    public async Task<IActionResult> Apply(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminEditOperationRequest request)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.ApplyAsync(editSessionId, actor, request));

    /// <summary>全サーバー退避確認後、変更せずに編集ロックを解除します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/cancel")]
    public async Task<IActionResult> Cancel(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminEditCancelRequest request)
        => !HasWebCredential() ? Unauthorized()
            : !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.CancelAsync(editSessionId, actor, request));

    /// <summary>ゲーム・ロビー・Proxy の起動個体を永続登録します。</summary>
    [HttpPut("runtime/servers/{serverId}")]
    public async Task<IActionResult> RegisterServer(string serverId,
        [FromBody] PlayerAdminRuntimeRegistrationRequest request)
        => !HasRuntimeCredential() ? Unauthorized()
            : Respond(await edits.RegisterServerAsync(serverId, request));

    /// <summary>指定した起動個体が退避すべきユーザーと継続ロックを返します。</summary>
    [HttpGet("runtime/servers/{serverId}/drains")]
    public async Task<IActionResult> GetDrains(string serverId,
        [FromQuery(Name = "server_session_id")] Guid serverSessionId)
        => !HasRuntimeCredential() ? Unauthorized()
            : Respond(await edits.GetDrainsAsync(serverId, serverSessionId));

    /// <summary>保存とログアウトを確認できたサーバーの退避証明を登録します。</summary>
    [HttpPost("runtime/edit-sessions/{editSessionId:guid}/drain-ack")]
    public async Task<IActionResult> AcknowledgeDrain(Guid editSessionId,
        [FromBody] PlayerAdminDrainAckRequest request)
        => !HasRuntimeCredential() ? Unauthorized()
            : Respond(await edits.AcknowledgeDrainAsync(editSessionId, request));

    private Task<bool> CanEditAsync(Guid actor) => actor == Guid.Empty
        ? Task.FromResult(false) : webAuth.IsWebAdminAsync(actor);

    private bool HasWebCredential()
    {
        var expected = configuration["PlayerAdmin:WebKey"];
        var provided = Request.Headers["X-Player-Admin-Web-Key"].FirstOrDefault();
        var common = configuration["ApiKey:Key"];
        if (string.IsNullOrWhiteSpace(expected) || string.IsNullOrWhiteSpace(provided)
            || string.Equals(expected, common, StringComparison.Ordinal)) return false;
        var left = Encoding.UTF8.GetBytes(expected);
        var right = Encoding.UTF8.GetBytes(provided);
        return left.Length == right.Length && CryptographicOperations.FixedTimeEquals(left, right);
    }

    private bool HasRuntimeCredential()
    {
        var expected = configuration["PlayerAdmin:RuntimeKey"];
        var provided = Request.Headers["X-Player-Admin-Runtime-Key"].FirstOrDefault();
        var common = configuration["ApiKey:Key"];
        var web = configuration["PlayerAdmin:WebKey"];
        if (string.IsNullOrWhiteSpace(expected) || string.IsNullOrWhiteSpace(provided)
            || string.Equals(expected, common, StringComparison.Ordinal)
            || string.Equals(expected, web, StringComparison.Ordinal)) return false;
        var left = Encoding.UTF8.GetBytes(expected);
        var right = Encoding.UTF8.GetBytes(provided);
        return left.Length == right.Length && CryptographicOperations.FixedTimeEquals(left, right);
    }

    private IActionResult Respond<T>(PlayerAdminResult<T> result) => result.Value is not null
        ? StatusCode(result.StatusCode, result.Value)
        : StatusCode(result.StatusCode, new ProblemDetails
        {
            Status = result.StatusCode,
            Title = result.Error ?? "Player admin operation failed.",
        });
}
