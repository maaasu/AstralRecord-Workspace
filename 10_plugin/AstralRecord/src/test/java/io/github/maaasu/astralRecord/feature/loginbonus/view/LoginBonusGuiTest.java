package io.github.maaasu.astralRecord.feature.loginbonus.view;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LoginBonusGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### ログインボーナス表示
     * 検証契約: 当日未受取セルだけを受取可能かつglint付きで描画し、未来日はglintなしにする。
     */
    @Test
    void claimableTodayUsesGlintAndFutureDateDoesNot() {
        LocalDate today = LocalDate.of(2026, 7, 19);
        YearMonth month = YearMonth.from(today);
        LoginBonusGui gui = new LoginBonusGui();
        var player = server().addPlayer();

        gui.open(player, month, today, Set.of(), null, null);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        ItemStack todayItem = inventory.getItem(slotOf(gui, month, today));
        ItemStack tomorrowItem = inventory.getItem(slotOf(gui, month, today.plusDays(1)));
        assertNotNull(todayItem);
        assertNotNull(tomorrowItem);
        assertEquals(Boolean.TRUE, todayItem.getItemMeta().getEnchantmentGlintOverride());
        assertEquals(Boolean.FALSE, tomorrowItem.getItemMeta().getEnchantmentGlintOverride());
    }

    private int slotOf(LoginBonusGui gui, YearMonth month, LocalDate date) {
        for (int slot = 0; slot < LoginBonusGui.SIZE; slot++) {
            if (date.equals(gui.resolveDate(month, slot))) {
                return slot;
            }
        }
        throw new AssertionError("Date slot was not found: " + date);
    }
}
