package io.github.maaasu.astralRecord.feature.currency.view;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 組み込みゴールド額面を隣接額面間で交換する専用GUIを描画します。
 */
public final class CurrencyExchangeGuiView {
    public static final int SIZE = 27;
    public static final int CLOSE_SLOT = 22;
    private static final int FIRST_DENOMINATION_SLOT = 10;

    /**
     * 両替GUIを開きます。
     *
     * @param player 表示対象プレイヤー
     * @param accountId 対象アカウントID
     * @param currencyService 通貨残高サービス
     */
    public void open(
        @NotNull Player player,
        @NotNull UUID accountId,
        @NotNull CurrencyService currencyService
    ) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(),
            SIZE,
            Component.text("ゴールド両替所", NamedTextColor.GOLD)
        );
        fill(inventory);
        inventory.setItem(4, GuiItems.create(
            Material.SUNFLOWER,
            Component.text("合計ゴールド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(Component.text(format(currencyService.getGoldAmount(accountId)) + " G", NamedTextColor.YELLOW))
        ));
        GoldDenomination[] denominations = GoldDenomination.values();
        for (int index = 0; index < denominations.length; index++) {
            GoldDenomination denomination = denominations[index];
            inventory.setItem(
                FIRST_DENOMINATION_SLOT + index,
                createDenominationItem(denomination, currencyService.getDisplayCurrencyAmount(accountId, denomination.itemId()))
            );
        }
        inventory.setItem(CLOSE_SLOT, GuiItems.closeButton());
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 指定インベントリが両替GUIか判定します。
     *
     * @param inventory 判定対象
     * @return 両替GUIの場合はtrue
     */
    public boolean isExchangeInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * クリックされたスロットに対応する額面を返します。
     *
     * @param rawSlot GUI上のraw slot
     * @return 対応額面。額面スロットでない場合はnull
     */
    public @Nullable GoldDenomination denominationAt(int rawSlot) {
        int index = rawSlot - FIRST_DENOMINATION_SLOT;
        GoldDenomination[] denominations = GoldDenomination.values();
        return index < 0 || index >= denominations.length ? null : denominations[index];
    }

    private @NotNull ItemStack createDenominationItem(
        @NotNull GoldDenomination denomination,
        long amount
    ) {
        Material material = Material.matchMaterial(denomination.icon());
        if (material == null) {
            material = Material.GOLD_NUGGET;
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("価値: " + format(denomination.goldValue()) + " G", NamedTextColor.YELLOW));
        lore.add(Component.text("所持: " + format(amount) + " 個", NamedTextColor.WHITE));
        lore.add(Component.empty());
        if (denomination.higher() != null) {
            lore.add(Component.text(
                "左クリック: " + denomination.higher().displayName() + "へまとめる",
                NamedTextColor.GREEN
            ));
            lore.add(Component.text("Shift+左クリック: 可能な分をすべて", NamedTextColor.DARK_GREEN));
        }
        if (denomination.lower() != null) {
            lore.add(Component.text(
                "右クリック: " + denomination.lower().displayName() + "へ崩す",
                NamedTextColor.AQUA
            ));
            lore.add(Component.text("Shift+右クリック: 所持分をすべて", NamedTextColor.DARK_AQUA));
        }
        return GuiItems.create(
            material,
            Component.text(denomination.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
            lore
        );
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = GuiItems.create(
            Material.GRAY_STAINED_GLASS_PANE,
            Component.text(" "),
            List.of()
        );
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull String format(long value) {
        return String.format(Locale.JAPAN, "%,d", value);
    }

    /**
     * 両替GUIを識別するholderです。
     */
    public static final class Holder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
