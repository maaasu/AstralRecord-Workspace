using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class EventsModel(ActivityHistoryApiClient api) : HistoryPageModel
{
    [BindProperty(SupportsGet = true)] public Guid? UserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public string? EventType { get; set; }
    public PagedPlayerActivityResponse<UserActivityEventResponse>? Results { get; private set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        if (EventType?.Length > 50) return BadRequest();
        var result = await api.GetAsync<PagedPlayerActivityResponse<UserActivityEventResponse>>("events", actor, FromUtc, ToUtc, PageNumber, Query, ct,
            ("userUuid", UserUuid?.ToString()), ("eventType", EventType?.Trim()));
        if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
        Results = result.Value;
        if (!result.Succeeded) ErrorMessage = "接続・パーティー履歴を取得できません。時間をおいて再試行してください。";
        return Page();
    }

    public static string EventLabel(string type) => type switch
    {
        "PLAYER_LOGIN" => "ログイン", "PLAYER_LOGOUT" => "ログアウト",
        "PARTY_CREATED" => "パーティー作成", "PARTY_INVITED" => "招待送信", "PARTY_INVITE_RECEIVED" => "招待受信",
        "PARTY_JOINED" => "パーティー参加", "PARTY_LEFT" => "パーティー退出", "PARTY_LEFT_LOGOUT" => "ログアウトによる退出",
        "PARTY_DISBANDED" => "パーティー解散", "PARTY_KICKED" => "パーティーから除外",
        "PARTY_MEMBER_KICKED" => "メンバー除外", "PARTY_LEADER_TRANSFERRED" => "リーダー移譲", "PARTY_LEADER_ASSIGNED" => "リーダー任命",
        _ => type,
    };

    public static Guid? PartyUuid(UserActivityEventResponse entry)
    {
        if (!entry.EventType.StartsWith("PARTY_", StringComparison.Ordinal)) return null;
        var separator = entry.Message.LastIndexOf(':');
        return separator >= 0 && Guid.TryParse(entry.Message[(separator + 1)..].Trim(), out var partyUuid)
            ? partyUuid : null;
    }

    public static string PartyDescription(UserActivityEventResponse entry) =>
        entry.Message[..entry.Message.LastIndexOf(':')].Trim();
}
