using System.Globalization;
using System.Net;
using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DefenseModel(NetworkManagementApiClient networkApi, DefenseManagementApiClient defenseApi) : PageModel
{
    private static readonly TimeZoneInfo Japan = TimeZoneInfo.FindSystemTimeZoneById("Asia/Tokyo");

    [BindProperty(SupportsGet = true)] public string? ServerId { get; set; }
    [BindProperty] public DefenseSettingsInput Input { get; set; } = new();
    [BindProperty] public Guid StartOperationId { get; set; }
    [BindProperty] public bool RetryStart { get; set; }
    [BindProperty] public Guid StopOperationId { get; set; }
    [BindProperty] public Guid StopRunId { get; set; }
    [BindProperty] public string StopReason { get; set; } = string.Empty;
    public IReadOnlyList<ManagedNetworkChannel> Channels { get; private set; } = [];
    public DefenseChannelState? State { get; private set; }
    public IReadOnlyList<DefenseRun> Runs { get; private set; } = [];
    public string? ErrorMessage { get; private set; }
    public string? HistoryError { get; private set; }
    [TempData] public string? StatusMessage { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!TryGetActor(out var actor)) return Challenge();
        var channelsResult = await LoadChannelsAsync(actor, ct);
        if (channelsResult is not null) return channelsResult;
        if (ServerId is null) return Page();
        var stateResult = await defenseApi.GetChannelAsync(actor, ServerId, ct);
        if (!stateResult.Succeeded) return Failure(stateResult, "防衛戦の状態を取得できませんでした。");
        State = stateResult.Value;
        Input = DefenseSettingsInput.From(State!);
        await LoadRunsAsync(actor, ct);
        StartOperationId = Guid.NewGuid();
        StopOperationId = Guid.NewGuid();
        StopRunId = State!.ActiveRun?.RunId ?? Guid.Empty;
        return Page();
    }

    public async Task<IActionResult> OnPostSaveAsync(CancellationToken ct)
    {
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        if (ModelState.Any(entry => entry.Key.StartsWith("Input.", StringComparison.Ordinal) && entry.Value?.Errors.Count > 0))
        {
            ErrorMessage = "入力値を確認してください。数値は半角数字で入力します。";
            return Page();
        }
        if (Input.DailyEnabled && !TimeOnly.TryParseExact(Input.DailyStartTimeJst, "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
        {
            ErrorMessage = "日次開催を有効にする場合は、日本時間の開始時刻を入力してください。";
            return Page();
        }
        var result = await defenseApi.SaveAsync(prepared.Actor, ServerId!, Input.ToUpdate(), ct);
        if (result.Succeeded)
        {
            StatusMessage = "防衛戦の設定を保存しました。開始前に不足項目を確認してください。";
            return RedirectToPage(new { serverId = ServerId });
        }
        ErrorMessage = ErrorFor(result.Status, result.ErrorMessage, "設定を保存できませんでした。");
        return Page();
    }

    public async Task<IActionResult> OnPostStartAsync(CancellationToken ct)
    {
        var expectedRevision = Input.Revision;
        var requestedOperationId = StartOperationId;
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        Input = DefenseSettingsInput.From(State!);
        ModelState.Clear();
        if (RetryStart && requestedOperationId == Guid.Empty)
        {
            RetryStart = false;
            ErrorMessage = "再試行する開始操作IDがありません。画面を読み直してください。";
            return Page();
        }
        if (!RetryStart && State?.MissingSettings.Count > 0)
        {
            ErrorMessage = "必須設定が不足しています。設定を保存し、不足項目を解消してから開始してください。";
            return Page();
        }
        if (!RetryStart && (State?.RegistrationStatus != "OPEN" || State.RegisteredCount is not > 0))
        {
            ErrorMessage = "受付中の参加者を確認できません。ゲームサーバーの最新の受付状態を確認してください。";
            return Page();
        }
        var result = await defenseApi.StartAsync(prepared.Actor, ServerId!, new(StartOperationId, expectedRevision), ct);
        if (result.Succeeded)
        {
            StatusMessage = "開始要求を受け付けました。実際に開始したか、状態が「防衛中」に変わったことを確認してください。";
            return RedirectToPage(new { serverId = ServerId });
        }
        RetryStart = (int)result.Status >= 500 || result.Status == HttpStatusCode.RequestTimeout;
        if (RetryStart)
        {
            var latest = await defenseApi.GetChannelAsync(prepared.Actor, ServerId!, ct);
            if (latest.Succeeded)
            {
                State = latest.Value;
                Input = DefenseSettingsInput.From(State!);
                await LoadRunsAsync(prepared.Actor, ct);
            }
            Input.Revision = expectedRevision;
        }
        ErrorMessage = ErrorFor(result.Status, result.ErrorMessage, "開始要求の結果を確認できませんでした。同じ操作IDで再試行できます。");
        return Page();
    }

    public async Task<IActionResult> OnPostStopAsync(CancellationToken ct)
    {
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        Input = DefenseSettingsInput.From(State!);
        ModelState.Clear();
        if (StopRunId == Guid.Empty || string.IsNullOrWhiteSpace(StopReason))
        {
            ErrorMessage = "停止対象と理由を確認してください。";
            return Page();
        }
        var result = await defenseApi.StopAsync(prepared.Actor, ServerId!, new(StopOperationId, StopRunId, StopReason.Trim()), ct);
        if (result.Succeeded)
        {
            StatusMessage = "停止要求を受け付けました。終了状態はゲームサーバーからの報告後に確認してください。";
            return RedirectToPage(new { serverId = ServerId });
        }
        ErrorMessage = ErrorFor(result.Status, result.ErrorMessage, "停止要求の結果を確認できませんでした。同じ操作IDで再試行できます。");
        return Page();
    }

    public async Task<IActionResult> OnPostAddSpawnAsync(CancellationToken ct)
    {
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        Input.SpawnPoints.Add(new());
        ModelState.Clear();
        return Page();
    }

    public async Task<IActionResult> OnPostRemoveSpawnAsync(int index, CancellationToken ct)
    {
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        if (index >= 0 && index < Input.SpawnPoints.Count) Input.SpawnPoints.RemoveAt(index);
        ModelState.Clear();
        return Page();
    }

    public async Task<IActionResult> OnPostAddRewardAsync(CancellationToken ct)
    {
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        Input.RewardTiers.Add(new());
        ModelState.Clear();
        return Page();
    }

    public async Task<IActionResult> OnPostRemoveRewardAsync(int index, CancellationToken ct)
    {
        var prepared = await PreparePostAsync(ct);
        if (prepared.Action is not null) return prepared.Action;
        if (index >= 0 && index < Input.RewardTiers.Count) Input.RewardTiers.RemoveAt(index);
        ModelState.Clear();
        return Page();
    }

    private async Task<(Guid Actor, IActionResult? Action)> PreparePostAsync(CancellationToken ct)
    {
        if (!TryGetActor(out var actor)) return (default, Challenge());
        var action = await LoadChannelsAsync(actor, ct);
        if (action is not null) return (actor, action);
        var stateResult = await defenseApi.GetChannelAsync(actor, ServerId!, ct);
        if (!stateResult.Succeeded) return (actor, Failure(stateResult, "防衛戦の状態を取得できませんでした。"));
        State = stateResult.Value;
        await LoadRunsAsync(actor, ct);
        if (StartOperationId == Guid.Empty) StartOperationId = Guid.NewGuid();
        if (StopOperationId == Guid.Empty) StopOperationId = Guid.NewGuid();
        if (StopRunId == Guid.Empty) StopRunId = State!.ActiveRun?.RunId ?? Guid.Empty;
        return (actor, null);
    }

    private async Task<IActionResult?> LoadChannelsAsync(Guid actor, CancellationToken ct)
    {
        var result = await networkApi.GetSettingsAsync(actor, ct);
        if (!result.Succeeded)
        {
            if (result.Status == HttpStatusCode.Forbidden) return Forbid();
            ErrorMessage = "ネットワークのチャンネル一覧を取得できませんでした。";
            return Page();
        }
        Channels = result.Value!.Channels.Where(channel => channel.IsGame).ToArray();
        if (ServerId is null) ServerId = Channels.FirstOrDefault()?.ServerId;
        if (ServerId is not null && !Channels.Any(channel => channel.ServerId == ServerId)) return NotFound();
        return null;
    }

    private async Task LoadRunsAsync(Guid actor, CancellationToken ct)
    {
        var result = await defenseApi.GetRunsAsync(actor, ServerId!, ct);
        if (result.Succeeded) Runs = result.Value!;
        else HistoryError = "履歴を取得できませんでした。時間をおいて再読み込みしてください。";
    }

    private IActionResult Failure<T>(DefenseApiResult<T> result, string fallback)
    {
        if (result.Status == HttpStatusCode.Forbidden) return Forbid();
        ErrorMessage = ErrorFor(result.Status, result.ErrorMessage, fallback);
        return Page();
    }

    private static string ErrorFor(HttpStatusCode status, string? detail, string fallback) => status switch
    {
        HttpStatusCode.Conflict => "競合が発生しました。最新の設定と状態を読み直してから再実行してください。" + (string.IsNullOrWhiteSpace(detail) ? "" : $"（{detail}）"),
        HttpStatusCode.BadRequest => detail ?? "入力内容を確認してください。",
        HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized => "管理権限または本人確認の状態を確認してください。",
        _ => fallback,
    };

    private bool TryGetActor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);
    public string Jst(DateTimeOffset? instant) => instant is null ? "―" : TimeZoneInfo.ConvertTime(instant.Value, Japan).ToString("yyyy/MM/dd HH:mm", CultureInfo.InvariantCulture) + " JST";
    public string StatusLabel(string? status) => status switch
    {
        "PENDING" => "開始要求を受理（ゲーム内開始待ち）",
        "RUNNING" => "防衛中",
        "STOP_REQUESTED" => "停止要求を受理（終了報告待ち）",
        "ENDED" => "終了",
        "ABORTED" => "中断",
        "SKIPPED" => "開催見送り",
        _ => "待機・未確認",
    };
    public string RegistrationLabel(string? status) => status switch
    {
        "CLOSED" => "受付前・受付終了",
        "OPEN" => "受付中",
        "PREPARING" => "開始準備中",
        _ => "確認できません",
    };
    public string SourceLabel(string? source) => source switch
    {
        "DAILY" => "日次",
        "MANUAL" => "手動",
        _ => "種別未確認",
    };

    public string MissingLabel(string path)
    {
        var key = path.Replace("configuration.", "", StringComparison.OrdinalIgnoreCase);
        if (key.StartsWith("spawnPoints", StringComparison.OrdinalIgnoreCase)) return "敵の出現地点";
        if (key.StartsWith("rewardTiers", StringComparison.OrdinalIgnoreCase)) return "オーブ報酬段階";
        return key switch
        {
            "eligibleMobs" => "防衛戦に参加可能なMob",
            "worldName" => "専用ワールド名",
            "coreX" or "coreY" or "coreZ" => "防衛対象の座標",
            "entryX" or "entryY" or "entryZ" => "参加者の入場座標",
            "coreMaxHealth" => "防衛対象の最大HP",
            "coreDefense" => "防衛対象の防御力",
            "baseSpawnCount" => "基本出現数",
            "spawnIntervalSeconds" => "増援間隔",
            "maxSpawnsPerWave" => "1回の増援上限",
            "maxSpawnsPerTick" => "1 tickの出現上限",
            "maxAliveEnemies" => "同時生存数上限",
            "retaliationSeconds" => "反撃継続秒数",
            "retaliationRange" => "反撃範囲",
            "playerSpawnCoefficient" => "人数による出現量係数",
            "playerHealthCoefficient" => "人数によるHP係数",
            "playerDamageCoefficient" => "人数による攻撃力係数",
            "postMaxLevelHealthCoefficient" => "最高登録Lv後のHP係数",
            "postMaxLevelDamageCoefficient" => "最高登録Lv後の攻撃力係数",
            "registrationLeadSeconds" => "受付開始の先行秒数",
            "reconnectGraceSeconds" => "切断復帰猶予",
            "reviveProtectionSeconds" => "自動復活後の無敵秒数",
            "reviveShieldMode" => "自動復活時のシールド",
            "rewardEveryLevels" => "報酬確定のLv間隔",
            "minimumActiveSeconds" => "報酬を得るための最短参加秒数",
            "dailyStartTimeJst" => "日次開始時刻（日本時間）",
            _ => path,
        };
    }
}

