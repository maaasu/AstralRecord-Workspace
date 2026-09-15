using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public class MyPageModel(PlayerProfileApiClient profiles) : PageModel
{
    public WebPlayerProfileResponse? Profile { get; private set; }
    public string? ErrorMessage { get; private set; }
    [TempData] public string? StatusMessage { get; set; }
    [BindProperty] public bool IsPublic { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var viewer)) return Challenge();
        var result = await profiles.GetMeAsync(viewer, ct);
        Profile = result.Value;
        if (!result.Succeeded) ErrorMessage = "プレイヤー情報を取得できませんでした。時間をおいて再読み込みしてください。";
        return Page();
    }

    public async Task<IActionResult> OnPostVisibilityAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var viewer)) return Challenge();
        if (!ModelState.IsValid || !await profiles.SetVisibilityAsync(viewer, IsPublic, ct))
        {
            await OnGetAsync(ct);
            ErrorMessage = "公開設定を変更できませんでした。現在の設定を確認して、もう一度お試しください。";
            return Page();
        }
        StatusMessage = IsPublic ? "プロフィールを公開しました。" : "プロフィールを非公開にしました。";
        return RedirectToPage();
    }
}
