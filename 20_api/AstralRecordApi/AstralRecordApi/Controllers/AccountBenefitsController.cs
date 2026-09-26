using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/account/{accountId:guid}/benefits")]
public sealed class AccountBenefitsController(IAccountBenefitsRepository repository, IWebAuthRepository authorization,
    AstralRecordApi.Data.AstralRecordDbContext db, IConfiguration configuration) : ControllerBase
{
    /// <summary>アカウント単位のVIP期間とインスタンス優先接続残高を取得します。</summary>
    [HttpGet]
    public async Task<IActionResult> Get(Guid accountId) => await repository.GetAsync(accountId) is { } value ? Ok(value) : NotFound();

    /// <summary>消耗品1個と特典を単一transactionで確定し、同じoperationIdの再送を冪等処理します。</summary>
    [HttpPost("consume-item")]
    public Task<IActionResult> ConsumeItem(Guid accountId, AccountBenefitItemRequest request)
        => Execute(accountId, "ITEM", request);

    /// <summary>待機列の優先予約のために1回消費します。</summary>
    [HttpPost("consume-priority")]
    public Task<IActionResult> ConsumePriority(Guid accountId, AccountBenefitOperationRequest request)
        => Execute(accountId, "PRIORITY", request);

    /// <summary>取消された予約の消費を元のoperationIdで一度だけ払い戻します。先行取消は後着消費を防止します。</summary>
    [HttpPost("refund-priority")]
    public Task<IActionResult> RefundPriority(Guid accountId, AccountBenefitOperationRequest request)
        => Execute(accountId, "REFUND", request);

    /// <summary>ASTRALDERのログイン特典をJST一日一回、購入日数を上限に付与します。</summary>
    [HttpPost("login")]
    public Task<IActionResult> Login(Guid accountId, AccountBenefitOperationRequest request)
        => Execute(accountId, "LOGIN", request);

    /// <summary>Web管理者に現在有効なVIPアカウントと残日数を返します。</summary>
    [HttpGet("/api/web/vip-supporters")]
    public async Task<IActionResult> Supporters([FromQuery(Name = "actor_user_uuid")] Guid actor)
    {
        if (!TrustedWeb(actor) || !await authorization.IsWebAdminAsync(actor)) return StatusCode(403);
        return Ok(await repository.ListSupportersAsync());
    }

    /// <summary>Web本人の選択中アカウントのVIP状態と優先接続回数を取得します。</summary>
    [HttpGet("/api/web/account-benefits")]
    public async Task<IActionResult> MyBenefits([FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromQuery(Name = "account_id")] Guid accountId)
    {
        if (!TrustedWeb(actor) || !await db.Users.AnyAsync(x => x.Uuid == actor && !x.IsDeleted && x.AccountId == accountId)
            || !await db.Accounts.AnyAsync(x => x.Uuid == accountId && x.UserId == actor && !x.IsDeleted))
            return StatusCode(403);
        return await repository.GetAsync(accountId) is { } value ? Ok(value) : NotFound();
    }

    private bool TrustedWeb(Guid actor)
    {
        Response.Headers.CacheControl = "no-store";
        var expected = configuration["Donations:WebKey"];
        var supplied = Request.Headers["X-Donation-Web-Key"].ToString();
        return actor != Guid.Empty && !string.IsNullOrWhiteSpace(expected)
            && System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
                System.Text.Encoding.UTF8.GetBytes(expected), System.Text.Encoding.UTF8.GetBytes(supplied));
    }

    private async Task<IActionResult> Execute(Guid accountId, string kind, AccountBenefitOperationRequest request)
    {
        if (request.OperationId == Guid.Empty || request is AccountBenefitItemRequest item
            && (item.InventoryEntryId == Guid.Empty || item.ExpectedUpdatedAt == default)) return BadRequest();
        var result = await repository.ExecuteAsync(accountId, kind, request);
        return result is null ? NotFound() : result.Reason == "operation_conflict" ? Conflict(result) : Ok(result);
    }
}
