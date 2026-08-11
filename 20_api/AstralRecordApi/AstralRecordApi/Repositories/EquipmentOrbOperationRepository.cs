using System.Data;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// オーブ支払い、抽選、装備更新、結果台帳を AstralRecord DB の同一 transaction で確定する。
/// </summary>
public class EquipmentOrbOperationRepository(
    AstralRecordDbContext dbContext,
    IItemRepository itemRepository,
    IEnchantRepository enchantRepository) : IEquipmentOrbOperationRepository
{
    private const string GameProfile = "GAME";
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly IReadOnlyDictionary<string, long> GoldValues =
        new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase)
        {
            ["gold"] = 1L,
            ["ast_gold"] = 1L,
            ["gold_coin"] = 10L,
            ["gold_ingot"] = 100L,
            ["gold_block"] = 1_000L,
            ["gold_diamond"] = 10_000L,
            ["gold_diamond_block"] = 100_000L,
            ["yggdrasil_star_core"] = 1_000_000L,
        };
    private static readonly (string ItemId, long Value)[] CanonicalGold =
    [
        ("yggdrasil_star_core", 1_000_000L),
        ("gold_diamond_block", 100_000L),
        ("gold_diamond", 10_000L),
        ("gold_block", 1_000L),
        ("gold_ingot", 100L),
        ("gold_coin", 10L),
        ("gold", 1L),
    ];

    /// <inheritdoc />
    public async Task<EquipmentOrbOperationResponse> ExecuteAsync(EquipmentOrbOperationRequest request)
    {
        var normalizedOrbItemId = NormalizeId(request.OrbItemId);
        var requestHash = ComputeRequestHash(request, normalizedOrbItemId);
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            // ExecutionStrategy は同じ scoped DbContext で callback を再実行する。
            // 前回 attempt の Added ledger / Modified payment / equipment を再利用しない。
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);

            var existing = await FindLedgerForUpdateAsync(request.OperationId);
            if (existing is not null)
            {
                var replay = existing.AccountId == request.AccountId
                    && string.Equals(existing.RequestHash, requestHash, StringComparison.Ordinal)
                    ? await WithCurrentEquipmentAsync(
                        Deserialize(existing.ResultPayloadJson),
                        existing.AccountId,
                        existing.EquipmentInstanceId)
                    : Conflict(request.OperationId);
                await transaction.CommitAsync();
                return replay;
            }

            var now = DateTime.UtcNow;
            var orbItem = itemRepository.GetById(normalizedOrbItemId);
            var effect = orbItem?.Orb?.Effect;
            var operationType = effect?.Type?.Trim().ToUpperInvariant() ?? string.Empty;

            async Task<EquipmentOrbOperationResponse> CompleteAsync(
                string result,
                EquipmentInstanceResponse? equipment = null,
                IReadOnlyCollection<Guid>? affected = null,
                bool paymentConsumed = false,
                bool enhancementSucceeded = false,
                string? failAction = null,
                double? successRate = null,
                int? repairedAmount = null,
                string? transitionName = null)
            {
                var response = new EquipmentOrbOperationResponse
                {
                    OperationId = request.OperationId,
                    Result = result,
                    OperationType = operationType,
                    Equipment = equipment,
                    // Failure responses also reconcile the origin entry: if it was removed by
                    // another server, Plugin must close/refresh instead of retaining a ghost orb.
                    AffectedInventoryEntryIds = (affected ?? [request.OrbInventoryEntryId])
                        .Distinct()
                        .Order()
                        .ToArray(),
                    PaymentConsumed = paymentConsumed,
                    EnhancementSucceeded = enhancementSucceeded,
                    FailAction = failAction,
                    SuccessRate = successRate,
                    RepairedAmount = repairedAmount,
                    TransitionName = transitionName,
                };
                var payload = JsonSerializer.Serialize(response, JsonOptions);
                dbContext.EquipmentOrbOperations.Add(new EquipmentOrbOperationEntity
                {
                    OperationId = request.OperationId,
                    AccountId = request.AccountId,
                    EquipmentInstanceId = request.EquipmentInstanceId,
                    OrbInventoryEntryId = request.OrbInventoryEntryId,
                    OrbItemId = normalizedOrbItemId,
                    OperationType = operationType,
                    RequestHash = requestHash,
                    ResultCode = result,
                    ResultPayloadJson = payload,
                    PaymentConsumed = paymentConsumed,
                    AffectedInventoryEntryIdsJson = JsonSerializer.Serialize(response.AffectedInventoryEntryIds),
                    CreatedAt = now,
                    CompletedAt = now,
                    CreatedBy = request.AccountId,
                });
                await dbContext.SaveChangesAsync();
                var current = await WithCurrentEquipmentAsync(
                    response,
                    request.AccountId,
                    request.EquipmentInstanceId);
                await transaction.CommitAsync();
                return current;
            }

            var hasActiveListing = await MarketListingRangeLock.HasActiveOrSuspendedAsync(
                dbContext,
                "EQUIPMENT",
                request.EquipmentInstanceId);
            var instance = await FindEquipmentForUpdateAsync(request.EquipmentInstanceId);
            if (hasActiveListing)
                return await CompleteAsync("NOT_ELIGIBLE");

            if (instance is null
                || instance.AccountId != request.AccountId
                || instance.IsDeleted
                || effect is null
                || string.IsNullOrWhiteSpace(operationType))
            {
                return await CompleteAsync("NOT_ELIGIBLE");
            }

            var equipmentItem = itemRepository.GetById(instance.ItemId);
            if (equipmentItem?.Equipment is null)
                return await CompleteAsync("NOT_ELIGIBLE");

            var normalEntries = await FindOwnedNormalEntriesAsync(request.AccountId);
            if (!await IsTargetPresentForUpdateAsync(
                    request.AccountId,
                    request.EquipmentInstanceId,
                    normalEntries))
                return await CompleteAsync("NOT_ELIGIBLE");

            var orbEntry = normalEntries.FirstOrDefault(entry =>
                entry.InventoryEntryId == request.OrbInventoryEntryId
                && !entry.IsDeleted
                && entry.Quantity > 0
                && IdEquals(entry.ItemCategory, "ORB")
                && IdEquals(entry.ItemId, normalizedOrbItemId));
            if (orbEntry is null)
                return await CompleteAsync("PAYMENT_UNAVAILABLE");

            var affectedEntryIds = new HashSet<Guid>();
            var requiredMaterials = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            long requiredGold = 0L;
            var currentEnchants = await dbContext.EquipmentInstanceEnchants
                .Where(enchant => enchant.EquipmentInstanceId == instance.EquipmentInstanceId)
                .OrderBy(enchant => enchant.SlotIndex)
                .ToListAsync();

            bool enhancementSucceeded = false;
            string? failAction = null;
            double? successRate = null;
            int? repairedAmount = null;
            string? transitionName = null;

            switch (operationType)
            {
                case "ENHANCE":
                {
                    if (!MatchesTargetSlot(effect, equipmentItem.Equipment.Slot)
                        || !MatchesRank(effect, instance.TranscendenceRank))
                        return await CompleteAsync("NOT_ELIGIBLE");

                    var maxLevel = GetEffectiveEnhanceMaxLevel(equipmentItem.Equipment, instance.TranscendenceRank);
                    var targetLevel = instance.EnhanceLevel + 1;
                    var level = equipmentItem.Equipment.Enhance?.Levels
                        .FirstOrDefault(candidate => candidate.Level == targetLevel);
                    if (level is null || targetLevel > maxLevel)
                        return await CompleteAsync("NOT_ELIGIBLE");

                    successRate = Math.Clamp(level.SuccessRate, 0.0F, 1.0F);
                    enhancementSucceeded = Random.Shared.NextDouble() < successRate.Value;
                    failAction = NormalizeFailAction(level.FailAction);
                    var appliedLevel = enhancementSucceeded
                        ? targetLevel
                        : failAction switch
                        {
                            "SET_LEVEL" => Math.Clamp(level.FailTargetLevel ?? instance.EnhanceLevel, 0, maxLevel),
                            "DECREASE_ONE" => Math.Max(0, instance.EnhanceLevel - 1),
                            _ => instance.EnhanceLevel,
                        };
                    ApplyEnhanceLevel(instance, equipmentItem.Equipment, appliedLevel);
                    break;
                }
                case "REPAIR":
                {
                    if (!MatchesOptionalTargetSlot(effect, equipmentItem.Equipment.Slot)
                        || !MatchesRank(effect, instance.TranscendenceRank)
                        || !instance.DurabilityMax.HasValue
                        || !instance.DurabilityValue.HasValue
                        || instance.DurabilityValue.Value >= instance.DurabilityMax.Value)
                        return await CompleteAsync("NOT_ELIGIBLE");

                    var before = instance.DurabilityValue.Value;
                    var after = effect.RepairFull
                        ? instance.DurabilityMax.Value
                        : Math.Min(instance.DurabilityMax.Value, before + Math.Max(0, effect.RepairAmount ?? 0));
                    if (after <= before)
                        return await CompleteAsync("NOT_ELIGIBLE");
                    instance.DurabilityValue = after;
                    repairedAmount = after - before;
                    break;
                }
                case "TRANSCENDENCE":
                {
                    var target = equipmentItem.Equipment.Transcendence
                        .Where(candidate => candidate.Rank > instance.TranscendenceRank)
                        .OrderBy(candidate => candidate.Rank)
                        .FirstOrDefault();
                    if (target is null
                        || !MatchesTargetRank(effect, target.Rank)
                        || instance.EnhanceLevel < GetEffectiveEnhanceMaxLevel(
                            equipmentItem.Equipment, instance.TranscendenceRank)
                        || instance.EnhanceLevel < target.RequiredEnhanceLevel)
                        return await CompleteAsync("NOT_ELIGIBLE");

                    foreach (var material in target.RequiredMaterials)
                    {
                        if (string.IsNullOrWhiteSpace(material.ItemId) || material.Amount <= 0)
                            return await CompleteAsync("NOT_ELIGIBLE");
                        requiredMaterials[NormalizeId(material.ItemId)] = checked(
                            requiredMaterials.GetValueOrDefault(NormalizeId(material.ItemId)) + material.Amount);
                    }
                    requiredGold = Math.Max(0, target.RequiredCurrency);
                    instance.TranscendenceRank = target.Rank;
                    if (target.Overrides?.Rune is not null
                        && !string.IsNullOrWhiteSpace(target.Overrides.Rune.MaxSlots))
                        instance.RuneMaxSlots = RangeValueResolver.ResolveInt(target.Overrides.Rune.MaxSlots);
                    transitionName = string.IsNullOrWhiteSpace(target.Name)
                        ? target.Overrides?.Name ?? "次の状態"
                        : target.Name;
                    break;
                }
                case "ENCHANT":
                {
                    var enchantResult = ApplyEnchant(
                        request.AccountId,
                        instance,
                        equipmentItem.Equipment,
                        effect,
                        currentEnchants,
                        now);
                    if (enchantResult != "APPLIED")
                        return await CompleteAsync(enchantResult);
                    break;
                }
                default:
                    return await CompleteAsync("NOT_ELIGIBLE");
            }

            var paymentAvailable = HasMaterials(normalEntries, requiredMaterials, normalizedOrbItemId)
                && await HasGoldAsync(request.AccountId, requiredGold);
            if (!paymentAvailable)
            {
                dbContext.ChangeTracker.Clear();
                return await CompleteAsync("PAYMENT_UNAVAILABLE");
            }

            ConsumeEntry(orbEntry, 1L, request.AccountId, now, affectedEntryIds);
            foreach (var requirement in requiredMaterials.OrderBy(pair => pair.Key, StringComparer.OrdinalIgnoreCase))
            {
                var remaining = requirement.Value;
                foreach (var entry in normalEntries.Where(entry =>
                             !entry.IsDeleted && entry.Quantity > 0 && IdEquals(entry.ItemId, requirement.Key)))
                {
                    if (remaining <= 0)
                        break;
                    var consumed = Math.Min(entry.Quantity, remaining);
                    ConsumeEntry(entry, consumed, request.AccountId, now, affectedEntryIds);
                    remaining -= consumed;
                }
            }
            if (requiredGold > 0)
                await RewriteGoldBalanceAsync(request.AccountId, requiredGold, request.AccountId, now, affectedEntryIds);

            instance.UpdatedAt = now;
            instance.UpdatedBy = request.AccountId;
            var responseEquipment = await BuildResponseAsync(instance, currentEnchants);
            return await CompleteAsync(
                "APPLIED",
                responseEquipment,
                affectedEntryIds,
                paymentConsumed: true,
                enhancementSucceeded,
                failAction,
                successRate,
                repairedAmount,
                transitionName);
        });
    }

    /// <inheritdoc />
    public async Task<EquipmentOrbOperationResponse?> FindAsync(Guid operationId, Guid accountId)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);
            var entity = await dbContext.EquipmentOrbOperations.AsNoTracking()
                .FirstOrDefaultAsync(operation => operation.OperationId == operationId
                    && operation.AccountId == accountId);
            var response = entity is null
                ? null
                : await WithCurrentEquipmentAsync(
                    Deserialize(entity.ResultPayloadJson),
                    entity.AccountId,
                    entity.EquipmentInstanceId);
            await transaction.CommitAsync();
            return response;
        });
    }

    private async Task<EquipmentOrbOperationResponse> WithCurrentEquipmentAsync(
        EquipmentOrbOperationResponse stored,
        Guid ledgerAccountId,
        Guid equipmentInstanceId)
    {
        var instance = await dbContext.EquipmentInstances.AsNoTracking()
            .FirstOrDefaultAsync(candidate =>
                candidate.EquipmentInstanceId == equipmentInstanceId
                && candidate.AccountId == ledgerAccountId
                && !candidate.IsDeleted);
        if (instance is null
            || !await IsTargetPresentAsync(ledgerAccountId, equipmentInstanceId))
            return CopyWithEquipment(stored, null, targetAvailable: false);
        var enchants = await dbContext.EquipmentInstanceEnchants.AsNoTracking()
            .Where(enchant => enchant.EquipmentInstanceId == instance.EquipmentInstanceId)
            .OrderBy(enchant => enchant.SlotIndex)
            .ToListAsync();
        var current = await BuildResponseAsync(instance, enchants);
        return CopyWithEquipment(stored, current, targetAvailable: true);
    }

    private static EquipmentOrbOperationResponse CopyWithEquipment(
        EquipmentOrbOperationResponse stored,
        EquipmentInstanceResponse? equipment,
        bool targetAvailable)
        => new()
        {
            OperationId = stored.OperationId,
            Result = stored.Result,
            OperationType = stored.OperationType,
            Equipment = equipment,
            TargetAvailable = targetAvailable,
            AffectedInventoryEntryIds = stored.AffectedInventoryEntryIds,
            PaymentConsumed = stored.PaymentConsumed,
            EnhancementSucceeded = stored.EnhancementSucceeded,
            FailAction = stored.FailAction,
            SuccessRate = stored.SuccessRate,
            RepairedAmount = stored.RepairedAmount,
            TransitionName = stored.TransitionName,
        };

    /** replay応答でも対象の現在membershipを検証し、旧ownerへ装備を返さない。 */
    private async Task<bool> IsTargetPresentAsync(Guid accountId, Guid equipmentInstanceId)
    {
        var presentInInventory = await (from entry in dbContext.InventoryEntries.AsNoTracking()
                join inventory in dbContext.Inventories.AsNoTracking()
                    on entry.InventoryId equals inventory.InventoryId
                where inventory.AccountId == accountId
                      && !inventory.IsDeleted
                      && inventory.IsEnabled
                      && inventory.InventoryProfile == GameProfile
                      && (inventory.InventoryType == "BAG" || inventory.InventoryType == "HOTBAR")
                      && !entry.IsDeleted
                      && (entry.InstanceType == "EQUIPMENT" || entry.InstanceType == "equipment")
                      && (entry.ItemCategory == "EQUIPMENT" || entry.ItemCategory == "equipment")
                      && entry.InstanceId == equipmentInstanceId
                select entry).AnyAsync();
        if (presentInInventory)
            return true;

        return await (from slot in dbContext.EquipmentLoadoutSlots.AsNoTracking()
                      join loadout in dbContext.EquipmentLoadouts.AsNoTracking()
                          on slot.EquipmentLoadoutId equals loadout.EquipmentLoadoutId
                      where loadout.AccountId == accountId
                            && loadout.LoadoutProfile == GameProfile
                            && loadout.IsActive
                            && !loadout.IsDeleted
                            && !slot.IsDeleted
                            && slot.EquipmentInstanceId == equipmentInstanceId
                      select slot).AnyAsync();
    }

    private string ApplyEnchant(
        Guid accountId,
        EquipmentInstanceEntity instance,
        ItemEquipmentResponse equipment,
        ItemOrbEffectResponse effect,
        List<EquipmentInstanceEnchantEntity> currentEnchants,
        DateTime now)
    {
        var maxSlots = GetEffectiveEnchantMaxSlots(equipment, instance.TranscendenceRank);
        var enchantMaster = string.IsNullOrWhiteSpace(effect.EnchantMasterId)
            ? null
            : enchantRepository.GetById(effect.EnchantMasterId);
        var equipmentType = ResolveEnchantEquipmentType(equipment.Slot);
        var target = enchantMaster?.Targets.FirstOrDefault(candidate =>
            IdEquals(candidate.EquipmentType, equipmentType));
        if (maxSlots <= 0 || target is null)
            return "NOT_ELIGIBLE";

        var uniqueCandidates = target.Entries
            .Where(IsValidEnchantEntry)
            .GroupBy(entry => entry.EffectId, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .ToList();
        var operation = effect.EnchantOperation?.Trim().ToUpperInvariant() ?? string.Empty;
        EquipmentInstanceEnchantEntity? overwriteTarget = null;
        IReadOnlyList<int> targetSlots;
        IReadOnlyList<EnchantEntryResponse> candidates;
        if (operation == "OVERWRITE_RANDOM")
        {
            if (currentEnchants.Count == 0)
                return "NO_SLOT";
            var viable = currentEnchants
                .Select(current => new
                {
                    Current = current,
                    Candidates = uniqueCandidates.Where(candidate =>
                        !currentEnchants.Any(other => other.EnchantId != current.EnchantId
                            && IsSameEnchantEffect(candidate, other))).ToList(),
                })
                .Where(candidate => candidate.Candidates.Count > 0)
                .ToList();
            if (viable.Count == 0)
                return "NO_CANDIDATE";
            var selected = viable[Random.Shared.Next(viable.Count)];
            overwriteTarget = selected.Current;
            targetSlots = [selected.Current.SlotIndex];
            candidates = selected.Candidates;
        }
        else
        {
            var emptySlots = GetAvailableSlotIndexes(currentEnchants, maxSlots);
            if (emptySlots.Count == 0)
                return "NO_SLOT";
            targetSlots = operation switch
            {
                "FILL_ONE_EMPTY" => [emptySlots[0]],
                "FILL_ALL_EMPTY" => emptySlots,
                _ => [],
            };
            if (targetSlots.Count == 0)
                return "NOT_ELIGIBLE";
            candidates = uniqueCandidates.Where(candidate =>
                !currentEnchants.Any(current => IsSameEnchantEffect(candidate, current))).ToList();
        }

        if (candidates.Count < targetSlots.Count)
            return "NO_CANDIDATE";
        var selectedEntries = SelectWeightedWithoutReplacement(candidates, targetSlots.Count);
        if (selectedEntries.Count != targetSlots.Count)
            return "NO_CANDIDATE";

        if (overwriteTarget is not null)
        {
            dbContext.EquipmentInstanceEnchants.Remove(overwriteTarget);
            currentEnchants.Remove(overwriteTarget);
        }
        var added = selectedEntries.Select((entry, index) => new EquipmentInstanceEnchantEntity
        {
            EnchantId = Guid.NewGuid(),
            EquipmentInstanceId = instance.EquipmentInstanceId,
            SlotIndex = targetSlots[index],
            EnchantMasterId = enchantMaster!.Id,
            EffectId = entry.EffectId,
            Status = entry.Status,
            Type = entry.Type,
            Value = RangeValueResolver.ResolveDecimal(entry.Value),
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        }).ToList();
        dbContext.EquipmentInstanceEnchants.AddRange(added);
        currentEnchants.AddRange(added);
        return "APPLIED";
    }

    private async Task<EquipmentInstanceResponse> BuildResponseAsync(
        EquipmentInstanceEntity instance,
        IReadOnlyCollection<EquipmentInstanceEnchantEntity> currentEnchants)
    {
        var statRolls = await dbContext.EquipmentInstanceStatRolls.AsNoTracking()
            .Where(roll => roll.EquipmentInstanceId == instance.EquipmentInstanceId)
            .OrderBy(roll => roll.SortOrder)
            .ToListAsync();
        var runes = await dbContext.EquipmentInstanceRunes.AsNoTracking()
            .Where(rune => rune.EquipmentInstanceId == instance.EquipmentInstanceId)
            .OrderBy(rune => rune.SlotIndex)
            .ToListAsync();
        return new EquipmentInstanceResponse
        {
            EquipmentInstanceId = instance.EquipmentInstanceId,
            AccountId = instance.AccountId,
            ItemId = instance.ItemId,
            EnhanceLevel = instance.EnhanceLevel,
            RuneMaxSlots = instance.RuneMaxSlots,
            TranscendenceRank = instance.TranscendenceRank,
            DurabilityMax = instance.DurabilityMax,
            DurabilityValue = instance.DurabilityValue,
            CreatedAt = instance.CreatedAt,
            UpdatedAt = instance.UpdatedAt,
            StatRolls = statRolls.Select(roll => new EquipmentInstanceStatRollResponse
            {
                StatRollId = roll.StatRollId,
                Status = roll.Status,
                Min = roll.RandomMin,
                Max = roll.RandomMax,
                SortOrder = roll.SortOrder,
            }).ToList(),
            Enchants = currentEnchants.OrderBy(enchant => enchant.SlotIndex).Select(enchant =>
                new EquipmentInstanceEnchantResponse
                {
                    EnchantId = enchant.EnchantId,
                    EquipmentInstanceId = enchant.EquipmentInstanceId,
                    SlotIndex = enchant.SlotIndex,
                    EnchantMasterId = enchant.EnchantMasterId,
                    EffectId = enchant.EffectId,
                    Status = enchant.Status,
                    Type = enchant.Type,
                    Value = enchant.Value,
                    CreatedAt = enchant.CreatedAt,
                    UpdatedAt = enchant.UpdatedAt,
                    CreatedBy = enchant.CreatedBy,
                    UpdatedBy = enchant.UpdatedBy,
                }).ToList(),
            Runes = runes.Select(rune => new EquipmentInstanceRuneResponse
            {
                RuneId = rune.RuneId,
                RuneInstanceId = rune.RuneInstanceId,
                EquipmentInstanceId = rune.EquipmentInstanceId,
                SlotIndex = rune.SlotIndex,
                ItemId = rune.ItemId,
                CreatedAt = rune.CreatedAt,
                UpdatedAt = rune.UpdatedAt,
                CreatedBy = rune.CreatedBy,
                UpdatedBy = rune.UpdatedBy,
            }).ToList(),
        };
    }

    private async Task<EquipmentOrbOperationEntity?> FindLedgerForUpdateAsync(Guid operationId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentOrbOperations
                .FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[equipment_orb_operation] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [operation_id] = {operationId}
                    """)
                .SingleOrDefaultAsync();
        }
        return await dbContext.EquipmentOrbOperations
            .SingleOrDefaultAsync(operation => operation.OperationId == operationId);
    }

    private async Task<EquipmentInstanceEntity?> FindEquipmentForUpdateAsync(Guid equipmentInstanceId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentInstances
                .FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[equipment_instance] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [equipment_instance_id] = {equipmentInstanceId}
                    """)
                .SingleOrDefaultAsync();
        }
        return await dbContext.EquipmentInstances
            .SingleOrDefaultAsync(instance => instance.EquipmentInstanceId == equipmentInstanceId);
    }

    private async Task<List<InventoryEntryEntity>> FindOwnedNormalEntriesAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries.FromSqlInterpolated($"""
                    SELECT entry.*
                    FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                        ON inventory.[inventory_id] = entry.[inventory_id]
                    WHERE inventory.[account_id] = {accountId}
                      AND inventory.[is_deleted] = 0
                      AND inventory.[is_enabled] = 1
                      AND inventory.[inventory_profile] = 'GAME'
                      AND inventory.[inventory_type] IN ('BAG', 'HOTBAR')
                      AND entry.[is_deleted] = 0
                    """)
                .OrderBy(entry => entry.SlotIndex ?? int.MaxValue)
                .ThenBy(entry => entry.InventoryEntryId)
                .ToListAsync();
        }
        return await (from entry in dbContext.InventoryEntries
                      join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                      where inventory.AccountId == accountId
                            && !inventory.IsDeleted
                            && inventory.IsEnabled
                            && inventory.InventoryProfile == GameProfile
                            && (inventory.InventoryType == "BAG" || inventory.InventoryType == "HOTBAR")
                            && !entry.IsDeleted
                      orderby inventory.InventoryType == "BAG" ? 0 : 1,
                          entry.SlotIndex ?? int.MaxValue,
                          entry.InventoryEntryId
                      select entry).ToListAsync();
    }

    private async Task<bool> IsTargetPresentForUpdateAsync(
        Guid accountId,
        Guid equipmentInstanceId,
        IReadOnlyCollection<InventoryEntryEntity> normalEntries)
    {
        if (normalEntries.Any(entry =>
                entry.InstanceId == equipmentInstanceId
                && IdEquals(entry.InstanceType, "EQUIPMENT")
                && IdEquals(entry.ItemCategory, "EQUIPMENT")))
            return true;

        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentLoadoutSlots
                .FromSqlInterpolated($"""
                    SELECT slot.*
                    FROM [dbo].[equipment_loadout_slot] AS slot WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[equipment_loadout] AS loadout WITH (HOLDLOCK)
                        ON loadout.[equipment_loadout_id] = slot.[equipment_loadout_id]
                    WHERE loadout.[account_id] = {accountId}
                      AND loadout.[loadout_profile] = 'GAME'
                      AND loadout.[is_active] = 1
                      AND loadout.[is_deleted] = 0
                      AND slot.[is_deleted] = 0
                      AND slot.[equipment_instance_id] = {equipmentInstanceId}
                    """)
                .AnyAsync();
        }
        return await (from slot in dbContext.EquipmentLoadoutSlots
                      join loadout in dbContext.EquipmentLoadouts
                          on slot.EquipmentLoadoutId equals loadout.EquipmentLoadoutId
                      where loadout.AccountId == accountId
                            && loadout.LoadoutProfile == GameProfile
                            && loadout.IsActive
                            && !loadout.IsDeleted
                            && !slot.IsDeleted
                            && slot.EquipmentInstanceId == equipmentInstanceId
                      select slot).AnyAsync();
    }

    private async Task<bool> HasGoldAsync(Guid accountId, long requiredGold)
    {
        if (requiredGold <= 0)
            return true;
        var entries = await FindGoldEntriesAsync(accountId);
        return TotalGold(entries) >= requiredGold;
    }

    private async Task<List<InventoryEntryEntity>> FindGoldEntriesAsync(Guid accountId)
    {
        List<InventoryEntryEntity> entries;
        if (dbContext.Database.IsSqlServer())
        {
            entries = await dbContext.InventoryEntries.FromSqlInterpolated($"""
                    SELECT entry.*
                    FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                        ON inventory.[inventory_id] = entry.[inventory_id]
                    WHERE inventory.[account_id] = {accountId}
                      AND inventory.[is_deleted] = 0
                      AND inventory.[is_enabled] = 1
                      AND inventory.[inventory_profile] = 'GAME'
                      AND inventory.[inventory_type] = 'CURRENCY'
                      AND entry.[is_deleted] = 0
                      AND entry.[quantity] > 0
                    """)
                .OrderBy(entry => entry.InventoryEntryId)
                .ToListAsync();
        }
        else
        {
            entries = await (from entry in dbContext.InventoryEntries
                      join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                      where inventory.AccountId == accountId
                            && !inventory.IsDeleted
                            && inventory.IsEnabled
                            && inventory.InventoryProfile == GameProfile
                            && inventory.InventoryType == "CURRENCY"
                            && !entry.IsDeleted
                            && entry.Quantity > 0
                      orderby entry.InventoryEntryId
                      select entry).ToListAsync();
        }
        return entries.Where(entry => entry.ItemId is not null && GoldValues.ContainsKey(entry.ItemId)).ToList();
    }

    private async Task RewriteGoldBalanceAsync(
        Guid accountId,
        long requiredGold,
        Guid updatedBy,
        DateTime now,
        ISet<Guid> affectedEntryIds)
    {
        var entries = await FindGoldEntriesAsync(accountId);
        var total = TotalGold(entries);
        var remainingValue = total - requiredGold;
        var currencyInventory = await dbContext.Inventories.FirstAsync(inventory =>
            inventory.AccountId == accountId
            && !inventory.IsDeleted
            && inventory.IsEnabled
            && inventory.InventoryProfile == GameProfile
            && inventory.InventoryType == "CURRENCY");
        var unused = new Queue<InventoryEntryEntity>(entries);
        foreach (var denomination in CanonicalGold)
        {
            var quantity = remainingValue / denomination.Value;
            remainingValue %= denomination.Value;
            if (quantity <= 0)
                continue;
            var matching = entries.FirstOrDefault(entry => !entry.IsDeleted
                && IdEquals(entry.ItemId, denomination.ItemId)
                && unused.Contains(entry));
            InventoryEntryEntity target;
            if (matching is not null)
            {
                target = matching;
                unused = new Queue<InventoryEntryEntity>(unused.Where(entry => entry != matching));
            }
            else if (unused.Count > 0)
            {
                target = unused.Dequeue();
            }
            else
            {
                target = new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = currencyInventory.InventoryId,
                    ItemCategory = "currency",
                    CreatedAt = now,
                    CreatedBy = updatedBy,
                };
                dbContext.InventoryEntries.Add(target);
            }
            target.ItemCategory = "currency";
            target.ItemId = denomination.ItemId;
            target.InstanceType = null;
            target.InstanceId = null;
            target.Quantity = quantity;
            target.MetadataJson = null;
            target.IsDeleted = false;
            target.UpdatedAt = now;
            target.UpdatedBy = updatedBy;
            affectedEntryIds.Add(target.InventoryEntryId);
        }
        foreach (var entry in unused)
        {
            entry.IsDeleted = true;
            entry.UpdatedAt = now;
            entry.UpdatedBy = updatedBy;
            affectedEntryIds.Add(entry.InventoryEntryId);
        }
    }

    private static long TotalGold(IEnumerable<InventoryEntryEntity> entries)
    {
        long total = 0L;
        foreach (var entry in entries)
        {
            if (entry.ItemId is null || !GoldValues.TryGetValue(entry.ItemId, out var value))
                continue;
            try
            {
                total = checked(total + checked(entry.Quantity * value));
            }
            catch (OverflowException)
            {
                return long.MaxValue;
            }
        }
        return total;
    }

    private static bool HasMaterials(
        IReadOnlyCollection<InventoryEntryEntity> entries,
        IReadOnlyDictionary<string, long> requirements,
        string orbItemId)
    {
        return requirements.All(requirement =>
        {
            var required = requirement.Value + (IdEquals(requirement.Key, orbItemId) ? 1L : 0L);
            return entries
                .Where(entry => !entry.IsDeleted && entry.Quantity > 0 && IdEquals(entry.ItemId, requirement.Key))
                .Sum(entry => entry.Quantity) >= required;
        });
    }

    private static void ConsumeEntry(
        InventoryEntryEntity entry,
        long amount,
        Guid updatedBy,
        DateTime now,
        ISet<Guid> affectedEntryIds)
    {
        if (amount <= 0 || entry.Quantity < amount)
            throw new InvalidOperationException("Inventory payment changed during orb operation.");
        if (entry.Quantity == amount)
            entry.IsDeleted = true;
        else
            entry.Quantity -= amount;
        entry.UpdatedAt = now;
        entry.UpdatedBy = updatedBy;
        affectedEntryIds.Add(entry.InventoryEntryId);
    }

    private static void ApplyEnhanceLevel(
        EquipmentInstanceEntity instance,
        ItemEquipmentResponse equipment,
        int targetLevel)
    {
        var currentBonus = equipment.Enhance?.Levels
            .Where(level => level.Level <= instance.EnhanceLevel)
            .Sum(level => level.DurabilityBonus ?? 0) ?? 0;
        var targetBonus = equipment.Enhance?.Levels
            .Where(level => level.Level <= targetLevel)
            .Sum(level => level.DurabilityBonus ?? 0) ?? 0;
        var delta = targetBonus - currentBonus;
        instance.EnhanceLevel = targetLevel;
        if (delta != 0 && instance.DurabilityMax.HasValue && instance.DurabilityValue.HasValue)
        {
            instance.DurabilityMax += delta;
            instance.DurabilityValue = Math.Clamp(instance.DurabilityValue.Value + delta, 0,
                instance.DurabilityMax.Value);
        }
    }

    private static bool MatchesOptionalTargetSlot(ItemOrbEffectResponse effect, string slot)
        => effect.TargetSlots.Count == 0 || MatchesTargetSlot(effect, slot);

    private static bool MatchesTargetSlot(ItemOrbEffectResponse effect, string slot)
        => effect.TargetSlots.Any(target => IdEquals(target, slot));

    private static bool MatchesRank(ItemOrbEffectResponse effect, int currentRank)
    {
        if (!effect.Rank.HasValue)
            return true;
        return IdEquals(effect.RankMode, "AT_MOST")
            ? currentRank <= effect.Rank.Value
            : currentRank == effect.Rank.Value;
    }

    private static bool MatchesTargetRank(ItemOrbEffectResponse effect, int targetRank)
    {
        if (!effect.Rank.HasValue)
            return false;
        return IdEquals(effect.RankMode, "AT_MOST")
            ? targetRank <= effect.Rank.Value
            : targetRank == effect.Rank.Value;
    }

    private static string NormalizeFailAction(string? value)
        => value?.Trim().ToUpperInvariant() switch
        {
            "SET_LEVEL" => "SET_LEVEL",
            "DECREASE_ONE" => "DECREASE_ONE",
            _ => "NONE",
        };

    private static bool IsSameEnchantEffect(
        EnchantEntryResponse candidate,
        EquipmentInstanceEnchantEntity current)
    {
        if (IdEquals(candidate.EffectId, current.EffectId))
            return true;
        if (!current.EffectId.StartsWith("legacy_", StringComparison.OrdinalIgnoreCase)
            || !IdEquals(candidate.Status, current.Status)
            || !IdEquals(candidate.Type, current.Type))
            return false;
        var (minText, maxText) = RangeValueResolver.SplitRange(candidate.Value);
        return decimal.TryParse(minText, NumberStyles.Number, CultureInfo.InvariantCulture, out var min)
            && decimal.TryParse(maxText, NumberStyles.Number, CultureInfo.InvariantCulture, out var max)
            && current.Value >= Math.Min(min, max)
            && current.Value <= Math.Max(min, max);
    }

    private static bool IsValidEnchantEntry(EnchantEntryResponse entry)
        => !string.IsNullOrWhiteSpace(entry.EffectId)
            && !string.IsNullOrWhiteSpace(entry.Status)
            && !string.IsNullOrWhiteSpace(entry.Type)
            && !string.IsNullOrWhiteSpace(entry.Value)
            && entry.Weight > 0;

    private static IReadOnlyList<EnchantEntryResponse> SelectWeightedWithoutReplacement(
        IReadOnlyList<EnchantEntryResponse> entries,
        int count)
    {
        var remaining = entries.ToList();
        var selected = new List<EnchantEntryResponse>(count);
        while (selected.Count < count && remaining.Count > 0)
        {
            long totalWeight = 0L;
            foreach (var entry in remaining)
                totalWeight = checked(totalWeight + Math.Max((long)entry.Weight, 1L));
            var roll = Random.Shared.NextInt64(totalWeight);
            var selectedIndex = remaining.Count - 1;
            for (var index = 0; index < remaining.Count; index++)
            {
                var weight = Math.Max((long)remaining[index].Weight, 1L);
                if (roll >= weight)
                {
                    roll -= weight;
                    continue;
                }
                selectedIndex = index;
                break;
            }
            selected.Add(remaining[selectedIndex]);
            remaining.RemoveAt(selectedIndex);
        }
        return selected;
    }

    private static IReadOnlyList<int> GetAvailableSlotIndexes(
        IReadOnlyCollection<EquipmentInstanceEnchantEntity> enchants,
        int maxSlots)
    {
        var used = enchants.Select(enchant => enchant.SlotIndex).ToHashSet();
        return Enumerable.Range(0, maxSlots).Where(index => !used.Contains(index)).ToList();
    }

    private static int GetEffectiveEnchantMaxSlots(ItemEquipmentResponse equipment, int rank)
    {
        var max = equipment.Enchant?.MaxSlots ?? 0;
        foreach (var transition in equipment.Transcendence.OrderBy(candidate => candidate.Rank))
        {
            if (transition.Rank > rank)
                break;
            if (transition.Overrides?.Enchant is not null)
                max = transition.Overrides.Enchant.MaxSlots;
        }
        return max;
    }

    private static int GetEffectiveEnhanceMaxLevel(ItemEquipmentResponse equipment, int rank)
    {
        var max = equipment.Enhance?.MaxLevel ?? 0;
        foreach (var transition in equipment.Transcendence.OrderBy(candidate => candidate.Rank))
        {
            if (transition.Rank > rank)
                break;
            if (transition.Overrides?.Enhance is not null)
                max = transition.Overrides.Enhance.MaxLevel;
        }
        return max;
    }

    private static string ResolveEnchantEquipmentType(string slot) => slot.Trim().ToUpperInvariant() switch
    {
        "WEAPON" or "SUBWEAPON" => "WEAPON",
        "HEAD" or "CHEST" or "LEGS" or "FEET" => "ARMOR",
        "ACCESSORY" => "ACCESSORY",
        _ => string.Empty,
    };

    private static string ComputeRequestHash(EquipmentOrbOperationRequest request, string orbItemId)
    {
        var canonical = string.Join('|',
            request.OperationId.ToString("D"),
            request.AccountId.ToString("D"),
            request.EquipmentInstanceId.ToString("D"),
            request.OrbInventoryEntryId.ToString("D"),
            orbItemId);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
    }

    private static EquipmentOrbOperationResponse Deserialize(string payload)
        => JsonSerializer.Deserialize<EquipmentOrbOperationResponse>(payload, JsonOptions)
            ?? throw new InvalidOperationException("Stored orb operation payload is invalid.");

    private static EquipmentOrbOperationResponse Conflict(Guid operationId) => new()
    {
        OperationId = operationId,
        Result = "OPERATION_CONFLICT",
        OperationType = string.Empty,
    };

    private static bool IdEquals(string? left, string? right)
        => string.Equals(left?.Trim(), right?.Trim(), StringComparison.OrdinalIgnoreCase);

    private static string NormalizeId(string? value) => value?.Trim().ToLowerInvariant() ?? string.Empty;
}
