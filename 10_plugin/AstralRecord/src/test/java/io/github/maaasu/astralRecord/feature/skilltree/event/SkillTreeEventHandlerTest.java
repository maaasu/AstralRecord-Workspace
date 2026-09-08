package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeUnlockCondition;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeEventHandlerTest {
    private SkillTreeService service;
    private Player player;
    private PlayerInteractionSnapshot snapshot;
    private PlayerInputContext<PlayerInteractionSnapshot> context;

    @BeforeEach
    void setUp() {
        service = mock(SkillTreeService.class);
        player = mock(Player.class);
        snapshot = new PlayerInteractionSnapshot(
            player,
            mock(Event.class),
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false,
            PlayerInteractionRayTrace.create(new Vector(), new Vector(0.0D, 0.0D, 1.0D), 8.0D),
            8.0D
        );

        when(player.getUniqueId()).thenReturn(
            UUID.fromString("00000000-0000-0000-0000-000000000581")
        );
        when(player.isOnline()).thenReturn(true);
        when(service.isPlayerModeSkillTree(player)).thenReturn(true);

        context = new PlayerInputContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000581"),
            1L,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            snapshot
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: 視線上にskill tree nodeがなければ入力候補を返さない。
     */
    @Test
    void returnsNoCandidateWhenPlayerDoesNotTargetSkillTreeNode() {
        when(service.findTargetedPositionHit(snapshot)).thenReturn(Optional.empty());

        Collection<PlayerInputCandidate> candidates = new SkillTreeEventHandler(service).resolve(context);

        assertTrue(candidates.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: node hit時にnode IDとray入口距離を持つplayer-control候補を1件返す。
     */
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: SQL ACK 前はスキルツリー解除通知を送信しない。
     */
    @Test
    void returnsPlayerControlCandidateWhenPlayerTargetsSkillTreeNode() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        when(service.findTargetedPositionHit(snapshot)).thenReturn(Optional.of(
            new SkillTreeService.SkillTreePositionHit(position, 2.5D)
        ));
        when(service.getNode("1000")).thenReturn(node("1000"));

        Collection<PlayerInputCandidate> candidates = new SkillTreeEventHandler(service).resolve(context);

        assertEquals(1, candidates.size());
        PlayerInputCandidate candidate = candidates.iterator().next();
        assertEquals("skill-tree-player-control", candidate.id());
        assertEquals("1000", candidate.targetKey());
        assertEquals(2.5D, candidate.hitDistance());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: winner選択後は解決済みnode/positionを使いray targetを再選択しない。
     */
    @Test
    void executesResolvedNodeWithoutTargetingItAgainAfterWinnerSelection() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit =
            new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = node("1000");
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
            .thenReturn(Optional.of(hit));
        when(service.getNode("1000")).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.hasAvailableUnlockPoint(astPlayer)).thenReturn(true);
        when(service.canUnlockNode(astPlayer, node)).thenReturn(true);
        stubAccount(astPlayer);
        when(service.unlockNodeAsync(astPlayer, node)).thenReturn(successfulMutation());
        when(service.availablePassivePoints(astPlayer)).thenReturn(4);

        PlayerInputContext<PlayerInteractionSnapshot> leftClickContext = new PlayerInputContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000581"),
            2L,
            InputFamily.LEFT_CLICK,
            InputSource.PRE_PLAYER_ATTACK_ENTITY,
            snapshot
        );
        PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
            .resolve(leftClickContext)
            .iterator()
            .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }

        verify(service).preloadState(astPlayer);
        verify(service).unlockNodeAsync(astPlayer, node);
        verify(service).acknowledgeCommittedNodeMutation(astPlayer, node,
            new SkillTreeService.SkillTreeMutationResult(true, Set.of()), true);
        verify(messageService).send(player, PlayerMsgId.P_5824, "Test Node", "PP", 4);
        verify(service, never()).findTargetedNode(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: CPノード解放成功通知は、実際の消費元クラスの表示名と解放後CP残高を含む。
     */
    @Test
    void unlockMessageUsesConsumedClassPointBalance() {
        String nodeId = "1000";
        SkillTreePosition position = new SkillTreePosition(nodeId, "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit =
            new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = new SkillTreeNodeDefinition(
            nodeId,
            "Test Node",
            Material.STONE,
            List.of(),
            List.of(),
            SkillTreePointType.CLASS_POINT,
            1,
            new SkillTreeUnlockCondition("adventurer", 0),
            List.of()
        );
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
            .thenReturn(Optional.of(hit));
        when(service.getNode(nodeId)).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.hasAvailableUnlockPoint(astPlayer)).thenReturn(true);
        when(service.canUnlockNode(astPlayer, node)).thenReturn(true);
        stubAccount(astPlayer);
        when(service.unlockNodeAsync(astPlayer, node)).thenReturn(successfulMutation());
        when(service.cpSourceOptions(astPlayer)).thenReturn(List.of(
            new SkillTreeService.CpSourceOption("adventurer", "&6冒険者", 3, 2)
        ));

        PlayerInputContext<PlayerInteractionSnapshot> leftClickContext = new PlayerInputContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000581"),
            5L,
            InputFamily.LEFT_CLICK,
            InputSource.PRE_PLAYER_ATTACK_ENTITY,
            snapshot
        );
        PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
            .resolve(leftClickContext)
            .iterator()
            .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }

        verify(messageService).send(player, PlayerMsgId.P_5824, "Test Node", "CP[冒険者]", 2);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: 解放ポイント残高が0でも、rootまたは無料PP nodeのpointCostが0なら最終可否判定を経て解放する。
     */
    @Test
    void unlocksRootAndFreePassiveNodesWithoutAvailablePoints() {
        for (String nodeId : List.of("1047", "1048")) {
            SkillTreePosition position = new SkillTreePosition(nodeId, "skill_tree", 0, 64, 0);
            SkillTreeService.SkillTreePositionHit hit =
                new SkillTreeService.SkillTreePositionHit(position, 2.5D);
            SkillTreeNodeDefinition node = node(nodeId, 0);
            AstPlayer astPlayer = mock(AstPlayer.class);
            PlayerMessageService messageService = mock(PlayerMessageService.class);

            allowSnapshotRefresh();
            when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
                .thenReturn(Optional.of(hit));
            when(service.getNode(nodeId)).thenReturn(node);
            when(service.isStateReady(astPlayer)).thenReturn(true);
            when(service.hasAvailableUnlockPoint(astPlayer)).thenReturn(false);
            when(service.canUnlockNode(astPlayer, node)).thenReturn(true);
            stubAccount(astPlayer);
            when(service.unlockNodeAsync(astPlayer, node)).thenReturn(successfulMutation());

            PlayerInputContext<PlayerInteractionSnapshot> leftClickContext = new PlayerInputContext<>(
                UUID.fromString("00000000-0000-0000-0000-000000000581"),
                3L,
                InputFamily.LEFT_CLICK,
                InputSource.PRE_PLAYER_ATTACK_ENTITY,
                snapshot
            );
            PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
                .resolve(leftClickContext)
                .iterator()
                .next();

            try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
                 MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
                cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

                assertTrue(candidate.executeIfValid());
            }

            verify(service, never()).hasAvailableUnlockPoint(astPlayer);
            verify(service).unlockNodeAsync(astPlayer, node);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: 解放ポイント残高が0でpointCostが1のPP nodeを指定した場合、最終可否判定や解放を行わず拒否する。
     */
    @Test
    void rejectsPaidPassiveNodeWithoutAvailablePoints() {
        String nodeId = "1056";
        SkillTreePosition position = new SkillTreePosition(nodeId, "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit =
            new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = node(nodeId, 1);
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
            .thenReturn(Optional.of(hit));
        when(service.getNode(nodeId)).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.hasAvailableUnlockPoint(astPlayer)).thenReturn(false);
        stubAccount(astPlayer);

        PlayerInputContext<PlayerInteractionSnapshot> leftClickContext = new PlayerInputContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000581"),
            4L,
            InputFamily.LEFT_CLICK,
            InputSource.PRE_PLAYER_ATTACK_ENTITY,
            snapshot
        );
        PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
            .resolve(leftClickContext)
            .iterator()
            .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }

        verify(service).hasAvailableUnlockPoint(astPlayer);
        verify(service, never()).canUnlockNode(astPlayer, node);
        verify(service, never()).unlockNodeAsync(astPlayer, node);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: 右click winnerは確認省略状態では解決済みnodeのrelockを実行する。
     */
    @Test
    void rightClickExecutesRelockForTheResolvedNode() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit =
            new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = node("1000");
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000581"));

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
            .thenReturn(Optional.of(hit));
        when(service.getNode("1000")).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.isNodeUnlocked(astPlayer, node)).thenReturn(true);
        when(service.canAffordRelock(astPlayer)).thenReturn(true);
        when(service.relockNodeAsync(astPlayer, node)).thenReturn(successfulMutation());
        SkillTreeEventHandler handler = new SkillTreeEventHandler(service);
        suppressRelockConfirmation(handler);

        PlayerInputCandidate candidate = handler
            .resolve(context)
            .iterator()
            .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }

        verify(service).relockNodeAsync(astPlayer, node);
        verify(service, never()).findTargetedNode(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: SQL ACK 前はスキルツリー解除通知を送信しない。
     */
    @Test
    void unlockDoesNotNotifyBeforeSqlAck() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit = new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = node("1000");
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        CompletableFuture<SkillTreeService.SkillTreeMutationResult> pending = new CompletableFuture<>();

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class))).thenReturn(Optional.of(hit));
        when(service.getNode("1000")).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.hasAvailableUnlockPoint(astPlayer)).thenReturn(true);
        when(service.canUnlockNode(astPlayer, node)).thenReturn(true);
        stubAccount(astPlayer);
        when(service.unlockNodeAsync(astPlayer, node)).thenReturn(pending);
        when(service.availablePassivePoints(astPlayer)).thenReturn(4);

        PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
                .resolve(new PlayerInputContext<>(
                        player.getUniqueId(), 6L, InputFamily.LEFT_CLICK,
                        InputSource.PRE_PLAYER_ATTACK_ENTITY, snapshot
                ))
                .iterator()
                .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
            verify(service, never()).acknowledgeCommittedNodeMutation(any(), any(), any(), anyBoolean());
            verify(messageService, never()).send(player, PlayerMsgId.P_5824, "Test Node", "PP", 4);

            pending.complete(new SkillTreeService.SkillTreeMutationResult(true, Set.of()));
        }

        verify(service).acknowledgeCommittedNodeMutation(astPlayer, node,
                new SkillTreeService.SkillTreeMutationResult(true, Set.of()), true);
        verify(messageService).send(player, PlayerMsgId.P_5824, "Test Node", "PP", 4);
    }

    @SuppressWarnings("unchecked")
    private void suppressRelockConfirmation(SkillTreeEventHandler handler) {
        try {
            var field = SkillTreeEventHandler.class.getDeclaredField("relockConfirmationSuppressed");
            field.setAccessible(true);
            ((java.util.Set<UUID>) field.get(handler)).add(player.getUniqueId());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void stubAccount(AstPlayer astPlayer) {
        AccountModel account = mock(AccountModel.class);
        UUID accountId = player.getUniqueId();
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
    }

    private CompletableFuture<SkillTreeService.SkillTreeMutationResult> successfulMutation() {
        return CompletableFuture.completedFuture(new SkillTreeService.SkillTreeMutationResult(true, Set.of()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: スキルツリーから別ワールドへ移動した場合、プレイヤー専用表示の再同期を要求する。
     */
    @Test
    void worldChangeOutOfSkillTreeRefreshesViewerPresentation() {
        PlayerChangedWorldEvent event = mock(PlayerChangedWorldEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(service.isPlayerModeSkillTree(player)).thenReturn(false);

        new SkillTreeEventHandler(service).onWorldChange(event);

        verify(service).refreshPlayerVisibility(player);
        verify(service).clearPlayerPresentation(player);
        verify(service).markViewerContextDirty(player);
    }

    private void allowSnapshotRefresh() {
        World world = mock(World.class);
        Location eye = new Location(world, 0.0D, 64.0D, 0.0D);
        eye.setDirection(new Vector(0.0D, 0.0D, 1.0D));
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.getWorld()).thenReturn(world);
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            anyDouble(),
            any(FluidCollisionMode.class),
            anyBoolean()
        )).thenReturn(null);
    }

    private SkillTreeNodeDefinition node(String nodeId) {
        return node(nodeId, 1);
    }

    private SkillTreeNodeDefinition node(String nodeId, int pointCost) {
        return new SkillTreeNodeDefinition(
            nodeId,
            "Test Node",
            Material.STONE,
            List.of(),
            List.of(),
            SkillTreePointType.PASSIVE_POINT,
            pointCost,
            List.of()
        );
    }
}
