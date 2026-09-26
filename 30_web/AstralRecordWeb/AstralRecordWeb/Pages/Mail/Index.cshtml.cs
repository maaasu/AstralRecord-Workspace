using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Mail;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(PaidServicesApiClient paid, PlayerProfileApiClient profiles,
    ItemMasterApiClient items) : PageModel
{
    public WebPlayerAccountProfileResponse? Account { get; private set; }
    public IReadOnlyList<WebMail> Messages { get; private set; } = [];
    public WebMailClaim? ClaimResult { get; private set; }
    public string? ErrorMessage { get; private set; }
    private IReadOnlyDictionary<string, string> itemNames = new Dictionary<string, string>();
    [BindProperty] public Guid AccountId { get; set; }
    [BindProperty] public Guid OperationId { get; set; }
    [BindProperty] public string MailId { get; set; } = "";
    [BindProperty(SupportsGet = true)] public Guid? ClaimId { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        await LoadAsync(actor, ct);
        if (ClaimId is { } claimId && claimId != Guid.Empty)
        {
            var result = await paid.ClaimResultAsync(actor, claimId, ct);
            if (result.Succeeded && result.Value?.AccountId == Account?.AccountId) ClaimResult = result.Value;
            else ErrorMessage ??= "受取結果を確認できませんでした。時間をおいて再読み込みしてください。";
        }
        return Page();
    }

    public async Task<IActionResult> OnPostClaimAsync(CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        await LoadAsync(actor, ct);
        if (Account is null || OperationId == Guid.Empty || AccountId != Account.AccountId
            || string.IsNullOrWhiteSpace(MailId) || !Messages.Any(mail => mail.Id == MailId && mail.CanClaimCurrency))
        {
            ErrorMessage = "このメールはWebで受け取れません。内容と現在のアカウントを確認してください。";
            return Page();
        }
        var result = await paid.ClaimCurrencyAsync(actor, MailId, OperationId, Account.AccountId, ct);
        if (result.Succeeded || result.Status == System.Net.HttpStatusCode.ServiceUnavailable)
            return RedirectToPage(new { ClaimId = OperationId });
        if (result.Value is { Status: "REJECTED" } rejected)
        {
            ClaimResult = rejected;
            return Page();
        }
        ErrorMessage = result.Status switch
        {
            System.Net.HttpStatusCode.Forbidden => "現在のアカウントで受け取れません。再ログインして確認してください。",
            System.Net.HttpStatusCode.Conflict => "受け取れませんでした。メールの状態を更新して確認してください。",
            _ => "受け取れませんでした。メールの内容を確認してください。",
        };
        return Page();
    }

    public static string ClaimStatus(WebMailClaim claim) => claim.Status switch
    {
        "COMPLETED" => "通貨の受取が完了しました。",
        "PENDING" => "受取処理中です。ページを更新して結果を確認してください。",
        "REJECTED" => "受取は成立しませんでした。メールの状態を確認してください。",
        _ => "受取状態を確認できません。時間をおいて更新してください。",
    };

    private bool Actor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);

    public string RewardName(WebMailReward reward) => itemNames.GetValueOrDefault(reward.ItemId)
        ?? (IsCurrency(reward) ? "未登録の通貨" : "未登録のアイテム");

    public static bool IsCurrency(WebMailReward reward) =>
        string.Equals(reward.Category, "currency", StringComparison.OrdinalIgnoreCase);

    private async Task LoadAsync(Guid actor, CancellationToken ct)
    {
        var profile = await profiles.GetMeAsync(actor, ct);
        if (!profile.Succeeded || profile.Value?.UserUuid != actor || profile.Value.CurrentAccount is null)
        {
            ErrorMessage = "現在のアカウントを確認できません。ゲーム内でアカウントを選択してから再読み込みしてください。";
            return;
        }
        Account = profile.Value.CurrentAccount;
        var mail = await paid.MailAsync(actor, Account.AccountId, ct);
        if (mail.Succeeded)
        {
            Messages = mail.Value!;
            var ids = Messages.SelectMany(message => message.Rewards).Select(reward => reward.ItemId).ToArray();
            if (ids.Length > 0)
            {
                try { itemNames = await items.GetNamesAsync(ids, ct); }
                catch (Exception failure) when (!ct.IsCancellationRequested
                    && failure is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
                {
                    ErrorMessage = "報酬名を取得できませんでした。時間をおいて再読み込みしてください。";
                }
            }
        }
        else ErrorMessage = "メールを取得できませんでした。時間をおいて再読み込みしてください。";
    }
}
