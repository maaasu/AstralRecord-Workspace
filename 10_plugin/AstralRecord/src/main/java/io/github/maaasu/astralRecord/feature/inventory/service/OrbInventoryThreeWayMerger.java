package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldCurrencyCalculator;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** オーブ操作前の保存済み値・現在値・API正本を、entry単位で損失なく三者マージします。 */
final class OrbInventoryThreeWayMerger {

    private OrbInventoryThreeWayMerger() {
    }

    static @NotNull MergeResult merge(
        @NotNull UUID accountId,
        @NotNull List<InventoryModel> inventories,
        @NotNull Map<UUID, List<InventoryEntryModel>> baselineEntries,
        @NotNull Map<UUID, List<InventoryEntryModel>> currentEntries,
        @NotNull Map<UUID, Optional<InventoryEntryModel>> authoritativeAffectedEntries,
        @Nullable UUID currencyInventoryId,
        @NotNull List<InventoryEntryModel> authoritativeCurrencyEntries
    ) {
        Map<UUID, InventoryModel> ownedInventories = new HashMap<>();
        for (InventoryModel inventory : inventories) {
            if (inventory.getAccountId().equals(accountId)
                && inventory.isEnabled()
                && !inventory.isDeleted()) {
                ownedInventories.put(inventory.getInventoryId(), inventory);
            }
        }

        Map<UUID, List<InventoryEntryModel>> merged = new LinkedHashMap<>();
        currentEntries.forEach((inventoryId, entries) ->
            merged.put(inventoryId, new ArrayList<>(entries.stream()
                .filter(entry -> !entry.isDeleted())
                .toList()))
        );
        Map<UUID, InventoryEntryModel> baselineById = indexEntries(baselineEntries);
        Map<UUID, InventoryEntryModel> currentById = indexEntries(currentEntries);
        Set<UUID> changedInventoryIds = new LinkedHashSet<>();

        for (Map.Entry<UUID, Optional<InventoryEntryModel>> affected
            : authoritativeAffectedEntries.entrySet()) {
            UUID entryId = affected.getKey();
            InventoryEntryModel baseline = baselineById.get(entryId);
            InventoryEntryModel current = currentById.get(entryId);
            InventoryEntryModel authoritative = validateAuthoritative(
                affected.getValue().orElse(null),
                ownedInventories
            );
            if (isGoldEntry(baseline) || isGoldEntry(current) || isGoldEntry(authoritative)) {
                continue;
            }

            // Start from the complete local current state and apply the API-side delta once.
            // Applying the delta to fungible current rows also handles a full move that replaced
            // the source entry ID and a concurrent consume that used another stack.
            if (baseline != null) {
                long apiDelta;
                try {
                    apiDelta = Math.subtractExact(quantity(authoritative), baseline.getQuantity());
                } catch (ArithmeticException overflow) {
                    throw inconsistent(entryId, "API quantity delta overflow", overflow);
                }
                applyApiDeltaToRelocatedEntries(
                    merged,
                    baseline,
                    authoritative,
                    apiDelta,
                    accountId,
                    ownedInventories,
                    changedInventoryIds
                );
                reconcileAffectedEntryIdentity(
                    merged,
                    entryId,
                    authoritative,
                    accountId,
                    changedInventoryIds
                );
                continue;
            }

            if (baseline == null && current != null && authoritative != null
                && !sameLogicalItem(current, authoritative)) {
                removeEntry(merged, entryId, changedInventoryIds);
                addEntry(merged, authoritative, ownedInventories, changedInventoryIds);
                addEntry(
                    merged,
                    asNewEntry(current, current.getQuantity(), accountId),
                    ownedInventories,
                    changedInventoryIds
                );
                continue;
            }

            long baselineQuantity = quantity(baseline);
            long currentQuantity = quantity(current);
            long authoritativeQuantity = quantity(authoritative);
            long localDelta;
            long mergedQuantity;
            try {
                localDelta = Math.subtractExact(currentQuantity, baselineQuantity);
                mergedQuantity = Math.addExact(authoritativeQuantity, localDelta);
            } catch (ArithmeticException overflow) {
                throw inconsistent(entryId, "quantity overflow", overflow);
            }
            if (mergedQuantity < 0L) {
                throw inconsistent(entryId, "merged quantity became negative", null);
            }

            removeEntry(merged, entryId, changedInventoryIds);
            if (mergedQuantity == 0L) {
                continue;
            }

            InventoryEntryModel result;
            if (authoritative != null) {
                InventoryEntryModel placement = hasLocalPlacementChange(baseline, current)
                    ? current
                    : authoritative;
                result = withAuthoritativeIdentityAndLocalPlacement(
                    authoritative,
                    Objects.requireNonNull(placement),
                    mergedQuantity,
                    accountId
                );
            } else {
                InventoryEntryModel local = current != null ? current : baseline;
                if (local == null) {
                    throw inconsistent(entryId, "positive merge has no source payload", null);
                }
                result = baseline == null
                    ? withQuantity(local, mergedQuantity, accountId)
                    : asNewEntry(local, mergedQuantity, accountId);
            }
            addEntry(merged, result, ownedInventories, changedInventoryIds);
        }

        if (currencyInventoryId != null) {
            mergeGoldCurrency(
                accountId,
                currencyInventoryId,
                baselineEntries.getOrDefault(currencyInventoryId, List.of()),
                currentEntries.getOrDefault(currencyInventoryId, List.of()),
                authoritativeCurrencyEntries,
                merged,
                ownedInventories,
                changedInventoryIds
            );
        }

        Map<UUID, List<InventoryEntryModel>> immutable = new LinkedHashMap<>();
        merged.forEach((inventoryId, entries) -> immutable.put(inventoryId, List.copyOf(entries)));
        return new MergeResult(Map.copyOf(immutable), Set.copyOf(changedInventoryIds));
    }

