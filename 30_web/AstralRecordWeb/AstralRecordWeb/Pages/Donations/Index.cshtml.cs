using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Donations;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(DonationApiClient api) : PageModel
{
    [BindProperty] public DonationInput Input { get; set; } = new();
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;
    [TempData] public string? StatusMessage { get; set; }
    public string? ErrorMessage { get; private set; }
    public DonationList? History { get; private set; }
    public bool HasNext => History is { } h && (long)PageNumber * 20 < h.TotalCount;

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        await Load(actor, ct);
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        if (Input.OperationId == Guid.Empty) ModelState.AddModelError("", "申請識別子が無効です。ページを再読み込みしてください。");
        if (!Input.Agree) ModelState.AddModelError("Input.Agree", "寄付に関する規約への同意が必要です。");
        var entries = Input.Entries;
        if (entries is null || entries.Count is < 1 or > 10)
        {
            ModelState.AddModelError("", "明細は1件から10件まで登録できます。");
            Input.Entries = entries?.Take(10).ToList() ?? [];
            if (Input.Entries.Count == 0) Input.Entries.Add(new());
        }
        else
        {
            for (var i = 0; i < entries.Count; i++)
            {
                var entry = entries[i];
                entry.Value = entry.Value?.Trim() ?? "";
                if (entry.Method is not ("amazon" or "paypay") || entry.DeclaredAmount <= 0 || entry.Value.Length is < 1 or > 2048)
                    ModelState.AddModelError("", $"明細{i + 1}の種類・金額・番号またはURLを確認してください。");
                if (entry.Method == "paypay" && !IsPayPayUrl(entry.Value))
                    ModelState.AddModelError("", $"明細{i + 1}には https://pay.paypay.ne.jp/ から始まる送金URLを入力してください。");
            }
            if (entries.Sum(x => (long)x.DeclaredAmount) != Input.DeclaredAmount)
                ModelState.AddModelError("Input.DeclaredAmount", "申告合計金額と各明細の金額の合計を一致させてください。");
        }
        if (ModelState.IsValid)
        {
            var result = await api.CreateAsync(actor, Input.OperationId, Input.DeclaredAmount, entries!, ct);
            if (result.Succeeded)
            {
                StatusMessage = "寄付申請を受け付けました。ゲーム内にも受付通知を送信します。";
                return RedirectToPage();
            }
            if (result.Status == System.Net.HttpStatusCode.Forbidden) ErrorMessage = result.ErrorMessage ?? "Discord連携・公式サーバー参加をご確認ください。";
            else ErrorMessage = result.ErrorMessage ?? "申請結果を確認できませんでした。履歴を確認し、同じ画面から再試行してください。";
        }
        await Load(actor, ct);
        return Page();
    }

    public async Task<IActionResult> OnPostCancelAsync(Guid id, CancellationToken ct)
    {
        if (!Actor(out var actor)) return Challenge();
        var result = await api.ActAsync(actor, id, "cancel", new { }, ct);
        if (result.Succeeded) { StatusMessage = "申請を取り消しました。"; return RedirectToPage(); }
        ErrorMessage = result.ErrorMessage ?? "取り消せませんでした。確認中または処理済みの申請は取り消せません。";
        ModelState.Clear();
        await Load(actor, ct);
        return Page();
    }

    public static bool IsPayPayUrl(string value) => Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme == Uri.UriSchemeHttps && uri.Host.Equals("pay.paypay.ne.jp", StringComparison.OrdinalIgnoreCase)
        && uri.IsDefaultPort && string.IsNullOrEmpty(uri.UserInfo) && uri.AbsolutePath.Length > 1;

    private bool Actor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);
    private async Task Load(Guid actor, CancellationToken ct)
    {
        PageNumber = Math.Clamp(PageNumber, 1, 1000000);
        var result = await api.ListAsync(actor, false, PageNumber, ct);
        if (result.Succeeded) History = result.Value;
        else ErrorMessage ??= result.ErrorMessage ?? "寄付履歴を取得できませんでした。時間をおいて再試行してください。";
        Response.Headers["Referrer-Policy"] = "no-referrer";
    }
}

public sealed class DonationInput
{
    public Guid OperationId { get; set; } = Guid.NewGuid();
    [Range(500, 1000000, ErrorMessage = "申告合計金額は500円から1,000,000円までの整数で入力してください。")]
    public int DeclaredAmount { get; set; } = 500;
    public bool Agree { get; set; }
    public List<DonationEntry> Entries { get; set; } = [new() { DeclaredAmount = 500 }];
}
