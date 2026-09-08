package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
     * 検証契約: レアドロップ通知が有効なオンラインプレイヤーへ block.amethyst_block.break を再生する。
     */
    @Test
    void rareDropNotificationPlaysAmethystBreakSoundForEnabledViewer() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
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
        Player player = onlinePlayer();
        Location deathLocation = mock(Location.class);
        Location playerLocation = mock(Location.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        ItemModel model = mock(ItemModel.class);
        java.util.UUID playerId = java.util.UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        doReturn(java.util.List.of(player)).when(server).getOnlinePlayers();
        when(recipient.getBukkit()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("winner");
        when(player.getLocation()).thenReturn(playerLocation);
        when(deathLocation.getWorld()).thenReturn(null);
        when(itemService.findLoadedById("rare_item")).thenReturn(model);
        when(model.getId()).thenReturn("rare_item");
        when(model.getName()).thenReturn("レアアイテム");
        when(settingService.isDropLogDisplayEnabled(playerId)).thenReturn(true);

        MobDropResult result = new MobDropResult(
            java.util.List.of(new MobDropResultItem("rare_item", 1, 0.1D)),
            0,
            0
        );

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.presentAndGrant(recipient, deathLocation, "スライム", result, MobCategory.ENEMY);
        }

        verify(messageService).send(player, PlayerMsgId.P_5728, "winner", "レアアイテム", 1, "0.1");
        verify(player).playSound(
            playerLocation,
            Sound.BLOCK_AMETHYST_BLOCK_BREAK,
            SoundCategory.PLAYERS,
            1.0F,
            1.0F
        );
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_2-ユースケース.md
     * 章・見出し: # 12_2-ユースケース > ## 7. Mob が死亡する
     * 検証契約: ドロップ結果のmoneyを通貨インベントリへ直接加算する。
     */
    @Test
    void grantsGoldDropToCurrencyInventory() {
        InventoryService inventoryService = mock(InventoryService.class);
        MobDropPresentationService service = createService(inventoryService);
        AstPlayer recipient = mock(AstPlayer.class);
        Player player = onlinePlayer();
        when(recipient.getBukkit()).thenReturn(player);
        Location deathLocation = mock(Location.class);
        when(deathLocation.getWorld()).thenReturn(null);

        service.presentAndGrant(
            recipient,
            deathLocation,
            "スライム",
            new MobDropResult(java.util.List.of(), 0, 34),
            "mob_drop"
        );

        verify(inventoryService).addGold(recipient, 34L);
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: 装備個体は重要操作内でローカル生成し、SQL ACK 後にだけ Bukkit inventory へ反映する。
     */
    @Test
    void equipmentDropCreatesLocalInstanceInsideCriticalMutationAndRefreshesAfterAck() {
        UUID accountId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        AstPlayer recipient = astPlayer(accountId, true);
        ItemModel model = mock(ItemModel.class);
        when(model.getId()).thenReturn("rare_sword");
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        when(itemService.createLocalEquipmentInstance(model, accountId)).thenReturn(instance);
        InventoryService.InventoryStateSnapshot before = new InventoryService.InventoryStateSnapshot(
            accountId, Map.of(), InventoryType.BAG, false);
        when(inventoryService.snapshotState(accountId)).thenReturn(before);
        when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(() -> { });
        InventoryService.PreparedInstanceSlotReservation reservation =
            new InventoryService.PreparedInstanceSlotReservation(UUID.randomUUID(), accountId);
        when(inventoryService.completePreparedInstanceReservationStateOnly(
            accountId, model, InventoryInstanceType.EQUIPMENT, instanceId, reservation))
            .thenReturn(new InventoryService.PreparedInstanceReservationCompletion(true, 5));

        AtomicReference<Supplier<InventorySaveCoordinator.CriticalMutation<Integer>>> captured =
            new AtomicReference<>();
        CompletableFuture<Integer> ack = new CompletableFuture<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return ack;
        }).when(inventoryService).executeCriticalPlayerMutation(eq(accountId), any());
        MobDropPresentationService service = createService(itemService, inventoryService);

        CompletableFuture<Boolean> result = service.grantPreparedInstance(recipient, model, reservation);

        verify(itemService, never()).createLocalEquipmentInstance(any(), any());
        InventorySaveCoordinator.CriticalMutation<Integer> mutation = captured.get().get();
        verify(itemService).createLocalEquipmentInstance(model, accountId);
        verify(inventoryService, never()).refreshNormalInventoryGrantUi(any());

        ack.complete(mutation.result());

        assertTrue(result.join());
        verify(inventoryService).refreshNormalInventoryGrantUi(recipient);
        verify(inventoryService).releasePreparedInstanceReservation(reservation);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ドロップ配布対象と演出
     * 検証契約: 装備ドロップの SQL 保存失敗時は inventory と装備 cache を復元し、プレイヤーへ失敗を通知する。
     */
    @Test
    void equipmentDropSaveFailureRollsBackAndNotifiesPlayer() {
        UUID accountId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        AstPlayer recipient = astPlayer(accountId, true);
        ItemModel model = mock(ItemModel.class);
        when(model.getId()).thenReturn("rare_sword");
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        when(itemService.createLocalEquipmentInstance(model, accountId)).thenReturn(instance);
        InventoryService.InventoryStateSnapshot before = new InventoryService.InventoryStateSnapshot(
            accountId, Map.of(), InventoryType.BAG, false);
        when(inventoryService.snapshotState(accountId)).thenReturn(before);
        Runnable equipmentRollback = mock(Runnable.class);
        when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(equipmentRollback);
        InventoryService.PreparedInstanceSlotReservation reservation =
            new InventoryService.PreparedInstanceSlotReservation(UUID.randomUUID(), accountId);
        when(inventoryService.completePreparedInstanceReservationStateOnly(
            accountId, model, InventoryInstanceType.EQUIPMENT, instanceId, reservation))
            .thenReturn(new InventoryService.PreparedInstanceReservationCompletion(true, 5));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<InventorySaveCoordinator.CriticalMutation<Integer>> supplier = invocation.getArgument(1);
            InventorySaveCoordinator.CriticalMutation<Integer> mutation = supplier.get();
            mutation.rollback().run();
            return CompletableFuture.failedFuture(new IllegalStateException("save failed"));
        }).when(inventoryService).executeCriticalPlayerMutation(eq(accountId), any());
        MobDropPresentationService service = createService(itemService, inventoryService);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertThrows(
                CompletionException.class,
                () -> service.grantPreparedInstance(recipient, model, reservation).join()
            );
        }

        verify(inventoryService).restoreState(before);
        verify(equipmentRollback).run();
        verify(messageService).send(recipient, PlayerMsgId.P_5731);
        verify(inventoryService, never()).refreshNormalInventoryGrantUi(any());
    }

    private static @org.jetbrains.annotations.NotNull MobDropPresentationService createService(
        @org.jetbrains.annotations.NotNull InventoryService inventoryService
    ) {
        return createService(mock(ItemService.class), inventoryService);
    }

    private static @org.jetbrains.annotations.NotNull MobDropPresentationService createService(
        @org.jetbrains.annotations.NotNull ItemService itemService,
        @org.jetbrains.annotations.NotNull InventoryService inventoryService
    ) {
        return new MobDropPresentationService(
            mock(Plugin.class),
            itemService,
            inventoryService,
            mock(ItemStackFactory.class),
            mock(ItemDropAnimationService.class),
            mock(PlayerSettingService.class),
            Runnable::run
        );
    }

    private static @org.jetbrains.annotations.NotNull AstPlayer astPlayer(UUID accountId, boolean online) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(account);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(online);
        when(player.getLocation()).thenReturn(mock(Location.class));
        when(astPlayer.getBukkit()).thenReturn(player);
        return astPlayer;
    }

    private static @org.jetbrains.annotations.NotNull Player onlinePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(mock(Location.class));
        return player;
    }

}