    private static void mergeGoldCurrency(
        @NotNull UUID accountId,
        @NotNull UUID currencyInventoryId,
        @NotNull List<InventoryEntryModel> baseline,
        @NotNull List<InventoryEntryModel> current,
        @NotNull List<InventoryEntryModel> authoritative,
        @NotNull Map<UUID, List<InventoryEntryModel>> merged,
        @NotNull Map<UUID, InventoryModel> ownedInventories,
        @NotNull Set<UUID> changedInventoryIds
    ) {
        if (!ownedInventories.containsKey(currencyInventoryId)) {
            throw new IllegalStateException("Currency inventory is unavailable for account " + accountId);
        }
        List<InventoryEntryModel> validAuthoritative = authoritative.stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> entry.getInventoryId().equals(currencyInventoryId))
            .toList();
        long baselineValue = goldValue(baseline);
        long currentValue = goldValue(current);
        long authoritativeValue = goldValue(validAuthoritative);
        long mergedValue;
        try {
            mergedValue = Math.addExact(
                authoritativeValue,
                Math.subtractExact(currentValue, baselineValue)
            );
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Gold three-way merge overflow for account " + accountId, overflow);
        }
        if (mergedValue < 0L) {
            throw new IllegalStateException("Gold three-way merge became negative for account " + accountId);
        }

        List<InventoryEntryModel> currentRows = merged.computeIfAbsent(
            currencyInventoryId,
            ignored -> new ArrayList<>()
        );
        List<InventoryEntryModel> retainedNonGold = currentRows.stream()
            .filter(entry -> !isGoldEntry(entry))
            .sorted(entryOrder())
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Map<String, InventoryEntryModel> authoritativeByDenomination = firstGoldRowsByItemId(
            validAuthoritative
        );
        Set<UUID> baselineIds = new HashSet<>();
        baseline.forEach(entry -> baselineIds.add(entry.getInventoryEntryId()));
        Map<String, InventoryEntryModel> localNewByDenomination = new HashMap<>();
        for (InventoryEntryModel entry : current) {
            if (isGoldEntry(entry)
                && !baselineIds.contains(entry.getInventoryEntryId())
                && entry.getItemId() != null) {
                localNewByDenomination.putIfAbsent(normalizeGoldItemId(entry.getItemId()), entry);
            }
        }

