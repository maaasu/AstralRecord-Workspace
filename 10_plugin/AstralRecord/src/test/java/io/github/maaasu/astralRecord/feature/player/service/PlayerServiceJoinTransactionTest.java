package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServiceJoinTransactionTest {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    @Test
    void regionInitializationFailureRollsBackPublishedCacheAndNewInventoryState() {
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        InventorySaveCoordinator saveCoordinator = mock(InventorySaveCoordinator.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PlayerRegionService regionService = mock(PlayerRegionService.class);
        Player player = player(playerId);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        PlayerService service = service(registry, saveCoordinator, inventoryService, regionService);
        PlayerService.PlayerJoinData joinData = joinData(accountId, state, null);
        doThrow(new IllegalStateException("region initialization failed"))
            .when(regionService).initializeRegion(org.mockito.ArgumentMatchers.any());

        assertThrows(
            IllegalStateException.class,
            () -> service.applyPlayerJoinTransactional(player, joinData)
        );

        assertNull(AstPlayerCache.get(playerId));
        assertNull(registry.get(accountId));
        verify(regionService).clearPlayer(playerId);
        verify(inventoryService).clearClickGuard(accountId);
    }

    @Test
    void failedJoinReturnsClaimedRetainedInventoryLeaseWithoutRemovingItsRegistryState() {
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        InventorySaveCoordinator saveCoordinator = mock(InventorySaveCoordinator.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PlayerRegionService regionService = mock(PlayerRegionService.class);
        Player player = player(playerId);
        PlayerInventoryState retainedState = new PlayerInventoryState(accountId);
        InventorySaveCoordinator.RetainedStateLease retainedLease =
            new InventorySaveCoordinator.RetainedStateLease(accountId, retainedState, 1L);
        registry.put(retainedState);
        PlayerService service = service(registry, saveCoordinator, inventoryService, regionService);
        PlayerService.PlayerJoinData joinData = joinData(accountId, retainedState, retainedLease);
        doThrow(new IllegalStateException("region initialization failed"))
            .when(regionService).initializeRegion(org.mockito.ArgumentMatchers.any());

        assertThrows(
            IllegalStateException.class,
            () -> service.applyPlayerJoinTransactional(player, joinData)
        );

        assertSame(retainedState, registry.get(accountId));
        verify(saveCoordinator).releaseRetainedStateLease(retainedLease);
    }

    @Test
    void immediateRelogClaimsSameDirtyStateBeforeOldJoinFinallyReleasesItsLease() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState retainedState = new PlayerInventoryState(accountId);
        retainedState.markDirty();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(retainedState);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        when(persistence.hasPendingChanges(retainedState)).thenReturn(true);
        InventorySaveCoordinator saveCoordinator = new InventorySaveCoordinator(
            persistence,
            registry,
            Runnable::run
        );
        PlayerService service = new PlayerService(
            mock(UserService.class),
            mock(AccountService.class),
            mock(InventoryService.class),
            saveCoordinator,
            persistence,
            registry,
            mock(StatusService.class),
            mock(PlayerSaveCoordinator.class),
            mock(PlayerRegionService.class)
        );
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);

        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            assertFalse(saveCoordinator.saveOnLogout(accountId, retainedState, () -> {
            }).join());

            PlayerService.PlayerJoinInventoryState oldAttempt = service.loadPlayerJoinInventoryState(account);
            PlayerService.PlayerJoinInventoryState relogAttempt = service.loadPlayerJoinInventoryState(account);

            assertSame(retainedState, oldAttempt.state());
            assertSame(retainedState, relogAttempt.state());
            assertNotNull(oldAttempt.retainedLease());
            assertNotNull(relogAttempt.retainedLease());
            assertNotEquals(oldAttempt.retainedLease().generation(), relogAttempt.retainedLease().generation());

            service.discardPlayerJoinInventoryState(oldAttempt);

            assertSame(retainedState, registry.get(accountId));
            assertTrue(saveCoordinator.commitRetainedStateLease(relogAttempt.retainedLease()));
            assertSame(retainedState, registry.get(accountId));
            verify(persistence, never()).load(accountId);
        }
    }

    private PlayerService service(
        PlayerInventoryStateRegistry registry,
        InventorySaveCoordinator saveCoordinator,
        InventoryService inventoryService,
        PlayerRegionService regionService
    ) {
        return new PlayerService(
            mock(UserService.class),
            mock(AccountService.class),
            inventoryService,
            saveCoordinator,
            mock(InventoryPersistence.class),
            registry,
            mock(StatusService.class),
            mock(PlayerSaveCoordinator.class),
            regionService
        );
    }

    private PlayerService.PlayerJoinData joinData(
        UUID accountId,
        PlayerInventoryState state,
        InventorySaveCoordinator.RetainedStateLease retainedLease
    ) {
        UserModel user = mock(UserModel.class);
        AccountModel account = mock(AccountModel.class);
        when(user.getPermission()).thenReturn(0);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(account.getClassProgresses()).thenReturn(List.of());
        when(account.getClassId()).thenReturn("adventurer");
        when(account.getClassLevel()).thenReturn(1);
        when(account.getClassExperience()).thenReturn(0L);
        return new PlayerService.PlayerJoinData(
            user,
            account,
            new PlayerService.PlayerJoinInventoryState(state, retainedLease)
        );
    }

    private Player player(UUID playerId) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("join-transaction");
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        return player;
    }
}
