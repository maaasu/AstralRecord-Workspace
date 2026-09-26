using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.AstraldShop;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(PaidServicesApiClient paid, PlayerProfileApiClient profiles) : PageModel
{
    public AstraldShopCatalog? Catalog { get; private set; }
    public WebPlayerAccountProfileResponse? Account { get; private set; }
    public AccountBenefits? Benefits { get; private set; }
    public ChannelBoostSnapshot? Boosts { get; private set; }
    public AstraldPurchase? PurchaseResult { get; private set; }
    public string? ErrorMessage { get; private set; }
    [BindProperty] public Guid AccountId { get; set; }
    [BindProperty] public Guid OperationId { get; set; }
    [BindProperty] public string ItemId { get; set; } = "";
    [BindProperty] public long ExpectedPricePaidAstrald { get; set; }
    [BindProperty] public string? ChannelId { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? PurchaseId { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        await LoadAsync(actor, ct);
        if (PurchaseId is { } purchaseId && purchaseId != Guid.Empty)
        {
            var result = await paid.PurchaseResultAsync(actor, purchaseId, ct);
            if (result.Succeeded && result.Value?.AccountId == Account?.AccountId) PurchaseResult = result.Value;
            else ErrorMessage ??= "購入結果を確認できませんでした。時間をおいて再読み込みしてください。";
        }
        return Page();
    }

    public async Task<IActionResult> OnPostPurchaseAsync(CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        await LoadAsync(actor, ct);
        if (Account is null || Catalog is null)
        {
            ErrorMessage ??= "現在のアカウントと商品を確認できません。再読み込みしてください。";
            return Page();
        }
        var item = Catalog.Items.FirstOrDefault(x => x.ItemId == ItemId);
        if (OperationId == Guid.Empty || AccountId != Account.AccountId || item is null || ExpectedPricePaidAstrald <= 0
            || (item.RequiresChannel && !Catalog.Channels.Any(channel => channel.ChannelId == ChannelId))
            || (!item.RequiresChannel && !string.IsNullOrEmpty(ChannelId)))
        {
            ErrorMessage = "購入内容または現在のアカウントが変わりました。ページを再読み込みしてください。";
            return Page();
        }
        var result = await paid.PurchaseAsync(actor, OperationId, Account.AccountId, ItemId, ChannelId, ExpectedPricePaidAstrald, ct);
        if (result.Succeeded || result.Status == System.Net.HttpStatusCode.ServiceUnavailable)
            return RedirectToPage(new { PurchaseId = OperationId });
        if (result.Value is { Status: "REJECTED" } rejected)
        {
            PurchaseResult = rejected;
            return Page();
        }
        ErrorMessage = result.Status switch
        {
            System.Net.HttpStatusCode.Forbidden => "現在のアカウントで購入できません。再ログインして確認してください。",
            System.Net.HttpStatusCode.Conflict => "購入できませんでした。残高や効果の重複を確認してください。",
            _ => "購入できませんでした。商品と対象チャンネルを確認してください。",
        };
        return Page();
    }

    public static string PurchaseStatus(AstraldPurchase purchase) => purchase.Status switch
    {
        "COMPLETED" => "購入が完了し、効果を直接適用しました。",
        "PENDING" => "購入を受け付けました。処理中です。ページを更新して結果を確認してください。",
        "REJECTED" => purchase.Reason switch
        {
            "insufficient_paid_astrald" => "有償アストラルドの残高が不足しています。購入は成立していません。",
            "boost_already_active" => "対象チャンネルには同種のブーストが有効です。購入は成立していません。",
            "network_boost_disabled" => "対象チャンネルはWebからのブースト発動を許可していません。購入は成立していません。",
            "account_not_current" => "ゲーム内で選択中のアカウントが変わりました。購入は成立していません。ページを更新してください。",
            "unknown_channel" or "channel_not_allowed" => "対象チャンネルを選択し直してください。購入は成立していません。",
            "price_changed" or "offer_changed" => "商品価格または内容が変わりました。購入は成立していません。ページを更新してください。",
            "currency_inventory_missing" => "有償アストラルドの残高を確認できませんでした。購入は成立していません。",
            "item_unavailable" => "この商品は現在購入できません。購入は成立していません。",
            "operation_conflict" => "同じ操作IDで異なる購入内容が送信されました。購入履歴を確認してください。",
            _ => "購入は成立しませんでした。商品と残高を確認してください。",
        },
        _ => "購入状態を確認できません。時間をおいて更新してください。",
    };

    public bool IsChannelBlocked(AstraldShopItem item, string channelId)
    {
        var state = Boosts?.Channels.FirstOrDefault(channel => channel.ChannelId == channelId);
        if (state is null) return false;
        var now = DateTimeOffset.UtcNow;
        var expActive = state.Exp?.ExpiresAt > now;
        var dropActive = state.Drop?.ExpiresAt > now;
        return item.EffectType switch
        {
            "CHANNEL_EXP_BOOST" => expActive,
            "CHANNEL_DROP_BOOST" => dropActive,
            "CHANNEL_SPECIAL_BOOST" => expActive || dropActive,
            _ => false,
        };
    }

    private bool Actor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);

    private async Task LoadAsync(Guid actor, CancellationToken ct)
    {
        var profile = await profiles.GetMeAsync(actor, ct);
        if (!profile.Succeeded || profile.Value?.UserUuid != actor || profile.Value.CurrentAccount is null)
        {
            ErrorMessage = "現在のアカウントを確認できません。ゲーム内でアカウントを選択してから再読み込みしてください。";
            return;
        }
        Account = profile.Value.CurrentAccount;
        var catalog = await paid.CatalogAsync(ct);
        if (catalog.Succeeded) Catalog = catalog.Value;
        else ErrorMessage = "商品一覧を取得できませんでした。時間をおいて再読み込みしてください。";
        var benefits = await paid.BenefitsAsync(actor, Account.AccountId, ct);
        if (benefits.Succeeded && benefits.Value?.AccountId == Account.AccountId) Benefits = benefits.Value;
        var boosts = await paid.ChannelBoostsAsync(ct);
        if (boosts.Succeeded) Boosts = boosts.Value;
    }
}
