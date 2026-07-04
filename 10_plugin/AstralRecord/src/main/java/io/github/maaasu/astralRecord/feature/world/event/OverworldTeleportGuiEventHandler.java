package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/**
 * オーバーワールド転送 GUI の操作を処理します。
 */
public final class OverworldTeleportGuiEventHandler extends AbstractEventHandler {
    private static final int GUI_DARKNESS_DURATION_TICKS = 20 * 60 * 10;

    private final Plugin plugin;
    private final OverworldTeleportGui gui;
    private final OverworldTeleportService teleportService;
    private final Set<UUID> guiDarknessPlayers = new HashSet<>();
    private final Map<UUID, PotionEffect> previousDarknessEffects = new HashMap<>();

    public OverworldTeleportGuiEventHandler(
            @NotNull Plugin plugin,
            @NotNull OverworldTeleportGui gui,
            @NotNull OverworldTeleportService teleportService
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.teleportService = teleportService;
    }

    /**
     * GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @return GUI を開いた場合は {@code true}
     */
    public boolean open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }

        List<WorldMasterData> destinations = teleportService.listDestinations();
        if (destinations.isEmpty()) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5769);
            return false;
        }

        gui.open(player, destinations, 0);
        applyGuiDarkness(player);
        return true;
    }

    /**
     * この GUI を表示中かを返します。
     *
     * @param player 対象プレイヤー
     * @return 表示中なら {@code true}
     */
    public boolean isOpen(@NotNull Player player) {
        return gui.isInventory(player.getOpenInventory().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            if (!gui.isInventory(topInventory)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleTopClick(player, topInventory, event.getRawSlot());
        }, LogId.E_5755, event.getWhoClicked().getName(), "click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5755, event.getWhoClicked().getName(), "drag");
    }

    /**
     * GUI を閉じたプレイヤーの暗黒エフェクト解除を次 tick で判定します。
     *
     * @param event インベントリクローズイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getInventory())) {
                return;
            }
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> clearGuiDarknessIfClosed(player));
        }, LogId.E_5755, event.getPlayer().getName(), "close");
    }

    /**
     * 切断したプレイヤーへ付与していた GUI 用暗黒エフェクトを解除します。
     *
     * @param event プレイヤー切断イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> clearGuiDarkness(event.getPlayer()), LogId.E_5755, event.getPlayer().getName(), "quit");
    }

    private void handleTopClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        OverworldTeleportGui.Holder holder = gui.holder(inventory);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (holder == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }

        List<WorldMasterData> destinations = teleportService.listDestinations();
        if (rawSlot == OverworldTeleportGui.PREVIOUS_SLOT && gui.hasPreviousPage(holder.pageIndex())) {
            GuiSound.SELECT.play(player);
            gui.open(player, destinations, holder.pageIndex() - 1);
            return;
        }
        if (rawSlot == OverworldTeleportGui.NEXT_SLOT && gui.hasNextPage(holder.pageIndex(), destinations.size())) {
            GuiSound.SELECT.play(player);
            gui.open(player, destinations, holder.pageIndex() + 1);
            return;
        }
        if (rawSlot < 0 || rawSlot >= OverworldTeleportGui.CONTENT_SLOT_COUNT || rawSlot >= holder.visibleWorldIds().size()) {
            GuiSound.DENY.play(player);
            return;
        }

        GuiSound.SELECT.play(player);
        teleportService.teleportToDestination(player, astPlayer, holder.visibleWorldIds().get(rawSlot));
    }

    private void applyGuiDarkness(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (guiDarknessPlayers.add(playerId)) {
            previousDarknessEffects.put(playerId, player.getPotionEffect(PotionEffectType.DARKNESS));
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS,
                GUI_DARKNESS_DURATION_TICKS,
                0,
                false,
                false,
                false
        ));
    }

    private void clearGuiDarknessIfClosed(@NotNull Player player) {
        if (!player.isOnline() || isOpen(player)) {
            return;
        }
        clearGuiDarkness(player);
    }

    private void clearGuiDarkness(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (!guiDarknessPlayers.remove(playerId)) {
            return;
        }

        PotionEffect previousEffect = previousDarknessEffects.remove(playerId);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        if (previousEffect != null) {
            player.addPotionEffect(previousEffect);
        }
    }
}
