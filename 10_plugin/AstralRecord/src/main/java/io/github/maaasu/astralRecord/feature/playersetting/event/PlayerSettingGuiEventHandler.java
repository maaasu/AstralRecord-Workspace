package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤー設定 GUI のイベント処理です。
 */
public final class PlayerSettingGuiEventHandler extends AbstractEventHandler {
    private static final int SUPER_MODE_TOGGLE_CLICK_COUNT = 5;

    private final PlayerSettingGui gui;
    private final PlayerSettingService playerSettingService;
    private final InventoryService inventoryService;
    private final ItemStackPacketAdapter itemStackPacketAdapter;
    private final ConcurrentHashMap<UUID, Integer> secretClickCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, EnumMap<PlayerSettingKey, Object>> draftValues = new ConcurrentHashMap<>();

    /**
     * プレイヤー設定 GUI の操作と保存後表示同期を行うハンドラを初期化します。
     *
     * @param gui プレイヤー設定 GUI
     * @param playerSettingService プレイヤー設定サービス
     * @param inventoryService hotbar shortcut 用 inventory サービス
     * @param itemStackPacketAdapter 装備表示を再同期するパケットアダプタ
     */
    public PlayerSettingGuiEventHandler(
        @NotNull PlayerSettingGui gui,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemStackPacketAdapter itemStackPacketAdapter
    ) {
        this.gui = gui;
        this.playerSettingService = playerSettingService;
        this.inventoryService = inventoryService;
        this.itemStackPacketAdapter = itemStackPacketAdapter;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            if (!(event.getWhoClicked() instanceof Player player)) {
                event.setCancelled(true);
                return;
            }
            if (handleHotbarShortcutClick(event, player)) {
                return;
            }
            event.setCancelled(true);
            handleClick(player, event.getRawSlot());
        }, LogId.E_5313, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5313, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getInventory())) {
                return;
            }
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            persistDraftChanges(player);
            secretClickCounts.remove(player.getUniqueId());
            draftValues.remove(player.getUniqueId());
        }, LogId.E_5313, event.getPlayer().getName());
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
    }

    private void handleClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == PlayerSettingGui.BACK_TO_MENU_SLOT) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.SELECT.play(player);
            AstralRecord.getInstance().getGuiNavigationService().openPrevious(player);
            return;
        }
        if (rawSlot == PlayerSettingGui.SUPER_MODE_SECRET_SLOT) {
            handleSuperModeSecretClick(player);
            return;
        }
        PlayerSettingKey key = gui.getKeyAtSlot(rawSlot);
        if (key == null) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.DENY.play(player);
            return;
        }

        Object currentValue = currentValue(player, key);
        Object nextValue = nextValue(key, currentValue);
        draftValues
            .computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(PlayerSettingKey.class))
            .put(key, nextValue);
        GuiSound.TOGGLE.play(player);
        refreshInventory(player);
    }

    private void handleSuperModeSecretClick(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.hasAdminPermission()) {
            secretClickCounts.remove(player.getUniqueId());
            Map<PlayerSettingKey, Object> draft = draftValues.get(player.getUniqueId());
            if (draft != null) {
                draft.remove(PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE);
            }
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5061);
            GuiSound.DENY.play(player);
            return;
        }
        int count = secretClickCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (count < SUPER_MODE_TOGGLE_CLICK_COUNT) {
            GuiSound.SELECT.play(player);
            return;
        }

        secretClickCounts.remove(player.getUniqueId());
        PlayerSettingKey key = PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE;
        Object currentValue = currentValue(player, key);
        Object nextValue = !(currentValue instanceof Boolean enabled && enabled);
        draftValues
            .computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(PlayerSettingKey.class))
            .put(key, nextValue);
        GuiSound.TOGGLE.play(player);
        refreshInventory(player);
    }

    private @NotNull Object currentValue(@NotNull Player player, @NotNull PlayerSettingKey key) {
        Map<PlayerSettingKey, Object> values = draftValues.get(player.getUniqueId());
        if (values != null && values.containsKey(key)) {
            return values.get(key);
        }
        return playerSettingService.getPlayerSetting(player.getUniqueId(), key);
    }

    private void refreshInventory(@NotNull Player player) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        UUID userId = gui.getUserId(inventory);
        if (userId == null) {
            return;
        }
        gui.refresh(inventory, userId, draftValues.get(player.getUniqueId()));
        player.updateInventory();
    }

    private void persistDraftChanges(@NotNull Player player) {
        Map<PlayerSettingKey, Object> pending = draftValues.get(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        EnumMap<PlayerSettingKey, Object> pendingCopy = new EnumMap<>(PlayerSettingKey.class);
        pendingCopy.putAll(pending);
        if (!astPlayer.hasAdminPermission()) {
            pendingCopy.remove(PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE);
        }
        if (pendingCopy.isEmpty()) {
            return;
        }

        UUID userId = astPlayer.getUser().getUuid();
        long sessionToken = playerSettingService.captureSessionToken(userId);
        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> persistChanges(userId, sessionToken, pendingCopy))
            .whenComplete((results, throwable) -> AsyncTaskUtil.runSync(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (playerSettingService.captureSessionToken(userId) != sessionToken
                    || plugin.getServer().getPlayer(userId) != player) {
                    return;
                }
                if (throwable != null) {
                    Logger.log(LogId.E_5312, throwable, userId);
                    PlayerMessageService.getInstance().sendRaw(
                        player,
                        PlayerMsgResource.getMessage(PlayerSettingMsgId.P_5326.getId())
                    );
                    return;
                }
                for (PersistResult persisted : results) {
                    if (persisted.result().staleSession()) {
                        continue;
                    }
                    if (persisted.result().conflict()) {
                        PlayerMessageService.getInstance().sendRaw(player, persisted.result().message());
                        continue;
                    }
                    PlayerMessageService.getInstance().sendRaw(player, PlayerMsgResource.format(
                        PlayerSettingMsgId.P_5321.getId(),
                        persisted.key().getDisplayNameJa(),
                        persisted.key().formatValue(persisted.value())
                    ));
                }
                boolean equipmentSettingSynchronized = results.stream().anyMatch(persisted ->
                    (persisted.key() == PlayerSettingKey.ARMOR_DISPLAY
                        || persisted.key() == PlayerSettingKey.OFF_HAND_DISPLAY)
                        && !persisted.result().staleSession()
                );
                if (equipmentSettingSynchronized) {
                    itemStackPacketAdapter.refreshEquipmentView(player);
                }
                boolean actionRingHoldSelectSynchronized = results.stream().anyMatch(persisted ->
                    persisted.key() == PlayerSettingKey.ACTION_RING_HOLD_SELECT
                        && !persisted.result().staleSession()
                );
                if (actionRingHoldSelectSynchronized) {
                    player.updateInventory();
                }
            }));
    }

    private @NotNull List<PersistResult> persistChanges(
        @NotNull UUID userId,
        long sessionToken,
        @NotNull Map<PlayerSettingKey, Object> pending
    ) {
        List<PersistResult> results = new ArrayList<>();
        for (Map.Entry<PlayerSettingKey, Object> entry : pending.entrySet()) {
            PlayerSettingKey key = entry.getKey();
            Object nextValue = entry.getValue();
            Object currentValue = playerSettingService.getPlayerSetting(userId, key);
            if (currentValue.equals(nextValue)) {
                continue;
            }
            PlayerSettingService.UpdateResult result = playerSettingService.updatePlayerSetting(
                new PlayerSettingChangeRequest(userId, key, nextValue, userId),
                sessionToken
            );
            results.add(new PersistResult(key, nextValue, result));
            if (result.staleSession()) {
                break;
            }
        }
        return results;
    }

    private @NotNull Object nextValue(@NotNull PlayerSettingKey key, @NotNull Object currentValue) {
        if (key.isBooleanValue()) {
            return !((Boolean) currentValue);
        }
        if (key.isParticleDensityValue()) {
            ParticleDensity[] values = ParticleDensity.values();
            ParticleDensity current = (ParticleDensity) currentValue;
            int nextIndex = (current.ordinal() + 1) % values.length;
            return values[nextIndex];
        }
        return currentValue;
    }

    private record PersistResult(
        @NotNull PlayerSettingKey key,
        @NotNull Object value,
        @NotNull PlayerSettingService.UpdateResult result
    ) {
    }
}
