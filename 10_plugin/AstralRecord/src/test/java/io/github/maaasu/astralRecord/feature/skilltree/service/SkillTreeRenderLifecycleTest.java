package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.*;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillTreeRenderLifecycleTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 10. スキルツリーノードの強調・絞り込み・簡易表示
     * 検証契約: 複数edgeが一度の状態計算を共有し、未解放nodeの解放可否を判定せず、距離外では状態を参照しない。
     */
    @Test
    void edgesShareOneSnapshotAndSkipUnlockChecksAndDistantNodes() {
        try (Fixture f = new Fixture()) {
            f.start();
            verify(f.service, times(1)).createNodePresentationSnapshot(f.ast);
            verify(f.service, never()).nodePresentationState(any(AstPlayer.class), any());
            verify(f.service, never()).nodePresentationState(any(SkillTreeService.NodePresentationSnapshot.class), any());
            verify(f.service, never()).canUnlockNode(any(), any());
            verify(f.service, never()).getNode("9000");
            assertEquals(3, f.entities.size());
            verify(f.entities.get(0)).spawn(f.player);
            verify(f.entities.get(1)).spawn(f.player);
            verify(f.entities.get(2), never()).spawn(any());
            verify(f.packets.constructed().get(0)).updateBlock(f.player, f.entities.get(0), Material.BLUE_STAINED_GLASS);
            verify(f.packets.constructed().get(0)).updateBlock(f.player, f.entities.get(1), Material.RED_STAINED_GLASS);
            when(f.service.createNodePresentationSnapshot(f.ast)).thenReturn(new SkillTreeService.NodePresentationSnapshot(
                    f.ast, new SkillTreePlayerState(f.player.getUniqueId(), Set.of("1000", "1001")), false,
                    Set.of("1000", "1001"), Set.of(), 0, Map.of()));
            f.visualizer.markViewerDirty(f.player.getUniqueId());
            server().getScheduler().performTicks(20);
            verify(f.packets.constructed().get(0)).updateBlock(f.player, f.entities.get(0), Material.LIME_STAINED_GLASS);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 10. スキルツリーノードの強調・絞り込み・簡易表示
     * 検証契約: 通常更新は20tick、移動dirtyでは構造再走査を行わず、全体更新は両通知順で部分更新より優先する。
     */
    @Test
    void fullRefreshWinsBothNotificationOrdersWithoutResyncingStructure() {
        try (Fixture f = new Fixture()) {
            f.start();
            for (boolean partialFirst : List.of(true, false)) {
                clearInvocations(f.service);
                if (partialFirst) f.visualizer.markNodeStateDirty(f.player.getUniqueId(), Set.of("unrelated"));
                f.visualizer.markViewerDirty(f.player.getUniqueId());
                if (!partialFirst) f.visualizer.markNodeStateDirty(f.player.getUniqueId(), Set.of("unrelated"));
                server().getScheduler().performTicks(10);
                verify(f.service, never()).createNodePresentationSnapshot(any());
                server().getScheduler().performTicks(10);
                verify(f.service, times(1)).createNodePresentationSnapshot(f.ast);
                verify(f.service, never()).getPositions();
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 10. スキルツリーノードの強調・絞り込み・簡易表示
     * 検証契約: 部分更新は移動基準を進めず、微小移動の合計変位1blockで全体更新し、退出時に状態を破棄する。
     */
    @Test
    void partialRefreshDoesNotResetMovementBaseline() {
        try (Fixture f = new Fixture()) {
            f.start();
            Location origin = f.player.getLocation();
            Location halfway = origin.clone().add(0.6, 0, 0);
            f.player.teleport(halfway);
            f.visualizer.markViewerMoved(f.player, halfway);
            f.visualizer.markNodeStateDirty(f.player.getUniqueId(), Set.of("1000"));
            server().getScheduler().performTicks(20);
            clearInvocations(f.service);
            Location oneBlock = origin.clone().add(1, 0, 0);
            f.player.teleport(oneBlock);
            f.visualizer.markViewerMoved(f.player, oneBlock);
            server().getScheduler().performTicks(20);
            verify(f.service, times(1)).createNodePresentationSnapshot(f.ast);
            clearInvocations(f.service);
            f.visualizer.markViewerMoved(f.player, oneBlock.clone().add(0.1, 0, 0));
            server().getScheduler().performTicks(20);
            verify(f.service, never()).createNodePresentationSnapshot(any());
            f.visualizer.removeViewer(f.player.getUniqueId());
            f.visualizer.markViewerMoved(f.player, oneBlock);
            server().getScheduler().performTicks(20);
            verify(f.service, times(1)).createNodePresentationSnapshot(f.ast);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 10. スキルツリーノードの強調・絞り込み・簡易表示
     * 検証契約: chunk再同期は静止viewerの部分更新を全体へ昇格し、新たなedgeを送信する。
     */
    @Test
    void structuralResyncShowsNewEdgesToStationaryViewer() {
        try (Fixture f = new Fixture()) {
            f.start();
            SkillTreeEdge newEdge = new SkillTreeEdge("1000", "1002");
            List<SkillTreeEdge> expanded = new ArrayList<>(f.edges);
            expanded.add(newEdge);
            when(f.service.getEdges()).thenReturn(expanded);
            f.visualizer.markNodeStateDirty(f.player.getUniqueId(), Set.of("unrelated"));
            f.visualizer.markStructureDirty();
            server().getScheduler().performTicks(20);
            assertEquals(4, f.entities.size());
            verify(f.entities.get(3)).spawn(f.player);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 9. スキルツリー表示フォールバック
     * 検証契約: 通常更新を20tickにしてもBedrockの可視edge粒子は10tickごとに維持し、BlockDisplayを送らない。
     */
    @Test
    void bedrockParticlesKeepTenTickCadence() {
        try (Fixture f = new Fixture()) {
            when(f.ast.isBedrock()).thenReturn(true);
            f.start();
            clearInvocations(f.particles);
            server().getScheduler().performTicks(10);
            verify(f.particles, times(2)).spawnForViewer(eq(f.ast), anyList(), any());
            clearInvocations(f.particles);
            server().getScheduler().performTicks(10);
            verify(f.particles, times(2)).spawnForViewer(eq(f.ast), anyList(), any());
            for (var entity : f.entities) verify(entity, never()).spawn(any());
        }
    }

    private final class Fixture implements AutoCloseable {
        final SkillTreeService service = mock(SkillTreeService.class);
        final ParticleDisplayService particles = mock(ParticleDisplayService.class);
        final AstPlayer ast = mock(AstPlayer.class);
        final Player player;
        final List<SkillTreeEdge> edges = List.of(new SkillTreeEdge("1000", "1001"),
                new SkillTreeEdge("1001", "1002"), new SkillTreeEdge("9000", "9001"));
        final List<SkillTreePacketDisplay.PacketEntity> entities = new ArrayList<>();
        final MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
        final MockedStatic<Logger> logger = mockStatic(Logger.class);
        final MockedConstruction<SkillTreePacketDisplay> packets;
        final SkillTreeVisualizer visualizer;

        /** 固定edgeとpacket送信mockだけで実schedulerを起動できる状態を作ります。 */
        Fixture() {
            var world = server().addSimpleWorld("skill_tree");
            world.loadChunk(0, 0);
            world.loadChunk(12, 0);
            player = server().addPlayer();
            player.teleport(new Location(world, 0, 64, 0));
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(ast);
            when(service.isPlayerModeSkillTree(player)).thenReturn(true);
            when(service.isSkillTreeVisualReady(player)).thenReturn(true);
            when(service.isNodeVisible(eq(ast), any())).thenReturn(true);
            when(service.viewDistance()).thenReturn(48);
            when(service.getPositions()).thenReturn(List.of());
            when(service.getEdges()).thenReturn(edges);
            for (String id : List.of("1000", "1001", "1002", "9000", "9001")) {
                int x = id.startsWith("9") ? 200 + Integer.parseInt(id) - 9000 : (Integer.parseInt(id) - 1000) * 3;
                when(service.getPosition(id)).thenReturn(new SkillTreePosition(id, world.getName(), x, 64, 0));
                when(service.getNode(id)).thenReturn(new SkillTreeNodeDefinition(id, "Node", Material.STONE,
                        List.of(), List.of(), SkillTreePointType.PASSIVE_POINT, 0, List.of()));
            }
            when(service.createNodePresentationSnapshot(ast)).thenReturn(new SkillTreeService.NodePresentationSnapshot(
                    ast, new SkillTreePlayerState(player.getUniqueId(), Set.of("1000")), false,
                    Set.of("1000"), Set.of(), 1, Map.of()));
            packets = mockConstruction(SkillTreePacketDisplay.class, (display, context) ->
                    when(display.block(any(), any(), any())).thenAnswer(invocation -> {
                        var entity = mock(SkillTreePacketDisplay.PacketEntity.class);
                        entities.add(entity);
                        return entity;
                    }));
            visualizer = new SkillTreeVisualizer(MockBukkit.createMockPlugin(), service, particles);
        }

        /** 初回の構造同期とviewer描画まで実行します。 */
        void start() {
            visualizer.start();
            server().getScheduler().performTicks(1);
        }

        @Override
        /** schedulerとテスト専用のstatic/constructor mockを破棄します。 */
        public void close() {
            visualizer.stop();
            packets.close();
            logger.close();
            cache.close();
        }
    }
}