public sealed class DefenseSettingsInput
{
    public int Revision { get; set; }
    public bool DailyEnabled { get; set; }
    public string? DailyStartTimeJst { get; set; }
    public string? WorldName { get; set; }
    public double? CoreX { get; set; }
    public double? CoreY { get; set; }
    public double? CoreZ { get; set; }
    public double? EntryX { get; set; }
    public double? EntryY { get; set; }
    public double? EntryZ { get; set; }
    public List<DefenseSpawnInput> SpawnPoints { get; set; } = [];
    public double? CoreMaxHealth { get; set; }
    public double? CoreDefense { get; set; }
    public int? BaseSpawnCount { get; set; }
    public int? SpawnIntervalSeconds { get; set; }
    public int? MaxSpawnsPerWave { get; set; }
    public int? MaxSpawnsPerTick { get; set; }
    public int? MaxAliveEnemies { get; set; }
    public int? RetaliationSeconds { get; set; }
    public double? RetaliationRange { get; set; }
    public double? PlayerSpawnCoefficient { get; set; }
    public double? PlayerHealthCoefficient { get; set; }
    public double? PlayerDamageCoefficient { get; set; }
    public double? PostMaxLevelHealthCoefficient { get; set; }
    public double? PostMaxLevelDamageCoefficient { get; set; }
    public int? RegistrationLeadSeconds { get; set; }
    public int? ReconnectGraceSeconds { get; set; }
    public int? ReviveProtectionSeconds { get; set; }
    public string? ReviveShieldMode { get; set; }
    public int? RewardEveryLevels { get; set; }
    public int? MinimumActiveSeconds { get; set; }
    public List<DefenseRewardInput> RewardTiers { get; set; } = [];

