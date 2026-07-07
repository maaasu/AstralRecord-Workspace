package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestServiceDesignTest extends MockBukkitTestBase {

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
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "guild_token")).thenReturn(1L);
        when(harness.inventoryService.consumeNormalItem(player.getAccount().getUuid(), "guild_token", 1)).thenReturn(true);

        boolean accepted = harness.service.accept(player, quest, "npc:guild_master");

        assertTrue(accepted);
        assertEquals(QuestDisplayState.IN_PROGRESS, harness.service.displayState(player, quest));
        assertNotNull(harness.service.progress(player, quest.id()));
        assertEquals("guild_master", harness.service.progress(player, quest.id()).acceptedNpcId());
        verify(harness.inventoryService).consumeNormalItem(player.getAccount().getUuid(), "guild_token", 1);
        verify(harness.inventoryService).saveNow(player.getAccount().getUuid());
        verify(harness.stateRepository).save(state);
    }

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
        when(harness.itemService.findLoadedById("wolf_claw")).thenReturn(rewardItem);
        when(harness.inventoryService.canAddItemToNormalInventory(player, rewardItem, 2)).thenReturn(true);
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
        when(harness.itemService.findLoadedById("letter_seal")).thenReturn(rewardItem);
        when(harness.inventoryService.canAddItemToNormalInventory(player, rewardItem, 1)).thenReturn(true);
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
        QuestDefinitionRepository questRepository = mock(QuestDefinitionRepository.class);
        QuestBoardRepository boardRepository = mock(QuestBoardRepository.class);
        QuestPlayerStateRepository stateRepository = mock(QuestPlayerStateRepository.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AccountService accountService = mock(AccountService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        StatusService statusService = mock(StatusService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        QuestService service = new QuestService(
            questRepository,
            boardRepository,
            stateRepository,
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService
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
        QuestService service
    ) {
    }
}
