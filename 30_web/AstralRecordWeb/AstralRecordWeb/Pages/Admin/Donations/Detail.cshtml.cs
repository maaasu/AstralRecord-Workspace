using System.Globalization;
using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin.Donations;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DetailModel(DonationApiClient api) : PageModel
{
    [BindProperty(SupportsGet = true)] public Guid Id { get; set; }
    [BindProperty, DisplayFormat(ConvertEmptyStringToNull = false)] public string? ApprovedAmount { get; set; }
    [BindProperty] public string? Reason { get; set; }
    [BindProperty] public bool ConfirmReceived { get; set; }
    [TempData] public string? StatusMessage { get; set; }
    public DonationRequest? Donation { get; private set; }
    public string? ErrorMessage { get; private set; }

    public Task<IActionResult> OnGetAsync(CancellationToken ct) => Load(ct);
    public Task<IActionResult> OnPostReviewAsync(CancellationToken ct) => Act("review", new { }, ct);
    public async Task<IActionResult> OnPostApproveAsync(CancellationToken ct)
    {
        int? amount = null;
        if (!string.IsNullOrEmpty(ApprovedAmount))
        {
            if (!int.TryParse(ApprovedAmount, NumberStyles.None, CultureInfo.InvariantCulture, out var parsed) || parsed is < 1 or > 1000000)
                ModelState.AddModelError(nameof(ApprovedAmount), "承認金額は1円から1,000,000円までの整数で入力してください。空欄の場合のみ申告額を採用します。");
            else amount = parsed;
        }
        if (!ConfirmReceived) ModelState.AddModelError(nameof(ConfirmReceived), "金額の受領確認が必要です。");
        if (!ModelState.IsValid) return await Load(ct);
        return await Act("approve", new { approvedAmount = amount }, ct);
    }
    public async Task<IActionResult> OnPostRejectAsync(CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(Reason) || Reason.Length > 1000)
        {
            ModelState.AddModelError(nameof(Reason), "否認理由を1文字から1,000文字で入力してください。");
            return await Load(ct);
        }
        return await Act("reject", new { reason = Reason.Trim() }, ct);
    }
    private async Task<IActionResult> Act(string action, object body, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return Challenge();
        var result = await api.ActAsync(actor, Id, action, body, ct);
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        if (result.Succeeded)
        {
            StatusMessage = action switch { "review" => "確認を開始しました。", "approve" => "承認しました。各アカウントへメールを配布します。", _ => "否認し、理由を通知しました。" };
            return RedirectToPage(new { id = Id });
        }
        ErrorMessage = result.ErrorMessage ?? "処理できませんでした。最新の状態を確認してください。";
        return await Load(ct);
    }
    private async Task<IActionResult> Load(CancellationToken ct)
    {
        Response.Headers["Referrer-Policy"] = "no-referrer";
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return Challenge();
        var result = await api.GetAsync(actor, Id, ct);
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        if (result.Status == System.Net.HttpStatusCode.NotFound) return NotFound();
        if (result.Succeeded) Donation = result.Value;
        else ErrorMessage ??= result.ErrorMessage ?? "申請を取得できませんでした。";
        return Page();
    }
}
