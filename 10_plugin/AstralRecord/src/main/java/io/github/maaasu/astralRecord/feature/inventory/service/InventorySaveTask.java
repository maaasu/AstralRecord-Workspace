package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTask;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー個別の save 契機で、対象プレイヤーの最新インベントリ状態を API へ反映する保存タスク。
 * <p>
 * 状態は既に {@link PlayerInventoryState} に集約されているため、本タスクは
 * Bukkit 側の最新スナップショットを state へ取り込んだ後、{@link InventoryPersistence} を 1 回呼ぶだけです。
 * オートセーブは {@link InventoryAutoSaveTask} が担当し、本タスクは LOGOUT / PLUGIN_DISABLE / MANUAL を受け持ちます。
 */
public class InventorySaveTask implements PlayerSaveTask {

    private final InventoryService inventoryService;
    private final PlayerInventoryStateRegistry stateRegistry;
    private final InventoryPersistence persistence;

    public InventorySaveTask(
        @NotNull InventoryService inventoryService,
        @NotNull PlayerInventoryStateRegistry stateRegistry,
        @NotNull InventoryPersistence persistence
    ) {
        this.inventoryService = inventoryService;
        this.stateRegistry = stateRegistry;
        this.persistence = persistence;
    }

    @Override
    public @NotNull String getTaskName() {
        return "inventory";
    }

    @Override
    public void prepare(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) {
        if (isToolInventoryMode(player.getAccount().getMode())) {
            inventoryService.saveToolInventorySnapshot(player);
        } else if (player.getAccount().getMode().shouldReflectInventoryToGui()) {
            inventoryService.saveEquipSlotSnapshot(player);
            inventoryService.saveHotbarSnapshot(player);
            inventoryService.saveAccessorySlotSnapshot(player);
            inventoryService.syncCurrentEquipmentState(player);
        }
    }

    @Override
    public void save(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) {
        PlayerInventoryState state = stateRegistry.get(player.getAccount().getUuid());
        if (state == null) {
            return;
        }

        persistence.save(state, mapTrigger(trigger));
    }

    private boolean isToolInventoryMode(@NotNull AccountMode mode) {
        return mode == AccountMode.ADMIN;
    }

    private @NotNull InventoryPersistence.SaveTrigger mapTrigger(@NotNull PlayerSaveTrigger trigger) {
        return switch (trigger) {
            case LOGOUT -> InventoryPersistence.SaveTrigger.LOGOUT;
            case PLUGIN_DISABLE -> InventoryPersistence.SaveTrigger.PLUGIN_DISABLE;
            case AUTO -> InventoryPersistence.SaveTrigger.AUTO;
            case MANUAL -> InventoryPersistence.SaveTrigger.AUTO;
        };
    }
}
