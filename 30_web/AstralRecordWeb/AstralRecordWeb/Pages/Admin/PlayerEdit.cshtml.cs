using System.Globalization;
using System.Net;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Encodings.Web;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class PlayerEditModel(PlayerAdminApiClient adminApi, PlayerProfileApiClient profiles,
    IDataProtectionProvider protection, ILogger<PlayerEditModel> logger) : PageModel
{
    private const string BrowserStateCookiePrefix = "__Host-AstralRecordPlayerEdit-";
    private static readonly JsonSerializerOptions BrowserJson = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    private readonly IDataProtector browserStateProtector = protection.CreateProtector("PlayerAdminEditBrowserState.v1");
    private PlayerEditBrowserState? currentBrowserState;
    private bool browserStateCleared;
    [BindProperty(SupportsGet = true)] public Guid UserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid AccountId { get; set; }
    [BindProperty] public Guid EditSessionId { get; set; }
    [BindProperty] public Guid OperationId { get; set; }
    [BindProperty] public long ExpectedRevision { get; set; }
    [BindProperty] public string? ExpectedStateHash { get; set; }
    [BindProperty] public string? ExpectedCatalogVersion { get; set; }
    [BindProperty] public string? Reason { get; set; }
    [BindProperty] public string? LevelText { get; set; }
    [BindProperty] public string? ClassId { get; set; }
    [BindProperty] public string InventoryAction { get; set; } = "NONE";
    [BindProperty] public Guid? InventoryEntryId { get; set; }
    [BindProperty] public string? ItemId { get; set; }
    [BindProperty] public string? QuantityText { get; set; }
    [BindProperty] public bool ConfirmTarget { get; set; }

    [TempData] public string? StatusMessage { get; set; }
    public string? ErrorMessage { get; private set; }
    public WebPlayerProfileResponse? Profile { get; private set; }
    public PlayerAdminEditSession? Session { get; private set; }
    public PlayerAdminEditor? Editor { get; private set; }
    public PlayerEditPendingOperation? PendingOperation { get; private set; }
    public bool PendingStart { get; private set; }
    public bool IsOwner { get; private set; }
    public bool CanEdit => PendingOperation is null && IsOwner && Session?.Status == "READY" && Editor?.Account is not null
        && !string.IsNullOrWhiteSpace(Editor.ExpectedStateHash) && !string.IsNullOrWhiteSpace(Editor.CatalogVersion);

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        EditSessionId = Guid.NewGuid();
        OperationId = Guid.NewGuid();
        return await LoadAsync(ct);
    }

    public async Task<IActionResult> OnPostStartAsync(CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        if (EditSessionId == Guid.Empty || string.IsNullOrWhiteSpace(Reason) || Reason.Trim().Length is > 500 or < 1)
        {
            ModelState.AddModelError(nameof(Reason), "編集理由を1～500文字で入力してください。");
            return await LoadAsync(ct);
        }
        if (!ConfirmTarget)
        {
            ModelState.AddModelError(nameof(ConfirmTarget), "対象と接続停止を確認してください。");
            return await LoadAsync(ct);
        }
        var target = await profiles.GetProfileAsync(UserUuid, actor, includePrivate: true, AccountId, ct);
        if (!target.Succeeded || target.Value?.CurrentAccount?.AccountId != AccountId) return NotFound();
        if (ReadBrowserState(actor) is { PendingStart: true } pendingStart)
        {
            ErrorMessage = "前回の編集開始結果を確認中です。同じ操作を再送するか、状態を再読み込みしてください。";
            currentBrowserState = pendingStart;
            return await LoadAsync(ct);
        }
        var current = await adminApi.GetSessionAsync(actor, AccountId, ct);
        if (current.Status != HttpStatusCode.NotFound)
        {
            ErrorMessage = "編集状態が変わりました。現在の状態を確認してください。";
            return await LoadAsync(ct);
        }
        var state = new PlayerEditBrowserState(actor, UserUuid, AccountId, EditSessionId, true, Reason.Trim(), null);
        WriteBrowserState(state);
        return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
    }

    public async Task<IActionResult> OnPostRetryStartAsync(CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        var state = ReadBrowserState(actor);
        if (state is not { PendingStart: true } || state.EditSessionId == Guid.Empty || string.IsNullOrWhiteSpace(state.StartReason))
            return await LoadAsync(ct);
        var existing = await adminApi.GetSessionAsync(actor, AccountId, ct);
        if (existing.Succeeded) return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
        if (existing.Status != HttpStatusCode.NotFound)
        {
            ErrorMessage = "開始結果を確認できません。ロック状態が判明するまで再送できません。";
            return await LoadAsync(ct);
        }
        var receipt = await adminApi.GetSessionByIdAsync(actor, state.EditSessionId, ct);
        if (receipt.Succeeded && receipt.Value?.AccountId == AccountId && receipt.Value.UserUuid == UserUuid
            && receipt.Value.ActorUserUuid == actor)
        {
            if (receipt.Value.Status is "COMPLETED" or "CANCELED")
                WriteBrowserState(state with { PendingStart = false });
            return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
        }
        if (receipt.Status != HttpStatusCode.NotFound)
        {
            ErrorMessage = "開始結果を確認できません。ロック状態が判明するまで再送できません。";
            return await LoadAsync(ct);
        }
        var result = await adminApi.StartAsync(actor, AccountId, state.EditSessionId, state.StartReason, ct);
        if (result.Succeeded && result.Value?.UserUuid == UserUuid)
        {
            WriteBrowserState(state with { PendingStart = false });
            return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
        }
        if (result.Status is HttpStatusCode.BadRequest or HttpStatusCode.Conflict or HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized)
            ClearBrowserState();
        ErrorMessage = Failure(result, "開始結果を確認できませんでした。");
        return await LoadAsync(ct);
    }

    public async Task<IActionResult> OnPostRefreshAsync(CancellationToken ct)
    {
        if (TryActor(out var refreshActor) && ReadBrowserState(refreshActor)?.PendingOperation is not null)
        {
            ErrorMessage = "前回の操作結果を先に確認してください。";
            return await LoadAsync(ct);
        }
        if (!await CheckOwnedSessionAsync(ct, requireReady: false)) return await LoadAsync(ct);
        var actor = Actor();
        var result = await adminApi.RefreshAsync(actor, EditSessionId, ct);
        if (result.Succeeded && result.Value?.AccountId == AccountId && result.Value.UserUuid == UserUuid)
            return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
        ErrorMessage = Failure(result, "状態を更新できませんでした。ロック状態を確認してください。");
        return await LoadAsync(ct);
    }

    public async Task<IActionResult> OnPostApplyAsync(CancellationToken ct)
    {
        if (TryActor(out var applyActor) && ReadBrowserState(applyActor)?.PendingOperation is not null)
        {
            ErrorMessage = "前回の操作結果を先に確認してください。変更内容は再送されません。";
            return await LoadAsync(ct);
        }
        if (!await CheckOwnedSessionAsync(ct, requireReady: true)) return await LoadAsync(ct);
        var actor = Actor();
        var editorResult = await adminApi.GetEditorAsync(actor, EditSessionId, ct);
        if (!editorResult.Succeeded || editorResult.Value?.Account is null || editorResult.Value.Session?.Revision != ExpectedRevision
            || editorResult.Value.Account.AccountId != AccountId || editorResult.Value.Account.UserUuid != UserUuid
            || string.IsNullOrWhiteSpace(ExpectedStateHash) || editorResult.Value.ExpectedStateHash != ExpectedStateHash
            || string.IsNullOrWhiteSpace(ExpectedCatalogVersion) || editorResult.Value.CatalogVersion != ExpectedCatalogVersion)
        {
            ErrorMessage = "編集内容が変わりました。最新の状態を確認し、変更を入力し直してください。ロックは維持されています。";
            return await LoadAsync(ct);
        }
        if (OperationId == Guid.Empty)
            ModelState.AddModelError(nameof(OperationId), "操作IDを確認できません。ページを再読み込みしてください。");
        if (!ConfirmTarget)
            ModelState.AddModelError(nameof(ConfirmTarget), "対象と変更内容を確認してください。");
        int? level = null;
        if (!string.IsNullOrWhiteSpace(LevelText))
        {
            if (!int.TryParse(LevelText, NumberStyles.None, CultureInfo.InvariantCulture, out var parsed) || parsed is < 1 or > 100)
                ModelState.AddModelError(nameof(LevelText), "プレイヤーレベルは1～100の整数で入力してください。");
            else level = parsed;
        }
        var classId = string.IsNullOrWhiteSpace(ClassId) ? null : ClassId.Trim();
        if (classId?.Length > 128) ModelState.AddModelError(nameof(ClassId), "クラスIDは128文字以内で入力してください。");
        if (classId is not null && !editorResult.Value.AvailableClasses.Any(candidate => candidate.ClassId == classId))
            ModelState.AddModelError(nameof(ClassId), "一覧からクラスを選んでください。");
        var changes = BuildChanges(editorResult.Value);
        if (level is null && classId is null && changes.Count == 0)
            ModelState.AddModelError(string.Empty, "変更内容を1つ以上指定してください。");
        if (!ModelState.IsValid) return await LoadAsync(ct);

        var pending = new PlayerEditPendingOperation("APPLY", OperationId, ExpectedRevision, ExpectedStateHash!, ExpectedCatalogVersion!, level, classId, changes);
        var browserState = new PlayerEditBrowserState(actor, UserUuid, AccountId, EditSessionId, false, null, pending);
        WriteBrowserState(browserState);
        return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
    }

    public async Task<IActionResult> OnPostCancelAsync(CancellationToken ct)
    {
        if (TryActor(out var cancelActor) && ReadBrowserState(cancelActor)?.PendingOperation is not null)
        {
            ErrorMessage = "前回の操作結果を先に確認してください。取消は送信されません。";
            return await LoadAsync(ct);
        }
        if (!await CheckOwnedSessionAsync(ct, requireReady: false)) return await LoadAsync(ct);
        if (OperationId == Guid.Empty)
        {
            ErrorMessage = "操作IDを確認できません。ページを再読み込みしてください。";
            return await LoadAsync(ct);
        }
        if (!ConfirmTarget)
        {
            ErrorMessage = "対象と編集取消を確認してください。";
            return await LoadAsync(ct);
        }
        var actor = Actor();
        var pending = new PlayerEditPendingOperation("CANCEL", OperationId, ExpectedRevision, string.Empty, string.Empty, null, null, []);
        var browserState = new PlayerEditBrowserState(actor, UserUuid, AccountId, EditSessionId, false, null, pending);
        WriteBrowserState(browserState);
        return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
    }

    public async Task<IActionResult> OnPostRetryOperationAsync(CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        var browserState = ReadBrowserState(actor);
        if (browserState?.PendingOperation is not { } pending || browserState.EditSessionId == Guid.Empty)
            return await LoadAsync(ct);
        var receipt = await adminApi.GetSessionByIdAsync(actor, browserState.EditSessionId, ct);
        if (receipt.Succeeded && receipt.Value?.AccountId == AccountId && receipt.Value.UserUuid == UserUuid
            && receipt.Value.ActorUserUuid == actor
            && receipt.Value.Status is ("COMPLETED" or "CANCELED"))
        {
            WriteBrowserState(browserState with { PendingOperation = null });
            return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
        }
        if (!receipt.Succeeded || receipt.Value?.AccountId != AccountId || receipt.Value.UserUuid != UserUuid
            || receipt.Value.ActorUserUuid != actor || receipt.Value.EditSessionId != browserState.EditSessionId)
        {
            ErrorMessage = "操作結果を確認できません。ロック状態が判明するまで再送できません。";
            return await LoadAsync(ct);
        }
        PlayerAdminApiResult<PlayerAdminEditSession> result;
        if (pending.Action == "APPLY")
        {
            if (receipt.Value.Status != "READY")
            {
                ErrorMessage = "適用処理の状態を確認中です。再送せず状態を更新してください。";
                return await LoadAsync(ct);
            }
            result = await adminApi.ApplyAsync(actor, browserState.EditSessionId, pending.OperationId, pending.ExpectedRevision,
                pending.ExpectedStateHash, pending.ExpectedCatalogVersion, pending.Level, pending.ClassId, pending.InventoryChanges, ct);
        }
        else if (pending.Action == "CANCEL")
        {
            if (receipt.Value.Status is not ("DRAINING" or "READY" or "RECOVERY_REQUIRED"))
            {
                ErrorMessage = "取消処理の状態を確認中です。再送せず状態を更新してください。";
                return await LoadAsync(ct);
            }
            result = await adminApi.CancelAsync(actor, browserState.EditSessionId, pending.OperationId, pending.ExpectedRevision, ct);
        }
        else return BadRequest();
        if (result.Succeeded && result.Value?.AccountId == AccountId && result.Value.UserUuid == UserUuid)
        {
            if (result.Value.Status is "COMPLETED" or "CANCELED")
                WriteBrowserState(browserState with { PendingOperation = null });
            return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
        }
        if (DefinitiveFailure(result.Status)) WriteBrowserState(browserState with { PendingOperation = null });
        ErrorMessage = Failure(result, "操作結果を確認できませんでした。ロック状態を確認してください。");
        return await LoadAsync(ct);
    }

    public async Task<IActionResult> OnPostNewAsync(CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        var browserState = ReadBrowserState(actor);
        if (browserState is null || browserState.PendingOperation is not null || browserState.PendingStart || browserState.EditSessionId == Guid.Empty)
            return await LoadAsync(ct);
        var active = await adminApi.GetSessionAsync(actor, AccountId, ct);
        var receipt = await adminApi.GetSessionByIdAsync(actor, browserState!.EditSessionId, ct);
        if (active.Status != HttpStatusCode.NotFound || !receipt.Succeeded || receipt.Value?.ActorUserUuid != actor
            || receipt.Value.AccountId != AccountId || receipt.Value.UserUuid != UserUuid
            || receipt.Value.Status is not ("COMPLETED" or "CANCELED"))
            return await LoadAsync(ct);
        ClearBrowserState();
        return RedirectToPage(new { userUuid = UserUuid, accountId = AccountId });
    }

    private IReadOnlyList<PlayerAdminInventoryChange> BuildChanges(PlayerAdminEditor editor)
    {
        if (InventoryAction == "NONE") return [];
        long? quantity = null;
        if (InventoryAction is "GRANT" or "SET_QUANTITY")
        {
            if (!long.TryParse(QuantityText, NumberStyles.None, CultureInfo.InvariantCulture, out var parsed) || parsed < 1)
                ModelState.AddModelError(nameof(QuantityText), "数量は1以上の整数で入力してください。削除には削除操作を選んでください。");
            else quantity = parsed;
        }
        if (InventoryAction == "GRANT")
        {
            var item = editor.AvailableItems.FirstOrDefault(candidate => candidate.ItemId == ItemId && IsGrantableCategory(candidate.Category));
            if (item is null) ModelState.AddModelError(nameof(ItemId), "一覧から付与するアイテムを選んでください。");
            if (item?.Category.Equals("EQUIPMENT", StringComparison.OrdinalIgnoreCase) == true && quantity != 1)
                ModelState.AddModelError(nameof(QuantityText), "装備の付与数量は1にしてください。");
            return ModelState.IsValid ? [new PlayerAdminInventoryChange { Action = "GRANT", ItemId = item!.ItemId, Quantity = quantity }] : [];
        }
        if (InventoryAction is "SET_QUANTITY" or "DELETE")
        {
            var entry = editor.Inventories.Where(inventory => inventory.IsEnabled && inventory.InventoryType == "BAG" && inventory.InventoryProfile == "GAME")
                .SelectMany(inventory => inventory.Entries)
                .FirstOrDefault(candidate => candidate.InventoryEntryId == InventoryEntryId && IsOrdinaryCategory(candidate.ItemCategory)
                    && candidate.InstanceId is null && string.IsNullOrEmpty(candidate.InstanceType) && string.IsNullOrEmpty(candidate.MetadataJson));
            if (entry is null) ModelState.AddModelError(nameof(InventoryEntryId), "一覧から変更するインベントリ項目を選んでください。");
            return ModelState.IsValid ? [new PlayerAdminInventoryChange
            {
                Action = InventoryAction, InventoryEntryId = entry!.InventoryEntryId,
                ExpectedUpdatedAt = entry.UpdatedAt, Quantity = quantity,
            }] : [];
        }
        ModelState.AddModelError(nameof(InventoryAction), "インベントリ操作を選び直してください。");
        return [];
    }

    private async Task<bool> CheckOwnedSessionAsync(CancellationToken ct, bool requireReady)
    {
        if (!TryActor(out var actor)) return false;
        var result = await adminApi.GetSessionAsync(actor, AccountId, ct);
        if (!result.Succeeded || result.Value?.UserUuid != UserUuid || result.Value.EditSessionId != EditSessionId
            || result.Value.ActorUserUuid != actor || result.Value.Revision != ExpectedRevision)
        {
            ErrorMessage = "編集状態が変わりました。最新の状態を確認してください。ロックは維持されています。";
            return false;
        }
        if (requireReady && result.Value.Status != "READY")
        {
            ErrorMessage = "全サーバーの保存・切断が確認されていません。編集はまだ適用できません。";
            return false;
        }
        if (!requireReady && result.Value.Status is not ("DRAINING" or "READY" or "RECOVERY_REQUIRED"))
        {
            ErrorMessage = "この編集状態では操作できません。最新の状態を確認してください。";
            return false;
        }
        return true;
    }

    private async Task<IActionResult> LoadAsync(CancellationToken ct)
    {
        if (UserUuid == Guid.Empty || AccountId == Guid.Empty) return NotFound();
        if (!TryActor(out var actor)) return Challenge();
        var profile = await profiles.GetProfileAsync(UserUuid, actor, includePrivate: true, AccountId, ct);
        if (profile.Status == HttpStatusCode.Forbidden) return Forbid();
        if (profile.Status == HttpStatusCode.NotFound || profile.Value?.CurrentAccount?.AccountId != AccountId) return NotFound();
        if (!profile.Succeeded)
        {
            ErrorMessage ??= "対象アカウントを確認できませんでした。";
            return Page();
        }
        Profile = profile.Value;
        var browserState = ReadBrowserState(actor);
        var result = await adminApi.GetSessionAsync(actor, AccountId, ct);
        if (result.Status == HttpStatusCode.Forbidden) return Forbid();
        if (result.Status == HttpStatusCode.NotFound)
        {
            if (browserState is null) return Page();
            EditSessionId = browserState.EditSessionId;
            var receipt = await adminApi.GetSessionByIdAsync(actor, browserState.EditSessionId, ct);
            if (receipt.Succeeded && receipt.Value?.AccountId == AccountId && receipt.Value.UserUuid == UserUuid
                && receipt.Value.ActorUserUuid == actor)
            {
                Session = receipt.Value;
                IsOwner = Session.ActorUserUuid == actor;
                if (Session.Status is "COMPLETED" or "CANCELED")
                {
                    if (browserState.PendingOperation is not null || browserState.PendingStart)
                        WriteBrowserState(browserState with { PendingOperation = null, PendingStart = false });
                    return Page();
                }
                ErrorMessage ??= "編集状態の取得先が一致しません。ロック状態を確認してください。";
                return Page();
            }
            if (browserState.PendingStart && receipt.Status == HttpStatusCode.NotFound)
            {
                PendingStart = true;
                return Page();
            }
            if (receipt.Status == HttpStatusCode.NotFound && browserState.PendingOperation is null)
            {
                ClearBrowserState();
                return Page();
            }
            PendingOperation = browserState.PendingOperation;
            ErrorMessage ??= "前回の操作結果を確認できません。編集ロックの状態を確認してください。";
            return Page();
        }
        if (!result.Succeeded || result.Value?.UserUuid != UserUuid || result.Value.AccountId != AccountId)
        {
            ErrorMessage ??= "編集状態を取得できませんでした。再読み込みしてください。";
            return Page();
        }
        Session = result.Value;
        IsOwner = Session.ActorUserUuid == actor;
        if (browserState is { } state && state.EditSessionId == Session.EditSessionId)
        {
            if (state.PendingStart) WriteBrowserState(state with { PendingStart = false });
            PendingOperation = state.PendingOperation;
        }
        else if (IsOwner)
        {
            WriteBrowserState(new PlayerEditBrowserState(actor, UserUuid, AccountId, Session.EditSessionId, false, null, null));
        }
        if (IsOwner && Session.Status == "READY" && PendingOperation is null)
        {
            var editor = await adminApi.GetEditorAsync(actor, Session.EditSessionId, ct);
            if (editor.Succeeded && editor.Value?.Session?.EditSessionId == Session.EditSessionId
                && editor.Value.Session.Revision == Session.Revision
                && editor.Value.Account?.AccountId == AccountId)
            {
                Editor = editor.Value;
                ExpectedStateHash = editor.Value.ExpectedStateHash;
                ExpectedCatalogVersion = editor.Value.CatalogVersion;
                ExpectedRevision = Session.Revision;
            }
            else ErrorMessage ??= "編集内容を取得できませんでした。状態を更新して再試行してください。";
        }
        return Page();
    }

    private static string Failure<T>(PlayerAdminApiResult<T> result, string fallback) => result.Status switch
    {
        HttpStatusCode.Conflict => "状態が変わったか、安全な編集条件を満たしていません。最新の状態を確認してください。ロックは維持されています。",
        HttpStatusCode.BadRequest => result.ErrorMessage ?? "入力内容を確認してください。",
        HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized => "管理権限を確認できませんでした。",
        _ => result.ErrorMessage ?? fallback,
    };

    private static bool DefinitiveFailure(HttpStatusCode status) => status is HttpStatusCode.BadRequest or HttpStatusCode.Conflict
        or HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized or HttpStatusCode.NotFound;

    private string BrowserCookieName => BrowserStateCookiePrefix + AccountId.ToString("N");

    private PlayerEditBrowserState? ReadBrowserState(Guid actor)
    {
        if (browserStateCleared) return null;
        if (currentBrowserState is { } current) return current.ActorUserUuid == actor && current.UserUuid == UserUuid && current.AccountId == AccountId ? current : null;
        var protectedValue = Request.Cookies[BrowserCookieName];
        if (string.IsNullOrWhiteSpace(protectedValue)) return null;
        try
        {
            var state = JsonSerializer.Deserialize<PlayerEditBrowserState>(browserStateProtector.Unprotect(protectedValue), BrowserJson);
            return state?.ActorUserUuid == actor && state.UserUuid == UserUuid && state.AccountId == AccountId ? state : null;
        }
        catch (Exception ex) when (ex is CryptographicException or JsonException or FormatException)
        {
            logger.LogWarning(ex, "Protected player edit browser state could not be read.");
            return null;
        }
    }

    private void WriteBrowserState(PlayerEditBrowserState state)
    {
        browserStateCleared = false;
        currentBrowserState = state;
        Response.Cookies.Append(BrowserCookieName, browserStateProtector.Protect(JsonSerializer.Serialize(state, BrowserJson)), new CookieOptions
        {
            HttpOnly = true, Secure = true, SameSite = SameSiteMode.Lax, Path = "/", IsEssential = true,
            Expires = DateTimeOffset.UtcNow.AddDays(30), MaxAge = TimeSpan.FromDays(30),
        });
    }

    private void ClearBrowserState()
    {
        browserStateCleared = true;
        currentBrowserState = null;
        Response.Cookies.Delete(BrowserCookieName, new CookieOptions { HttpOnly = true, Secure = true, SameSite = SameSiteMode.Lax, Path = "/" });
    }

    public static bool IsOrdinaryCategory(string category) => category.ToUpperInvariant() is "MATERIAL" or "ORB" or "CONSUMABLE" or "RUNE" or "SIGIL" or "BUNDLE";
    public static bool IsGrantableCategory(string category) => IsOrdinaryCategory(category) || category.Equals("EQUIPMENT", StringComparison.OrdinalIgnoreCase);

    private Guid Actor() => Guid.Parse(User.FindFirstValue(ClaimTypes.NameIdentifier)!);
    private bool TryActor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);
}

public sealed record PlayerEditPendingOperation(string Action, Guid OperationId, long ExpectedRevision,
    string ExpectedStateHash, string ExpectedCatalogVersion, int? Level, string? ClassId,
    IReadOnlyList<PlayerAdminInventoryChange> InventoryChanges);

public sealed record PlayerEditBrowserState(Guid ActorUserUuid, Guid UserUuid, Guid AccountId, Guid EditSessionId,
    bool PendingStart, string? StartReason, PlayerEditPendingOperation? PendingOperation);
