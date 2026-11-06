using System.Security.Claims;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public class MyPageModel(
    PlayerProfileApiClient profiles,
    PaidServicesApiClient paid,
    SkillTreeEditorApiClient editor,
    MinecraftAvatarService avatars) : PageModel
{
    public WebPlayerProfileResponse? Profile { get; private set; }
    public JsonElement? SkillTreeEditorState { get; private set; }
    public WebSkillTreeProfileResponse? EditableSkillTree { get; private set; }
    public AccountBenefits? Benefits { get; private set; }
    public string? BenefitsError { get; private set; }
    public string? ErrorMessage { get; private set; }
    [TempData] public string? StatusMessage { get; set; }
    [BindProperty] public bool IsPublic { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var viewer)) return Challenge();
        var result = await profiles.GetMeAsync(viewer, ct);
        Profile = result.Value;
        if (!result.Succeeded) ErrorMessage = "プレイヤー情報を取得できませんでした。時間をおいて再読み込みしてください。";
        if (result.Succeeded && result.Value?.UserUuid == viewer && result.Value.CurrentAccount is { } account)
        {
            var benefits = await paid.BenefitsAsync(viewer, account.AccountId, ct);
            if (benefits.Succeeded && benefits.Value?.AccountId == account.AccountId) Benefits = benefits.Value;
            else BenefitsError = "特典情報を取得できませんでした。時間をおいて再読み込みしてください。";
        }
        if (result.Succeeded && result.Value?.UserUuid == viewer && result.Value.CurrentAccount is { } selected)
        {
            var editorResult = await editor.GetStateAsync(viewer, selected.AccountId, ct);
            if (editorResult.Succeeded && editorResult.Value.TryGetProperty("accountId", out var editorAccountId)
                && editorAccountId.ValueKind == JsonValueKind.String
                && editorAccountId.TryGetGuid(out var stateAccountId) && stateAccountId == selected.AccountId
                && editorResult.Value.TryGetProperty("tree", out var tree) && tree.ValueKind == JsonValueKind.Object)
            {
                try
                {
                    var editableTree = tree.Deserialize<WebSkillTreeProfileResponse>(new JsonSerializerOptions(JsonSerializerDefaults.Web));
                    if (editableTree?.Nodes is not null && editableTree.Edges is not null)
                    {
                        SkillTreeEditorState = editorResult.Value;
                        EditableSkillTree = editableTree;
                    }
                }
                catch (JsonException) { /* The profile's read-only tree remains available. */ }
            }
        }
        return Page();
    }

    public async Task<IActionResult> OnGetAvatarAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var uuid) || uuid == Guid.Empty)
            return NotFound();

        var avatar = await avatars.GetAsync(uuid, ct);
        return avatar is null ? NotFound() : Content(avatar, "image/svg+xml");
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