    public static DefenseSettingsInput From(DefenseChannelState state)
    {
        var config = state.Configuration;
        return new()
        {
            Revision = state.Revision, DailyEnabled = state.DailyEnabled, DailyStartTimeJst = state.DailyStartTimeJst,
            WorldName = config.WorldName, CoreX = config.CoreX, CoreY = config.CoreY, CoreZ = config.CoreZ,
            EntryX = config.EntryX, EntryY = config.EntryY, EntryZ = config.EntryZ,
            SpawnPoints = config.SpawnPoints.Select(point => new DefenseSpawnInput { X = point.X, Y = point.Y, Z = point.Z }).ToList(),
            CoreMaxHealth = config.CoreMaxHealth, CoreDefense = config.CoreDefense,
            BaseSpawnCount = config.BaseSpawnCount, SpawnIntervalSeconds = config.SpawnIntervalSeconds,
            MaxSpawnsPerWave = config.MaxSpawnsPerWave, MaxSpawnsPerTick = config.MaxSpawnsPerTick,
            MaxAliveEnemies = config.MaxAliveEnemies, RetaliationSeconds = config.RetaliationSeconds,
            RetaliationRange = config.RetaliationRange, PlayerSpawnCoefficient = config.PlayerSpawnCoefficient,
            PlayerHealthCoefficient = config.PlayerHealthCoefficient, PlayerDamageCoefficient = config.PlayerDamageCoefficient,
            PostMaxLevelHealthCoefficient = config.PostMaxLevelHealthCoefficient,
            PostMaxLevelDamageCoefficient = config.PostMaxLevelDamageCoefficient,
            RegistrationLeadSeconds = config.RegistrationLeadSeconds, ReconnectGraceSeconds = config.ReconnectGraceSeconds,
            ReviveProtectionSeconds = config.ReviveProtectionSeconds, ReviveShieldMode = config.ReviveShieldMode,
            RewardEveryLevels = config.RewardEveryLevels, MinimumActiveSeconds = config.MinimumActiveSeconds,
            RewardTiers = config.RewardTiers.Select(tier => new DefenseRewardInput
            { RequiredDefenseLevel = tier.RequiredDefenseLevel, OrbItemId = tier.OrbItemId, OrbQuantity = tier.OrbQuantity }).ToList(),
        };
    }

