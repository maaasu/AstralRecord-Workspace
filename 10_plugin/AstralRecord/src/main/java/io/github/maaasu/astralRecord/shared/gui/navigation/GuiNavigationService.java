package io.github.maaasu.astralRecord.shared.gui.navigation;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiCloseSoundPolicy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * GUI を閉じない連続操作だけを対象に、プレイヤーごとの戻る履歴を管理します。
 */
public final class GuiNavigationService {
    private final AstralRecord plugin;

    /**
     * GUI 履歴サービスを生成します。
     *
     * @param plugin スケジューラを利用するプラグイン本体
     */
    public GuiNavigationService(@NotNull AstralRecord plugin) {
        this.plugin = plugin;
    }

    /**
     * 開かれた GUI を現在位置として記録し、戻り先がなければ戻るボタンを除去します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開かれた GUI
     */
    public void registerOpen(@NotNull Player player, @NotNull Inventory inventory) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (holder == null || astPlayer == null) {
            return;
        }

        GuiNavigationState state = astPlayer.getGuiNavigationState();
        if (!state.completeBack(inventory)) {
            Inventory current = state.getCurrentGui();
            boolean replaceCurrent = current != null
                && navigationId(current).equals(holder.getNavigationId());
            state.recordOpen(inventory, replaceCurrent);
        }
        updateBackButton(inventory, holder, state.getPreviousGui() != null);
    }

    /**
     * GUI close 後に別の管理 GUI が開かれなければ、セッション履歴を破棄します。
     *
     * @param player 対象プレイヤー
     * @param closedInventory 閉じられた GUI
     */
    public void scheduleSessionCloseCheck(@NotNull Player player, @NotNull Inventory closedInventory) {
        if (navigationHolder(closedInventory) == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                return;
            }
            Inventory openedInventory = player.getOpenInventory().getTopInventory();
            GuiNavigationState state = astPlayer.getGuiNavigationState();
            if (navigationHolder(openedInventory) != null && state.getCurrentGui() == openedInventory) {
                return;
            }
            state.clear();
        });
    }

    /**
     * 一つ前の GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @return 戻り先を開けた場合は {@code true}
     */
    public boolean openPrevious(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }
        Inventory previous = astPlayer.getGuiNavigationState().beginBack();
        if (previous == null) {
            return false;
        }
        GuiCloseSoundPolicy.suppressNextCloseSound(player);
        player.openInventory(previous);
        return true;
    }

    /**
     * GUI セッション内に戻り先が存在するか返します。
     *
     * @param player 対象プレイヤー
     * @return 戻り先が存在する場合は {@code true}
     */
    public boolean hasPrevious(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getGuiNavigationState().getPreviousGui() != null;
    }

    /**
     * 指定クリックが holder の戻るスロットか判定します。
     *
     * @param inventory クリック対象 GUI
     * @param rawSlot クリックされた raw slot
     * @return 戻るスロットなら {@code true}
     */
    public boolean isBackClick(@NotNull Inventory inventory, int rawSlot) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder != null && holder.getBackSlot() >= 0 && holder.getBackSlot() == rawSlot;
    }

    /**
     * 指定クリックが共通処理対象の戻るボタンか判定します。
     *
     * @param inventory クリック対象 GUI
     * @param rawSlot クリックされた raw slot
     * @return 共通処理対象の戻るボタンなら {@code true}
     */
    public boolean isDirectBackClick(@NotNull Inventory inventory, int rawSlot) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder != null
            && holder.isDirectBackNavigation()
            && isBackClick(inventory, rawSlot);
    }

    private void updateBackButton(
        @NotNull Inventory inventory,
        @NotNull GuiNavigationHolder holder,
        boolean hasPrevious
    ) {
        int backSlot = holder.getBackSlot();
        if (!hasPrevious && backSlot >= 0 && backSlot < inventory.getSize()) {
            inventory.setItem(backSlot, new org.bukkit.inventory.ItemStack(Material.AIR));
        }
    }

    private @NotNull String navigationId(@NotNull Inventory inventory) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder == null ? "" : holder.getNavigationId();
    }

    private GuiNavigationHolder navigationHolder(@NotNull Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof GuiNavigationHolder navigationHolder ? navigationHolder : null;
    }
}
