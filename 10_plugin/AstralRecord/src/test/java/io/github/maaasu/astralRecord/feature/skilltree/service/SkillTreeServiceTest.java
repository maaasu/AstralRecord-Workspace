package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeServiceTest extends MockBukkitTestBase {

    @Test
    void unlockNodeTreatsSkillIdDeltaAsStatusAffected() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition passiveNode = new SkillTreeNodeDefinition(
                "1000",
                "root",
                "Passive Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of("sw_passive_wall"),
                List.of()
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillTreeService service = newService(passiveNode);
        service.setPassiveSkillService(passiveSkillService);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        service.unlockNode(player, passiveNode);

        verify(passiveSkillService).reconcileSkillOwnershipDelta(
                eq(player),
                eq(Set.of("sw_passive_wall")),
                eq(Set.of()),
                eq(true)
        );
    }

    @Test
    void setupItemInUnselectedHotbarDoesNotSuppressControls() {
        SkillTreeService service = newService(null);
        Player player = server().addPlayer();
        player.getInventory().setItem(0, service.createPositionItem("position-test", 1));
        player.getInventory().setItem(1, new ItemStack(Material.STONE));
        player.getInventory().setHeldItemSlot(1);

        assertFalse(service.shouldSuppressSkillTreeSetupControls(player));
    }

    @Test
    void setupItemInActiveHandSuppressesControls() {
        SkillTreeService service = newService(null);
        Player player = server().addPlayer();
        player.getInventory().setItemInMainHand(service.createPositionItem("position-test", 1));

        assertTrue(service.shouldSuppressSkillTreeSetupControls(player));
    }

    @Test
    void discardingOldJoinStateDoesNotRemoveNewerSessionState() {
        UUID accountId = UUID.randomUUID();
        SkillTreeService service = newService(null);
        SkillTreePlayerState oldState = new SkillTreePlayerState(accountId, Set.of("old"));
        SkillTreePlayerState currentState = new SkillTreePlayerState(accountId, Set.of("current"));
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);

        service.applyInitialPlayerState(oldState);
        service.applyInitialPlayerState(currentState);
        service.discardInitialPlayerState(oldState);

        assertTrue(service.isStateReady(player));

        service.discardInitialPlayerState(currentState);
        assertFalse(service.isStateReady(player));
    }

    private SkillTreeService newService(SkillTreeNodeDefinition node) {
        SkillTreeNodeRepository nodeRepository = mock(SkillTreeNodeRepository.class);
        SkillTreeStructureRepository structureRepository = mock(SkillTreeStructureRepository.class);
        SkillTreePlayerStateRepository stateRepository = mock(SkillTreePlayerStateRepository.class);

        Plugin plugin = PluginMock.builder()
                .withPluginName("AstralRecord")
                .build();

        SkillTreeService service = new SkillTreeService(
                plugin,
                mock(WorldService.class),
                null,
                nodeRepository,
                structureRepository,
                stateRepository
        );
        if (node != null) {
            putNode(service, node);
        }
        return service;
    }

    @SuppressWarnings("unchecked")
    private void putNode(SkillTreeService service, SkillTreeNodeDefinition node) {
        try {
            Field nodesById = SkillTreeService.class.getDeclaredField("nodesById");
            nodesById.setAccessible(true);
            ((Map<String, SkillTreeNodeDefinition>) nodesById.get(service)).put(node.id(), node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private AstPlayer astPlayer(UUID accountId) {
        Player bukkitPlayer = server().addPlayer();
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserModel user = new UserModel(
                userId,
                bukkitPlayer.getName(),
                now,
                now,
                "127.0.0.1",
                accountId,
                false,
                null,
                false,
                0,
                now,
                now,
                userId,
                userId,
                false
        );
        AccountModel account = new AccountModel(
                accountId,
                userId,
                "test-account",
                0,
                true,
                AccountMode.PLAYER,
                "[]",
                now,
                now,
                userId,
                userId,
                false,
                2,
                0L,
                "adventurer",
                1,
                0L
        );
        return new AstPlayer(bukkitPlayer, user, account);
    }
}
