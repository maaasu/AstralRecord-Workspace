using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>信頼済みWebからの寄付申請・審査と、ゲームサーバー向け本人通知を提供します。</summary>
[ApiController]
[Route("api/donations")]
public sealed class DonationController(IDonationRepository repository, IDonationDiscordRepository discord,
    IWebAuthRepository authorization, IConfiguration configuration) : ControllerBase
{
    /// <summary>本人の寄付履歴と承認累計を取得します。</summary>
    [HttpGet]
    public Task<IActionResult> List([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] int page = 1,
        [FromQuery(Name = "page_size")] int pageSize = 20) => Web(actor, false, async () => await repository.ListAsync(actor, false, page, pageSize));

    /// <summary>管理者だけが全ユーザーの申請履歴を取得します。</summary>
    [HttpGet("admin")]
    public Task<IActionResult> AdminList([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] int page = 1,
        [FromQuery(Name = "page_size")] int pageSize = 20) => Web(actor, true, async () => await repository.ListAsync(actor, true, page, pageSize));

    /// <summary>本人または管理者だけに申請の支払い明細を開示します。</summary>
    [HttpGet("{id:guid}")]
    public Task<IActionResult> Detail(Guid id, [FromQuery(Name = "actor_user_uuid")] Guid actor) =>
        Web(actor, false, async () => await repository.GetAsync(id, actor, await authorization.IsWebAdminAsync(actor)));

    /// <summary>Discord公式サーバー参加を再確認して申請します。</summary>
    [HttpPost]
    public Task<IActionResult> Create([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] DonationCreateRequest request) =>
        Web(actor, false, async () => await repository.CreateAsync(actor, request));

    /// <summary>受領前の確認を開始し、申請者による取消しを停止します。</summary>
    [HttpPost("{id:guid}/review")]
    public Task<IActionResult> Review(Guid id, [FromQuery(Name = "actor_user_uuid")] Guid actor) =>
        Web(actor, true, async () => await repository.TransitionAsync(id, actor, "review"));

    /// <summary>受領確認済みの申請を承認します。承認額がnullなら申告額を採用します。</summary>
    [HttpPost("{id:guid}/approve")]
    public Task<IActionResult> Approve(Guid id, [FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] DonationApproveRequest request) =>
        Web(actor, true, async () => await repository.TransitionAsync(id, actor, "approve", request.ApprovedAmount));

    /// <summary>金額を受領せず、理由を付けて否認します。</summary>
    [HttpPost("{id:guid}/reject")]
    public Task<IActionResult> Reject(Guid id, [FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] DonationRejectRequest request) =>
        Web(actor, true, async () => await repository.TransitionAsync(id, actor, "reject", reason: request.Reason));

    /// <summary>本人の未確認申請だけを取り消します。</summary>
    [HttpPost("{id:guid}/cancel")]
    public Task<IActionResult> Cancel(Guid id, [FromQuery(Name = "actor_user_uuid")] Guid actor) =>
        Web(actor, false, async () => await repository.TransitionAsync(id, actor, "cancel"));

    /// <summary>本人の保存済みDiscord連携状態を取得します。</summary>
    [HttpGet("discord")]
    public Task<IActionResult> Discord([FromQuery(Name = "actor_user_uuid")] Guid actor) =>
        Web(actor, false, async () => await discord.GetAsync(actor));

    /// <summary>OAuthトークンをDiscordへ照会し、本人IDと公式サーバー参加を検証して保存します。</summary>
    [HttpPost("discord")]
    public Task<IActionResult> LinkDiscord([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] DonationDiscordLinkRequest request) =>
        Web(actor, false, async () => await discord.LinkAsync(actor, request));

    /// <summary>ゲームサーバーがオンライン本人への未通知イベントを取得します。</summary>
    [HttpGet("notifications")]
    public Task<IActionResult> Notifications([FromQuery(Name = "user_uuid")] Guid user) => Execute(async () =>
        user == Guid.Empty || string.IsNullOrEmpty(configuration["Donations:WebKey"])
            ? Array.Empty<DonationNotificationResponse>() : await repository.NotificationsAsync(user));

    /// <summary>ゲームサーバーが本人へ表示できた通知を確認済みにします。</summary>
    [HttpPost("notifications/{id:guid}/ack")]
    public Task<IActionResult> Acknowledge(Guid id, [FromQuery(Name = "user_uuid")] Guid user) => Execute(async () =>
    {
        if (user == Guid.Empty) throw new ArgumentException("ユーザーが不正です。");
        await repository.AcknowledgeAsync(user, id);
        return new { acknowledged = true };
    });

    private async Task<IActionResult> Web(Guid actor, bool admin, Func<Task<object?>> operation)
    {
        Response.Headers.CacheControl = "no-store";
        if (actor == Guid.Empty) return StatusCode(403);
        var expected = configuration["Donations:WebKey"];
        if (string.IsNullOrWhiteSpace(expected)) return StatusCode(503, new { message = "寄付受付の設定が完了していません。" });
        var provided = Request.Headers["X-Donation-Web-Key"].ToString();
        if (!CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(provided))) return StatusCode(403);
        if (admin && !await authorization.IsWebAdminAsync(actor)) return StatusCode(403);
        return await Execute(operation);
    }

    private async Task<IActionResult> Execute(Func<Task<object?>> operation)
    {
        try { return await operation() is { } result ? Ok(result) : NotFound(); }
        catch (DonationConflictException ex) { return Conflict(new { message = ex.Message }); }
        catch (ArgumentException ex) { return BadRequest(new { message = ex.Message }); }
        catch (KeyNotFoundException) { return NotFound(); }
        catch (UnauthorizedAccessException) { return StatusCode(403, new { message = "本人のDiscord連携と公式サーバー参加、または操作権限を確認してください。" }); }
        catch (InvalidOperationException) { return StatusCode(503, new { message = "連携サービスを利用できません。設定と接続状況を確認してから再試行してください。" }); }
        catch (HttpRequestException) { return StatusCode(503, new { message = "Discordに接続できません。しばらくしてから再試行してください。" }); }
        catch (CryptographicException) { return StatusCode(503, new { message = "保存情報を復号できません。管理者へお問い合わせください。" }); }
    }
}
