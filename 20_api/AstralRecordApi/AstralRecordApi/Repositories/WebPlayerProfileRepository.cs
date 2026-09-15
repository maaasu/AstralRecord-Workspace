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
    IOptions<FileDatabaseOptions> fileDatabaseOptions,
    IOptions<WebPlayerProfileOptions> profileOptions) : IWebPlayerProfileRepository
{
    private const string GoldItemId = "99a00001";
    private const string LegacyGoldItemId = "ast_gold";
    private const string LegacyPreviousGoldItemId = "gold";
    private readonly string filebaseRoot = fileDatabaseOptions.Value.RootPath;
    private readonly string structureId = profileOptions.Value.SkillTreeStructureId.Trim();

    public async Task<WebPlayerProfileResponse?> GetMyProfileAsync(Guid viewerUserUuid) =>
        await BuildProfileAsync(viewerUserUuid);

    public async Task<WebPlayerProfileResponse?> GetProfileAsync(
        Guid targetUserUuid, Guid viewerUserUuid, bool includePrivate)
    {
        var isAdmin = await IsWebAdminAsync(viewerUserUuid);
        var profile = await BuildProfileAsync(targetUserUuid);
        if (profile is null)
            return null;
        return targetUserUuid == viewerUserUuid || profile.IsPublic || (includePrivate && isAdmin)
            ? profile
            : null;
    }

    public async Task<WebPlayerProfileSearchResponse> SearchAsync(
        Guid viewerUserUuid, string? mcid, string? classId, string? sort, int page, int pageSize, bool includePrivate)
    {
        var isAdmin = await IsWebAdminAsync(viewerUserUuid);
        var includesAllRegisteredPlayers = includePrivate && isAdmin;
        var managementPlayers = await managementDb.Players.AsNoTracking().ToDictionaryAsync(player => player.PlayerUuid);
        var publicPlayerIds = managementPlayers.Values.Where(player => player.IsProfilePublic)
            .Select(player => player.PlayerUuid).ToArray();
        var normalizedMcid = string.IsNullOrWhiteSpace(mcid) ? null : mcid.Trim();
        var usersQuery = gameDb.Users.AsNoTracking().Where(user => !user.IsDeleted);
        if (!includesAllRegisteredPlayers)
            usersQuery = usersQuery.Where(user => publicPlayerIds.Contains(user.Uuid));
        if (normalizedMcid is not null)
            usersQuery = usersQuery.Where(user => user.Mcid.Contains(normalizedMcid));
        var users = await usersQuery
            .ToDictionaryAsync(user => user.Uuid);
        var accountIds = users.Values.Where(user => user.AccountId.HasValue).Select(user => user.AccountId!.Value).ToArray();
        var accounts = await gameDb.Accounts.AsNoTracking()
            .Where(account => accountIds.Contains(account.Uuid) && !account.IsDeleted)
            .ToDictionaryAsync(account => account.Uuid);

        var candidateIds = new HashSet<Guid>(users.Keys);
        if (includesAllRegisteredPlayers)
            foreach (var player in managementPlayers.Values.Where(player => normalizedMcid is null || player.Mcid.Contains(normalizedMcid)))
                candidateIds.Add(player.PlayerUuid);
        var candidates = candidateIds
            .Where(userId => string.IsNullOrWhiteSpace(classId)
                || (users.TryGetValue(userId, out var user)
                    && user.AccountId.HasValue
                    && accounts.TryGetValue(user.AccountId.Value, out var account)
                    && string.Equals(account.ClassId, classId.Trim(), StringComparison.OrdinalIgnoreCase)))
            .Select(userId => new { UserId = userId, Level = TryGetLevel(userId, users, accounts), Mcid = users.TryGetValue(userId, out var user) ? user.Mcid : managementPlayers[userId].Mcid })
            .OrderBy(candidate => sort == "level_asc" ? candidate.Level : -candidate.Level)
            .ThenBy(candidate => candidate.Mcid, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var total = candidates.Count;
        var selectedIds = candidates.Skip((page - 1) * pageSize).Take(pageSize).Select(candidate => candidate.UserId).ToArray();
        var profiles = new List<WebPlayerProfileResponse>(selectedIds.Length);
        foreach (var userId in selectedIds)
        {
            var profile = await BuildProfileAsync(userId);
            if (profile is not null && (includesAllRegisteredPlayers || profile.IsPublic))
                profiles.Add(includesAllRegisteredPlayers || userId == viewerUserUuid ? profile : WithoutPermission(profile));
        }

        return new WebPlayerProfileSearchResponse
        {
            Profiles = profiles,
            Classes = await GetClassFiltersAsync(),
            Page = page,
            PageSize = pageSize,
            TotalCount = total,
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
        return await BuildProfileAsync(viewerUserUuid);
    }

    private async Task<bool> IsWebAdminAsync(Guid viewerUserUuid) =>
        viewerUserUuid != Guid.Empty && await managementDb.Players.AsNoTracking()
            .Where(player => player.PlayerUuid == viewerUserUuid)
            .Select(player => (bool?)player.WebAdmin)
            .FirstOrDefaultAsync() == true;

    private async Task<WebPlayerProfileResponse?> BuildProfileAsync(Guid userUuid)
    {
        var managementPlayer = await managementDb.Players.AsNoTracking()
            .FirstOrDefaultAsync(player => player.PlayerUuid == userUuid);
        var user = await gameDb.Users.AsNoTracking()
            .FirstOrDefaultAsync(candidate => candidate.Uuid == userUuid && !candidate.IsDeleted);
        if (managementPlayer is null && user is null)
            return null;

        var account = user?.AccountId is Guid accountId
            ? await gameDb.Accounts.AsNoTracking().FirstOrDefaultAsync(candidate =>
                candidate.Uuid == accountId && candidate.UserId == user!.Uuid && !candidate.IsDeleted)
            : null;
        return new WebPlayerProfileResponse
        {
            UserUuid = userUuid,
            Mcid = user?.Mcid ?? managementPlayer!.Mcid,
            Permission = user?.Permission,
            IsPublic = managementPlayer?.IsProfilePublic ?? false,
            CurrentAccount = account is null ? null : await BuildAccountAsync(account),
        };
    }

    private async Task<WebPlayerAccountProfileResponse> BuildAccountAsync(AccountEntity account)
    {
        var classMap = await GetClassMapAsync();
        var progress = await gameDb.AccountClassProgresses.AsNoTracking()
            .Where(item => item.AccountId == account.Uuid)
            .OrderBy(item => item.ClassId)
            .ToListAsync();
        var currentClassLevel = progress.FirstOrDefault(item =>
            string.Equals(item.ClassId, account.ClassId, StringComparison.OrdinalIgnoreCase))?.Level ?? account.ClassLevel;
        var gold = await gameDb.InventoryEntries.AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.ItemId != null &&
                (entry.ItemId == GoldItemId || entry.ItemId == LegacyGoldItemId || entry.ItemId == LegacyPreviousGoldItemId))
            .Join(gameDb.Inventories.AsNoTracking().Where(inventory => !inventory.IsDeleted && inventory.IsEnabled
                    && inventory.AccountId == account.Uuid
                    && inventory.InventoryType == "CURRENCY" && inventory.InventoryProfile == "GAME"),
                entry => entry.InventoryId, inventory => inventory.InventoryId, (entry, _) => (long?)entry.Quantity)
            .SumAsync() ?? 0L;
        var unlockedNodes = await GetUnlockedNodesAsync(account.Uuid);
        var classAncestors = GetClassAncestors(account.ClassId, classMap);
        return new WebPlayerAccountProfileResponse
        {
            AccountId = account.Uuid,
            AccountName = account.AccountName,
            PlayerLevel = account.Level,
            ClassId = account.ClassId,
            ClassName = classMap.TryGetValue(account.ClassId, out var currentClass) ? currentClass.Name : account.ClassId,
            ClassLevel = currentClassLevel,
            ClassProgresses = progress.Select(item => new WebPlayerClassProgressResponse
            {
                ClassId = item.ClassId,
                ClassName = classMap.TryGetValue(item.ClassId, out var classValue) ? classValue.Name : item.ClassId,
                Level = item.Level,
            }).ToList(),
            Gold = gold,
            UpdatedAt = DateTime.SpecifyKind(account.UpdatedAt, DateTimeKind.Utc),
            SkillTree = await TryBuildSkillTreeAsync(account.Level, classAncestors, unlockedNodes),
        };
    }

    private async Task<Dictionary<string, string?>> GetUnlockedNodesAsync(Guid accountId)
    {
        var stateId = await gameDb.AccountSkillTreeStates.AsNoTracking()
            .Where(state => state.AccountId == accountId && !state.IsDeleted)
            .Select(state => (Guid?)state.AccountSkillTreeStateId)
            .FirstOrDefaultAsync();
        if (!stateId.HasValue)
            return new Dictionary<string, string?>(StringComparer.Ordinal);
        return await gameDb.AccountSkillTreeUnlockedNodes.AsNoTracking()
            .Where(node => node.AccountSkillTreeStateId == stateId.Value)
            .ToDictionaryAsync(node => node.NodeId, node => node.ConsumedClassId, StringComparer.Ordinal);
    }

    private async Task<WebSkillTreeProfileResponse> BuildSkillTreeAsync(
        int playerLevel, HashSet<string> classAncestors, IReadOnlyDictionary<string, string?> unlockedNodes)
    {
        var structurePath = Path.Combine(filebaseRoot, "35.features.skilltree", "structures", structureId + ".json");
        using var structureDocument = JsonDocument.Parse(await File.ReadAllTextAsync(structurePath));
        var root = structureDocument.RootElement;
        var positions = root.GetProperty("nodes").EnumerateArray()
            .ToDictionary(node => node.GetProperty("nodeId").GetString()!, node => node, StringComparer.Ordinal);
        var visibleIds = new HashSet<string>(StringComparer.Ordinal);
        var profiles = new List<WebSkillTreeNodeProfileResponse>();
        var skillNames = await GetSkillNamesAsync();
        foreach (var (nodeId, position) in positions.OrderBy(pair => pair.Key, StringComparer.Ordinal))
        {
            var nodePath = Path.Combine(filebaseRoot, "35.features.skilltree", "nodes", nodeId + ".json");
            using var nodeDocument = JsonDocument.Parse(await File.ReadAllTextAsync(nodePath));
            var node = nodeDocument.RootElement;
            var condition = node.TryGetProperty("unlockCondition", out var rawCondition) ? rawCondition : (JsonElement?)null;
            if (!MeetsCondition(condition, playerLevel, classAncestors))
                continue;
            visibleIds.Add(nodeId);
            var effects = node.GetProperty("effects").EnumerateArray().Select(effect => effect.Clone()).ToList();
            profiles.Add(new WebSkillTreeNodeProfileResponse
            {
                NodeId = nodeId,
                Name = StripLegacyColors(node.GetProperty("name").GetString()!),
                Icon = node.GetProperty("icon").GetString()!,
                Lore = ReadStrings(node, "lore"),
                Tags = ReadStrings(node, "tags"),
                PointType = node.GetProperty("pointType").GetString()!,
                PointCost = node.GetProperty("pointCost").GetInt32(),
                UnlockCondition = condition?.Clone(),
                Effects = effects,
                DisplayEffects = effects.Select(effect => DescribeEffect(effect, skillNames)).ToList(),
                X = position.GetProperty("x").GetDouble(),
                Y = position.GetProperty("y").GetDouble(),
                Z = position.GetProperty("z").GetDouble(),
                IsUnlocked = unlockedNodes.TryGetValue(nodeId, out var consumedClassId),
                ConsumedClassId = consumedClassId,
            });
        }
        return new WebSkillTreeProfileResponse
        {
            StructureId = root.GetProperty("structureId").GetString()!,
            Name = StripLegacyColors(root.GetProperty("name").GetString()!),
            RootNodeId = root.GetProperty("rootNodeId").GetString()!,
            Nodes = profiles,
            Edges = root.GetProperty("edges").EnumerateArray()
                .Where(edge => visibleIds.Contains(edge.GetProperty("sourceNodeId").GetString()!)
                    && visibleIds.Contains(edge.GetProperty("targetNodeId").GetString()!))
                .Select(edge => new WebSkillTreeEdgeResponse
                {
                    SourceNodeId = edge.GetProperty("sourceNodeId").GetString()!,
                    TargetNodeId = edge.GetProperty("targetNodeId").GetString()!,
                }).ToList(),
        };
    }

    private async Task<WebSkillTreeProfileResponse> TryBuildSkillTreeAsync(
        int playerLevel, HashSet<string> classAncestors, IReadOnlyDictionary<string, string?> unlockedNodes)
    {
        try
        {
            return await BuildSkillTreeAsync(playerLevel, classAncestors, unlockedNodes);
        }
        catch (IOException)
        {
            return EmptySkillTree();
        }
        catch (JsonException)
        {
            return EmptySkillTree();
        }
    }

    private async Task<Dictionary<string, ClassResponse>> GetClassMapAsync() =>
        (await masterDataDb.Entries.AsNoTracking().Where(entry => !entry.IsDeleted && entry.MasterType == "class")
            .Select(entry => entry.PayloadJson).ToListAsync())
        .Select(MasterDataPayloadJson.Deserialize<ClassResponse>).Where(item => item is not null)
        .Cast<ClassResponse>().ToDictionary(item => item.Id, StringComparer.OrdinalIgnoreCase);

    private async Task<Dictionary<string, string>> GetSkillNamesAsync() =>
        (await masterDataDb.Entries.AsNoTracking().Where(entry => !entry.IsDeleted && entry.MasterType == "skill")
            .Select(entry => entry.PayloadJson).ToListAsync())
        .Select(MasterDataPayloadJson.Deserialize<SkillResponse>).Where(item => item is not null)
        .Cast<SkillResponse>().ToDictionary(item => item.Id, item => item.Name, StringComparer.OrdinalIgnoreCase);

    private async Task<IReadOnlyList<WebPlayerProfileClassFilterResponse>> GetClassFiltersAsync() =>
        (await GetClassMapAsync()).Values.OrderBy(value => value.Order).ThenBy(value => value.Id, StringComparer.Ordinal)
            .Select(value => new WebPlayerProfileClassFilterResponse { Id = value.Id, Name = value.Name }).ToList();

    private static int TryGetLevel(Guid userId, IReadOnlyDictionary<Guid, UserEntity> users, IReadOnlyDictionary<Guid, AccountEntity> accounts) =>
        users.TryGetValue(userId, out var user) && user.AccountId.HasValue && accounts.TryGetValue(user.AccountId.Value, out var account)
            ? account.Level : -1;

    private static HashSet<string> GetClassAncestors(string classId, IReadOnlyDictionary<string, ClassResponse> classMap)
    {
        var result = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { classId };
        var pending = new Queue<string>();
        pending.Enqueue(classId);
        while (pending.TryDequeue(out var current) && classMap.TryGetValue(current, out var currentClass))
        foreach (var ancestor in currentClass.UnlockClassLevel.Select(item => item.ClassId))
            if (result.Add(ancestor)) pending.Enqueue(ancestor);
        return result;
    }

    private static bool MeetsCondition(JsonElement? condition, int playerLevel, ISet<string> classAncestors)
    {
        if (!condition.HasValue) return true;
        var value = condition.Value;
        return (!value.TryGetProperty("classId", out var classId) || classAncestors.Contains(classId.GetString() ?? string.Empty))
            && (!value.TryGetProperty("playerLevel", out var level) || playerLevel >= level.GetInt32());
    }

    private static IReadOnlyList<string> ReadStrings(JsonElement parent, string property) =>
        parent.TryGetProperty(property, out var value) ? value.EnumerateArray().Select(item => StripLegacyColors(item.GetString() ?? string.Empty)).ToList() : [];

    private static string DescribeEffect(JsonElement effect, IReadOnlyDictionary<string, string> skillNames)
    {
        var type = effect.GetProperty("type").GetString();
        if (type == "skill")
        {
            var id = effect.GetProperty("skillId").GetString() ?? string.Empty;
            return skillNames.TryGetValue(id, out var name) ? name : id;
        }
        var statusId = effect.GetProperty("status").GetString() ?? string.Empty;
        var status = StatusTypes.TryGet(statusId, out var definition) ? definition!.DisplayName : statusId;
        var modifier = effect.GetProperty("modifierType").GetString() ?? string.Empty;
        var amount = effect.GetProperty("value").GetDouble();
        return $"{status} {modifier} {amount}";
    }

    private WebSkillTreeProfileResponse EmptySkillTree() => new()
    {
        StructureId = structureId, Name = string.Empty, RootNodeId = string.Empty, Nodes = [], Edges = [],
    };

    private static WebPlayerProfileResponse WithoutPermission(WebPlayerProfileResponse profile) => new()
    {
        UserUuid = profile.UserUuid, Mcid = profile.Mcid, Permission = null, IsPublic = profile.IsPublic,
        CurrentAccount = profile.CurrentAccount,
    };

    private static string StripLegacyColors(string value) => System.Text.RegularExpressions.Regex.Replace(value, "&[0-9A-FK-OR]", string.Empty, System.Text.RegularExpressions.RegexOptions.IgnoreCase);
}
