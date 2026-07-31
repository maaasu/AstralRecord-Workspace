package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobDropPresentationServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: 通常Mobのrate=0.1%境界をrare dropに含める。
     */
    @Test
    void enemyRareDropIncludesZeroPointOnePercentBoundary() {
        assertTrue(MobDropPresentationService.isRareDrop(MobCategory.ENEMY, 0.1D));
        assertFalse(MobDropPresentationService.isRareDrop(MobCategory.ENEMY, 0.1001D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: BOSSのrate=5%境界をrare dropに含める。
     */
    @Test
    void bossRareDropIncludesFivePercentBoundary() {
        assertTrue(MobDropPresentationService.isRareDrop(MobCategory.BOSS, 5.0D));
        assertFalse(MobDropPresentationService.isRareDrop(MobCategory.BOSS, 5.0001D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: drop rate表示で有効小数を保持し不要な末尾0だけ除く。
     */
    @Test
    void dropRateFormattingRemovesOnlyUnnecessaryTrailingZeros() {
        assertEquals("5", MobDropPresentationService.formatDropRate(5.0D));
        assertEquals("0.1", MobDropPresentationService.formatDropRate(0.1D));
        assertEquals("0.0125", MobDropPresentationService.formatDropRate(0.0125D));
        assertEquals("0.00001", MobDropPresentationService.formatDropRate(0.00001D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: inventoryに入らなかった数量を安全なworld dropへfallbackする。
     */
    @Test
    void inventoryShortfallFallsBackToWorldDrop() {
        Plugin plugin = mock(Plugin.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        ItemDropAnimationService animationService = mock(ItemDropAnimationService.class);
        PlayerSettingService settingService = mock(PlayerSettingService.class);
        MobDropPresentationService service = new MobDropPresentationService(
            plugin,
            itemService,
            inventoryService,
            itemStackFactory,
            animationService,
            settingService
        );
        AstPlayer recipient = mock(AstPlayer.class);
        ItemModel model = mock(ItemModel.class);
        World world = mock(World.class);
        Location dropLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        ItemStack fallbackStack = mock(ItemStack.class);
        when(model.getMaxStack()).thenReturn(64);
        when(inventoryService.addItemToNormalInventory(recipient, model, 5, "mob_drop")).thenReturn(2);
        when(itemStackFactory.create(model, 3)).thenReturn(fallbackStack);
        when(itemStackFactory.asDisplayStack(fallbackStack)).thenReturn(fallbackStack);

        int handled = service.grantStackedItemWithFallback(
            recipient,
            dropLocation,
            model,
            5,
            "mob_drop"
        );

        assertEquals(5, handled);
        verify(world).dropItemNaturally(dropLocation, fallbackStack);
    }
}
