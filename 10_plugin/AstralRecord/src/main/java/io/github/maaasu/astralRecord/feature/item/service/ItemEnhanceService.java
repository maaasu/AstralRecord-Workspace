package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 管理者による所持装備の強化値変更を、読み込み済みの個体と保存境界で処理します。 */
public final class ItemEnhanceService {
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemChatShareService selectionService;

    /**
     * 強化値変更サービスを初期化します。
     *
     * @param itemService 装備マスタ・個体サービス
     * @param inventoryService 所持品の正本・保存サービス
     * @param selectionService showitem と同じ表示名・BAG番号の解決サービス
     */
    public ItemEnhanceService(
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemChatShareService selectionService
    ) {
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.selectionService = selectionService;
    }

    /**
     * 表示中の BAG から、強化定義と所有個体が有効な装備を取得します。
     * 上限到達済みの装備も減算・設定の対象として含み、API へは問い合わせません。
     *
     * @param player 対象プレイヤー。Bukkit メインスレッドで呼び出します
     * @return BAG スロット順の選択候補
     */
    public @NotNull List<Candidate> getCandidates(@NotNull AstPlayer player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return List.of();
        }
        UUID accountId = player.getAccount().getUuid();
        ItemStack[] contents = player.getBukkit().getInventory().getContents();
        return inventoryService.withPlayerStateLock(accountId, () -> {
            List<Candidate> candidates = new ArrayList<>();
            for (int bukkitSlot = 0; bukkitSlot < contents.length; bukkitSlot++) {
                var names = selectionService.getShareableItems(new ItemStack[] {contents[bukkitSlot]});
                if (names.isEmpty()) {
                    continue;
                }
                var name = names.getFirst();
                InventoryEntryModel entry = inventoryService.getDisplayedEntryAtBukkitSlot(player, bukkitSlot);
                if (!isEquipmentEntry(entry) || !Objects.equals(entry.getSlotIndex(), name.slotNumber())) {
                    continue;
                }
                String instanceId = ItemStackFactory.getEquipmentInstanceId(name.item());
                if (instanceId == null || !entry.getInstanceId().toString().equalsIgnoreCase(instanceId)) {
                    continue;
                }
                EquipmentInstance instance = itemService.findLoadedEquipmentInstanceById(instanceId);
                ItemModel model = resolveEnhanceableModel(accountId, instance);
                if (model == null || !model.getId().equalsIgnoreCase(ItemStackFactory.getAstralItemId(name.item()))) {
                    continue;
                }
                candidates.add(new Candidate(
                    entry.getInventoryEntryId(), entry.getInventoryId(), name.slotNumber(), name.displayName(),
                    instanceId, EquipmentEnhanceCalculator.maxLevel(model.getEquipment(), instance.getTranscendenceRank())
                ));
            }
            return List.copyOf(candidates);
        });
    }

    /**
     * BAG 番号付き表示名、または表示名だけから現在の強化対象を解決します。
     *
     * @param candidates 現在の所持品候補
     * @param selection コマンドに入力した装備指定
     * @return 一致した最初の装備。対象がない場合は null
     */
    public @Nullable Candidate findCandidate(@NotNull List<Candidate> candidates, @NotNull String selection) {
        String normalized = selection.strip();
        return candidates.stream()
            .filter(candidate -> candidate.commandSelection().equalsIgnoreCase(normalized)
                || candidate.displayName().equalsIgnoreCase(normalized))
            .findFirst().orElse(null);
    }

    /**
     * 装備指定と値指定を再検証し、強化値・耐久値をローカルで一体変更します。
     * 外部取引の確定待ちを拒否し、成功した変更だけ既存の非同期保存へ登録します。
     *
     * @param player 対象プレイヤー。Bukkit メインスレッドで呼び出します
     * @param selection BAG 番号付き表示名、または表示名
     * @param input 設定値、または符号付き差分
     * @return 確定した変更結果。所持品・個体・強化定義・入力が不正なら null
     * @throws io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator.ExternalOperationPendingException 外部操作中の場合
     * @throws IllegalStateException 所持品の正本が未ロードの場合
     */
    public @Nullable ChangeResult setLevel(
        @NotNull AstPlayer player, @NotNull String selection, @NotNull String input
    ) {
        Candidate candidate = findCandidate(getCandidates(player), selection);
        if (candidate == null) {
            return null;
        }
        UUID accountId = player.getAccount().getUuid();
        ChangeResult result = inventoryService.executeLocalPlayerMutation(accountId, () -> {
            InventoryEntryModel entry = inventoryService.findOwnedEntry(accountId, candidate.entryId());
            if (!isEquipmentEntry(entry) || !entry.getInventoryId().equals(candidate.inventoryId())
                || !Objects.equals(entry.getSlotIndex(), candidate.slotNumber())
                || !entry.getInstanceId().toString().equalsIgnoreCase(candidate.instanceId())) {
                return null;
            }
            EquipmentInstance current = itemService.findLoadedEquipmentInstanceById(candidate.instanceId());
            ItemModel model = resolveEnhanceableModel(accountId, current);
            if (model == null) {
                return null;
            }
            int maxLevel = EquipmentEnhanceCalculator.maxLevel(model.getEquipment(), current.getTranscendenceRank());
            Integer level = EquipmentEnhanceCalculator.resolveLevel(input, current.getEnhanceLevel(), maxLevel);
            if (level == null) {
                return null;
            }
            EquipmentInstance updated = current;
            if (level != current.getEnhanceLevel()) {
                updated = EquipmentEnhanceCalculator.withLevel(model.getEquipment(), current, level);
                if (itemService.applyLocalEquipmentInstance(updated) == null) {
                    return null;
                }
            }
            return new ChangeResult(candidate.displayName(), current.getEnhanceLevel(), maxLevel, updated);
        });
        if (result != null && result.previousLevel() != result.instance().getEnhanceLevel()) {
            inventoryService.queueLocalPlayerSave(accountId);
        }
        return result;
    }

    /**
     * 所有者と強化定義を照合し、現在の状態で強化値を変更できるマスタだけ返します。
     *
     * @param accountId 操作対象の所有アカウント
     * @param instance 読み込み済み装備個体。未取得なら null
     * @return 所有者と強化定義が有効なマスタ。条件外なら null
     */
    private @Nullable ItemModel resolveEnhanceableModel(
        @NotNull UUID accountId, @Nullable EquipmentInstance instance
    ) {
        if (instance == null || !instance.getAccountId().equalsIgnoreCase(accountId.toString())) {
            return null;
        }
        ItemModel model = itemService.findLoadedById(instance.getItemId());
        if (model == null || ItemCategory.fromApiValue(model.getCategory()) != ItemCategory.EQUIPMENT
            || model.getEquipment() == null || model.getEquipment().getSlot() == null
            || model.getEquipment().getEnhance() == null
            || EquipmentEnhanceCalculator.maxLevel(model.getEquipment(), instance.getTranscendenceRank()) <= 0) {
            return null;
        }
        return model;
    }

    /**
     * 正本 entry が削除されていない単一の装備個体かを判定します。
     *
     * @param entry 所持品の正本 entry。未取得なら null
     * @return カテゴリ・個体種別・数量・個体 ID が有効な装備なら true
     */
    private boolean isEquipmentEntry(@Nullable InventoryEntryModel entry) {
        return entry != null && !entry.isDeleted() && entry.getQuantity() == 1L && entry.getInstanceId() != null
            && ItemCategory.fromApiValue(entry.getItemCategory()) == ItemCategory.EQUIPMENT
            && InventoryInstanceType.fromCode(entry.getInstanceType()) == InventoryInstanceType.EQUIPMENT;
    }

    /**
     * 所持品の正本と対応する、強化対象の選択候補です。
     *
     * @param entryId 所持品 entry ID
     * @param inventoryId 所属 BAG ID
     * @param slotNumber BAG 論理スロット番号
     * @param displayName 装飾を除いた表示名
     * @param instanceId 装備個体 ID
     * @param maxLevel 現在状態の強化上限
     */
    public record Candidate(
        @NotNull UUID entryId, @NotNull UUID inventoryId, int slotNumber,
        @NotNull String displayName, @NotNull String instanceId, int maxLevel
    ) {
        /** @return showitem と同じ BAG 番号付き装備指定 */
        public @NotNull String commandSelection() {
            return "[" + slotNumber + "] " + displayName;
        }
    }

    /**
     * ローカルで確定した強化値変更結果です。
     *
     * @param displayName 変更前の装備表示名
     * @param previousLevel 変更前の強化値
     * @param maxLevel 現在状態の強化上限
     * @param instance 変更後の装備個体
     */
    public record ChangeResult(
        @NotNull String displayName, int previousLevel, int maxLevel, @NotNull EquipmentInstance instance
    ) {
    }
}
