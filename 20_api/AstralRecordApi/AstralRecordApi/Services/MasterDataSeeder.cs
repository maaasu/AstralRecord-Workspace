using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;
using AstralRecordApi.Utilities;

namespace AstralRecordApi.Services;

public class MasterDataSeeder(
    MasterDataDbContext db,
    IOptions<FileDatabaseOptions> fileDatabaseOptions,
    IOptions<MasterDataOptions> masterDataOptions,
    ILogger<MasterDataSeeder> logger) : IMasterDataSeeder
{
    private const string StatusRunning = "RUNNING";
    private const string StatusSucceeded = "SUCCEEDED";
    private const string StatusFailed = "FAILED";
    private const string GeneratedSkillGemIdPrefix = "00_skill_gem_";
    private const string BuiltInAstraldCurrencyItemId = "astrald";
    private const int InventoryItemIdMaxLength = 100;
    private const int LearnedSkillSigilIdMaxLength = 128;

    // 同一プロセス内で Seeder の同時実行を直列化する。
    private static readonly SemaphoreSlim Gate = new(1, 1);

    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    // サブディレクトリの扱いがデフォルト(Flat)と異なる source。
    private static readonly IReadOnlyDictionary<string, SourceStructure> StructureBySource =
        new Dictionary<string, SourceStructure>(StringComparer.OrdinalIgnoreCase)
        {
            ["item"] = SourceStructure.Category,
            ["loot"] = SourceStructure.Split,
            ["mob"] = SourceStructure.Split,
            ["gathering"] = SourceStructure.Split,
        };

    public async Task<MasterDataSeedResultResponse> RunAsync(
        MasterDataSeedTrigger trigger,
        MasterDataSeedMode mode,
        CancellationToken cancellationToken = default)
    {
        await Gate.WaitAsync(cancellationToken);
        try
        {
            return await RunCoreAsync(trigger, mode, cancellationToken);
        }
        finally
        {
            Gate.Release();
        }
    }

    private async Task<MasterDataSeedResultResponse> RunCoreAsync(
        MasterDataSeedTrigger trigger,
        MasterDataSeedMode mode,
        CancellationToken cancellationToken)
    {
        var rootPath = fileDatabaseOptions.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath が設定されていません。");

        var systemUser = masterDataOptions.Value.SystemUserId;
        var startedAt = DateTime.UtcNow;

        var run = new MasterDataSeedRunEntity
        {
            SeedRunId = Guid.NewGuid(),
            TriggerType = ToTriggerType(trigger),
            Status = StatusRunning,
            SourceRootPath = rootPath,
            StartedAt = startedAt,
            CreatedAt = startedAt,
            UpdatedAt = startedAt,
            CreatedBy = systemUser,
            UpdatedBy = systemUser,
        };
        db.SeedRuns.Add(run);
        await db.SaveChangesAsync(cancellationToken);

        logger.LogInformation(
            "MasterDataDB Seeder を開始します (RunId: {RunId}, Trigger: {Trigger}, Mode: {Mode})",
            run.SeedRunId, run.TriggerType, mode);

        var warnings = new List<string>();
        var counts = new SeedCounts();

        try
        {
            var config = SeedConfig.Load(rootPath);

            var strategy = db.Database.CreateExecutionStrategy();
            await strategy.ExecuteAsync(async ct =>
            {
                // リトライ戦略が有効な DbContext では、ユーザー開始トランザクションを
                // ExecutionStrategy のスコープ内で開く必要がある。
                db.ChangeTracker.Clear();
                counts.Reset();
                warnings.Clear();

                await using var transaction = await db.Database.BeginTransactionAsync(ct);

                if (mode == MasterDataSeedMode.Rebuild)
                {
                    await db.References.ExecuteDeleteAsync(ct);
                    // 補償メールが削除済みシジルの詳細を後から解決できるよう、entryはtombstoneとして残す。
                    await db.Entries
                        .Where(entry => !entry.IsDeleted)
                        .ExecuteUpdateAsync(setters => setters
                            .SetProperty(entry => entry.IsDeleted, true)
                            .SetProperty(entry => entry.UpdatedAt, startedAt)
                            .SetProperty(entry => entry.UpdatedBy, systemUser), ct);
                }

                var plans = BuildSourcePlans(rootPath, config, warnings);
                var sourceIdByKey = await SyncSourcesAsync(plans, config.SchemaVersion, systemUser, ct);
                await db.SaveChangesAsync(ct);

                await SyncEntriesAsync(plans, sourceIdByKey, config, systemUser, counts, ct);
                await db.SaveChangesAsync(ct);

                await ValidateReferencesAsync(warnings, ct);
                await ValidateWorldRequiredItemsAsync(ct);
                await ValidateSkillSystemMastersAsync(ct);
                await transaction.CommitAsync(ct);
            }, cancellationToken);

            var finishedAt = DateTime.UtcNow;
            run.Status = StatusSucceeded;
            run.FinishedAt = finishedAt;
            run.UpdatedAt = finishedAt;
            ApplyCounts(run, counts);
            // ExecuteAsync 内で ChangeTracker.Clear() しているため、run を再アタッチして UPDATE を永続化する。
            db.SeedRuns.Update(run);
            await db.SaveChangesAsync(cancellationToken);

            logger.LogInformation(
                "MasterDataDB Seeder が完了しました (RunId: {RunId}, files: {Files}, upserted: {Upserted}, deleted: {Deleted}, skipped: {Skipped})",
                run.SeedRunId, counts.FileCount, counts.UpsertedCount, counts.DeletedCount, counts.SkippedCount);
        }
        catch (Exception ex)
        {
            var finishedAt = DateTime.UtcNow;
            run.Status = StatusFailed;
            run.FinishedAt = finishedAt;
            run.UpdatedAt = finishedAt;
            run.ErrorMessage = ex.Message;
            ApplyCounts(run, counts);

            // トランザクションはロールバック済み。追跡中の entry/source/reference 変更を
            // 破棄し、seed_run の FAILED 更新のみを永続化する。
            db.ChangeTracker.Clear();
            db.SeedRuns.Update(run);
            await db.SaveChangesAsync(cancellationToken);

            logger.LogError(ex, "MasterDataDB Seeder が失敗しました (RunId: {RunId})", run.SeedRunId);
        }

        return ToResult(run, warnings);
    }

    // ---- source 同期 -------------------------------------------------------

    private List<SourcePlan> BuildSourcePlans(string rootPath, SeedConfig config, List<string> warnings)
    {
        var plans = new List<SourcePlan>();

        foreach (var entry in config.Databases)
        {
            var structure = StructureBySource.GetValueOrDefault(entry.Name, SourceStructure.Flat);
            var relativePath = entry.Path.Replace('\\', '/').Trim('/');
            var absolutePath = ToAbsolutePath(rootPath, relativePath);

            if (!Directory.Exists(absolutePath))
            {
                warnings.Add($"source ディレクトリが存在しません: {entry.Name} ({relativePath})");
                continue;
            }

            if (structure == SourceStructure.Split)
            {
                foreach (var subDirectory in EnumerateSubDirectories(absolutePath))
                {
                    var subName = Path.GetFileName(subDirectory);
                    plans.Add(new SourcePlan(
                        SourceKey: $"{entry.Name}.{subName}",
                        MasterType: $"{entry.Name}.{subName}",
                        RelativePath: $"{relativePath}/{subName}",
                        AbsolutePath: subDirectory,
                        SourceKind: ToSourceKind(relativePath),
                        WalkMode: SourceWalkMode.Direct));
                }
            }
            else
            {
                plans.Add(new SourcePlan(
                    SourceKey: entry.Name,
                    MasterType: entry.Name,
                    RelativePath: relativePath,
                    AbsolutePath: absolutePath,
                    SourceKind: ToSourceKind(relativePath),
                    WalkMode: structure == SourceStructure.Category
                        ? SourceWalkMode.Categorized
                        : SourceWalkMode.Direct));
            }
        }

        return plans;
    }

    private async Task<IReadOnlyDictionary<string, Guid>> SyncSourcesAsync(
        IReadOnlyList<SourcePlan> plans,
        int defaultSchemaVersion,
        Guid systemUser,
        CancellationToken cancellationToken)
    {
        var now = DateTime.UtcNow;
        var existing = await db.Sources.ToListAsync(cancellationToken);
        var existingByKey = existing.ToDictionary(source => source.SourceKey, KeyComparer);
        var planKeys = plans.Select(plan => plan.SourceKey).ToHashSet(KeyComparer);
        var sourceIdByKey = new Dictionary<string, Guid>(KeyComparer);

        foreach (var plan in plans)
        {
            if (existingByKey.TryGetValue(plan.SourceKey, out var source))
            {
                source.SourcePath = plan.RelativePath;
                source.SourceKind = plan.SourceKind;
                source.SchemaVersion = defaultSchemaVersion;
                source.IsEnabled = true;
                source.IsDeleted = false;
                source.UpdatedAt = now;
                source.UpdatedBy = systemUser;
            }
            else
            {
                source = new MasterDataSourceEntity
                {
                    SourceId = Guid.NewGuid(),
                    SourceKey = plan.SourceKey,
                    SourcePath = plan.RelativePath,
                    SourceKind = plan.SourceKind,
                    SchemaVersion = defaultSchemaVersion,
                    IsEnabled = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = systemUser,
                    UpdatedBy = systemUser,
                    IsDeleted = false,
                };
                db.Sources.Add(source);
            }

            sourceIdByKey[plan.SourceKey] = source.SourceId;
        }

        foreach (var source in existing)
        {
            if (!source.IsDeleted && !planKeys.Contains(source.SourceKey))
            {
                source.IsDeleted = true;
                source.IsEnabled = false;
                source.UpdatedAt = now;
                source.UpdatedBy = systemUser;
            }
        }

        return sourceIdByKey;
    }

    // ---- entry / reference 同期 -------------------------------------------

    private async Task SyncEntriesAsync(
        IReadOnlyList<SourcePlan> plans,
        IReadOnlyDictionary<string, Guid> sourceIdByKey,
        SeedConfig config,
        Guid systemUser,
        SeedCounts counts,
        CancellationToken cancellationToken)
    {
        var now = DateTime.UtcNow;

        var existingEntries = await db.Entries.ToListAsync(cancellationToken);
        var entryByKey = existingEntries.ToDictionary(
            entry => (entry.MasterType, entry.MasterId), TupleKeyComparer.Instance);

        var existingReferences = await db.References.ToListAsync(cancellationToken);
        var referencesByEntry = existingReferences
            .GroupBy(reference => reference.FromEntryId)
            .ToDictionary(group => group.Key, group => group.ToList());

        var seenKeys = new HashSet<(string, string)>(TupleKeyComparer.Instance);
        var classIdByShortName = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        foreach (var plan in plans)
        {
            var sourceId = sourceIdByKey[plan.SourceKey];

            foreach (var file in EnumerateFiles(plan))
            {
                cancellationToken.ThrowIfCancellationRequested();
                counts.FileCount++;

                var rawBytes = await File.ReadAllBytesAsync(file.AbsolutePath, cancellationToken);
                var hash = Sha256Hex(rawBytes);
                var root = ParseYamlObject(Encoding.UTF8.GetString(rawBytes), file.RelativePath);

                var masterId = RequireScalar(root, "id", file.RelativePath);
                var schemaVersion = RequireInt(root, "schemaVersion", file.RelativePath);
                var type = OptionalScalar(root, "type");
                var displayName = OptionalScalar(root, "name");
                if (string.Equals(plan.MasterType, "class", StringComparison.OrdinalIgnoreCase))
                {
                    ValidateClassShortName(root, masterId, file.RelativePath, classIdByShortName);
                }
                var payloadJson = root.ToJsonString();

                var key = (plan.MasterType, masterId);
                if (!seenKeys.Add(key))
                {
                    throw new InvalidOperationException(
                        $"master_type '{plan.MasterType}' で master_id '{masterId}' が重複しています: {file.RelativePath}");
                }

                if (entryByKey.TryGetValue(key, out var entry))
                {
                    if (!entry.IsDeleted && string.Equals(entry.SourceFileHash, hash, StringComparison.Ordinal))
                    {
                        counts.SkippedCount++;
                        continue;
                    }

                    entry.SourceId = sourceId;
                    entry.Category = file.Category;
                    entry.Type = type;
                    entry.SchemaVersion = schemaVersion;
                    entry.DisplayName = displayName;
                    entry.SourceFilePath = file.RelativePath;
                    entry.SourceFileHash = hash;
                    entry.PayloadJson = payloadJson;
                    entry.PayloadVersion += 1;
                    entry.EffectiveFrom = now;
                    entry.UpdatedAt = now;
                    entry.UpdatedBy = systemUser;
                    entry.IsDeleted = false;

                    if (referencesByEntry.TryGetValue(entry.EntryId, out var oldReferences))
                    {
                        db.References.RemoveRange(oldReferences);
                        referencesByEntry.Remove(entry.EntryId);
                    }
                }
                else
                {
                    entry = new MasterDataEntryEntity
                    {
                        EntryId = Guid.NewGuid(),
                        SourceId = sourceId,
                        MasterType = plan.MasterType,
                        MasterId = masterId,
                        Category = file.Category,
                        Type = type,
                        SchemaVersion = schemaVersion,
                        DisplayName = displayName,
                        SourceFilePath = file.RelativePath,
                        SourceFileHash = hash,
                        PayloadJson = payloadJson,
                        PayloadVersion = 1,
                        EffectiveFrom = now,
                        CreatedAt = now,
                        UpdatedAt = now,
                        CreatedBy = systemUser,
                        UpdatedBy = systemUser,
                        IsDeleted = false,
                    };
                    db.Entries.Add(entry);
                    entryByKey[key] = entry;
                }

                counts.UpsertedCount++;
                AddReferences(entry, root, config, systemUser, now);
            }
        }

        foreach (var entry in existingEntries)
        {
            if (entry.IsDeleted || seenKeys.Contains((entry.MasterType, entry.MasterId)))
                continue;

            entry.IsDeleted = true;
            entry.UpdatedAt = now;
            entry.UpdatedBy = systemUser;
            counts.DeletedCount++;

            if (referencesByEntry.TryGetValue(entry.EntryId, out var orphanReferences))
            {
                db.References.RemoveRange(orphanReferences);
                referencesByEntry.Remove(entry.EntryId);
            }
        }
    }

    private void AddReferences(
        MasterDataEntryEntity entry,
        JsonObject root,
        SeedConfig config,
        Guid systemUser,
        DateTime now)
    {
        var found = new List<ResolvedReference>();
        CollectReferences(root, "$", config, found);

        var sortOrder = 0;
        foreach (var reference in found)
        {
            db.References.Add(new MasterDataReferenceEntity
            {
                ReferenceId = Guid.NewGuid(),
                FromEntryId = entry.EntryId,
                FromMasterType = entry.MasterType,
                FromMasterId = entry.MasterId,
                ReferenceType = reference.ReferenceType,
                ReferenceIdValue = reference.ReferenceId,
                ReferencePath = reference.JsonPath,
                IsRequired = true,
                SortOrder = sortOrder++,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = systemUser,
                UpdatedBy = systemUser,
                IsDeleted = false,
            });
        }
    }

    private async Task ValidateReferencesAsync(List<string> warnings, CancellationToken cancellationToken)
    {
        var activeEntries = await db.Entries
                .Where(entry => !entry.IsDeleted)
                .Select(entry => new { entry.MasterId, entry.MasterType })
                .ToListAsync(cancellationToken);
        var activeTypesByMasterId = activeEntries
            .ToLookup(entry => entry.MasterId, entry => entry.MasterType, KeyComparer);
        var generatedSkillGemIds = activeEntries
            .Where(entry => KeyComparer.Equals(entry.MasterType, "skill"))
            .Select(entry => GeneratedSkillGemIdPrefix + entry.MasterId)
            .ToHashSet(KeyComparer);

        var references = await db.References
            .Where(reference => !reference.IsDeleted)
            .ToListAsync(cancellationToken);

        var unresolvedRequired = new List<string>();

        foreach (var reference in references)
        {
            var resolved = activeTypesByMasterId[reference.ReferenceIdValue].Any(masterType =>
                string.Equals(masterType, reference.ReferenceType, StringComparison.OrdinalIgnoreCase)
                || masterType.StartsWith(reference.ReferenceType + ".", StringComparison.OrdinalIgnoreCase))
                || (KeyComparer.Equals(reference.ReferenceType, "item")
                    && (generatedSkillGemIds.Contains(reference.ReferenceIdValue)
                        || KeyComparer.Equals(reference.ReferenceIdValue, BuiltInAstraldCurrencyItemId)));

            if (resolved)
                continue;

            var description =
                $"{reference.FromMasterType}:{reference.FromMasterId} -> {reference.ReferenceType}:{reference.ReferenceIdValue}";
            if (reference.IsRequired)
                unresolvedRequired.Add(description);
            else
                warnings.Add($"任意参照が未解決です: {description}");
        }

        if (unresolvedRequired.Count > 0)
        {
            throw new InvalidOperationException(
                "必須参照が未解決です: " + string.Join(", ", unresolvedRequired));
        }
    }

    private async Task ValidateWorldRequiredItemsAsync(CancellationToken cancellationToken)
    {
        var entries = await db.Entries
            .Where(entry => !entry.IsDeleted
                && (entry.MasterType == "world" || entry.MasterType == "item"))
            .Select(entry => new
            {
                entry.MasterType,
                entry.MasterId,
                entry.Category,
                entry.PayloadJson,
                entry.SourceFilePath,
            })
            .ToArrayAsync(cancellationToken);

        var currencyItemIds = entries
            .Where(entry => KeyComparer.Equals(entry.MasterType, "item")
                && KeyComparer.Equals(entry.Category, "currency"))
            .Select(entry => entry.MasterId)
            .ToHashSet(KeyComparer);

        foreach (var entry in entries.Where(entry => KeyComparer.Equals(entry.MasterType, "world")))
        {
            var world = MasterDataPayloadJson.Deserialize<WorldResponse>(entry.PayloadJson)
                ?? throw new InvalidOperationException($"worldの解析に失敗しました: {entry.SourceFilePath}");
            if (!KeyComparer.Equals(world.Id, entry.MasterId))
                throw new InvalidOperationException($"world.id と master_id が一致しません: {entry.SourceFilePath}");

            if (world.RequiredItemId is null)
                continue;

            var rawReference = world.RequiredItemId.Trim();
            const string itemPrefix = "item:";
            if (!rawReference.StartsWith(itemPrefix, StringComparison.OrdinalIgnoreCase)
                || rawReference[itemPrefix.Length..].Trim().Length == 0)
            {
                throw new InvalidOperationException(
                    $"world.requiredItemId には item:<id> 形式の通貨アイテム参照を指定してください: {entry.SourceFilePath}");
            }

            var itemId = rawReference[itemPrefix.Length..].Trim();
            if (!currencyItemIds.Contains(itemId))
            {
                throw new InvalidOperationException(
                    $"world.requiredItemId の参照先は currency item である必要があります: "
                    + $"{entry.SourceFilePath} -> item:{itemId}");
            }
        }
    }

    private async Task ValidateSkillSystemMastersAsync(CancellationToken cancellationToken)
    {
        var entries = await db.Entries
            .Where(entry => !entry.IsDeleted
                && (entry.MasterType == "skill"
                    || entry.MasterType == "class"
                    || entry.MasterType == "item"))
            .Select(entry => new
            {
                entry.MasterType,
                entry.MasterId,
                entry.Category,
                entry.PayloadJson,
                entry.SourceFilePath,
            })
            .ToArrayAsync(cancellationToken);

        var skillIds = entries
            .Where(entry => KeyComparer.Equals(entry.MasterType, "skill"))
            .Select(entry => entry.MasterId)
            .ToHashSet(KeyComparer);
        var sigilIds = entries
            .Where(entry => KeyComparer.Equals(entry.MasterType, "item")
                && KeyComparer.Equals(entry.Category, "sigil"))
            .Select(entry => entry.MasterId)
            .ToHashSet(KeyComparer);

        foreach (var entry in entries.Where(entry => KeyComparer.Equals(entry.MasterType, "item")
            && KeyComparer.Equals(entry.Category, "sigil")))
        {
            var item = MasterDataPayloadJson.Deserialize<ItemResponse>(entry.PayloadJson)
                ?? throw new InvalidOperationException($"sigilの解析に失敗しました: {entry.SourceFilePath}");
            if (!KeyComparer.Equals(item.Id, entry.MasterId))
                throw new InvalidOperationException($"sigil.id と master_id が一致しません: {entry.SourceFilePath}");
            if (item.Id.Length > InventoryItemIdMaxLength)
                throw new InvalidOperationException($"sigil.id は{InventoryItemIdMaxLength}文字以内です: {entry.SourceFilePath}");
            if (item.Sigil is null)
                throw new InvalidOperationException($"sigilカテゴリにはsigil定義が必須です: {entry.SourceFilePath}");
            if (string.IsNullOrWhiteSpace(item.Sigil.EquipGroupId)
                || item.Sigil.EquipGroupId.Length > LearnedSkillSigilIdMaxLength)
                throw new InvalidOperationException(
                    $"sigil.equipGroupId は1～{LearnedSkillSigilIdMaxLength}文字です: {entry.SourceFilePath}");
            if (item.Sigil.Modifiers.Any(modifier => string.IsNullOrWhiteSpace(modifier.Status)
                || !StatusTypes.TryGet(modifier.Status, out _)
                || !double.IsFinite(modifier.Value)))
                throw new InvalidOperationException(
                    $"sigil.modifiersには既知status IDと有限値を指定してください: {entry.SourceFilePath}");
        }
        var reservedItem = entries.FirstOrDefault(entry => KeyComparer.Equals(entry.MasterType, "item")
            && entry.MasterId.StartsWith(GeneratedSkillGemIdPrefix, StringComparison.OrdinalIgnoreCase));
        if (reservedItem is not null)
            throw new InvalidOperationException(
                $"item.id '{reservedItem.MasterId}' は自動生成スキルジェム用の予約prefixです: {reservedItem.SourceFilePath}");

        foreach (var entry in entries.Where(entry => KeyComparer.Equals(entry.MasterType, "skill")))
        {
            var skillPayload = JsonNode.Parse(entry.PayloadJson)?.AsObject();
            var gemPayload = skillPayload?["gem"] as JsonObject;
            var explicitGemRarity = gemPayload?["rarity"]?.GetValue<string>();
            if (gemPayload is null || string.IsNullOrWhiteSpace(explicitGemRarity))
                throw new InvalidOperationException($"skill.gem と gem.rarity は明示必須です: {entry.SourceFilePath}");
            var skill = MasterDataPayloadJson.Deserialize<SkillResponse>(entry.PayloadJson)
                ?? throw new InvalidOperationException($"skillの解析に失敗しました: {entry.SourceFilePath}");
            if (!KeyComparer.Equals(skill.Id, entry.MasterId))
                throw new InvalidOperationException($"skill.id と master_id が一致しません: {entry.SourceFilePath}");
            if (GeneratedSkillGemIdPrefix.Length + skill.Id.Length > InventoryItemIdMaxLength)
                throw new InvalidOperationException(
                    $"skill.id が長すぎるため自動生成ジェムIDをinventory_entry.item_idへ保存できません: {entry.SourceFilePath}");
            if (skill.MaxLevel < 1)
                throw new InvalidOperationException($"maxLevel は1以上です: {entry.SourceFilePath}");
            var expectedLevels = Enumerable.Range(2, Math.Max(0, skill.MaxLevel - 1)).ToArray();
            var actualLevels = skill.Levels.Select(level => level.Level).Order().ToArray();
            if (!actualLevels.SequenceEqual(expectedLevels))
                throw new InvalidOperationException(
                    $"levels はLv.2からmaxLevelまで重複なく定義してください: {entry.SourceFilePath}");
            if (skill.Levels.Any(level => !double.IsFinite(level.ResourceCostDelta)
                || level.ParamDeltas.Any(delta => string.IsNullOrWhiteSpace(delta.Key)
                    || !double.IsFinite(delta.Value))
                || level.StatusModifiers.Any(modifier => string.IsNullOrWhiteSpace(modifier.Status)
                    || !StatusTypes.TryGet(modifier.Status, out _)
                    || !double.IsFinite(modifier.Value))))
                throw new InvalidOperationException($"levelsに不正な差分値があります: {entry.SourceFilePath}");

            var slotLevels = new HashSet<int>();
            var previousSlots = -1;
            foreach (var slot in skill.SigilSlotsByLevel.OrderBy(slot => slot.Level))
            {
                if (slot.Level < 1 || slot.Level > skill.MaxLevel || slot.Slots < 0
                    || !slotLevels.Add(slot.Level) || slot.Slots < previousSlots)
                    throw new InvalidOperationException(
                        $"sigilSlotsByLevelは有効level・0以上・重複なし・非減少で定義してください: {entry.SourceFilePath}");
                previousSlots = slot.Slots;
            }
            var duplicateAllowedSigil = skill.AllowedSigilIds
                .GroupBy(id => id, KeyComparer)
                .FirstOrDefault(group => group.Count() > 1 || string.IsNullOrWhiteSpace(group.Key));
            if (duplicateAllowedSigil is not null)
                throw new InvalidOperationException($"allowedSigilIdsに空値または重複があります: {entry.SourceFilePath}");
            var unknownSigils = skill.AllowedSigilIds.Where(id => !sigilIds.Contains(id)).ToArray();
            if (unknownSigils.Length > 0)
                throw new InvalidOperationException(
                    $"allowedSigilIdsに未定義シジルがあります ({string.Join(", ", unknownSigils)}): {entry.SourceFilePath}");
            if (skill.CooldownId is not null && string.IsNullOrWhiteSpace(skill.CooldownId))
                throw new InvalidOperationException($"cooldownIdは未指定または空でない文字列です: {entry.SourceFilePath}");
        }

        foreach (var entry in entries.Where(entry => KeyComparer.Equals(entry.MasterType, "class")))
        {
            var classMaster = MasterDataPayloadJson.Deserialize<ClassResponse>(entry.PayloadJson)
                ?? throw new InvalidOperationException($"classの解析に失敗しました: {entry.SourceFilePath}");
            var normalized = classMaster.UsableSkills
                .Select(reference => reference.Trim().StartsWith("skill:", StringComparison.OrdinalIgnoreCase)
                    ? reference.Trim()["skill:".Length..]
                    : reference.Trim())
                .ToArray();
            if (normalized.Any(string.IsNullOrWhiteSpace)
                || normalized.Distinct(KeyComparer).Count() != normalized.Length)
                throw new InvalidOperationException($"usableSkillsに空値または重複があります: {entry.SourceFilePath}");
            var unknownSkills = normalized.Where(id => !skillIds.Contains(id)).ToArray();
            if (unknownSkills.Length > 0)
                throw new InvalidOperationException(
                    $"usableSkillsに未定義スキルがあります ({string.Join(", ", unknownSkills)}): {entry.SourceFilePath}");
        }
    }

    // ---- ファイル走査 -----------------------------------------------------

    private static IEnumerable<SeedFile> EnumerateFiles(SourcePlan plan)
    {
        if (plan.WalkMode == SourceWalkMode.Categorized)
        {
            foreach (var subDirectory in EnumerateSubDirectories(plan.AbsolutePath))
            {
                var category = Path.GetFileName(subDirectory);
                foreach (var file in EnumerateYamlFiles(subDirectory))
                    yield return ToSeedFile(plan, file, category);
            }
        }
        else
        {
            foreach (var file in EnumerateYamlFiles(plan.AbsolutePath))
                yield return ToSeedFile(plan, file, category: null);
        }
    }

    private static SeedFile ToSeedFile(SourcePlan plan, string absolutePath, string? category)
        => new(absolutePath, NormalizeRelativePath(plan, absolutePath), category);

    private static string NormalizeRelativePath(SourcePlan plan, string absolutePath)
    {
        var fileName = Path.GetFileName(absolutePath);
        var directory = Path.GetDirectoryName(absolutePath) ?? plan.AbsolutePath;
        var sameAsSourceRoot = string.Equals(
            Path.TrimEndingDirectorySeparator(directory),
            Path.TrimEndingDirectorySeparator(plan.AbsolutePath),
            StringComparison.OrdinalIgnoreCase);

        if (sameAsSourceRoot)
            return $"{plan.RelativePath}/{fileName}";

        var category = Path.GetFileName(directory);
        return $"{plan.RelativePath}/{category}/{fileName}";
    }

    private static IEnumerable<string> EnumerateSubDirectories(string directory)
        => Directory.EnumerateDirectories(directory)
            .Where(path => !Path.GetFileName(path).StartsWith('.'))
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase);

    private static IEnumerable<string> EnumerateYamlFiles(string directory)
        => Directory.EnumerateFiles(directory, "*.yml", SearchOption.TopDirectoryOnly)
            .Where(path => !Path.GetFileName(path).StartsWith('.'))
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase);

    // ---- YAML / JSON 変換 -------------------------------------------------

    private static JsonObject ParseYamlObject(string rawText, string relativePath)
        => MasterDataYamlParser.ParseObject(rawText, relativePath);

    private static void CollectReferences(
        JsonNode? node,
        string path,
        SeedConfig config,
        List<ResolvedReference> found)
    {
        switch (node)
        {
            case JsonObject jsonObject:
                foreach (var pair in jsonObject)
                    CollectReferences(pair.Value, $"{path}.{pair.Key}", config, found);
                break;

            case JsonArray jsonArray:
                for (var index = 0; index < jsonArray.Count; index++)
                    CollectReferences(jsonArray[index], $"{path}[{index}]", config, found);
                break;

            case JsonValue jsonValue when jsonValue.TryGetValue<string>(out var text):
                if (config.TryResolveReference(text, out var referenceType, out var referenceId))
                    found.Add(new ResolvedReference(referenceType, referenceId, path));
                break;
        }
    }

    // ---- スカラ取得ヘルパ -------------------------------------------------

    private static string RequireScalar(JsonObject root, string key, string relativePath)
    {
        var value = OptionalScalar(root, key);
        if (string.IsNullOrWhiteSpace(value))
            throw new InvalidOperationException($"必須フィールド '{key}' がありません: {relativePath}");
        return value;
    }

    private static void ValidateClassShortName(
        JsonObject root,
        string masterId,
        string relativePath,
        IDictionary<string, string> classIdByShortName)
    {
        var shortName = RequireScalar(root, "shortName", relativePath);
        var visibleShortName = StripLegacyColorCodes(shortName).Trim();
        if (visibleShortName.Length != 3 || visibleShortName.Any(value => value < 'A' || value > 'Z'))
        {
            throw new InvalidOperationException(
                $"class '{masterId}' の shortName は色・装飾コードと前後空白を除いてASCII英大文字3文字である必要があります: {relativePath}");
        }

        if (classIdByShortName.TryGetValue(visibleShortName, out var existingClassId))
        {
            throw new InvalidOperationException(
                $"class shortName '{visibleShortName}' が '{existingClassId}' と '{masterId}' で重複しています: {relativePath}");
        }

        classIdByShortName[visibleShortName] = masterId;
    }

    private static string StripLegacyColorCodes(string value)
    {
        var builder = new StringBuilder(value.Length);
        for (var index = 0; index < value.Length; index++)
        {
            if (value[index] == '&' && index + 1 < value.Length && IsLegacyColorCode(value[index + 1]))
            {
                index++;
                continue;
            }
            builder.Append(value[index]);
        }
        return builder.ToString();
    }

    private static bool IsLegacyColorCode(char value)
        => value is >= '0' and <= '9'
            or >= 'a' and <= 'f'
            or >= 'A' and <= 'F'
            or 'k' or 'K' or 'l' or 'L' or 'm' or 'M'
            or 'n' or 'N' or 'o' or 'O' or 'r' or 'R';

    private static string? OptionalScalar(JsonObject root, string key)
        => root.TryGetPropertyValue(key, out var node) && node is JsonValue value
            ? value.ToString()
            : null;

    private static int RequireInt(JsonObject root, string key, string relativePath)
    {
        if (root.TryGetPropertyValue(key, out var node) && node is JsonValue value
            && value.TryGetValue<long>(out var longValue))
        {
            return checked((int)longValue);
        }

        throw new InvalidOperationException($"必須フィールド '{key}' が整数ではありません: {relativePath}");
    }

    // ---- 変換ヘルパ -------------------------------------------------------

    private static string Sha256Hex(byte[] content)
        => Convert.ToHexStringLower(SHA256.HashData(content));

    private static string ToAbsolutePath(string rootPath, string relativePath)
        => Path.GetFullPath(Path.Combine(rootPath, relativePath.Replace('/', Path.DirectorySeparatorChar)));

    private static string ToSourceKind(string relativePath)
    {
        if (relativePath.Contains(".shared.", StringComparison.OrdinalIgnoreCase))
            return "SHARED";
        if (relativePath.Contains(".features.", StringComparison.OrdinalIgnoreCase))
            return "FEATURE";
        if (relativePath.Contains(".meta.", StringComparison.OrdinalIgnoreCase))
            return "META";
        return "SYSTEM";
    }

    private static string ToTriggerType(MasterDataSeedTrigger trigger) => trigger switch
    {
        MasterDataSeedTrigger.Startup => "STARTUP",
        MasterDataSeedTrigger.SeederApi => "SEEDER_API",
        MasterDataSeedTrigger.Manual => "MANUAL",
        _ => "MANUAL",
    };

    private static void ApplyCounts(MasterDataSeedRunEntity run, SeedCounts counts)
    {
        run.FileCount = counts.FileCount;
        run.UpsertedCount = counts.UpsertedCount;
        run.DeletedCount = counts.DeletedCount;
        run.SkippedCount = counts.SkippedCount;
    }

    private static MasterDataSeedResultResponse ToResult(MasterDataSeedRunEntity run, IReadOnlyList<string> warnings)
        => new()
        {
            SeedRunId = run.SeedRunId,
            TriggerType = run.TriggerType,
            Status = run.Status,
            SourceRootPath = run.SourceRootPath,
            StartedAt = run.StartedAt,
            FinishedAt = run.FinishedAt,
            FileCount = run.FileCount,
            UpsertedCount = run.UpsertedCount,
            DeletedCount = run.DeletedCount,
            SkippedCount = run.SkippedCount,
            ErrorMessage = run.ErrorMessage,
            Warnings = warnings,
        };

    // ---- 内部型 -----------------------------------------------------------

    private enum SourceStructure
    {
        Flat,
        Category,
        Split
    }

    private enum SourceWalkMode
    {
        Direct,
        Categorized
    }

    private sealed record SourcePlan(
        string SourceKey,
        string MasterType,
        string RelativePath,
        string AbsolutePath,
        string SourceKind,
        SourceWalkMode WalkMode);

    private sealed record SeedFile(string AbsolutePath, string RelativePath, string? Category);

    private sealed record ResolvedReference(string ReferenceType, string ReferenceId, string JsonPath);

    private sealed class SeedCounts
    {
        public int FileCount { get; set; }
        public int UpsertedCount { get; set; }
        public int DeletedCount { get; set; }
        public int SkippedCount { get; set; }

        public void Reset()
        {
            FileCount = 0;
            UpsertedCount = 0;
            DeletedCount = 0;
            SkippedCount = 0;
        }
    }

    private sealed class TupleKeyComparer : IEqualityComparer<(string, string)>
    {
        public static readonly TupleKeyComparer Instance = new();

        public bool Equals((string, string) x, (string, string) y)
            => string.Equals(x.Item1, y.Item1, StringComparison.OrdinalIgnoreCase)
               && string.Equals(x.Item2, y.Item2, StringComparison.OrdinalIgnoreCase);

        public int GetHashCode((string, string) obj)
            => HashCode.Combine(
                StringComparer.OrdinalIgnoreCase.GetHashCode(obj.Item1),
                StringComparer.OrdinalIgnoreCase.GetHashCode(obj.Item2));
    }

    private sealed class SeedConfig
    {
        private readonly IReadOnlyDictionary<string, string> _referenceTypeByPrefix;

        private SeedConfig(
            int schemaVersion,
            IReadOnlyList<DatabaseEntry> databases,
            IReadOnlyDictionary<string, string> referenceTypeByPrefix)
        {
            SchemaVersion = schemaVersion;
            Databases = databases;
            _referenceTypeByPrefix = referenceTypeByPrefix;
        }

        public int SchemaVersion { get; }

        public IReadOnlyList<DatabaseEntry> Databases { get; }

        public static SeedConfig Load(string rootPath)
        {
            var configPath = Path.Combine(rootPath, "config.yml");
            if (!File.Exists(configPath))
                throw new FileNotFoundException($"filebase の config.yml が見つかりません: {configPath}");

            var deserializer = new DeserializerBuilder()
                .WithNamingConvention(CamelCaseNamingConvention.Instance)
                .IgnoreUnmatchedProperties()
                .Build();

            var document = deserializer.Deserialize<ConfigDocument>(File.ReadAllText(configPath))
                ?? throw new InvalidOperationException("config.yml の解析結果が空です。");

            var databases = new List<DatabaseEntry>();
            foreach (var entry in document.Database ?? [])
            {
                if (!string.IsNullOrWhiteSpace(entry.Name) && !string.IsNullOrWhiteSpace(entry.Path))
                    databases.Add(new DatabaseEntry(entry.Name.Trim(), entry.Path.Trim()));
            }

            var referenceTypeByPrefix = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var resolver in document.ReferenceResolver ?? [])
            {
                if (string.IsNullOrWhiteSpace(resolver.Database))
                    continue;

                var database = resolver.Database.Trim();
                AddPrefix(referenceTypeByPrefix, resolver.Prefix, database);
                foreach (var alias in resolver.Aliases ?? [])
                    AddPrefix(referenceTypeByPrefix, alias, database);
            }

            return new SeedConfig(document.SchemaVersion ?? 1, databases, referenceTypeByPrefix);
        }

        public bool TryResolveReference(string rawValue, out string referenceType, out string referenceId)
        {
            referenceType = string.Empty;
            referenceId = string.Empty;

            if (string.IsNullOrWhiteSpace(rawValue))
                return false;

            var value = rawValue.Trim();
            var separatorIndex = value.IndexOf(':');
            if (separatorIndex <= 0 || separatorIndex >= value.Length - 1)
                return false;

            var prefix = value[..separatorIndex].Trim();
            if (!_referenceTypeByPrefix.TryGetValue(prefix, out var database))
                return false;

            referenceType = database;
            referenceId = value[(separatorIndex + 1)..].Trim();
            return referenceId.Length > 0;
        }

        private static void AddPrefix(IDictionary<string, string> map, string? token, string database)
        {
            if (string.IsNullOrWhiteSpace(token))
                return;

            var normalized = token.Trim().TrimEnd(':');
            if (normalized.Length > 0)
                map[normalized] = database;
        }

        private sealed class ConfigDocument
        {
            public int? SchemaVersion { get; init; }
            public List<DatabaseConfigEntry>? Database { get; init; }
            public List<ReferenceResolverEntry>? ReferenceResolver { get; init; }
        }

        private sealed class DatabaseConfigEntry
        {
            public string? Name { get; init; }
            public string? Path { get; init; }
        }

        private sealed class ReferenceResolverEntry
        {
            public string? Prefix { get; init; }
            public string? Database { get; init; }
            public List<string>? Aliases { get; init; }
        }
    }

    private sealed record DatabaseEntry(string Name, string Path);
}
