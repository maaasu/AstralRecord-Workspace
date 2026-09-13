package io.github.maaasu.astralRecord.feature.rebirth.gui;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthInventoryHolder;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthScreen;
import io.github.maaasu.astralRecord.feature.rebirth.service.RebirthService;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 転生の状態確認、開始、早期終了の画面を描画します。 */
public final class RebirthGui {
    public static final int ACTION_SLOT = 13;
    private static final int INFO_SLOT = 11;
    private static final int SIZE = 27;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();
    private final RebirthService rebirthService;

    /** @param rebirthService 転生状態と費用を提供するサービス */
    public RebirthGui(@NotNull RebirthService rebirthService) {
        this.rebirthService = rebirthService;
    }

    /**
     * 現在の転生状態に応じたメイン画面を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param account 表示するアカウント状態
     */
    public void open(@NotNull Player player, @NotNull AccountModel account) {
        boolean active = rebirthService.isActive(account);
        Inventory inventory = Bukkit.createInventory(
            new RebirthInventoryHolder(RebirthScreen.MAIN),
            SIZE,
            Component.text("転生", NamedTextColor.DARK_PURPLE)
        );
        fill(inventory);
        if (active) {
            inventory.setItem(INFO_SLOT, GuiItems.create(
                Material.EXPERIENCE_BOTTLE,
                Component.text("転生中", NamedTextColor.AQUA),
                List.of(
                    Component.text("現在レベル: " + account.getLevel(), NamedTextColor.AQUA),
                    Component.text("転生前レベル: " + account.getRebirthOriginalLevel(), NamedTextColor.GRAY),
                    Component.text("必要EXPは通常の3分の1", NamedTextColor.GREEN),
                    Component.text("100EXPごとに1EXPポイント", NamedTextColor.LIGHT_PURPLE)
                )
            ));
            inventory.setItem(ACTION_SLOT, GuiItems.create(
                Material.AMETHYST_SHARD,
                Component.text("転生を終了する", NamedTextColor.LIGHT_PURPLE),
                List.of(
                    Component.text("100アストラルドを消費", NamedTextColor.YELLOW),
                    Component.text("転生前レベルへ戻ります", NamedTextColor.GRAY),
                    Component.text("クリックで確認", NamedTextColor.GREEN)
                )
            ));
        } else {
            long cost = rebirthService.startGoldCost(account.getLevel());
            inventory.setItem(ACTION_SLOT, GuiItems.create(
                Material.WRITABLE_BOOK,
                Component.text("レベル1へ転生する", NamedTextColor.AQUA),
                List.of(
                    Component.text("現在レベル: " + account.getLevel(), NamedTextColor.WHITE),
                    Component.text("転生後レベル: 1", NamedTextColor.AQUA),
                    Component.text("必要Gold: " + cost, NamedTextColor.GOLD),
                    Component.text("スキルツリーとPPは維持されます", NamedTextColor.GRAY),
                    Component.text("転生前レベルまで必要EXPは通常の3分の1", NamedTextColor.GREEN),
                    Component.text("転生中に100EXPごとに1EXPポイント獲得", NamedTextColor.LIGHT_PURPLE),
                    Component.text("クリックで転生確認を開く", NamedTextColor.YELLOW)
                )
            ));
        }
        GuiOpenSupport.open(player, inventory);
    }

    /** @param player 表示対象 @param account 転生前状態 */
    public void openStartConfirm(@NotNull Player player, @NotNull AccountModel account) {
        long cost = rebirthService.startGoldCost(account.getLevel());
        Inventory inventory = Bukkit.createInventory(
            new RebirthInventoryHolder(RebirthScreen.CONFIRM_START),
            ConfirmDialogView.SIZE,
            Component.text("転生の確認", NamedTextColor.YELLOW)
        );
        confirmDialogView.render(
            inventory,
            Component.text("レベル1へ転生しますか？", NamedTextColor.AQUA),
            List.of(
                Component.text("転生前レベル: " + account.getLevel(), NamedTextColor.GRAY),
                Component.text("消費Gold: " + cost, NamedTextColor.GOLD),
                Component.text("転生中は再転生できません", NamedTextColor.RED)
            ),
            Component.text("転生する", NamedTextColor.AQUA),
            Component.text("戻る", NamedTextColor.GREEN)
        );
        GuiOpenSupport.open(player, inventory);
    }

    /** @param player 表示対象 @param account 転生中状態 */
    public void openEndConfirm(@NotNull Player player, @NotNull AccountModel account) {
        Inventory inventory = Bukkit.createInventory(
            new RebirthInventoryHolder(RebirthScreen.CONFIRM_END),
            ConfirmDialogView.SIZE,
            Component.text("転生終了の確認", NamedTextColor.YELLOW)
        );
        confirmDialogView.render(
            inventory,
            Component.text("転生を終了しますか？", NamedTextColor.LIGHT_PURPLE),
            List.of(
                Component.text("100アストラルドを消費", NamedTextColor.YELLOW),
                Component.text("レベル" + account.getRebirthOriginalLevel() + "へ戻ります", NamedTextColor.GRAY),
                Component.text("未変換のEXP端数は失われます", NamedTextColor.RED)
            ),
            Component.text("転生を終了する", NamedTextColor.LIGHT_PURPLE),
            Component.text("戻る", NamedTextColor.GREEN)
        );
        GuiOpenSupport.open(player, inventory);
    }

    /** @return 対象が転生GUIならholder、それ以外はnull */
    public @Nullable RebirthInventoryHolder holder(@Nullable Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof RebirthInventoryHolder rebirthHolder ? rebirthHolder : null;
    }

    private void fill(@NotNull Inventory inventory) {
        var spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, spacer);
    }
}