        int nextSlot = NormalInventoryLayout.DB_SLOT_START;
        List<InventoryEntryModel> rebuilt = new ArrayList<>();
        for (InventoryEntryModel entry : retainedNonGold) {
            rebuilt.add(withSlot(entry, nextSlot++, accountId));
        }
        for (Map.Entry<GoldDenomination, Long> amount : GoldCurrencyCalculator.decompose(mergedValue).entrySet()) {
            String itemId = amount.getKey().itemId();
            InventoryEntryModel base = authoritativeByDenomination.get(itemId);
            if (base == null) {
                base = localNewByDenomination.get(itemId);
            }
            InventoryEntryModel rebuiltEntry = base == null
                ? newCurrencyEntry(currencyInventoryId, nextSlot, itemId, amount.getValue(), accountId)
                : withCurrencyValue(base, currencyInventoryId, nextSlot, itemId, amount.getValue(), accountId);
            rebuilt.add(rebuiltEntry);
            nextSlot++;
        }
        if (!rebuilt.equals(currentRows)) {
            merged.put(currencyInventoryId, rebuilt);
            changedInventoryIds.add(currencyInventoryId);
        }
    }

    private static void applyApiDeltaToRelocatedEntries(
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventory,
        @NotNull InventoryEntryModel baseline,
        @Nullable InventoryEntryModel authoritative,
        long apiDelta,
        @NotNull UUID accountId,
        @NotNull Map<UUID, InventoryModel> ownedInventories,
        @NotNull Set<UUID> changedInventoryIds
    ) {
        if (apiDelta == 0L) {
            return;
        }
        if (apiDelta > 0L) {
            InventoryEntryModel source = authoritative == null ? baseline : authoritative;
            InventoryEntryModel added = asNewEntry(source, apiDelta, accountId);
            addEntry(entriesByInventory, added, ownedInventories, changedInventoryIds);
            return;
        }

        long remaining;
        try {
            remaining = Math.negateExact(apiDelta);
        } catch (ArithmeticException overflow) {
            throw inconsistent(baseline.getInventoryEntryId(), "API consumption overflow", overflow);
        }
        List<LocatedEntry> candidates = new ArrayList<>();
        entriesByInventory.forEach((inventoryId, entries) -> {
            for (InventoryEntryModel entry : entries) {
                if (sameLogicalItem(entry, baseline)) {
                    candidates.add(new LocatedEntry(inventoryId, entry));
                }
            }
        });
        candidates.sort(Comparator
            .comparing((LocatedEntry located) ->
                !located.entry().getInventoryEntryId().equals(baseline.getInventoryEntryId()))
            .thenComparing(located -> located.entry().getCreatedAt())
            .thenComparing(located -> located.entry().getInventoryEntryId()));
        for (LocatedEntry located : candidates) {
            if (remaining == 0L) {
                break;
            }
            List<InventoryEntryModel> rows = entriesByInventory.get(located.inventoryId());
            int index = findEntryIndex(rows, located.entry().getInventoryEntryId());
            if (index < 0) {
                continue;
            }
            InventoryEntryModel current = rows.get(index);
            long consumed = Math.min(current.getQuantity(), remaining);
            remaining -= consumed;
            long nextQuantity = current.getQuantity() - consumed;
            if (nextQuantity == 0L) {
                rows.remove(index);
            } else {
                rows.set(index, withQuantity(current, nextQuantity, accountId));
            }
            changedInventoryIds.add(located.inventoryId());
        }
        if (remaining != 0L) {
            throw inconsistent(
                baseline.getInventoryEntryId(),
                "API consumption exceeds relocated local quantity by " + remaining,
                null
            );
        }
    }

    private static void reconcileAffectedEntryIdentity(
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventory,
        @NotNull UUID affectedEntryId,
        @Nullable InventoryEntryModel authoritative,
        @NotNull UUID accountId,
        @NotNull Set<UUID> changedInventoryIds
    ) {
        for (Map.Entry<UUID, List<InventoryEntryModel>> inventory : entriesByInventory.entrySet()) {
            int index = findEntryIndex(inventory.getValue(), affectedEntryId);
            if (index < 0) {
                continue;
            }
            InventoryEntryModel current = inventory.getValue().get(index);
            InventoryEntryModel reconciled = authoritative == null
                ? asNewEntry(current, current.getQuantity(), accountId)
                : withAuthoritativeIdentityAndLocalPlacement(
                    authoritative,
                    current,
                    current.getQuantity(),
                    accountId
                );
            inventory.getValue().set(index, reconciled);
            changedInventoryIds.add(inventory.getKey());
            return;
        }
    }

    private static int findEntryIndex(
        @NotNull List<InventoryEntryModel> entries,
        @NotNull UUID entryId
    ) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).getInventoryEntryId().equals(entryId)) {
                return index;
            }
        }
        return -1;
    }

    private static @NotNull Map<String, InventoryEntryModel> firstGoldRowsByItemId(
        @NotNull List<InventoryEntryModel> entries
    ) {
        Map<String, InventoryEntryModel> result = new HashMap<>();
        entries.stream().sorted(entryOrder()).forEach(entry -> {
            if (entry.getItemId() != null && isGoldEntry(entry)) {
                result.putIfAbsent(normalizeGoldItemId(entry.getItemId()), entry);
            }
        });
        return result;
    }

    private static long goldValue(@NotNull List<InventoryEntryModel> entries) {
        long total = 0L;
        for (InventoryEntryModel entry : entries) {
            if (entry.isDeleted() || !isGoldEntry(entry)) {
                continue;
            }
            if (entry.getQuantity() < 0L) {
                throw new IllegalStateException("Negative gold quantity: " + entry.getInventoryEntryId());
            }
            GoldDenomination denomination = GoldDenomination.findByItemId(entry.getItemId());
            long unitValue = denomination == null ? 1L : denomination.goldValue();
            try {
                total = Math.addExact(total, Math.multiplyExact(entry.getQuantity(), unitValue));
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("Gold value overflow: " + entry.getInventoryEntryId(), overflow);
            }
        }
        return total;
    }

    private static @NotNull String normalizeGoldItemId(@NotNull String itemId) {
        GoldDenomination denomination = GoldDenomination.findByItemId(itemId);
        if (denomination != null) {
            return denomination.itemId();
        }
        return ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(itemId.trim())
            ? GoldDenomination.GOLD.itemId()
            : itemId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isGoldEntry(@Nullable InventoryEntryModel entry) {
        if (entry == null || entry.getItemId() == null) {
            return false;
        }
        return GoldDenomination.findByItemId(entry.getItemId()) != null
            || ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(entry.getItemId().trim());
    }

    private static @NotNull Map<UUID, InventoryEntryModel> indexEntries(
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventory
    ) {
        Map<UUID, InventoryEntryModel> result = new HashMap<>();
        for (List<InventoryEntryModel> entries : entriesByInventory.values()) {
            for (InventoryEntryModel entry : entries) {
                if (!entry.isDeleted()) {
                    result.put(entry.getInventoryEntryId(), entry);
                }
            }
        }
        return result;
    }

    private static @Nullable InventoryEntryModel validateAuthoritative(
        @Nullable InventoryEntryModel authoritative,
        @NotNull Map<UUID, InventoryModel> ownedInventories
    ) {
        if (authoritative == null || authoritative.isDeleted()) {
            return null;
        }
        return ownedInventories.containsKey(authoritative.getInventoryId()) ? authoritative : null;
    }

    private static long quantity(@Nullable InventoryEntryModel entry) {
        return entry == null || entry.isDeleted() ? 0L : entry.getQuantity();
    }

    private static boolean hasLocalPlacementChange(
        @Nullable InventoryEntryModel baseline,
        @Nullable InventoryEntryModel current
    ) {
        if (current == null) {
            return false;
        }
        return baseline == null
            || !baseline.getInventoryId().equals(current.getInventoryId())
            || !Objects.equals(baseline.getSlotIndex(), current.getSlotIndex())
            || !Objects.equals(baseline.getMetadataJson(), current.getMetadataJson());
    }

    private static boolean sameLogicalItem(
        @NotNull InventoryEntryModel left,
        @NotNull InventoryEntryModel right
    ) {
        return left.getItemCategory().equalsIgnoreCase(right.getItemCategory())
            && equalsIgnoreCase(left.getItemId(), right.getItemId())
            && equalsIgnoreCase(left.getInstanceType(), right.getInstanceType())
            && Objects.equals(left.getInstanceId(), right.getInstanceId());
    }

    private static boolean equalsIgnoreCase(@Nullable String left, @Nullable String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private static void removeEntry(
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventory,
        @NotNull UUID entryId,
        @NotNull Set<UUID> changedInventoryIds
    ) {
        entriesByInventory.forEach((inventoryId, entries) -> {
            if (entries.removeIf(entry -> entry.getInventoryEntryId().equals(entryId))) {
                changedInventoryIds.add(inventoryId);
            }
        });
    }

    private static void addEntry(
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventory,
        @NotNull InventoryEntryModel entry,
        @NotNull Map<UUID, InventoryModel> ownedInventories,
        @NotNull Set<UUID> changedInventoryIds
    ) {
        if (!ownedInventories.containsKey(entry.getInventoryId())) {
            throw new IllegalStateException("Merged entry targets unavailable inventory " + entry.getInventoryId());
        }
        entriesByInventory.computeIfAbsent(entry.getInventoryId(), ignored -> new ArrayList<>()).add(entry);
        changedInventoryIds.add(entry.getInventoryId());
    }

    private static @NotNull InventoryEntryModel withAuthoritativeIdentityAndLocalPlacement(
        @NotNull InventoryEntryModel authoritative,
        @NotNull InventoryEntryModel placement,
        long quantity,
        @NotNull UUID actor
    ) {
        return new InventoryEntryModel(
            authoritative.getInventoryEntryId(),
            placement.getInventoryId(),
            placement.getSlotIndex(),
            authoritative.getItemCategory(),
            authoritative.getItemId(),
            authoritative.getInstanceType(),
            authoritative.getInstanceId(),
            quantity,
            placement.getMetadataJson(),
            authoritative.getCreatedAt(),
            authoritative.getUpdatedAt(),
            authoritative.getCreatedBy(),
            actor,
            false
        );
    }

    private static @NotNull InventoryEntryModel withQuantity(
        @NotNull InventoryEntryModel entry,
        long quantity,
        @NotNull UUID actor
    ) {
        return new InventoryEntryModel(
            entry.getInventoryEntryId(), entry.getInventoryId(), entry.getSlotIndex(),
            entry.getItemCategory(), entry.getItemId(), entry.getInstanceType(), entry.getInstanceId(),
            quantity, entry.getMetadataJson(), entry.getCreatedAt(), entry.getUpdatedAt(),
            entry.getCreatedBy(), actor, false
        );
    }

    private static @NotNull InventoryEntryModel withSlot(
        @NotNull InventoryEntryModel entry,
        int slot,
        @NotNull UUID actor
    ) {
        if (Objects.equals(entry.getSlotIndex(), slot)) {
            return entry;
        }
        return new InventoryEntryModel(
            entry.getInventoryEntryId(), entry.getInventoryId(), slot,
            entry.getItemCategory(), entry.getItemId(), entry.getInstanceType(), entry.getInstanceId(),
            entry.getQuantity(), entry.getMetadataJson(), entry.getCreatedAt(), entry.getUpdatedAt(),
            entry.getCreatedBy(), actor, false
        );
    }

    private static @NotNull InventoryEntryModel asNewEntry(
        @NotNull InventoryEntryModel source,
        long quantity,
        @NotNull UUID actor
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(), source.getInventoryId(), source.getSlotIndex(),
            source.getItemCategory(), source.getItemId(), source.getInstanceType(), source.getInstanceId(),
            quantity, source.getMetadataJson(), now, now, actor, actor, false
        );
    }

    private static @NotNull InventoryEntryModel newCurrencyEntry(
        @NotNull UUID inventoryId,
        int slot,
        @NotNull String itemId,
        long quantity,
        @NotNull UUID actor
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, slot, ItemCategory.CURRENCY.getApiValue(), itemId,
            null, null, quantity, null, now, now, actor, actor, false
        );
    }

    private static @NotNull InventoryEntryModel withCurrencyValue(
        @NotNull InventoryEntryModel base,
        @NotNull UUID inventoryId,
        int slot,
        @NotNull String itemId,
        long quantity,
        @NotNull UUID actor
    ) {
        return new InventoryEntryModel(
            base.getInventoryEntryId(), inventoryId, slot, ItemCategory.CURRENCY.getApiValue(), itemId,
            null, null, quantity, base.getMetadataJson(), base.getCreatedAt(), base.getUpdatedAt(),
            base.getCreatedBy(), actor, false
        );
    }

    private static @NotNull Comparator<InventoryEntryModel> entryOrder() {
        return Comparator.<InventoryEntryModel, Integer>comparing(
            entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
        ).thenComparing(InventoryEntryModel::getCreatedAt)
            .thenComparing(InventoryEntryModel::getInventoryEntryId);
    }

    private static @NotNull IllegalStateException inconsistent(
        @NotNull UUID entryId,
        @NotNull String reason,
        @Nullable Throwable cause
    ) {
        String message = "Inventory three-way merge is inconsistent for entry " + entryId + ": " + reason;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    record MergeResult(
        @NotNull Map<UUID, List<InventoryEntryModel>> entriesByInventoryId,
        @NotNull Set<UUID> changedInventoryIds
    ) {
    }

    private record LocatedEntry(
        @NotNull UUID inventoryId,
        @NotNull InventoryEntryModel entry
    ) {
    }
}
