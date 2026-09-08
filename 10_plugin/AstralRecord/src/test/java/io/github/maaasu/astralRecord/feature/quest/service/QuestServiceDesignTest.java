package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
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
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestServiceDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 5. プレイヤークエスト状態
     * 検証契約: 条件消費とactive quest追加を一つのcritical player-state保存へ含める。
     */
    @Test
    void acceptCommitsRequirementAndQuestThroughOneCriticalSnapshot() {
        QuestDefinition quest = quest(
            "wolf_intro",
            List.of(new QuestRequirementDefinition(
                new QuestItemStackDefinition("guild_token", "material", 1), true)),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestHarness harness = harness(quest);
        AstPlayer player = player();
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = emptyState(accountId);
        harness.service.applyInitialState(state);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        when(harness.inventoryService.getNormalItemAmount(accountId, "guild_token")).thenReturn(1L);
        when(harness.inventoryService.consumeNormalItem(accountId, "guild_token", 1L)).thenReturn(true);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(accountId));
        AtomicReference<String> accepted = new AtomicReference<>();
        harness.service.setQuestAcceptedListener((ignored, questId) -> accepted.set(questId));

        assertTrue(harness.service.accept(player, quest, "npc:guild_master"));

        assertNotNull(state.activeQuests().get(quest.id()));
        assertEquals("guild_master", state.activeQuests().get(quest.id()).acceptedNpcId());
        assertEquals(quest.id(), accepted.get());
        verify(harness.inventoryService).executeCriticalPlayerMutation(any(UUID.class), any());
        verify(harness.inventoryService).consumeNormalItem(accountId, "guild_token", 1L);
        verify(harness.inventoryService, never()).saveNow(accountId);
        assertNotNull(harness.service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 5. プレイヤークエスト状態
     * 検証契約: critical保存失敗では条件itemとquest stateを操作前へ戻し、成功通知を出さない。
     */
    @Test
    void acceptFailureRollsBackInventoryAndQuestBeforeReportingFailure() {
        QuestDefinition quest = quest(
            "failed_accept",
            List.of(new QuestRequirementDefinition(
                new QuestItemStackDefinition("guild_token", "material", 1), true)),
            new QuestRewardDefinition(0, 0L, List.of())
        );
        QuestHarness harness = harness(quest);
        AstPlayer player = player();
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = emptyState(accountId);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot before = inventorySnapshot(accountId);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        when(harness.inventoryService.getNormalItemAmount(accountId, "guild_token")).thenReturn(1L);
        when(harness.inventoryService.consumeNormalItem(accountId, "guild_token", 1L)).thenReturn(true);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(before);
        failCriticalMutations(harness.inventoryService);
        AtomicReference<String> accepted = new AtomicReference<>();
        harness.service.setQuestAcceptedListener((ignored, questId) -> accepted.set(questId));

        assertTrue(harness.service.accept(player, quest, null));

        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertNull(accepted.get());
        verify(harness.inventoryService).restoreState(before);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 4. クエスト報酬
     * 検証契約: 報酬とcompletionはローカル反映後もSQL ACKまで成功通知を保留する。
     */
    @Test
    void completionNotifiesOnlyAfterCriticalSnapshotAcknowledgement() {
        ItemModel reward = DesignTestFixtures.item("wolf_claw", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest(
            "reward_ack",
            List.of(),
            new QuestRewardDefinition(0, 5L,
                List.of(new QuestItemStackDefinition("wolf_claw", "material", 2)))
        );
        QuestHarness harness = harness(quest);
        AstPlayer player = player();
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = readyState(accountId, quest);
        harness.service.applyInitialState(state);
        when(harness.itemService.findLoadedById("wolf_claw")).thenReturn(reward);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(accountId));
        when(harness.inventoryService.addGoldStateOnly(accountId, 5L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventoryStateOnly(player, reward, 2, "quest_reward"))
            .thenReturn(2);
        AtomicReference<InventorySaveCoordinator.CriticalMutation<Object>> mutation = new AtomicReference<>();
        CompletableFuture<Object> acknowledgement = new CompletableFuture<>();
        holdCriticalMutation(harness.inventoryService, mutation, acknowledgement);
        AtomicReference<String> completed = new AtomicReference<>();
        harness.service.setQuestCompletedListener((ignored, questId) -> completed.set(questId));

        assertTrue(harness.service.turnIn(player, quest, null));
        assertNull(completed.get());
        assertTrue(harness.service.hasPendingRewardClaim(accountId, quest.id()));

        acknowledgement.complete(mutation.get().result());

        assertEquals(quest.id(), completed.get());
        assertFalse(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        assertFalse(state.activeQuests().containsKey(quest.id()));
        assertTrue(state.completedAt().containsKey(quest.id()));
        verify(harness.inventoryService).addGoldStateOnly(accountId, 5L);
        verify(harness.inventoryService).addItemToNormalInventoryStateOnly(player, reward, 2, "quest_reward");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 4. クエスト報酬
     * 検証契約: 報酬snapshot失敗ではinventoryとquest completionを戻し、同じ報告を再試行可能にする。
     */
    @Test
    void completionFailureRestoresRewardsAndQuestState() {
        ItemModel reward = DesignTestFixtures.item("retry_reward", ItemCategory.MATERIAL, 64);
        QuestDefinition quest = quest(
            "reward_failure",
            List.of(),
            new QuestRewardDefinition(0, 0L,
                List.of(new QuestItemStackDefinition("retry_reward", "material", 1)))
        );
        QuestHarness harness = harness(quest);
        AstPlayer player = player();
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = readyState(accountId, quest);
        harness.service.applyInitialState(state);
        InventoryService.InventoryStateSnapshot before = inventorySnapshot(accountId);
        when(harness.itemService.findLoadedById("retry_reward")).thenReturn(reward);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(before);
        when(harness.inventoryService.addItemToNormalInventoryStateOnly(player, reward, 1, "quest_reward"))
            .thenReturn(1);
        failCriticalMutations(harness.inventoryService);
        AtomicReference<String> completed = new AtomicReference<>();
        harness.service.setQuestCompletedListener((ignored, questId) -> completed.set(questId));

        assertTrue(harness.service.turnIn(player, quest, null));

        assertNotNull(state.activeQuests().get(quest.id()));
        assertFalse(state.completedAt().containsKey(quest.id()));
        assertFalse(harness.service.hasPendingRewardClaim(accountId, quest.id()));
        assertNull(completed.get());
        verify(harness.inventoryService).restoreState(before);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 4. クエスト報酬
     * 検証契約: 装備報酬は先行API作成せず、critical snapshot内でclient UUID個体を作る。
     */
    @Test
    void equipmentRewardUsesLocalInstanceInsideCriticalSnapshot() {
        ItemModel reward = DesignTestFixtures.item("quest_sword", ItemCategory.EQUIPMENT, 1);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        UUID instanceId = UUID.randomUUID();
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        QuestDefinition quest = quest(
            "equipment_reward",
            List.of(),
            new QuestRewardDefinition(0, 0L,
                List.of(new QuestItemStackDefinition("quest_sword", "equipment", 1)))
        );
        QuestHarness harness = harness(quest);
        AstPlayer player = player();
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = readyState(accountId, quest);
        harness.service.applyInitialState(state);
        when(harness.itemService.findLoadedById("quest_sword")).thenReturn(reward);
        when(harness.itemService.createLocalEquipmentInstance(reward, accountId)).thenReturn(instance);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(accountId));
        when(harness.inventoryService.addPreparedInstanceToNormalInventoryStateOnly(
            player, reward, InventoryInstanceType.EQUIPMENT, instanceId)).thenReturn(1);

        assertTrue(harness.service.turnIn(player, quest, null));

        verify(harness.itemService).createLocalEquipmentInstance(reward, accountId);
        verify(harness.inventoryService).addPreparedInstanceToNormalInventoryStateOnly(
            player, reward, InventoryInstanceType.EQUIPMENT, instanceId);
        assertTrue(state.completedAt().containsKey(quest.id()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 9. 永続化世代
     * 検証契約: ACK metadataだけを適用し、送信後に増えたlocal revisionを消さない。
     */
    @Test
    void questSnapshotCarriesExpectedVersionAndAcknowledgesItsRevision() {
        QuestDefinition quest = quest("snapshot", List.of(), new QuestRewardDefinition(0, 0L, List.of()));
        QuestHarness harness = harness(quest);
        AstPlayer player = player();
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = emptyState(accountId);
        harness.service.applyInitialState(state);
        when(harness.statusService.getStatus(player)).thenReturn(player.getStatusSnapshot());
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(inventorySnapshot(accountId));
        assertTrue(harness.service.accept(player, quest, null));
        PlayerStateSection section = harness.service.snapshotPlayerState(accountId);
        assertNotNull(section);
        assertEquals(0, section.payload().getAsJsonObject().get("expectedVersion").getAsInt());
        long revision = section.payload().getAsJsonObject().get("clientRevision").getAsLong();
        com.google.gson.JsonObject ack = new com.google.gson.JsonObject();
        ack.addProperty("clientRevision", revision);
        ack.addProperty("version", 1);

        section.acknowledge().accept(ack);

        assertEquals(1, state.persistedVersion());
        assertFalse(harness.service.hasPendingSave(accountId));
    }

    private static void holdCriticalMutation(
        InventoryService inventoryService,
        AtomicReference<InventorySaveCoordinator.CriticalMutation<Object>> mutation,
        CompletableFuture<Object> acknowledgement
    ) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<InventorySaveCoordinator.CriticalMutation<Object>> supplier = invocation.getArgument(1);
            mutation.set(supplier.get());
            return acknowledgement;
        }).when(inventoryService).executeCriticalPlayerMutation(any(UUID.class), any());
    }

    private static void failCriticalMutations(InventoryService inventoryService) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<InventorySaveCoordinator.CriticalMutation<Object>> supplier = invocation.getArgument(1);
            InventorySaveCoordinator.CriticalMutation<Object> mutation = supplier.get();
            mutation.rollback().run();
            return CompletableFuture.failedFuture(new IllegalStateException("snapshot failed"));
        }).when(inventoryService).executeCriticalPlayerMutation(any(UUID.class), any());
    }

    private QuestHarness harness(QuestDefinition quest) {
        QuestDefinitionRepository questRepository = mock(QuestDefinitionRepository.class);
        QuestBoardRepository boardRepository = mock(QuestBoardRepository.class);
        QuestPlayerStateRepository stateRepository = mock(QuestPlayerStateRepository.class);
        when(stateRepository.createSnapshotSection(any(QuestPlayerState.class), anyLong())).thenCallRealMethod();
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountService accountService = mock(AccountService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        StatusService statusService = mock(StatusService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<InventorySaveCoordinator.CriticalMutation<Object>> supplier = invocation.getArgument(1);
            InventorySaveCoordinator.CriticalMutation<Object> mutation = supplier.get();
            return CompletableFuture.completedFuture(mutation.result());
        }).when(inventoryService).executeCriticalPlayerMutation(any(UUID.class), any());
        when(itemService.captureEquipmentStateRollback(any(UUID.class))).thenReturn(() -> { });
        when(questRepository.findAll()).thenReturn(List.of(quest));
        when(boardRepository.findAll()).thenReturn(List.<QuestBoardDefinition>of());
        QuestService service = new QuestService(
            null, questRepository, boardRepository, stateRepository, itemService, inventoryService,
            accountService, playerClassService, statusService, particleDisplayService,
            Runnable::run, Runnable::run
        );
        service.loadAll();
        return new QuestHarness(service, itemService, inventoryService, statusService);
    }

    private AstPlayer player() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(
            Map.of(StatusType.QUEST_LIMIT, 2.0D), 100.0D, 0.0D, 0.0D));
        return player;
    }

    private static QuestPlayerState emptyState(UUID accountId) {
        return new QuestPlayerState(accountId, Map.of(), Map.of(), Map.of());
    }

    private static QuestPlayerState readyState(UUID accountId, QuestDefinition quest) {
        QuestProgress progress = QuestProgress.start(quest, null);
        progress.readyToTurnIn(true);
        return new QuestPlayerState(accountId, Map.of(quest.id(), progress), Map.of(), Map.of());
    }

    private static InventoryService.InventoryStateSnapshot inventorySnapshot(UUID accountId) {
        return new InventoryService.InventoryStateSnapshot(accountId, Map.of(), InventoryType.BAG, false);
    }

    private static QuestDefinition quest(
        String id,
        List<QuestRequirementDefinition> requirements,
        QuestRewardDefinition rewards
    ) {
        return new QuestDefinition(
            id, id, List.of(), Material.PAPER, QuestRepeatMode.ONCE, 0L,
            QuestCompletionMode.NPC, null,
            List.of(new QuestObjectiveDefinition("objective", QuestObjectiveType.KILL_MOB, "wolf", "Wolf", 1)),
            requirements, rewards
        );
    }

    private record QuestHarness(
        QuestService service,
        ItemService itemService,
        InventoryService inventoryService,
        StatusService statusService
    ) {
    }
}
