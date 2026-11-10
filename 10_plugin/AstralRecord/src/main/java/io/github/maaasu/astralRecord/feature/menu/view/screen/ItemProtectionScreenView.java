package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.menu.MenuMsgId;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * アイテム保護の一覧と確認画面を描画します。
 */
public final class ItemProtectionScreenView {
    private static final Material MENU_ICON = Material.SHIELD;
    private static final int GUIDE_SLOT = 50;
    private static final int PREVIEW_SLOT = 13;

    private final PagedGuiView pagedView = new PagedGuiView();
    private final ConfirmDialogView confirmView = new ConfirmDialogView();

    /**
     * メインメニューに表示するアイテム保護の入口を作ります。
     *
     * @return 入口用の盾アイコン
     */
    public static @NotNull ItemStack createMenuIcon() {
        return GuiItems.create(MENU_ICON, title(), List.of(message(MenuMsgId.P_5683)));
    }

    /**
     * 共通 GUI 履歴で表示するアイテム保護の行き先を返します。
     *
     * @return アイテム保護のアイコンと画面名
     */
    public static @NotNull GuiNavigationDestination navigationDestination() {
        return new GuiNavigationDestination(MENU_ICON, PlayerMsgResource.getMessage(MenuMsgId.P_5682.getId()));
    }

    /**
     * 装備のスナップショットを指定ページで表示します。メインスレッドで呼び出します。
     *
     * @param player 一覧を開くプレイヤー
     * @param items 所有装備の表示用コピー
     * @param pageIndex 0 始まりのページ番号。有効範囲へ補正する
     */
    public void openList(@NotNull Player player, @NotNull List<ItemStack> items, int pageIndex) {
        int page = pagedView.normalizePage(pageIndex, items.size());
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.ITEM_PROTECTION, -1, page), PagedGuiView.SIZE, title()
        );
        renderList(inventory, items, page);
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 装備の表示用コピーとページ案内で一覧を再描画します。
     *
     * @param inventory 描画先の一覧インベントリ
     * @param items 最新の所有装備の表示用コピー
     * @param pageIndex 0 始まりのページ番号。有効範囲へ補正する
     */
    public void renderList(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        int page = pagedView.normalizePage(pageIndex, items.size());
        pagedView.render(inventory, items, page);
        inventory.setItem(GUIDE_SLOT, GuiItems.create(
            Material.PAPER,
            PlayerMsgResource.formatPlainComponent(
                MenuMsgId.P_5655.getId(), items.size(), page + 1, pagedView.totalPages(items.size())
            ),
            List.of(message(items.isEmpty() ? MenuMsgId.P_5685 : MenuMsgId.P_5684))
        ));
    }

    /**
     * 対象の装備と確定する操作を確認画面へ表示します。メインスレッドで呼び出します。
     *
     * @param player 確認画面を開くプレイヤー
     * @param item 選択した装備の表示用コピー
     * @param instanceId 所有装備を再照合するための装備インスタンス ID
     * @param protect 確定時に保護する場合は true、解除する場合は false
     */
    public void openConfirm(
        @NotNull Player player, @NotNull ItemStack item, @NotNull String instanceId, boolean protect
    ) {
        MenuScreen screen = protect ? MenuScreen.ITEM_PROTECTION_ENABLE : MenuScreen.ITEM_PROTECTION_DISABLE;
        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(screen, -1, 0, instanceId), ConfirmDialogView.SIZE, title()
        );
        confirmView.render(
            inventory,
            message(protect ? MenuMsgId.P_5689 : MenuMsgId.P_5690),
            message(protect ? MenuMsgId.P_5686 : MenuMsgId.P_5687),
            message(MenuMsgId.P_5688)
        );
        inventory.setItem(PREVIEW_SLOT, item.clone());
        GuiOpenSupport.open(player, inventory);
    }

    /** 一覧、確認画面、入口で共通の表示名を返します。 */
    private static @NotNull Component title() {
        return message(MenuMsgId.P_5682).color(NamedTextColor.GOLD);
    }

    /** アイテム保護画面用のメッセージを取得します。 */
    private static @NotNull Component message(@NotNull MenuMsgId id) {
        return PlayerMsgResource.getComponent(id.getId());
    }
}
