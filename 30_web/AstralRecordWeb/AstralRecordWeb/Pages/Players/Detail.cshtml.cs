using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Players;

[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public class DetailModel(PlayerProfileApiClient profiles, NetworkManagementApiClient networkManagementApiClient, IAuthorizationService authorization) : PageModel
{
    [BindProperty(SupportsGet = true)] public string? Mcid { get; set; }
    [BindProperty(SupportsGet = true)] public string? ClassId { get; set; }
    [BindProperty(SupportsGet = true)] public string Sort { get; set; } = "level_desc";
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;
    [BindProperty(SupportsGet = true)] public bool IncludePrivate { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    public WebPlayerProfileResponse? Profile { get; private set; }
    public bool IsPrivateView { get; private set; }
    public bool IsWebAdmin { get; private set; }
    public NetworkBanStateResponse? BanState { get; private set; }
    public string? BanErrorMessage { get; private set; }
    public string? ErrorMessage { get; private set; }
    [BindProperty] public NetworkBanInput BanInput { get; set; } = new();

    public async Task<IActionResult> OnGetAsync(Guid userUuid, CancellationToken ct)
    {
        if (userUuid == Guid.Empty) return NotFound();
        IsWebAdmin = (await authorization.AuthorizeAsync(User, null, "WebAdminOnly")).Succeeded;
        if (IncludePrivate && !IsWebAdmin) return Forbid();
        Guid? viewer = Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;
        var result = await profiles.GetProfileAsync(userUuid, viewer, IncludePrivate, AccountId, ct);
        if (result.Status == System.Net.HttpStatusCode.NotFound) return NotFound();
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        Profile = result.Value;
        IsPrivateView = Profile is { IsPublic: false };
        if (!result.Succeeded) ErrorMessage = "プレイヤー情報を取得できませんでした。時間をおいて再読み込みしてください。";
        if (IsWebAdmin)
            await LoadBanStateAsync(userUuid, ct);
        return Page();
    }

    public async Task<IActionResult> OnPostBanAsync(Guid userUuid, CancellationToken ct)
    {
        if (userUuid == Guid.Empty) return NotFound();
        if (!(await authorization.AuthorizeAsync(User, null, "WebAdminOnly")).Succeeded) return Forbid();
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return Challenge();

        DateTimeOffset? expiresAtUtc = null;
        if (BanInput.IsBanned && !BanInput.IsIndefinite)
        {
            if (!DateTime.TryParse(BanInput.ExpiresAtLocal, out var expiresAtLocal))
            {
                BanErrorMessage = "期限付き BAN には日時を指定してください。";
                await LoadProfileAndBanAsync(userUuid, ct);
                return Page();
            }

            expiresAtUtc = new DateTimeOffset(DateTime.SpecifyKind(expiresAtLocal, DateTimeKind.Unspecified), TimeSpan.FromHours(9)).ToUniversalTime();
            if (expiresAtUtc <= DateTimeOffset.UtcNow)
            {
                BanErrorMessage = "BAN の期限は現在より後の日時を指定してください。";
                await LoadProfileAndBanAsync(userUuid, ct);
                return Page();
            }
        }

        if (BanInput.Reason?.Length > 500)
        {
            BanErrorMessage = "理由は 500 文字以内で入力してください。";
            await LoadProfileAndBanAsync(userUuid, ct);
            return Page();
        }

        var result = await networkManagementApiClient.UpdateBanAsync(actor, userUuid, new NetworkBanUpdateRequest
        {
            ExpectedRevision = BanInput.ExpectedRevision,
            IsBanned = BanInput.IsBanned,
            ExpiresAtUtc = BanInput.IsBanned ? expiresAtUtc : null,
            Reason = BanInput.IsBanned ? BanInput.Reason?.Trim() : null,
        }, ct);
        if (result.Succeeded) return RedirectToPage(new { userUuid, Mcid, ClassId, Sort, PageNumber, IncludePrivate, AccountId });

        BanErrorMessage = result.Status switch
        {
            System.Net.HttpStatusCode.Conflict => "BAN 状態が他の管理者によって更新されました。最新の状態を確認してからやり直してください。",
            System.Net.HttpStatusCode.BadRequest => result.ErrorMessage ?? "入力内容を確認してください。",
            System.Net.HttpStatusCode.Forbidden => "管理権限を確認できませんでした。",
            _ => "BAN 状態を更新できませんでした。時間をおいて再試行してください。",
        };
        await LoadProfileAndBanAsync(userUuid, ct);
        return Page();
    }

    private async Task LoadProfileAndBanAsync(Guid userUuid, CancellationToken ct)
    {
        Guid? viewer = Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;
        var profile = await profiles.GetProfileAsync(userUuid, viewer, IncludePrivate, AccountId, ct);
        Profile = profile.Value;
        IsPrivateView = Profile is { IsPublic: false };
        if (!profile.Succeeded) ErrorMessage = "プレイヤー情報を取得できませんでした。時間をおいて再読み込みしてください。";
        await LoadBanStateAsync(userUuid, ct);
    }

    private async Task LoadBanStateAsync(Guid userUuid, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return;
        var result = await networkManagementApiClient.GetBanAsync(actor, userUuid, ct);
        BanState = result.Value;
        if (!result.Succeeded)
            BanErrorMessage = result.Status == System.Net.HttpStatusCode.NotFound
                ? "このプレイヤーの利用停止状態はまだ管理対象として登録されていません。"
                : "BAN 状態を取得できませんでした。";
    }
}

public sealed class NetworkBanInput
{
    public int ExpectedRevision { get; set; }
    public bool IsBanned { get; set; }
    public bool IsIndefinite { get; set; }
    public string? ExpiresAtLocal { get; set; }
    public string? Reason { get; set; }
}