    public DefenseSettingsUpdate ToUpdate() => new(Revision, DailyEnabled, DailyStartTimeJst, new DefenseConfiguration
    {
        WorldName = WorldName?.Trim(), CoreX = CoreX, CoreY = CoreY, CoreZ = CoreZ,
        EntryX = EntryX, EntryY = EntryY, EntryZ = EntryZ,
        SpawnPoints = SpawnPoints.Select(point => new DefenseSpawnPoint { X = point.X, Y = point.Y, Z = point.Z }).ToArray(),
        CoreMaxHealth = CoreMaxHealth, CoreDefense = CoreDefense, BaseSpawnCount = BaseSpawnCount,
        SpawnIntervalSeconds = SpawnIntervalSeconds, MaxSpawnsPerWave = MaxSpawnsPerWave,
        MaxSpawnsPerTick = MaxSpawnsPerTick, MaxAliveEnemies = MaxAliveEnemies,
        RetaliationSeconds = RetaliationSeconds, RetaliationRange = RetaliationRange,
        PlayerSpawnCoefficient = PlayerSpawnCoefficient, PlayerHealthCoefficient = PlayerHealthCoefficient,
        PlayerDamageCoefficient = PlayerDamageCoefficient,
        PostMaxLevelHealthCoefficient = PostMaxLevelHealthCoefficient, PostMaxLevelDamageCoefficient = PostMaxLevelDamageCoefficient,
        RegistrationLeadSeconds = RegistrationLeadSeconds, ReconnectGraceSeconds = ReconnectGraceSeconds,
        ReviveProtectionSeconds = ReviveProtectionSeconds, ReviveShieldMode = ReviveShieldMode,
        RewardEveryLevels = RewardEveryLevels, MinimumActiveSeconds = MinimumActiveSeconds,
        RewardTiers = RewardTiers.Select(tier => new DefenseRewardTier
        { RequiredDefenseLevel = tier.RequiredDefenseLevel, OrbItemId = tier.OrbItemId?.Trim(), OrbQuantity = tier.OrbQuantity }).ToArray(),
    });
}

public sealed class DefenseSpawnInput
{
    public double? X { get; set; }
    public double? Y { get; set; }
    public double? Z { get; set; }
}

public sealed class DefenseRewardInput
{
    public int? RequiredDefenseLevel { get; set; }
    public string? OrbItemId { get; set; }
    public int? OrbQuantity { get; set; }
}
