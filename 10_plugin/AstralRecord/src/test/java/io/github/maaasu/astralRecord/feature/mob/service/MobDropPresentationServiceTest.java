package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
     * 検証契約: リザルト TextDisplay は固定報酬の後、獲得アイテムの前に共通区切り線を表示する。
     */
    @Test
    void resultTextSeparatesFixedRewardsFromItems() {
        String text = MobDropPresentationService.formatResultText(
            "スライム",
            new MobDropResult(java.util.List.of(), 12, 34),
            java.util.List.of()
        );

        assertTrue(text.contains("&6ゴールド &f+34\n&8◈───────────◈\n&a獲得アイテム"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: 経験値・ゴールドが1未満の固定報酬行は個別に非表示にし、アイテム欄は維持する。
     */
    @Test
    void resultTextOmitsFixedRewardsBelowOneIndividually() {
        String noExperience = MobDropPresentationService.formatResultText(
            "スライム",
            new MobDropResult(java.util.List.of(), 0, 34),
            java.util.List.of()
        );
        String noGold = MobDropPresentationService.formatResultText(
            "スライム",
            new MobDropResult(java.util.List.of(), 12, 0),
            java.util.List.of()
        );
        String noFixedRewards = MobDropPresentationService.formatResultText(
            "スライム",
            new MobDropResult(java.util.List.of(), 0, 0),
            java.util.List.of()
        );

        assertFalse(noExperience.contains("経験値"));
        assertTrue(noExperience.contains("&6ゴールド &f+34"));
        assertTrue(noGold.contains("&e経験値 &f+12"));
        assertFalse(noGold.contains("ゴールド"));
        assertFalse(noFixedRewards.contains("経験値"));
        assertFalse(noFixedRewards.contains("ゴールド"));
        assertTrue(noFixedRewards.contains("\n&a獲得アイテム\n&7・なし"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: inventoryに入らなかった通常アイテムの残数をworld dropへfallbackせず破棄する。
     */
    @Test
    void inventoryShortfallIsDiscardedWithoutWorldDrop() {
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
        when(inventoryService.addItemToNormalInventoryWithCapacityResult(recipient, model, 5, "mob_drop"))
            .thenReturn(new InventoryService.NormalInventoryGrantResult(5, 2, 0, 1));

        int granted = service.grantStackedItemDiscardingShortfall(
            recipient,
            model,
            5,
            "mob_drop"
        );

        assertEquals(2, granted);
        verifyNoInteractions(itemStackFactory);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: BAG が満杯で通常スタック品を新規付与できないとき、P_5241 と拒否音を送る。
     */
    @Test
    void fullInventorySendsCapacityMessageAndDenySound() {
        InventoryService inventoryService = mock(InventoryService.class);
        MobDropPresentationService service = createService(inventoryService);
        AstPlayer recipient = mock(AstPlayer.class);
        Player player = onlinePlayer();
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        ItemModel model = mock(ItemModel.class);
        when(recipient.getBukkit()).thenReturn(player);
        when(inventoryService.addItemToNormalInventoryWithCapacityResult(recipient, model, 1, "mob_drop"))
            .thenReturn(new InventoryService.NormalInventoryGrantResult(1, 0, 0, 0));

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertEquals(0, service.grantStackedItemDiscardingShortfall(recipient, model, 1, "mob_drop"));
        }

        verify(messageService).send(recipient, PlayerMsgId.P_5241);
        verify(player).playSound(
            any(Location.class),
            eq(Sound.BLOCK_NOTE_BLOCK_BASS),
            eq(SoundCategory.PLAYERS),
            eq(0.5F),
            eq(0.7F)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: 新規 BAG slot を消費して残り3枠になったときだけ、P_5244 と拒否音を一度送る。
     */
    @Test
    void newlyOccupiedBagSlotAtThreeRemainingSendsLowCapacityMessageOnlyOnce() {
        InventoryService inventoryService = mock(InventoryService.class);
        MobDropPresentationService service = createService(inventoryService);
        AstPlayer recipient = mock(AstPlayer.class);
        Player player = onlinePlayer();
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        ItemModel model = mock(ItemModel.class);
        when(recipient.getBukkit()).thenReturn(player);
        when(inventoryService.addItemToNormalInventoryWithCapacityResult(recipient, model, 1, "mob_drop"))
            .thenReturn(
                new InventoryService.NormalInventoryGrantResult(1, 1, 1, 3),
                new InventoryService.NormalInventoryGrantResult(1, 1, 0, 3)
            );

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertEquals(1, service.grantStackedItemDiscardingShortfall(recipient, model, 1, "mob_drop"));
            assertEquals(1, service.grantStackedItemDiscardingShortfall(recipient, model, 1, "mob_drop"));
        }

        verify(messageService, times(1)).send(recipient, PlayerMsgId.P_5244, 3);
        verify(player, times(1)).playSound(
            any(Location.class),
            eq(Sound.BLOCK_NOTE_BLOCK_BASS),
            eq(SoundCategory.PLAYERS),
            eq(0.5F),
            eq(0.7F)
        );
    }

    private static @org.jetbrains.annotations.NotNull MobDropPresentationService createService(
        @org.jetbrains.annotations.NotNull InventoryService inventoryService
    ) {
        return new MobDropPresentationService(
            mock(Plugin.class),
            mock(ItemService.class),
            inventoryService,
            mock(ItemStackFactory.class),
            mock(ItemDropAnimationService.class),
            mock(PlayerSettingService.class)
        );
    }

    private static @org.jetbrains.annotations.NotNull Player onlinePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(mock(Location.class));
        return player;
    }

}
