package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * メニュー系 GUI の切替時に必要なインベントリ復元を管理するサービスです。
 */
public final class MenuGuiTransitionService {
    private final AstralRecord plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final Set<UUID> suppressPlayerInventoryRestoreOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> playerInventoryDummyApplied = ConcurrentHashMap.newKeySet();

    /**
     * GUI 切替制御サービスを初期化します。
     *
     * @param plugin プラグイン本体
     * @param menuView メニュー GUI 表示
     * @param inventoryService インベントリサービス
     */
    public MenuGuiTransitionService(
        @NotNull AstralRecord plugin,
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.menuView = menuView;
        this.inventoryService = inventoryService;
    }

    /**
     * 指定 GUI を開く前提で、プレイヤーインベントリのダミー表示を必要に応じて適用します。
     *
     * @param player 対象プレイヤー
     * @param openedInventory 開かれたインベントリ
     * @param viewType インベントリ種別
     */
    public void applyPlayerInventoryDummy(
        @NotNull Player player,
        @NotNull Inventory openedInventory,
        @NotNull org.bukkit.event.inventory.InventoryType viewType
    ) {
        if (!shouldFillPlayerInventoryDummy(player, openedInventory, viewType)) {
            return;
        }
        playerInventoryDummyApplied.add(player.getUniqueId());
        fillPlayerInventoryDummy(player);
    }

    /**
     * 確認ダイアログ用のダミーアイテムでプレイヤーインベントリを埋めます。
     *
     * @param player 対象プレイヤー
     */
    public void fillPlayerInventoryDummy(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        Material dummyMaterial = playerInventoryDummyMaterial(astPlayer != null && astPlayer.isBedrock());
        ItemStack dummy = new ItemStack(dummyMaterial);
        ItemMeta meta = dummy.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            dummy.setItemMeta(meta);
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, dummy.clone());
        }
        player.updateInventory();
    }

    /**
     * プレイヤーインベントリを隠すためのダミー素材を版ごとに選択します。
     *
     * @param bedrock BE プレイヤーの場合 true
     * @return 使用するダミー素材
     */
    static @NotNull Material playerInventoryDummyMaterial(boolean bedrock) {
        return bedrock ? Material.GLOW_LICHEN : Material.GRAY_STAINED_GLASS_PANE;
    }

    /**
     * ダミー表示が適用されていたかを取得しつつ、適用状態を消費します。
     *
     * @param player 対象プレイヤー
     * @return ダミー表示が適用されていた場合は true
     */
    public boolean consumePlayerInventoryDummyApplied(@NotNull Player player) {
        return playerInventoryDummyApplied.remove(player.getUniqueId());
    }

    /**
     * GUI 切替のために抑制していたインベントリ復元を消費します。
     *
     * @param player 対象プレイヤー
     * @return インベントリ復元抑制が存在した場合は true
     */
    public boolean consumeSuppressedPlayerInventoryRestore(@NotNull Player player) {
        return suppressPlayerInventoryRestoreOnClose.remove(player.getUniqueId());
    }

    /**
     * プレイヤー GUI 表示を現在の仮想インベントリ状態へ復元します。
     *
     * @param player 対象プレイヤー
     */
    public void restorePlayerInventory(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.applyInventoriesToGui(astPlayer);
            player.updateInventory();
        }
    }

    /**
     * プレイヤーインベントリ復元を伴わずに GUI を切り替えます。
     *
     * @param player 対象プレイヤー
     * @param opener GUI を開く処理
     */
    public void switchGuiWithoutInventoryReload(@NotNull Player player, @NotNull Runnable opener) {
        suppressPlayerInventoryRestoreForGuiSwitch(player);
        opener.run();
    }

    /**
     * プレイヤーインベントリ復元を行いながら GUI を切り替えます。
     *
     * @param player 対象プレイヤー
     * @param opener GUI を開く処理
     */
    public void switchGuiWithInventoryRestore(@NotNull Player player, @NotNull Runnable opener) {
        suppressPlayerInventoryRestoreForGuiSwitch(player);
        opener.run();
        restorePlayerInventory(player);
    }

    private boolean shouldFillPlayerInventoryDummy(
        @NotNull Player player,
        @NotNull Inventory openedInventory,
        @NotNull org.bukkit.event.inventory.InventoryType viewType
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return false;
        }
        if (viewType == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            return false;
        }

        MenuScreen menuScreen = menuView.getMenuScreen(openedInventory);
        if (menuScreen != null) {
            return false;
        }

        PlayerSettingGui playerSettingGui = plugin.getPlayerSettingGui();
        if (playerSettingGui != null && playerSettingGui.isInventory(openedInventory)) {
            return false;
        }

        var partyGui = plugin.getPartyGui();
        if (partyGui != null && partyGui.isInventory(openedInventory)) {
            return false;
        }
        var playerListGui = plugin.getPlayerListGui();
        if (playerListGui != null && playerListGui.isInventory(openedInventory)) {
            return false;
        }
        var playerDetailGui = plugin.getPlayerDetailGui();
        if (playerDetailGui != null && playerDetailGui.isInventory(openedInventory)) {
            return false;
        }
        var mailGuiEventHandler = plugin.getMailGuiEventHandler();
        if (mailGuiEventHandler != null && mailGuiEventHandler.isInventory(openedInventory)) {
            return false;
        }
        return false;
    }

    private void suppressPlayerInventoryRestoreForGuiSwitch(@NotNull Player player) {
        if (playerInventoryDummyApplied.contains(player.getUniqueId())) {
            suppressPlayerInventoryRestoreOnClose.add(player.getUniqueId());
        }
    }

}
