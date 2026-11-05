package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.model.DamageNumberSize;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingGuiSaveService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤー設定 GUI のイベント処理です。
 */
public final class PlayerSettingGuiEventHandler extends AbstractEventHandler {
    private static final int SUPER_MODE_TOGGLE_CLICK_COUNT = 5;

    private final PlayerSettingGui gui;
    private final PlayerSettingService playerSettingService;
    private final PlayerSettingGuiSaveService guiSaveService;
    private final InventoryService inventoryService;
    private final ConcurrentHashMap<UUID, Integer> secretClickCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, EnumMap<PlayerSettingKey, Object>> draftValues = new ConcurrentHashMap<>();

    /**
     * プレイヤー設定 GUI の操作と保存後表示同期を行うハンドラを初期化します。
     *
     * @param gui プレイヤー設定 GUI
     * @param playerSettingService プレイヤー設定サービス
     * @param guiSaveService GUI の設定保存サービス
     * @param inventoryService hotbar shortcut 用 inventory サービス
     */
    public PlayerSettingGuiEventHandler(
        @NotNull PlayerSettingGui gui,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull PlayerSettingGuiSaveService guiSaveService,
        @NotNull InventoryService inventoryService
    ) {
        this.gui = gui;
        this.playerSettingService = playerSettingService;
        this.guiSaveService = guiSaveService;
        this.inventoryService = inventoryService;
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
        UUID userId = astPlayer.getUser().getUuid();
        guiSaveService.save(player, userId, playerSettingService.captureSessionToken(userId), new EnumMap<>(pending));
    }

    /**
     * クリック後の下書き値を求めます。選択式の値は末尾から先頭へ戻ります。
     *
     * @param key クリックされた設定キー
     * @param currentValue キーに対応する型の現在値
     * @return 次の値。GUIで切替対象外のキーなら現在値
     */
    private @NotNull Object nextValue(@NotNull PlayerSettingKey key, @NotNull Object currentValue) {
        if (key.isBooleanValue()) {
            return !((Boolean) currentValue);
        }
        if (key == PlayerSettingKey.DAMAGE_NUMBER_SIZE) {
            DamageNumberSize[] values = DamageNumberSize.values();
            DamageNumberSize current = (DamageNumberSize) currentValue;
            return values[(current.ordinal() + 1) % values.length];
        }
        if (key.isParticleDensityValue()) {
            ParticleDensity[] values = ParticleDensity.values();
            ParticleDensity current = (ParticleDensity) currentValue;
            int nextIndex = (current.ordinal() + 1) % values.length;
            return values[nextIndex];
        }
        return currentValue;
    }

}
