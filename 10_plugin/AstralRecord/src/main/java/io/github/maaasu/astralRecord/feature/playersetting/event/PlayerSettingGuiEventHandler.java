package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤー設定 GUI のイベント処理です。
 */
public final class PlayerSettingGuiEventHandler extends AbstractEventHandler {
    private static final int SUPER_MODE_TOGGLE_CLICK_COUNT = 5;

    private final PlayerSettingGui gui;
    private final PlayerSettingService playerSettingService;
    private final InventoryService inventoryService;
    private final MenuView menuView;
    private final ConcurrentHashMap<UUID, Integer> secretClickCounts = new ConcurrentHashMap<>();

    public PlayerSettingGuiEventHandler(
        @NotNull PlayerSettingGui gui,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull InventoryService inventoryService,
        @NotNull MenuView menuView
    ) {
        this.gui = gui;
        this.playerSettingService = playerSettingService;
        this.inventoryService = inventoryService;
        this.menuView = menuView;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
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

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot > 8) {
            return false;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            return false;
        }

        event.setCancelled(true);
        if (!inventoryService.getClickGuard().tryAcquire(
            astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT
        )) {
            return true;
        }

        boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
        if (handled) {
            if (slot == 4) {
                GuiSound.CLOSE.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
        } else {
            GuiSound.DENY.play(player);
        }
        return true;
    }

    private void handleClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == PlayerSettingGui.BACK_TO_MENU_SLOT) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }
        if (rawSlot == PlayerSettingGui.SUPER_MODE_SECRET_SLOT) {
            handleSuperModeSecretClick(player);
            return;
        }
        if (rawSlot == PlayerSettingGui.CLOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            player.closeInventory();
            return;
        }

        PlayerSettingKey key = gui.getKeyAtSlot(rawSlot);
        if (key == null) {
            secretClickCounts.remove(player.getUniqueId());
            GuiSound.DENY.play(player);
            return;
        }

        Object currentValue = playerSettingService.getPlayerSetting(player.getUniqueId(), key);
        Object nextValue = nextValue(key, currentValue);
        PlayerSettingService.UpdateResult result = playerSettingService.updatePlayerSetting(
            new PlayerSettingChangeRequest(player.getUniqueId(), key, nextValue, player.getUniqueId())
        );
        if (result.conflict()) {
            GuiSound.DENY.play(player);
            player.sendMessage(result.message());
            gui.open(player);
            return;
        }

        GuiSound.SELECT.play(player);
        player.sendMessage(PlayerMsgResource.format(
            PlayerSettingMsgId.P_5321.getId(),
            key.getDisplayNameJa(),
            key.formatValue(nextValue)
        ));
        gui.open(player);
    }

    private void handleSuperModeSecretClick(@NotNull Player player) {
        int count = secretClickCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (count < SUPER_MODE_TOGGLE_CLICK_COUNT) {
            GuiSound.SELECT.play(player);
            return;
        }

        secretClickCounts.remove(player.getUniqueId());
        PlayerSettingKey key = PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE;
        Object currentValue = playerSettingService.getPlayerSetting(player.getUniqueId(), key);
        Object nextValue = !(currentValue instanceof Boolean enabled && enabled);
        PlayerSettingService.UpdateResult result = playerSettingService.updatePlayerSetting(
            new PlayerSettingChangeRequest(player.getUniqueId(), key, nextValue, player.getUniqueId())
        );
        if (result.conflict()) {
            GuiSound.DENY.play(player);
            player.sendMessage(result.message());
            gui.open(player);
            return;
        }

        GuiSound.SELECT.play(player);
        player.sendMessage(PlayerMsgResource.format(
            PlayerSettingMsgId.P_5321.getId(),
            key.getDisplayNameJa(),
            key.formatValue(nextValue)
        ));
        gui.open(player);
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
}
