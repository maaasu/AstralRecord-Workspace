package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeRuntimeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillTreeStateLockOrderTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### 保存参加機能のロック順序
     * 検証契約: 表示処理がinventory取得を待つ間も、inventory保持中の保存処理はSkillTreeへ入れる。
     */
    @Test
    void runtimeViewDoesNotHoldSkillTreeWhileWaitingForInventory() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch acquiringInventory = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(fixture.tree));
            acquiringInventory.countDown();
            return invocation.callRealMethod();
        }).when(fixture.inventory).withPlayerStateLock(eq(fixture.accountId), any());
        RuntimeException viewReached = new RuntimeException("view evaluation reached");
        when(fixture.world.findByBukkitWorld(any())).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(fixture.state));
            assertTrue(Thread.holdsLock(fixture.tree));
            throw viewReached;
        });
        // Bukkitサーバーやマスターは起動せず、表示内容の生成直前で検証を終える。
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-view-lock-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            java.util.concurrent.Future<?> view;
            synchronized (fixture.state) {
                view = executor.submit(() -> assertSame(viewReached,
                    assertThrows(RuntimeException.class, () -> fixture.tree.createRuntimePlayerView(fixture.player))));
                assertTrue(acquiringInventory.await(5, TimeUnit.SECONDS));
                // 本番保存と同じ inventory -> section capture を、view の取得待ち中に完了させる。
                assertNull(fixture.tree.snapshotPlayerState(fixture.accountId));
            }
            view.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### 保存参加機能のロック順序
     * 検証契約: 退出最終viewはinventoryから同期し、session-close通信時には両monitorを解放する。
     */
    @Test
    void logoutReleasesStateLocksBeforeClosingRuntimeSession() throws Exception {
        Fixture fixture = new Fixture();
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(fixture.tree));
            return invocation.callRealMethod();
        }).when(fixture.inventory).withPlayerStateLock(eq(fixture.accountId), any());
        SkillTreeRuntimeRepository runtime = mock(SkillTreeRuntimeRepository.class);
        setField(fixture.tree, "runtimeRepository", runtime);
        // master公開中の既存分岐を利用し、コンテンツに依存しない空の最終viewを生成する。
        setField(fixture.tree, "masterPublicationInProgress", true);
        Field sessionsField = SkillTreeService.class.getDeclaredField("runtimeAccountSessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, SkillTreeRuntimeRepository.AccountSession> sessions =
            (Map<UUID, SkillTreeRuntimeRepository.AccountSession>) sessionsField.get(fixture.tree);
        sessions.put(fixture.accountId, SkillTreeRuntimeRepository.AccountSession.create());
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(fixture.state));
            assertFalse(Thread.holdsLock(fixture.tree));
            return null;
        }).when(runtime).closeAccount(any(), any(), eq(fixture.accountId), any(), any(), anyLong());

        fixture.tree.finishRuntimeLogout(fixture.player);

        verify(fixture.inventory).withPlayerStateLock(eq(fixture.accountId), any());
        verify(runtime).closeAccount(any(), any(), eq(fixture.accountId), any(), any(), anyLong());
        assertFalse(sessions.containsKey(fixture.accountId));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture {
        final UUID accountId = UUID.randomUUID();
        final PlayerInventoryState state = new PlayerInventoryState(accountId);
        final WorldService world = mock(WorldService.class);
        final AstPlayer player = mock(AstPlayer.class);
        final InventoryService inventory;
        final SkillTreeService tree;

        Fixture() {
            PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
            registry.put(state);
            inventory = spy(new InventoryService(mock(InventoryRepository.class), mock(EquipmentLoadoutRepository.class),
                mock(ItemService.class), mock(ItemStackFactory.class), registry, mock(InventoryPersistence.class),
                mock(InventorySaveCoordinator.class)));
            Plugin plugin = mock(Plugin.class);
            when(plugin.namespace()).thenReturn("astralrecord");
            tree = new SkillTreeService(plugin, world, inventory, mock(SkillTreeNodeRepository.class),
                mock(SkillTreeStructureRepository.class), mock(SkillTreePlayerStateRepository.class));
            AccountModel account = mock(AccountModel.class);
            when(account.getUuid()).thenReturn(accountId);
            when(player.getAccount()).thenReturn(account);
            when(player.getBukkit()).thenReturn(mock(Player.class));
        }
    }
}
