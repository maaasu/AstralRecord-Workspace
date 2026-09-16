using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class NetworkModel(NetworkManagementApiClient networkManagementApiClient) : PageModel
{
    [BindProperty] public ManagedNetworkSettingsInput Input { get; set; } = new();
    [BindProperty] public string? PlayerQuery { get; set; }
    [BindProperty] public string PlayerTargetChannel { get; set; } = "0";
    [BindProperty] public string PlayerTargetRole { get; set; } = "debug";
    public IReadOnlyDictionary<Guid, string> PlayerNames { get; private set; } = new Dictionary<Guid, string>();
    public bool IsUninitialized { get; private set; }
    public string? ErrorMessage { get; private set; }
    [TempData] public string? StatusMessage { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!TryGetActor(out var actor)) return Challenge();
        var result = await networkManagementApiClient.GetSettingsAsync(actor, ct);
        if (result.Succeeded)
        {
            Input = ManagedNetworkSettingsInput.From(result.Value!);
            PlayerNames = PlayerMap(result.Value!);
            return Page();
        }

        if (result.Status == System.Net.HttpStatusCode.NotFound)
        {
            IsUninitialized = true;
            Input = ManagedNetworkSettingsInput.Initial();
            return Page();
        }

        return ApiFailure(result);
    }

    public IActionResult OnPostAddChannel()
    {
        Input.Channels.Add(ManagedNetworkChannelInput.Create(Input.Channels.Count + 1));
        return Page();
    }

    public IActionResult OnPostRemoveChannel(int channelIndex)
    {
        if (channelIndex >= 0 && channelIndex < Input.Channels.Count)
        {
            Input.Channels.RemoveAt(channelIndex);
            if (int.TryParse(PlayerTargetChannel, out var selected))
                PlayerTargetChannel = Input.Channels.Count == 0 ? "authority"
                    : Math.Clamp(selected > channelIndex ? selected - 1 : selected, 0, Input.Channels.Count - 1).ToString();
        }
        return Page();
    }

    public async Task<IActionResult> OnGetSearchPlayersAsync(string? query, CancellationToken ct)
    {
        if (!TryGetActor(out var actor)) return Challenge();
        var prefix = query?.Trim() ?? string.Empty;
        if (prefix.Length == 0) return new JsonResult(new { players = Array.Empty<NetworkManagedPlayer>() });
        if (prefix.Length > 100) return new JsonResult(new { message = "MCIDは100文字以内で入力してください。" }) { StatusCode = 400 };
        var result = await networkManagementApiClient.SearchPlayersAsync(actor, prefix, ct);
        if (result.Succeeded) return new JsonResult(new { players = result.Value });
        return new JsonResult(new { message = "候補を取得できませんでした。入力し直して再試行してください。" })
        {
            StatusCode = result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized ? 403 : 503,
        };
    }

    public async Task<IActionResult> OnPostSaveAsync(CancellationToken ct)
    {
        if (!TryGetActor(out var actor)) return Challenge();
        if (ModelState.Any(entry => entry.Key.StartsWith("Input.", StringComparison.Ordinal) && entry.Value?.Errors.Count > 0))
        {
            ErrorMessage = "入力内容を確認してください。人数・秒数は整数で指定します。";
            return Page();
        }
        var result = await networkManagementApiClient.SaveSettingsAsync(actor, Input.ToSettings(), ct);
        if (result.Succeeded)
        {
            StatusMessage = "ネットワーク設定を保存しました。";
            return RedirectToPage();
        }

        ErrorMessage = result.Status switch
        {
            System.Net.HttpStatusCode.Conflict => "他の管理者が設定を更新しました。最新の内容を読み直してから、もう一度保存してください。",
            System.Net.HttpStatusCode.BadRequest => result.ErrorMessage ?? "入力内容を確認してください。",
            _ => "ネットワーク設定を保存できませんでした。時間をおいて再試行してください。",
        };
        return Page();
    }

    public string PlayerName(Guid userUuid) => PlayerNames.TryGetValue(userUuid, out var mcid) ? mcid : "登録済みプレイヤー";

    private IActionResult ApiFailure<T>(NetworkManagementApiResult<T> result)
    {
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        ErrorMessage = result.ErrorMessage ?? "管理 API から情報を取得できませんでした。時間をおいて再試行してください。";
        return Page();
    }

    private bool TryGetActor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);

    private static IReadOnlyDictionary<Guid, string> PlayerMap(ManagedNetworkSettings settings) =>
        settings.Players.GroupBy(player => player.UserUuid).ToDictionary(group => group.Key, group => group.First().Mcid);
}

