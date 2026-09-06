package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestItemStackDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveType;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRequirementDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRewardDefinition;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestBoardRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestDefinitionRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestServiceDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 7. クエスト受領
     * 検証契約: 受領条件itemを消費し、prefix除去済みNPC ID付きprogressをactiveへ追加してquest/inventory保存を予約し、受領成功listenerへquest IDを通知する。
     */
    @Test
    void acceptConsumesRequiredItemsAndStoresNpcBoundProgress() {
        QuestDefinition quest = quest(
            "wolf_intro",
            QuestCompletionMode.NPC,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 2)),
            List.of(new QuestRequirementDefinition(new QuestItemStackDefinition("guild_token", "material", 1), true)),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        AtomicReference<String> acceptedQuestId = new AtomicReference<>();
        harness.service.setQuestAcceptedListener((ignored, questId) -> acceptedQuestId.set(questId));
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        when(harness.stateRepository.load(player.getAccount().getUuid())).thenReturn(state);
        harness.service.applyInitialState(state);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "guild_token")).thenReturn(1L);
        when(harness.inventoryService.consumeNormalItem(player.getAccount().getUuid(), "guild_token", 1)).thenReturn(true);

        boolean accepted = harness.service.accept(player, quest, "npc:guild_master");

        assertTrue(accepted);
        assertEquals(quest.id(), acceptedQuestId.get());
        assertEquals(QuestDisplayState.IN_PROGRESS, harness.service.displayState(player, quest));
        assertNotNull(harness.service.progress(player, quest.id()));
        assertEquals("guild_master", harness.service.progress(player, quest.id()).acceptedNpcId());
        verify(harness.inventoryService).consumeNormalItem(player.getAccount().getUuid(), "guild_token", 1);
        verify(harness.inventoryService).saveNow(player.getAccount().getUuid());
        assertTrue(harness.service.hasPendingSave(player.getAccount().getUuid()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 7. クエスト受領
     * 検証契約: 同一itemの重複条件を合計し、合計所持不足なら消費もactive追加も行わない。
     */
    @Test
    void acceptAggregatesDuplicateItemRequirementsBeforeConsuming() {
        QuestDefinition quest = quest(
            "duplicate_token_requirement",
            QuestCompletionMode.NPC,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(
                new QuestRequirementDefinition(new QuestItemStackDefinition("guild_token", "material", 2), true),
                new QuestRequirementDefinition(new QuestItemStackDefinition("guild_token", "material", 3), true)
            ),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        harness.service.applyInitialState(state);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "guild_token")).thenReturn(4L);
        when(harness.inventoryService.consumeNormalItem(
            eq(player.getAccount().getUuid()),
            eq("guild_token"),
            anyLong()
        )).thenReturn(true);

        assertFalse(harness.service.accept(player, quest, null));

        verify(harness.inventoryService, never()).consumeNormalItem(
            eq(player.getAccount().getUuid()),
            eq("guild_token"),
            anyLong()
        );
        assertFalse(state.activeQuests().containsKey(quest.id()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 7. クエスト受領
     * 検証契約: 同一itemのconsume条件を合計し、合計数量を一回だけ消費してactiveへ追加する。
     */
    @Test
    void acceptConsumesAggregatedDuplicateItemRequirementsOnce() {
        QuestDefinition quest = quest(
            "duplicate_token_consumption",
            QuestCompletionMode.NPC,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(
                new QuestRequirementDefinition(new QuestItemStackDefinition("guild_token", "material", 2), true),
                new QuestRequirementDefinition(new QuestItemStackDefinition("guild_token", "material", 3), true)
            ),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        harness.service.applyInitialState(state);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "guild_token")).thenReturn(5L);
        when(harness.inventoryService.consumeNormalItem(player.getAccount().getUuid(), "guild_token", 5L)).thenReturn(true);

        assertTrue(harness.service.accept(player, quest, null));

        verify(harness.inventoryService, times(1)).consumeNormalItem(
            player.getAccount().getUuid(),
            "guild_token",
            5L
        );
        assertTrue(state.activeQuests().containsKey(quest.id()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 8. 目標進行
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 10. 報酬準備・反映
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: AUTO目標達成時にaccount/class EXP・gold・itemを付与し、activeを削除して完了履歴と両store保存を確定する。
     */
    @Test
    void autoQuestCompletionGrantsExpGoldItemsAndClearsActiveQuest() {
        ItemModel rewardItem = DesignTestFixtures.item("wolf_claw", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest(
            "wolf_hunt",
            QuestCompletionMode.AUTO,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(),
            new QuestRewardDefinition(
                25,
                10L,
                List.of(new QuestItemStackDefinition("wolf_claw", "material", 2))
            )
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        when(harness.stateRepository.load(player.getAccount().getUuid())).thenReturn(state);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.itemService.findLoadedById("wolf_claw")).thenReturn(rewardItem);
        when(harness.inventoryService.addGold(player, 10L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, rewardItem, 2, "quest_reward")).thenReturn(2);
        when(harness.accountService.grantExperienceCached(player.getAccount(), 25, player.getUser().getUuid()))
            .thenReturn(new AccountExperienceResult(player.getAccount(), player.getAccount(), 25, 0));
        when(harness.playerClassService.grantClassExperience(player, 25))
            .thenReturn(new ClassExperienceResult(1, 1, 25, 0));

        assertTrue(harness.service.accept(player, quest, null));
        harness.service.recordMobKill(player, "mob:wolf");

        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.accountService).grantExperienceCached(player.getAccount(), 25, player.getUser().getUuid());
        verify(harness.playerClassService).grantClassExperience(player, 25);
        verify(harness.inventoryService).addGold(player, 10L);
        verify(harness.inventoryService).addItemToNormalInventory(player, rewardItem, 2, "quest_reward");
        verify(harness.inventoryService, times(2)).saveNow(player.getAccount().getUuid());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 9. NPC報告・重複受取guard
     * 検証契約: NPC quest達成をREADY_TO_TURN_INとし、報告前は完了listenerを通知せず、受領元以外を拒否して正しいNPCからだけ報酬を付与し、報告・保存成功後に完了listenerへquest IDを通知する。
     */
    @Test
    void npcQuestBecomesReadyAndRequiresExpectedTurnInNpcBeforeRewards() {
        ItemModel rewardItem = DesignTestFixtures.item("letter_seal", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest(
            "sealed_letter",
            QuestCompletionMode.NPC,
            List.of(new QuestObjectiveDefinition("kill_bandit", QuestObjectiveType.KILL_MOB, "bandit", "Bandit", 1)),
            List.of(),
            new QuestRewardDefinition(0, 5L, List.of(new QuestItemStackDefinition("letter_seal", "material", 1)))
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        AtomicReference<String> completedQuestId = new AtomicReference<>();
        harness.service.setQuestCompletedListener((ignored, questId) -> completedQuestId.set(questId));
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        when(harness.stateRepository.load(player.getAccount().getUuid())).thenReturn(state);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.itemService.findLoadedById("letter_seal")).thenReturn(rewardItem);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, rewardItem, 1, "quest_reward")).thenReturn(1);

        assertTrue(harness.service.accept(player, quest, "npc:captain"));
        harness.service.recordMobKill(player, "bandit");

        assertEquals(QuestDisplayState.READY_TO_TURN_IN, harness.service.displayState(player, quest));
        assertNull(completedQuestId.get());
        assertFalse(harness.service.turnIn(player, quest, "wrong_npc"));
        assertTrue(harness.service.turnIn(player, quest, "captain"));
        assertEquals(quest.id(), completedQuestId.get());
        assertFalse(state.activeQuests().containsKey(quest.id()));
        verify(harness.inventoryService).addGold(player, 5L);
        verify(harness.inventoryService).addItemToNormalInventory(player, rewardItem, 1, "quest_reward");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 10. 報酬準備・反映
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: 二つ目の商品報酬がexact付与できなければinventoryを全復元し、EXPを付与せずquestをactiveに残す。
     */
    @Test
    void autoQuestKeepsQuestActiveAndRestoresAllRewardsWhenSecondItemDoesNotFit() {
        ItemModel firstReward = DesignTestFixtures.item("wolf_claw", ItemCategory.MATERIAL, 64);
        ItemModel secondReward = DesignTestFixtures.item("wolf_fang", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest(
            "two_item_reward",
            QuestCompletionMode.AUTO,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(),
            new QuestRewardDefinition(
                25,
                0L,
                List.of(
                    new QuestItemStackDefinition("wolf_claw", "material", 2),
                    new QuestItemStackDefinition("wolf_fang", "material", 2)
                )
            )
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.itemService.findLoadedById("wolf_claw")).thenReturn(firstReward);
        when(harness.itemService.findLoadedById("wolf_fang")).thenReturn(secondReward);
        when(harness.inventoryService.addItemToNormalInventory(player, firstReward, 2, "quest_reward")).thenReturn(2);
        when(harness.inventoryService.addItemToNormalInventory(player, secondReward, 2, "quest_reward")).thenReturn(1);

        assertTrue(harness.service.accept(player, quest, null));
        harness.service.recordMobKill(player, "wolf");

        assertTrue(state.activeQuests().containsKey(quest.id()));
        assertFalse(state.completedAt().containsKey(quest.id()));
        verify(harness.inventoryService).restoreState(inventorySnapshot);
        verify(harness.accountService, never()).grantExperienceCached(player.getAccount(), 25, player.getUser().getUuid());
        verify(harness.playerClassService, never()).grantClassExperience(player, 25);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 10. 報酬準備・反映
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: equipment instanceを非同期事前生成し、main反映の一部失敗時はinventoryを復元して全生成instanceを削除しquestをactiveに残す。
     */
    @Test
    void equipmentRewardsArePreparedOffMainAndCleanedUpAfterAtomicGrantFailure() {
        ItemModel equipmentReward = DesignTestFixtures.item("quest_sword", ItemCategory.EQUIPMENT, 1);
        QuestDefinition quest = quest(
            "equipment_reward",
            QuestCompletionMode.AUTO,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(),
            new QuestRewardDefinition(
                0,
                0L,
                List.of(new QuestItemStackDefinition("quest_sword", "equipment", 2))
            )
        );
        ManualExecutor asyncExecutor = new ManualExecutor();
        ManualExecutor mainExecutor = new ManualExecutor();
        QuestHarness harness = questHarness(quest, asyncExecutor, mainExecutor);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        harness.service.applyInitialState(state);
        when(harness.itemService.findLoadedById("quest_sword")).thenReturn(equipmentReward);

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        EquipmentInstance first = mock(EquipmentInstance.class);
        EquipmentInstance second = mock(EquipmentInstance.class);
        when(first.getEquipmentInstanceId()).thenReturn(firstId.toString());
        when(second.getEquipmentInstanceId()).thenReturn(secondId.toString());
        when(harness.itemService.createEquipmentInstance(
            "quest_sword",
            player.getAccount().getUuid().toString(),
            "quest_reward",
            player.getAccount().getUuid().toString()
        )).thenReturn(first, second);

        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addPreparedInstanceToNormalInventory(
            player,
            equipmentReward,
            InventoryInstanceType.EQUIPMENT,
            firstId
        )).thenReturn(1);
        when(harness.inventoryService.addPreparedInstanceToNormalInventory(
            player,
            equipmentReward,
            InventoryInstanceType.EQUIPMENT,
            secondId
        )).thenReturn(0);

        assertTrue(harness.service.accept(player, quest, null));
        asyncExecutor.runAll();
        harness.service.recordMobKill(player, "wolf");

        verify(harness.inventoryService, never()).snapshotState(player.getAccount().getUuid());
        asyncExecutor.runAll();
        verify(harness.itemService, times(2)).createEquipmentInstance(
            "quest_sword",
            player.getAccount().getUuid().toString(),
            "quest_reward",
            player.getAccount().getUuid().toString()
        );
        verify(harness.inventoryService, never()).snapshotState(player.getAccount().getUuid());

        mainExecutor.runAll();

        verify(harness.inventoryService).restoreState(inventorySnapshot);
        verify(harness.inventoryService, never()).addItemToNormalInventory(
            player,
            equipmentReward,
            2,
            "quest_reward"
        );
        assertTrue(state.activeQuests().containsKey(quest.id()));
        assertFalse(state.completedAt().containsKey(quest.id()));

        asyncExecutor.runAll();
        verify(harness.itemService).deleteEquipmentInstance(firstId.toString());
        verify(harness.itemService).deleteEquipmentInstance(secondId.toString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: class EXP付与例外時にinventory・account・quest stateを復元してclaimを解放し、同じ報告を再試行可能にする。
     */
    @Test
    void rewardExceptionRestoresAllStateAndAllowsRetry() {
        ItemModel rewardItem = DesignTestFixtures.item("retry_reward", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest(
            "retry_after_failure",
            QuestCompletionMode.NPC,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(),
            new QuestRewardDefinition(
                25,
                5L,
                List.of(new QuestItemStackDefinition("retry_reward", "material", 1))
            )
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.itemService.findLoadedById("retry_reward")).thenReturn(rewardItem);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, rewardItem, 1, "quest_reward")).thenReturn(1);
        when(harness.accountService.grantExperienceCached(
            player.getAccount(),
            25,
            player.getUser().getUuid()
        )).thenReturn(new AccountExperienceResult(player.getAccount(), player.getAccount(), 25, 0));
        doThrow(new IllegalStateException("class progress failure"))
            .doReturn(new ClassExperienceResult(1, 1, 25, 0))
            .when(harness.playerClassService).grantClassExperience(player, 25);

        assertTrue(harness.service.accept(player, quest, "npc:captain"));
        harness.service.recordMobKill(player, "wolf");

        // このテストは同期 Executor のため、報酬反映失敗は呼び出し内で確定して false となる。
        assertFalse(harness.service.turnIn(player, quest, "captain"));
        assertTrue(state.activeQuests().containsKey(quest.id()));
        assertFalse(state.completedAt().containsKey(quest.id()));
        verify(harness.inventoryService).restoreState(inventorySnapshot);
        verify(harness.accountService).restoreCachedProgress(
            player.getAccount(),
            player.getUser().getUuid()
        );

        assertTrue(harness.service.turnIn(player, quest, "captain"));
        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.playerClassService, times(2)).grantClassExperience(player, 25);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: quest保存後もinventory保存完了までclaim・演出・完了callbackを保留し、両保存成功後だけclaim解除・完了state・confirm音・particle・callbackを公開する。
     */
    @Test
    void completionKeepsClaimAndPresentationPendingUntilBothStoresAreSaved() {
        QuestDefinition quest = quest(
            "ordered_persistence",
            QuestCompletionMode.NPC,
            List.of(),
            List.of(),
            new QuestRewardDefinition(0, 5L, List.of())
        );
        ManualExecutor asyncExecutor = new ManualExecutor();
        ManualExecutor mainExecutor = new ManualExecutor();
        QuestHarness harness = questHarness(quest, asyncExecutor, mainExecutor);
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = playerWithQuestLimit(bukkitPlayer, 2.0D);
        QuestPlayerState state = readyState(player, quest);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        CompletableFuture<Boolean> inventorySave = new CompletableFuture<>();
        when(harness.inventoryService.saveNow(player.getAccount().getUuid())).thenReturn(inventorySave);

        AtomicBoolean refreshed = new AtomicBoolean();
        assertTrue(harness.service.turnIn(player, quest, null, () -> refreshed.set(true)));
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertFalse(refreshed.get());
        verify(harness.stateRepository, never()).save(any(QuestPlayerState.class));
        verify(harness.inventoryService, never()).saveNow(player.getAccount().getUuid());
        verify(harness.particleDisplayService, never()).spawnForNearbyViewers(
            any(),
            eq(SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM)
        );

        asyncExecutor.runAll();

        verify(harness.stateRepository).save(any(QuestPlayerState.class));
        verify(harness.inventoryService).saveNow(player.getAccount().getUuid());
        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertFalse(refreshed.get());
        verify(harness.particleDisplayService, never()).spawnForNearbyViewers(
            any(),
            eq(SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM)
        );

        inventorySave.complete(true);
        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        mainExecutor.runAll();

        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertTrue(refreshed.get());
        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.particleDisplayService).spawnForNearbyViewers(
            any(),
            eq(SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM)
        );
        verify(harness.particleDisplayService).spawnForNearbyViewers(
            any(),
            eq(SharedParticleDefinitions.PLAYER_LEVEL_UP_END_ROD)
        );
        assertEquals(1L, heardSoundCount(bukkitPlayer, Sound.BLOCK_NOTE_BLOCK_PLING));
        assertEquals(0L, heardSoundCount(bukkitPlayer, Sound.UI_TOAST_CHALLENGE_COMPLETE));
        assertEquals(0L, heardSoundCount(bukkitPlayer, Sound.ENTITY_PLAYER_LEVELUP));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: quest API応答不明時は完了状態とclaimを保持し、報酬を再付与せず保存再試行成功時だけclaimを解放する。
     */
    @Test
    void questSaveFailureRetainsRewardsAndRetriesPersistence() {
        QuestDefinition quest = quest(
            "quest_save_retry",
            QuestCompletionMode.NPC,
            List.of(),
            List.of(),
            new QuestRewardDefinition(0, 5L, List.of())
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        QuestPlayerState state = readyState(player, quest);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        doThrow(new IllegalStateException("quest_save_failure"))
            .doNothing()
            .when(harness.stateRepository).save(any(QuestPlayerState.class));

        assertTrue(harness.service.turnIn(player, quest, null));

        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        verify(harness.inventoryService, never()).restoreState(any());
        assertFalse(harness.service.turnIn(player, quest, null));

        harness.service.retryRewardPersistence(Long.MAX_VALUE);

        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.inventoryService).addGold(player, 5L);
        verify(harness.stateRepository, times(2)).save(any(QuestPlayerState.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: 保存再試行は最新quest世代を保存し、待機中に記録された別questの進行を巻き戻さない。
     */
    @Test
    void questSaveFailurePreservesLaterProgressForAnotherQuest() {
        QuestDefinition rewardQuest = quest(
            "reward_failure_isolated",
            QuestCompletionMode.NPC,
            List.of(),
            List.of(),
            new QuestRewardDefinition(0, 5L, List.of())
        );
        QuestDefinition otherQuest = quest(
            "later_progress",
            QuestCompletionMode.NPC,
            List.of(new QuestObjectiveDefinition("kill_wolf", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            List.of(),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        ManualExecutor asyncExecutor = new ManualExecutor();
        ManualExecutor mainExecutor = new ManualExecutor();
        QuestHarness harness = questHarness(List.of(rewardQuest, otherQuest), asyncExecutor, mainExecutor);
        AstPlayer player = playerWithQuestLimit(2.0D);
        QuestProgress rewardProgress = QuestProgress.start(rewardQuest, null);
        rewardProgress.readyToTurnIn(true);
        QuestProgress otherProgress = QuestProgress.start(otherQuest, null);
        QuestPlayerState state = new QuestPlayerState(
            player.getAccount().getUuid(),
            Map.of(rewardQuest.id(), rewardProgress, otherQuest.id(), otherProgress),
            Map.of(),
            Map.of()
        );
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        doThrow(new IllegalStateException("quest_save_failure"))
            .doNothing()
            .when(harness.stateRepository).save(any(QuestPlayerState.class));

        assertTrue(harness.service.turnIn(player, rewardQuest, null));
        asyncExecutor.runAll();
        mainExecutor.runAll();

        harness.service.recordMobKill(player, "wolf");
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertFalse(state.activeQuests().containsKey(rewardQuest.id()));
        assertTrue(state.completedAt().containsKey(rewardQuest.id()));
        harness.service.retryRewardPersistence(Long.MAX_VALUE);
        asyncExecutor.runAll();
        mainExecutor.runAll();
        ArgumentCaptor<QuestPlayerState> snapshots = ArgumentCaptor.forClass(QuestPlayerState.class);
        verify(harness.stateRepository, times(2)).save(snapshots.capture());
        assertEquals(1, snapshots.getValue().activeQuests().get(otherQuest.id()).progress("kill_wolf"));
        assertEquals(1, state.activeQuests().get(otherQuest.id()).progress("kill_wolf"));
        assertTrue(state.activeQuests().get(otherQuest.id()).readyToTurnIn());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: 同一accountの別quest報酬は先行保存再試行の成功後に反映し、双方のGold・item・account/class EXPと完了状態を保持する。
     */
    @Test
    void failedRewardPersistenceQueuesAnotherQuestAndPreservesItsRewards() {
        ItemModel failedRewardItem = DesignTestFixtures.item("failed_reward_item", ItemCategory.MATERIAL, 64);
        ItemModel succeedingRewardItem = DesignTestFixtures.item("succeeding_reward_item", ItemCategory.MATERIAL, 64);
        QuestDefinition failedQuest = quest(
            "failed_reward_persistence",
            QuestCompletionMode.NPC,
            List.of(),
            List.of(),
            new QuestRewardDefinition(
                10,
                5L,
                List.of(new QuestItemStackDefinition("failed_reward_item", "material", 1))
            )
        );
        QuestDefinition succeedingQuest = quest(
            "succeeding_reward_persistence",
            QuestCompletionMode.NPC,
            List.of(),
            List.of(),
            new QuestRewardDefinition(
                20,
                7L,
                List.of(new QuestItemStackDefinition("succeeding_reward_item", "material", 2))
            )
        );
        ManualExecutor asyncExecutor = new ManualExecutor();
        ManualExecutor mainExecutor = new ManualExecutor();
        QuestHarness harness = questHarness(List.of(failedQuest, succeedingQuest), asyncExecutor, mainExecutor);
        AstPlayer player = playerWithQuestLimit(2.0D);
        player.selectClass("adventurer");
        player.setClassLevel(1);
        player.setClassExperience(100L);
        QuestPlayerState state = new QuestPlayerState(
            player.getAccount().getUuid(),
            Map.of(
                failedQuest.id(), QuestProgress.start(failedQuest, null),
                succeedingQuest.id(), QuestProgress.start(succeedingQuest, null)
            ),
            Map.of(),
            Map.of()
        );
        state.activeQuests().get(failedQuest.id()).readyToTurnIn(true);
        state.activeQuests().get(succeedingQuest.id()).readyToTurnIn(true);
        harness.service.applyInitialState(state);

        InventoryService.InventoryStateSnapshot failedInventorySnapshot = inventorySnapshot(player);
        InventoryService.InventoryStateSnapshot succeedingInventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid()))
            .thenReturn(failedInventorySnapshot, succeedingInventorySnapshot);
        when(harness.itemService.findLoadedById("failed_reward_item")).thenReturn(failedRewardItem);
        when(harness.itemService.findLoadedById("succeeding_reward_item")).thenReturn(succeedingRewardItem);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        when(harness.inventoryService.addGold(player, 7L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, failedRewardItem, 1, "quest_reward")).thenReturn(1);
        when(harness.inventoryService.addItemToNormalInventory(player, succeedingRewardItem, 2, "quest_reward")).thenReturn(2);
        AccountModel initialAccount = player.getAccount();
        AccountModel failedRewardAccount = mock(AccountModel.class);
        AccountModel succeedingRewardAccount = mock(AccountModel.class);
        when(failedRewardAccount.getUuid()).thenReturn(initialAccount.getUuid());
        when(failedRewardAccount.getMode()).thenReturn(AccountMode.PLAYER);
        when(succeedingRewardAccount.getUuid()).thenReturn(initialAccount.getUuid());
        when(succeedingRewardAccount.getMode()).thenReturn(AccountMode.PLAYER);
        when(harness.accountService.grantExperienceCached(
            eq(initialAccount),
            eq(10),
            eq(player.getUser().getUuid())
        )).thenReturn(new AccountExperienceResult(initialAccount, failedRewardAccount, 10, 0));
        when(harness.accountService.grantExperienceCached(
            eq(failedRewardAccount),
            eq(20),
            eq(player.getUser().getUuid())
        )).thenReturn(new AccountExperienceResult(initialAccount, succeedingRewardAccount, 20, 0));
        doAnswer(invocation -> {
            int grantedExperience = invocation.getArgument(1, Integer.class);
            player.setClassExperience(player.getClassExperience() + grantedExperience);
            return new ClassExperienceResult(player.getClassLevel(), player.getClassLevel(), grantedExperience, 0);
        }).when(harness.playerClassService).grantClassExperience(eq(player), anyInt());
        doThrow(new IllegalStateException("quest_save_failure"))
            .doNothing()
            .doNothing()
            .when(harness.stateRepository).save(any(QuestPlayerState.class));

        assertTrue(harness.service.turnIn(player, failedQuest, null));
        assertTrue(harness.service.turnIn(player, succeedingQuest, null));
        asyncExecutor.runAll();
        mainExecutor.runAll();

        verify(harness.inventoryService, never()).addGold(player, 7L);
        verify(harness.inventoryService, never()).addItemToNormalInventory(
            player,
            succeedingRewardItem,
            2,
            "quest_reward"
        );

        asyncExecutor.runAll();
        mainExecutor.runAll();
        assertTrue(harness.service.hasPendingRewardClaim(initialAccount.getUuid(), failedQuest.id()));
        verify(harness.inventoryService, never()).addGold(player, 7L);
        harness.service.retryRewardPersistence(Long.MAX_VALUE);
        asyncExecutor.runAll();
        mainExecutor.runAll();
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertFalse(state.activeQuests().containsKey(failedQuest.id()));
        assertTrue(state.completedAt().containsKey(failedQuest.id()));
        assertFalse(state.activeQuests().containsKey(succeedingQuest.id()));
        assertTrue(state.completedAt().containsKey(succeedingQuest.id()));
        assertSame(succeedingRewardAccount, player.getAccount());
        assertEquals(130L, player.getClassExperience());

        InOrder rewardOrder = inOrder(
            harness.inventoryService,
            harness.accountService,
            harness.playerClassService
        );
        rewardOrder.verify(harness.inventoryService).addGold(player, 5L);
        rewardOrder.verify(harness.inventoryService).addItemToNormalInventory(
            player,
            failedRewardItem,
            1,
            "quest_reward"
        );
        rewardOrder.verify(harness.accountService).grantExperienceCached(
            initialAccount,
            10,
            player.getUser().getUuid()
        );
        rewardOrder.verify(harness.playerClassService).grantClassExperience(player, 10);
        verify(harness.inventoryService, never()).restoreState(any());
        verify(harness.accountService, never()).restoreCachedProgress(any(), any());
        rewardOrder.verify(harness.inventoryService).addGold(player, 7L);
        rewardOrder.verify(harness.inventoryService).addItemToNormalInventory(
            player,
            succeedingRewardItem,
            2,
            "quest_reward"
        );
        rewardOrder.verify(harness.accountService).grantExperienceCached(
            failedRewardAccount,
            20,
            player.getUser().getUuid()
        );
        rewardOrder.verify(harness.playerClassService).grantClassExperience(player, 20);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 7. クエスト受領
     * 検証契約: 繰り返しクエストの報酬保存が失敗して再試行中でも、同一questを再受領して受領条件itemを消費できない。
     */
    @Test
    void repeatableQuestCannotBeAcceptedWhileFailedRewardSaveIsRetried() {
        QuestDefinition quest = new QuestDefinition(
            "repeatable_reward_save_failure",
            "repeatable_reward_save_failure",
            List.of(),
            Material.PAPER,
            QuestRepeatMode.REPEATABLE,
            0L,
            QuestCompletionMode.NPC,
            null,
            List.of(),
            List.of(new QuestRequirementDefinition(new QuestItemStackDefinition("guild_token", "material", 1), true)),
            new QuestRewardDefinition(0, 5L, List.of())
        );
        ManualExecutor asyncExecutor = new ManualExecutor();
        ManualExecutor mainExecutor = new ManualExecutor();
        QuestHarness harness = questHarness(quest, asyncExecutor, mainExecutor);
        AstPlayer player = playerWithQuestLimit(2.0D);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = readyState(player, quest);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "guild_token")).thenReturn(1L);
        doThrow(new IllegalStateException("quest_save_failure"))
            .doNothing()
            .when(harness.stateRepository).save(any(QuestPlayerState.class));

        assertTrue(harness.service.turnIn(player, quest, null));
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertFalse(harness.service.accept(player, quest, null));
        verify(harness.inventoryService, never()).consumeNormalItem(
            player.getAccount().getUuid(),
            "guild_token",
            1
        );

        asyncExecutor.runAll();
        mainExecutor.runAll();
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertFalse(harness.service.accept(player, quest, null));
        harness.service.retryRewardPersistence(Long.MAX_VALUE);
        asyncExecutor.runAll();
        mainExecutor.runAll();
        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        assertFalse(state.activeQuests().containsKey(quest.id()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: quest保存後のinventory保存失敗時は完了状態を保持し、報酬再付与や巻戻しなしでinventory保存を再試行する。
     */
    @Test
    void inventorySaveFailureRetainsBothStoresAndRetriesPersistence() {
        QuestDefinition quest = quest(
            "inventory_save_retry",
            QuestCompletionMode.NPC,
            List.of(),
            List.of(),
            new QuestRewardDefinition(0, 5L, List.of())
        );
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        QuestPlayerState state = readyState(player, quest);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        when(harness.inventoryService.saveNow(player.getAccount().getUuid()))
            .thenReturn(CompletableFuture.completedFuture(false))
            .thenReturn(CompletableFuture.completedFuture(true))
            .thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(harness.service.turnIn(player, quest, null));

        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        verify(harness.inventoryService, never()).restoreState(any());
        assertFalse(harness.service.turnIn(player, quest, null));

        harness.service.retryRewardPersistence(Long.MAX_VALUE);

        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        verify(harness.inventoryService).addGold(player, 5L);
        verify(harness.stateRepository).save(any(QuestPlayerState.class));
        verify(harness.inventoryService, times(2)).saveNow(player.getAccount().getUuid());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: 保存中に報酬を消費し別item・Gold・EXPを取得しても、応答不明と再試行失敗で後続状態を巻き戻さず、再付与せずに保存を確定する。
     */
    @Test
    void uncertainSavePreservesSpentRewardsAndLaterInventoryAndExperience() {
        ItemModel rewardItem = DesignTestFixtures.item("retry_stack", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest("later_mutations", QuestCompletionMode.NPC, List.of(), List.of(),
            new QuestRewardDefinition(10, 5L, List.of(new QuestItemStackDefinition("retry_stack", "material", 2))));
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        player.selectClass("adventurer");
        player.setClassLevel(1);
        player.setClassExperience(100L);
        UUID accountId = player.getAccount().getUuid();
        AccountModel initialAccount = player.getAccount();
        QuestPlayerState state = readyState(player, quest);
        harness.service.applyInitialState(state);
        AtomicLong gold = new AtomicLong(100L);
        AtomicLong stack = new AtomicLong(3L);
        AtomicLong otherItem = new AtomicLong(1L);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(player));
        doAnswer(ignored -> {
            gold.set(100L);
            stack.set(3L);
            otherItem.set(1L);
            return true;
        }).when(harness.inventoryService).restoreState(any());
        when(harness.itemService.findLoadedById("retry_stack")).thenReturn(rewardItem);
        when(harness.inventoryService.addGold(player, 5L)).thenAnswer(ignored -> {
            gold.addAndGet(5L);
            return true;
        });
        when(harness.inventoryService.addItemToNormalInventory(player, rewardItem, 2, "quest_reward"))
            .thenAnswer(ignored -> {
                stack.addAndGet(2L);
                return 2;
            });
        when(harness.accountService.grantExperienceCached(initialAccount, 10, player.getUser().getUuid()))
            .thenReturn(new AccountExperienceResult(initialAccount, initialAccount, 10, 0));
        when(harness.playerClassService.grantClassExperience(player, 10)).thenAnswer(ignored -> {
            player.setClassExperience(110L);
            return new ClassExperienceResult(1, 1, 10, 0);
        });
        CompletableFuture<Boolean> unknownSave = new CompletableFuture<>();
        when(harness.inventoryService.saveNow(accountId)).thenReturn(unknownSave)
            .thenReturn(CompletableFuture.completedFuture(false))
            .thenReturn(CompletableFuture.completedFuture(true));
        AtomicBoolean completed = new AtomicBoolean();
        assertTrue(harness.service.turnIn(player, quest, null, () -> completed.set(true)));

        // 別操作が報酬を含めて消費し、その後に別の取得とレベル進行を確定した状態。
        gold.addAndGet(-105L);
        gold.addAndGet(7L);
        stack.addAndGet(-5L);
        otherItem.addAndGet(4L);
        AccountModel laterAccount = mock(AccountModel.class);
        when(laterAccount.getUuid()).thenReturn(accountId);
        when(laterAccount.getMode()).thenReturn(AccountMode.PLAYER);
        player.setAccount(laterAccount);
        player.setClassLevel(2);
        player.setClassExperience(130L);
        unknownSave.completeExceptionally(new IllegalStateException("response_lost_after_commit"));

        assertTrue(state.completedAt().containsKey(quest.id()));
        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        assertFalse(harness.service.turnIn(player, quest, null));
        assertFalse(completed.get());
        harness.service.retryRewardPersistence(0L);
        verify(harness.inventoryService).saveNow(accountId);
        harness.service.retryRewardPersistence(Long.MAX_VALUE);
        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        assertFalse(completed.get());
        harness.service.retryRewardPersistence(Long.MAX_VALUE);

        assertEquals(7L, gold.get());
        assertEquals(0L, stack.get());
        assertEquals(5L, otherItem.get());
        assertSame(laterAccount, player.getAccount());
        assertEquals(2, player.getClassLevel());
        assertEquals(130L, player.getClassExperience());
        assertFalse(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        assertTrue(completed.get());
        verify(harness.inventoryService, never()).restoreState(any());
        verify(harness.accountService, never()).restoreCachedProgress(any(), any());
        verify(harness.inventoryService).addGold(player, 5L);
        verify(harness.inventoryService).addItemToNormalInventory(player, rewardItem, 2, "quest_reward");
        verify(harness.accountService).grantExperienceCached(initialAccount, 10, player.getUser().getUuid());
        verify(harness.playerClassService).grantClassExperience(player, 10);
        verify(harness.inventoryService, times(3)).saveNow(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: EXPのみの報酬でも共通プレイヤー状態保存の成功までclaimを保持し、保存再試行で後続class EXPを復元・再付与しない。
     */
    @Test
    void experienceOnlyRewardAwaitsPlayerStateSaveAndPreservesLaterExperience() {
        QuestDefinition quest = quest("exp_only_retry", QuestCompletionMode.NPC, List.of(), List.of(),
            new QuestRewardDefinition(10, 0L, List.of()));
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        player.selectClass("adventurer");
        UUID accountId = player.getAccount().getUuid();
        harness.service.applyInitialState(readyState(player, quest));
        when(harness.accountService.grantExperienceCached(player.getAccount(), 10, player.getUser().getUuid()))
            .thenReturn(new AccountExperienceResult(player.getAccount(), player.getAccount(), 10, 0));
        when(harness.playerClassService.grantClassExperience(player, 10))
            .thenReturn(new ClassExperienceResult(1, 1, 10, 0));
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        when(harness.inventoryService.saveNow(accountId)).thenReturn(save)
            .thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(harness.service.turnIn(player, quest, null));
        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        player.setClassExperience(50L);
        save.complete(false);
        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        harness.service.retryRewardPersistence(Long.MAX_VALUE);

        assertEquals(50L, player.getClassExperience());
        assertFalse(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        verify(harness.inventoryService, never()).snapshotState(any());
        verify(harness.inventoryService, times(2)).saveNow(accountId);
        verify(harness.playerClassService).grantClassExperience(player, 10);
        verify(harness.accountService, never()).restoreCachedProgress(any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: equipment報酬反映後のquest API応答不明ではinstanceを削除せず、同じ完了状態を保存再試行する。
     */
    @Test
    void uncertainQuestSaveDoesNotDeleteAppliedEquipmentInstance() {
        ItemModel item = DesignTestFixtures.item("retry_sword", ItemCategory.EQUIPMENT, 1);
        QuestDefinition quest = quest("equipment_retry", QuestCompletionMode.NPC, List.of(), List.of(),
            new QuestRewardDefinition(0, 0L, List.of(new QuestItemStackDefinition("retry_sword", "equipment", 1))));
        QuestHarness harness = questHarness(quest);
        AstPlayer player = playerWithQuestLimit(2.0D);
        UUID accountId = player.getAccount().getUuid();
        UUID instanceId = UUID.randomUUID();
        EquipmentInstance instance = mock(EquipmentInstance.class);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        harness.service.applyInitialState(readyState(player, quest));
        when(harness.itemService.findLoadedById("retry_sword")).thenReturn(item);
        when(harness.itemService.createEquipmentInstance("retry_sword", accountId.toString(), "quest_reward", accountId.toString()))
            .thenReturn(instance);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(player));
        when(harness.inventoryService.addPreparedInstanceToNormalInventory(player, item, InventoryInstanceType.EQUIPMENT, instanceId))
            .thenReturn(1);
        doThrow(new IllegalStateException("response_lost_after_commit")).doNothing()
            .when(harness.stateRepository).save(any(QuestPlayerState.class));

        assertTrue(harness.service.turnIn(player, quest, null));
        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        harness.service.retryRewardPersistence(Long.MAX_VALUE);

        assertFalse(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        verify(harness.itemService, never()).deleteEquipmentInstance(any());
        verify(harness.inventoryService, never()).restoreState(any());
        verify(harness.inventoryService).addPreparedInstanceToNormalInventory(player, item, InventoryInstanceType.EQUIPMENT, instanceId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: logout保存と報酬再試行が同じ保存tailへ合流して失敗しても、保存済みと誤認せずclaimを保持し、再ログイン状態を巻き戻さない。
     */
    @Test
    void retryJoiningFailedLogoutSaveKeepsClaimUntilLatestQuestIsPersisted() {
        QuestDefinition quest = quest("logout_retry", QuestCompletionMode.NPC, List.of(), List.of(),
            new QuestRewardDefinition(0, 5L, List.of()));
        ManualExecutor asyncExecutor = new ManualExecutor();
        ManualExecutor mainExecutor = new ManualExecutor();
        QuestHarness harness = questHarness(quest, asyncExecutor, mainExecutor);
        AstPlayer player = playerWithQuestLimit(2.0D);
        UUID accountId = player.getAccount().getUuid();
        harness.service.applyInitialState(readyState(player, quest));
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(player));
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        doThrow(new IllegalStateException("first_save_failure"))
            .doThrow(new IllegalStateException("logout_save_failure")).doNothing()
            .when(harness.stateRepository).save(any(QuestPlayerState.class));

        assertTrue(harness.service.turnIn(player, quest, null));
        asyncExecutor.runAll();
        mainExecutor.runAll();
        asyncExecutor.runAll();
        mainExecutor.runAll();
        harness.service.releaseState(accountId);
        harness.service.retryRewardPersistence(Long.MAX_VALUE);
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        verify(harness.inventoryService, never()).saveNow(accountId);
        harness.service.applyInitialState(harness.service.loadInitialState(accountId));
        assertEquals(QuestDisplayState.COMPLETED, harness.service.displayState(player, quest));
        harness.service.retryRewardPersistence(Long.MAX_VALUE);
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertFalse(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        assertEquals(QuestDisplayState.COMPLETED, harness.service.displayState(player, quest));
        verify(harness.inventoryService).addGold(player, 5L);
        verify(harness.inventoryService, never()).restoreState(any());
    }

    private AstPlayer playerWithQuestLimit(double questLimit) {
        return playerWithQuestLimit(server().addPlayer(), questLimit);
    }

    private AstPlayer playerWithQuestLimit(PlayerMock bukkitPlayer, double questLimit) {
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.QUEST_LIMIT, questLimit
        ), 100.0D, 0.0D, 0.0D));
        return player;
    }

    private static long heardSoundCount(PlayerMock player, Sound sound) {
        String soundKey = Registry.SOUND_EVENT.getKeyOrThrow(sound).getKey();
        return player.getHeardSounds().stream()
            .filter(heardSound -> soundKey.equals(heardSound.getSound()))
            .count();
    }

    private QuestDefinition quest(
        String id,
        QuestCompletionMode completionMode,
        List<QuestObjectiveDefinition> objectives,
        List<QuestRequirementDefinition> requirements,
        QuestRewardDefinition rewards
    ) {
        return new QuestDefinition(
            id,
            id,
            List.of(),
            Material.PAPER,
            QuestRepeatMode.ONCE,
            0L,
            completionMode,
            null,
            objectives,
            requirements,
            rewards
        );
    }

    private QuestHarness questHarness(QuestDefinition quest) {
        return questHarness(List.of(quest), Runnable::run, Runnable::run);
    }

    private QuestHarness questHarness(
        List<QuestDefinition> quests,
        Executor asyncExecutor,
        Executor mainExecutor
    ) {
        return questHarness(quests.getFirst(), quests, asyncExecutor, mainExecutor);
    }

    private InventoryService.InventoryStateSnapshot inventorySnapshot(AstPlayer player) {
        return new InventoryService.InventoryStateSnapshot(
            player.getAccount().getUuid(),
            Map.of(),
            InventoryType.BAG,
            false
        );
    }

    private QuestPlayerState readyState(AstPlayer player, QuestDefinition quest) {
        QuestProgress progress = QuestProgress.start(quest, null);
        progress.readyToTurnIn(true);
        return new QuestPlayerState(
            player.getAccount().getUuid(),
            Map.of(quest.id(), progress),
            Map.of(),
            Map.of()
        );
    }

    private QuestHarness questHarness(
        QuestDefinition quest,
        Executor asyncExecutor,
        Executor mainExecutor
    ) {
        return questHarness(quest, List.of(quest), asyncExecutor, mainExecutor);
    }

    private QuestHarness questHarness(
        QuestDefinition quest,
        List<QuestDefinition> quests,
        Executor asyncExecutor,
        Executor mainExecutor
    ) {
        QuestDefinitionRepository questRepository = mock(QuestDefinitionRepository.class);
        QuestBoardRepository boardRepository = mock(QuestBoardRepository.class);
        QuestPlayerStateRepository stateRepository = mock(QuestPlayerStateRepository.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountService accountService = mock(AccountService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        StatusService statusService = mock(StatusService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        when(inventoryService.saveNow(any(UUID.class))).thenReturn(CompletableFuture.completedFuture(true));
        doAnswer(invocation -> invocation.<Supplier<Object>>getArgument(1).get())
            .when(inventoryService)
            .executeLocalPlayerMutation(any(UUID.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any());
        QuestService service = new QuestService(
            null,
            questRepository,
            boardRepository,
            stateRepository,
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService,
            asyncExecutor,
            mainExecutor
        );
        when(questRepository.findAll()).thenReturn(quests);
        when(boardRepository.findAll()).thenReturn(List.<QuestBoardDefinition>of());
        service.loadAll();
        return new QuestHarness(
            stateRepository,
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService,
            service
        );
    }

    private record QuestHarness(
        QuestPlayerStateRepository stateRepository,
        ItemService itemService,
        InventoryService inventoryService,
        AccountService accountService,
        PlayerClassService playerClassService,
        StatusService statusService,
        ParticleDisplayService particleDisplayService,
        QuestService service
    ) {
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
