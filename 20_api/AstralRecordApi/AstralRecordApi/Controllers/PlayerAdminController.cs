using AstralRecordApi.Authentication;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>共通APIキー認証と管理者権限確認でプレイヤーの安全なオフライン編集とサーバー退避を扱います。</summary>
[ApiController]
[Route("api/player-admin")]
public sealed class PlayerAdminController(
    IPlayerAdminEditRepository edits,
    IWebAuthRepository webAuth,
    WebCodeProofProtector codeProof,
    TimeProvider clock) : ControllerBase
{
    /// <summary>ユーザーの全ゲーム接続を止める編集セッションを開始します。</summary>
    [HttpPost("accounts/{accountId:guid}/edit-sessions")]
    public async Task<IActionResult> Start(Guid accountId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminEditStartRequest request)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.StartAsync(accountId, actor, request));

    /// <summary>指定アカウントの有効な編集セッションを返します。</summary>
    [HttpGet("accounts/{accountId:guid}/edit-session")]
    public async Task<IActionResult> GetActive(Guid accountId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.GetActiveAsync(accountId, actor));

    /// <summary>完了済みも含めて編集セッションの結果を返します。</summary>
    [HttpGet("edit-sessions/{editSessionId:guid}")]
    public async Task<IActionResult> GetById(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.GetByIdAsync(editSessionId, actor));

    /// <summary>保存済みのサーバー退避証明を確認して編集権を更新します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/refresh")]
    public async Task<IActionResult> Refresh(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.RefreshAsync(editSessionId, actor));

    /// <summary>安全に退避したアカウントの編集用スナップショットを返します。</summary>
    [HttpGet("edit-sessions/{editSessionId:guid}/editor")]
    public async Task<IActionResult> GetEditor(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.GetEditorAsync(editSessionId, actor));

    /// <summary>期待版を検証して変更と監査用領収書を一つのトランザクションで確定します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/apply")]
    public async Task<IActionResult> Apply(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminEditOperationRequest request)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.ApplyAsync(editSessionId, actor, request));

    /// <summary>本人のコード再認証後、退避済みのキャラクターを削除します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/delete")]
    public async Task<IActionResult> Delete(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminDeleteAccountRequest request)
    {
        if (!await CanEditAsync(actor) || request.SessionVersion == Guid.Empty)
            return StatusCode(403);
        var credential = await webAuth.GetCredentialAsync(actor);
        if (credential?.SessionVersion != request.SessionVersion
            || codeProof.Validate(request.CodeAuthenticationProof, actor, request.SessionVersion, clock.GetUtcNow()) is null)
            return StatusCode(403);

        return Respond(await edits.DeleteAccountAsync(editSessionId, actor,
            new(request.OperationId, request.ExpectedRevision, request.UserUuid, request.AccountId)));
    }

    /// <summary>全サーバー退避確認後、変更せずに編集ロックを解除します。</summary>
    [HttpPost("edit-sessions/{editSessionId:guid}/cancel")]
    public async Task<IActionResult> Cancel(Guid editSessionId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] PlayerAdminEditCancelRequest request)
        => !await CanEditAsync(actor) ? StatusCode(403)
            : Respond(await edits.CancelAsync(editSessionId, actor, request));

    /// <summary>共通APIキー認証でゲーム・ロビー・Proxyの起動個体を永続登録します。</summary>
    [HttpPut("runtime/servers/{serverId}")]
    public async Task<IActionResult> RegisterServer(string serverId,
        [FromBody] PlayerAdminRuntimeRegistrationRequest request)
        => Respond(await edits.RegisterServerAsync(serverId, request));

    /// <summary>共通APIキー認証で指定した起動個体の退避対象ユーザーと継続ロックを返します。</summary>
    [HttpGet("runtime/servers/{serverId}/drains")]
    public async Task<IActionResult> GetDrains(string serverId,
        [FromQuery(Name = "server_session_id")] Guid serverSessionId)
        => Respond(await edits.GetDrainsAsync(serverId, serverSessionId));

    /// <summary>共通APIキー認証で保存とログアウトを確認できたサーバーの退避証明を登録します。</summary>
    [HttpPost("runtime/edit-sessions/{editSessionId:guid}/drain-ack")]
    public async Task<IActionResult> AcknowledgeDrain(Guid editSessionId,
        [FromBody] PlayerAdminDrainAckRequest request)
        => Respond(await edits.AcknowledgeDrainAsync(editSessionId, request));

    private Task<bool> CanEditAsync(Guid actor) => actor == Guid.Empty
        ? Task.FromResult(false) : webAuth.IsWebAdminAsync(actor);

    private IActionResult Respond<T>(PlayerAdminResult<T> result) => result.Value is not null
        ? StatusCode(result.StatusCode, result.Value)
        : StatusCode(result.StatusCode, new ProblemDetails
        {
            Status = result.StatusCode,
            Title = result.Error ?? "Player admin operation failed.",
        });
}
