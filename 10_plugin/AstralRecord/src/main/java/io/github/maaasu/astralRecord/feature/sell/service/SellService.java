package io.github.maaasu.astralRecord.feature.sell.service;

import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 売却 GUI の公開入口を提供するサービスです。
 */
public final class SellService {
    private final MenuView menuView;

    public SellService(@NotNull MenuView menuView) {
        this.menuView = menuView;
    }

    public void open(@NotNull Player player) {
        open(player, List.of(), 0);
    }

    public void open(@NotNull Player player, @NotNull List<ItemStack> sellItems, int pageIndex) {
        menuView.openSell(player, sellItems, pageIndex);
    }
}
