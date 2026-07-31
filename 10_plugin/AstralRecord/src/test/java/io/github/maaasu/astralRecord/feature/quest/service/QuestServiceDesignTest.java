package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
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
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestServiceDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 7. クエスト受領
     * 検証契約: 受領条件itemを消費し、prefix除去済みNPC ID付きprogressをactiveへ追加してquest/inventory保存を予約する。
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
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        QuestPlayerState state = new QuestPlayerState(player.getAccount().getUuid(), Map.of(), Map.of(), Map.of());
        when(harness.stateRepository.load(player.getAccount().getUuid())).thenReturn(state);
        harness.service.applyInitialState(state);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "guild_token")).thenReturn(1L);
        when(harness.inventoryService.consumeNormalItem(player.getAccount().getUuid(), "guild_token", 1)).thenReturn(true);

        boolean accepted = harness.service.accept(player, quest, "npc:guild_master");

        assertTrue(accepted);
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
     * 検証契約: NPC quest達成をREADY_TO_TURN_INとし、受領元以外を拒否して正しいNPCからだけ報酬を付与する。
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
        assertFalse(harness.service.turnIn(player, quest, "wrong_npc"));
        assertTrue(harness.service.turnIn(player, quest, "captain"));
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
     * 検証契約: quest保存後もinventory保存完了までclaimと演出を保留し、両保存成功後だけclaim解除・完了state・particleを公開する。
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
        AstPlayer player = playerWithQuestLimit(2.0D);
        QuestPlayerState state = readyState(player, quest);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot inventorySnapshot = inventorySnapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(inventorySnapshot);
        when(harness.inventoryService.addGold(player, 5L)).thenReturn(true);
        CompletableFuture<Boolean> inventorySave = new CompletableFuture<>();
        when(harness.inventoryService.saveNow(player.getAccount().getUuid())).thenReturn(inventorySave);

        assertTrue(harness.service.turnIn(player, quest, null));
        asyncExecutor.runAll();
        mainExecutor.runAll();

        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
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
        verify(harness.particleDisplayService, never()).spawnForNearbyViewers(
            any(),
            eq(SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM)
        );

        inventorySave.complete(true);
        assertTrue(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        mainExecutor.runAll();

        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
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
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: quest API保存失敗時に報酬とquest stateを復元してclaimを解放し、再試行成功時だけ完了へ進める。
     */
    @Test
    void questSaveFailureRestoresRewardsAndAllowsRetry() {
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

        assertTrue(state.activeQuests().containsKey(quest.id()));
        assertFalse(state.completedAt().containsKey(quest.id()));
        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        verify(harness.inventoryService).restoreState(inventorySnapshot);

        assertTrue(harness.service.turnIn(player, quest, null));

        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.stateRepository, times(3)).save(any(QuestPlayerState.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_3-メソッド仕様.md
     * 章・見出し: # 29_3-メソッド仕様 > ## 11. 報酬commit・補償
     * 検証契約: quest保存後のinventory保存失敗時に両storeを受取前へ戻して再保存し、再試行成功時だけ完了へ進める。
     */
    @Test
    void inventorySaveFailureRestoresBothStoresAndAllowsRetry() {
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

        assertTrue(state.activeQuests().containsKey(quest.id()));
        assertFalse(state.completedAt().containsKey(quest.id()));
        assertFalse(harness.service.hasPendingRewardClaim(player.getAccount().getUuid(), quest.id()));
        verify(harness.inventoryService).restoreState(inventorySnapshot);

        assertTrue(harness.service.turnIn(player, quest, null));

        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.stateRepository, times(3)).save(any(QuestPlayerState.class));
        verify(harness.inventoryService, times(3)).saveNow(player.getAccount().getUuid());
    }

    private AstPlayer playerWithQuestLimit(double questLimit) {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.QUEST_LIMIT, questLimit
        ), 100.0D, 0.0D, 0.0D));
        return player;
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
        return questHarness(quest, Runnable::run, Runnable::run);
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
        when(questRepository.findAll()).thenReturn(List.of(quest));
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
