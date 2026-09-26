using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/account/{accountId:guid}/benefits")]
public sealed class AccountBenefitsController(IAccountBenefitsRepository repository) : ControllerBase
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

    /// <summary>現在有効なVIPアカウントと残日数の公開表示用一覧です。</summary>
    [HttpGet("/api/web/vip-supporters")]
    public async Task<IActionResult> Supporters() => Ok(await repository.ListSupportersAsync());

    private async Task<IActionResult> Execute(Guid accountId, string kind, AccountBenefitOperationRequest request)
    {
        if (request.OperationId == Guid.Empty || request is AccountBenefitItemRequest item
            && (item.InventoryEntryId == Guid.Empty || item.ExpectedUpdatedAt == default)) return BadRequest();
        var result = await repository.ExecuteAsync(accountId, kind, request);
        return result is null ? NotFound() : result.Reason == "operation_conflict" ? Conflict(result) : Ok(result);
    }
}
