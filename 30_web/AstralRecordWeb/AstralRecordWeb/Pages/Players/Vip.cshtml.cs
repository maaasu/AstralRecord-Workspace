using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Players;

[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class VipModel(PlayerProfileApiClient profiles) : PageModel
{
    public IReadOnlyList<VipSupporter> Supporters { get; private set; } = [];
    public string? ErrorMessage { get; private set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        var result = await profiles.GetVipSupportersAsync(ct);
        if (result.Succeeded) Supporters = result.Value!;
        else ErrorMessage = "VIP一覧を取得できませんでした。時間をおいて再読み込みしてください。";
    }
}
