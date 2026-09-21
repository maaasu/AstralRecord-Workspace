using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Repositories;

/// <summary>ゲーム状態を変更せず、Webプロフィール用に保存済み状態を合成します。</summary>
public sealed class WebPlayerProfileRepository(
    AstralRecordDbContext gameDb,
    ManagementDbContext managementDb,
    MasterDataDbContext masterDataDb,
    IOptions<WebPlayerProfileOptions> profileOptions, ISkillTreeOperationRepository? runtime = null) : IWebPlayerProfileRepository
{
    private readonly string structureId = profileOptions.Value.SkillTreeStructureId.Trim();

    public async Task<WebPlayerProfileResponse?> GetMyProfileAsync(Guid viewerUserUuid) =>
        await BuildProfileAsync(viewerUserUuid, includeConnection: true);

    public async Task<WebPlayerProfileResponse?> GetProfileAsync(
        Guid targetUserUuid, Guid viewerUserUuid, bool includePrivate, Guid? accountId = null)
    {
        var isAdmin = await IsWebAdminAsync(viewerUserUuid);
        if (targetUserUuid != viewerUserUuid && !(includePrivate && isAdmin)
            && !await managementDb.Players.AsNoTracking().AnyAsync(player => player.PlayerUuid == targetUserUuid && player.IsProfilePublic))
            return null;
        var profile = await BuildProfileAsync(targetUserUuid, accountId, targetUserUuid == viewerUserUuid);
        if (profile is null)
            return null;
        if (targetUserUuid == viewerUserUuid || (includePrivate && isAdmin)) return profile;
        return profile.IsPublic ? WithoutPermission(profile) : null;
    }

    public async Task<WebPlayerProfileSearchResponse> SearchAsync(
        Guid viewerUserUuid, string? mcid, string? classId, string? sort, int page, int pageSize, bool includePrivate)
    {
        var includesAllRegisteredPlayers = includePrivate && await IsWebAdminAsync(viewerUserUuid);
        var managementPlayers = await managementDb.Players.AsNoTracking().ToDictionaryAsync(player => player.PlayerUuid);
        var publicPlayerIds = managementPlayers.Values.Where(player => player.IsProfilePublic).Select(player => player.PlayerUuid).ToArray();
        var usersQuery = gameDb.Users.AsNoTracking().Where(user => !user.IsDeleted);
        if (!includesAllRegisteredPlayers) usersQuery = usersQuery.Where(user => publicPlayerIds.Contains(user.Uuid));
        var users = await usersQuery.ToDictionaryAsync(user => user.Uuid);
        var accounts = await gameDb.Accounts.AsNoTracking()
            .Where(account => !account.IsDeleted && users.Keys.Contains(account.UserId))
            .ToListAsync();
        var classMap = await GetClassMapAsync();
        var candidates = accounts.Select(account =>
        {
            var userId = account.UserId;
            users.TryGetValue(userId, out var user);
            return new { UserId = userId, Mcid = user!.Mcid, Account = account };
        })
            .Where(item => string.IsNullOrWhiteSpace(mcid) || item.Mcid.Contains(mcid.Trim(), StringComparison.OrdinalIgnoreCase))
            .Where(item => string.IsNullOrWhiteSpace(classId) || string.Equals(item.Account?.ClassId, classId.Trim(), StringComparison.OrdinalIgnoreCase))
            .OrderBy(item => sort == "level_asc" ? item.Account.Level : -(long)item.Account.Level)
            .ThenBy(item => item.Mcid, StringComparer.OrdinalIgnoreCase).ThenBy(item => item.UserId)
            .ToList();
        var selected = candidates.Skip((page - 1) * pageSize).Take(pageSize).ToList();
        var selectedIds = selected.Select(item => item.UserId).ToArray();
        // Recheck visibility after selection; do not build full account/skill-tree details for a list.
        var publicIdsNow = await managementDb.Players.AsNoTracking()
            .Where(player => selectedIds.Contains(player.PlayerUuid) && player.IsProfilePublic)
            .Select(player => player.PlayerUuid).ToListAsync();
        var profiles = selected.Where(item => includesAllRegisteredPlayers || publicIdsNow.Contains(item.UserId))
            .Select(item => new WebPlayerProfileSummaryResponse
            {
                UserUuid = item.UserId, Mcid = item.Mcid, IsPublic = publicIdsNow.Contains(item.UserId),
                Account = new WebPlayerAccountSummaryResponse
                {
                    AccountId = item.Account.Uuid, AccountName = item.Account.AccountName, SlotIndex = item.Account.SlotIndex, PlayerLevel = item.Account.Level,
                    ClassId = item.Account.ClassId,
                    ClassName = classMap.TryGetValue(item.Account.ClassId, out var c) ? StripLegacyColors(c.Name) : item.Account.ClassId,
                },
            }).ToList();
        return new WebPlayerProfileSearchResponse
        {
            Profiles = profiles,
            Classes = classMap.Values.OrderBy(c => c.Order).ThenBy(c => c.Id, StringComparer.Ordinal)
                .Select(c => new WebPlayerProfileClassFilterResponse { Id = c.Id, Name = StripLegacyColors(c.Name) }).ToList(),
            Page = page, PageSize = pageSize, TotalCount = candidates.Count,
        };
    }

    public async Task<WebPlayerProfileResponse?> UpdateVisibilityAsync(Guid viewerUserUuid, bool isPublic)
    {
        var player = await managementDb.Players.FirstOrDefaultAsync(player => player.PlayerUuid == viewerUserUuid);
        if (player is null)
            return null;
        player.IsProfilePublic = isPublic;
        player.UpdatedAt = DateTime.UtcNow;
        await managementDb.SaveChangesAsync();
        return await BuildProfileAsync(viewerUserUuid, includeConnection: true);
    }

    private async Task<bool> IsWebAdminAsync(Guid viewerUserUuid) =>
        viewerUserUuid != Guid.Empty && await managementDb.Players.AsNoTracking()
            .Where(player => player.PlayerUuid == viewerUserUuid)
            .Select(player => (bool?)player.WebAdmin)
            .FirstOrDefaultAsync() == true;

    private async Task<WebPlayerProfileResponse?> BuildProfileAsync(Guid userUuid, Guid? requestedAccountId = null, bool includeConnection = false)
    {
        var managementPlayer = await managementDb.Players.AsNoTracking()
            .FirstOrDefaultAsync(player => player.PlayerUuid == userUuid);
        var user = await gameDb.Users.AsNoTracking()
            .FirstOrDefaultAsync(candidate => candidate.Uuid == userUuid && !candidate.IsDeleted);
        if (managementPlayer is null && user is null)
            return null;

        var accounts = user is null ? new List<AccountEntity>() : await gameDb.Accounts.AsNoTracking()
            .Where(candidate => candidate.UserId == user.Uuid && !candidate.IsDeleted)
            .OrderBy(candidate => candidate.SlotIndex).ThenBy(candidate => candidate.Uuid)
            .ToListAsync();
        var account = requestedAccountId.HasValue
            ? accounts.FirstOrDefault(candidate => candidate.Uuid == requestedAccountId.Value)
            : user?.AccountId is Guid accountId ? accounts.FirstOrDefault(candidate => candidate.Uuid == accountId) : null;
        if (requestedAccountId.HasValue && account is null)
            return null;
        var classMap = await GetClassMapAsync();
        return new WebPlayerProfileResponse
        {
            UserUuid = userUuid,
            Mcid = user?.Mcid ?? managementPlayer!.Mcid,
            Permission = user?.Permission,
            IsPublic = managementPlayer?.IsProfilePublic ?? false,
            CurrentAccount = account is null ? null : await BuildAccountAsync(account, includeConnection),
            Accounts = accounts.Select(candidate => new WebPlayerAccountSummaryResponse
            {
                AccountId = candidate.Uuid, AccountName = candidate.AccountName, SlotIndex = candidate.SlotIndex,
                PlayerLevel = candidate.Level, ClassId = candidate.ClassId,
                ClassName = classMap.TryGetValue(candidate.ClassId, out var c) ? StripLegacyColors(c.Name) : candidate.ClassId,
            }).ToList(),
        };
    }

    private async Task<WebPlayerAccountProfileResponse> BuildAccountAsync(AccountEntity account, bool includeConnection)
    {
        var classMap = await GetClassMapAsync();
        var progress = await gameDb.AccountClassProgresses.AsNoTracking()
            .Where(item => item.AccountId == account.Uuid)
            .OrderBy(item => item.ClassId)
            .ToListAsync();
        var currentClassLevel = progress.FirstOrDefault(item =>
            string.Equals(item.ClassId, account.ClassId, StringComparison.OrdinalIgnoreCase))?.Level ?? account.ClassLevel;
        var currencyEntries = await gameDb.InventoryEntries.AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.Quantity > 0)
            .Join(gameDb.Inventories.AsNoTracking().Where(inventory => !inventory.IsDeleted && inventory.IsEnabled
                    && inventory.AccountId == account.Uuid
                    && inventory.InventoryType == "CURRENCY" && inventory.InventoryProfile == "GAME"),
                entry => entry.InventoryId, inventory => inventory.InventoryId, (entry, _) => entry)
            .ToListAsync();
        var gold = GoldCurrencyBalanceSupport.TotalGold(currencyEntries);
        var totalMobDefeats = await gameDb.AccountMobRecords.AsNoTracking()
            .Where(record => record.AccountId == account.Uuid && !record.IsDeleted)
            .SumAsync(record => (long?)record.DefeatCount) ?? 0L;
        var editor = runtime is null ? null : await runtime.GetEditorAsync(account.Uuid, account.UserId);
        return new WebPlayerAccountProfileResponse
        {
            AccountId = account.Uuid,
            AccountName = account.AccountName,
            SlotIndex = account.SlotIndex,
            PlayerLevel = account.Level,
            ClassId = account.ClassId,
            ClassName = classMap.TryGetValue(account.ClassId, out var currentClass) ? StripLegacyColors(currentClass.Name) : account.ClassId,
            ClassLevel = currentClassLevel,
            ClassProgresses = progress.Select(item => new WebPlayerClassProgressResponse
            {
                ClassId = item.ClassId,
                ClassName = classMap.TryGetValue(item.ClassId, out var classValue) ? StripLegacyColors(classValue.Name) : item.ClassId,
                Level = item.Level,
            }).ToList(),
            Gold = gold,
            TotalMobDefeats = totalMobDefeats,
            UpdatedAt = DateTime.SpecifyKind(account.UpdatedAt, DateTimeKind.Utc),
            SkillTree = BuildVerifiedSkillTree(editor),
            Connection = includeConnection ? editor?.Connection : null,
        };
    }

    private async Task<Dictionary<string, ClassResponse>> GetClassMapAsync() =>
        (await masterDataDb.Entries.AsNoTracking().Where(entry => !entry.IsDeleted && entry.MasterType == "class")
            .Select(entry => entry.PayloadJson).ToListAsync())
        .Select(MasterDataPayloadJson.Deserialize<ClassResponse>).Where(item => item is not null)
        .Cast<ClassResponse>().ToDictionary(item => item.Id, StringComparer.OrdinalIgnoreCase);

    private WebSkillTreeProfileResponse EmptySkillTree() => new()
    {
        StructureId = structureId, Name = string.Empty, RootNodeId = string.Empty, Nodes = [], Edges = [],
    };

    private WebSkillTreeProfileResponse BuildVerifiedSkillTree(SkillTreeEditorResponse? editor)
    {
        if (editor?.Tree is not { ValueKind: JsonValueKind.Object } tree) return EmptySkillTree();
        try { return tree.Deserialize<WebSkillTreeProfileResponse>(new JsonSerializerOptions(JsonSerializerDefaults.Web)) ?? EmptySkillTree(); }
        catch (JsonException) { return EmptySkillTree(); }
    }

    private static WebPlayerProfileResponse WithoutPermission(WebPlayerProfileResponse profile) => new()
    {
        UserUuid = profile.UserUuid, Mcid = profile.Mcid, Permission = null, IsPublic = profile.IsPublic,
        CurrentAccount = profile.CurrentAccount,
        Accounts = profile.Accounts,
    };

    private static string StripLegacyColors(string value) => System.Text.RegularExpressions.Regex.Replace(value, "[&§][0-9A-FK-ORX]", string.Empty, System.Text.RegularExpressions.RegexOptions.IgnoreCase);
}
