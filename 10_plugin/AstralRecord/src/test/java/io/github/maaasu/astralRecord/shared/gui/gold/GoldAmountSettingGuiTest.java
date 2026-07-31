package io.github.maaasu.astralRecord.shared.gui.gold;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GoldAmountSettingGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 6. 共通 gold 金額入力
     * 検証契約: 桁別増減とquick adjustment controlを共通gold GUIへ描画する。
     */
    @Test
    void rendersDigitAndQuickAdjustmentControls() {
        GoldAmountSettingGui gui = new GoldAmountSettingGui();
        var player = server().addPlayer();

        gui.open(player, "test", UUID.randomUUID(), 1L, Long.MAX_VALUE);

        var inventory = player.getOpenInventory().getTopInventory();
        assertEquals(27, inventory.getSize());
        assertInstanceOf(GoldAmountSettingGui.GoldAmountHolder.class, inventory.getHolder());
        assertEquals(Material.REDSTONE, inventory.getItem(GoldAmountSettingGui.STEP_DOWN_SLOT).getType());
        assertEquals(Material.RED_CONCRETE, inventory.getItem(GoldAmountSettingGui.MINUS_SLOT).getType());
        assertEquals(Material.ORANGE_STAINED_GLASS_PANE, inventory.getItem(GoldAmountSettingGui.HALF_SLOT).getType());
        assertEquals(Material.LIME_STAINED_GLASS_PANE, inventory.getItem(GoldAmountSettingGui.DOUBLE_SLOT).getType());
        assertEquals(Material.LIME_CONCRETE, inventory.getItem(GoldAmountSettingGui.PLUS_SLOT).getType());
        assertEquals(Material.GLOWSTONE_DUST, inventory.getItem(GoldAmountSettingGui.STEP_UP_SLOT).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 6. 共通 gold 金額入力
     * 検証契約: 1 goldおよび10進桁stepで金額を増減し0..maxへclampする。
     */
    @Test
    void supportsOneGoldAndDecimalStepAdjustment() {
        GoldAmountSettingGui gui = new GoldAmountSettingGui();
        GoldAmountSettingGui.GoldAmountHolder holder = holder(0L, 1_000_000L);

        assertEquals(1L, gui.applyStepDelta(holder, 1, 1));
        assertEquals(1_000L, gui.shiftStep(holder, 3));
        holder.setAmount(gui.applyStepDelta(holder, 1, 5));

        assertEquals(5_000L, holder.amount());
        assertEquals(10L, gui.shiftStep(holder, -2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 6. 共通 gold 金額入力
     * 検証契約: 巨大step×回数でもlong overflowせず0または上限へ安全に丸める。
     */
    @Test
    void clampsLargeAdjustmentsWithoutLongOverflow() {
        GoldAmountSettingGui gui = new GoldAmountSettingGui();
        GoldAmountSettingGui.GoldAmountHolder holder = holder(Long.MAX_VALUE - 5L, Long.MAX_VALUE);
        gui.shiftStep(holder, 18);

        assertEquals(1_000_000_000_000_000_000L, holder.step());
        assertEquals(Long.MAX_VALUE, gui.applyStepDelta(holder, 1, 10));
        assertEquals(0L, gui.applyStepDelta(holder, -1, 10));
    }

    private static GoldAmountSettingGui.GoldAmountHolder holder(long amount, long maxAmount) {
        return new GoldAmountSettingGui.GoldAmountHolder(
            "test",
            UUID.randomUUID(),
            UUID.randomUUID(),
            amount,
            maxAmount
        );
    }
}