public sealed class ManagedNetworkSettingsInput
{
    public int Revision { get; set; }
    public string LobbyServerId { get; set; } = "lobby";
    public int TransferCooldownSeconds { get; set; } = 30;
    public int TabRefreshSeconds { get; set; } = 2;
    public int PresenceHeartbeatSeconds { get; set; } = 10;
    public List<Guid> AuthorityUsers { get; set; } = [];
    public List<ManagedNetworkChannelInput> Channels { get; set; } = [];

    public static ManagedNetworkSettingsInput Initial() => new()
    {
        Revision = 0,
        Channels = [new ManagedNetworkChannelInput { ServerId = "lobby", DisplayName = "ロビー", IsGame = false }],
    };

    public static ManagedNetworkSettingsInput From(ManagedNetworkSettings settings) => new()
    {
        Revision = settings.Revision,
        LobbyServerId = settings.LobbyServerId,
        TransferCooldownSeconds = settings.TransferCooldownSeconds,
        TabRefreshSeconds = settings.TabRefreshSeconds,
        PresenceHeartbeatSeconds = settings.PresenceHeartbeatSeconds,
        AuthorityUsers = settings.AuthorityUsers.ToList(),
        Channels = settings.Channels.Select(ManagedNetworkChannelInput.From).ToList(),
    };

    public ManagedNetworkSettings ToSettings() => new()
    {
        Revision = Revision,
        LobbyServerId = LobbyServerId?.Trim() ?? string.Empty,
        TransferCooldownSeconds = TransferCooldownSeconds,
        TabRefreshSeconds = TabRefreshSeconds,
        PresenceHeartbeatSeconds = PresenceHeartbeatSeconds,
        AuthorityUsers = AuthorityUsers.Distinct().ToArray(),
        Channels = Channels.Select(channel => channel.ToChannel()).ToArray(),
    };
}

public sealed class ManagedNetworkChannelInput
{
    public string ServerId { get; set; } = string.Empty;
    public string DisplayName { get; set; } = string.Empty;
    public bool IsGame { get; set; }
    public int MaxPlayers { get; set; } = 30;
    public int DonorExtraPlayers { get; set; }
    public int AdminExtraPlayers { get; set; }
    public bool DiscordEnabled { get; set; } = true;
    public bool WhitelistEnabled { get; set; }
    public List<Guid> DebugUsers { get; set; } = [];
    public List<Guid> WhitelistUsers { get; set; } = [];

    public static ManagedNetworkChannelInput Create(int index) => new()
    {
        ServerId = $"channel-{index}",
        DisplayName = "新しいチャンネル",
        IsGame = true,
    };

    public static ManagedNetworkChannelInput From(ManagedNetworkChannel channel) => new()
    {
        ServerId = channel.ServerId,
        DisplayName = channel.DisplayName,
        IsGame = channel.IsGame,
        MaxPlayers = channel.MaxPlayers,
        DonorExtraPlayers = channel.DonorExtraPlayers,
        AdminExtraPlayers = channel.AdminExtraPlayers,
        DiscordEnabled = channel.DiscordEnabled,
        WhitelistEnabled = channel.WhitelistEnabled,
        DebugUsers = channel.DebugUsers.ToList(),
        WhitelistUsers = channel.WhitelistUsers.ToList(),
    };

    public ManagedNetworkChannel ToChannel() => new()
    {
        ServerId = ServerId?.Trim() ?? string.Empty,
        DisplayName = DisplayName?.Trim() ?? string.Empty,
        IsGame = IsGame,
        MaxPlayers = MaxPlayers,
        DonorExtraPlayers = DonorExtraPlayers,
        AdminExtraPlayers = AdminExtraPlayers,
        DiscordEnabled = DiscordEnabled,
        WhitelistEnabled = WhitelistEnabled,
        DebugUsers = DebugUsers.Distinct().ToArray(),
        WhitelistUsers = WhitelistUsers.Distinct().ToArray(),
    };
}
