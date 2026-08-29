package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.ClassProgressModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeStatusEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeUnlockCondition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: 現在構造にない解放済みnodeを含むログイン状態は、API補修で全解除へ置換する。
     */
    @Test
    void initialLoadRepairsStateContainingDeletedNode() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreePlayerState invalidState = new SkillTreePlayerState(accountId, Set.of("1000", "9999"));
        SkillTreePlayerState repairedState = new SkillTreePlayerState(accountId, Set.of());
        SkillTreePlayerStateRepository stateRepository = mock(SkillTreePlayerStateRepository.class);
        SkillTreeService service = newService(root, stateRepository);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                "1000",
                List.of(root),
                List.of(new SkillTreePosition("1000", "skill_tree", 0, 64, 0)),
                List.of()
        ));
        when(stateRepository.load(accountId)).thenReturn(invalidState);
        when(stateRepository.repairInvalidState(eq(accountId), eq(userId), any(String.class)))
                .thenReturn(repairedState);

        SkillTreePlayerState result = service.loadInitialPlayerState(accountId, userId);

        assertTrue(result.unlockedNodeIds().isEmpty());
        verify(stateRepository).repairInvalidState(eq(accountId), eq(userId), any(String.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: rootから到達できない解放済みnodeを含むログイン状態は、全解除へ補修する。
     */
    @Test
    void initialLoadRepairsDisconnectedUnlockedNodes() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreeNodeDefinition disconnected = node("1001");
        SkillTreePlayerState invalidState = new SkillTreePlayerState(accountId, Set.of("1000", "1001"));
        SkillTreePlayerState repairedState = new SkillTreePlayerState(accountId, Set.of());
        SkillTreePlayerStateRepository stateRepository = mock(SkillTreePlayerStateRepository.class);
        SkillTreeService service = newService(root, stateRepository);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                "1000",
                List.of(root, disconnected),
                List.of(
                        new SkillTreePosition("1000", "skill_tree", 0, 64, 0),
                        new SkillTreePosition("1001", "skill_tree", 3, 64, 0)
                ),
                List.of()
        ));
        when(stateRepository.load(accountId)).thenReturn(invalidState);
        when(stateRepository.repairInvalidState(eq(accountId), eq(userId), any(String.class)))
                .thenReturn(repairedState);

        SkillTreePlayerState result = service.loadInitialPlayerState(accountId, userId);

        assertTrue(result.unlockedNodeIds().isEmpty());
        verify(stateRepository).repairInvalidState(eq(accountId), eq(userId), any(String.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: 同じnode IDの定義変更や未選択node追加で到達可能性が維持される状態は補修しない。
     */
    @Test
    void initialLoadKeepsStructurallyValidState() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreeNodeDefinition child = node("1001");
        SkillTreePlayerState validState = new SkillTreePlayerState(accountId, Set.of("1000", "1001"));
        SkillTreePlayerStateRepository stateRepository = mock(SkillTreePlayerStateRepository.class);
        SkillTreeService service = newService(root, stateRepository);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                "1000",
                List.of(root, child, node("1002")),
                List.of(
                        new SkillTreePosition("1000", "skill_tree", 0, 64, 0),
                        new SkillTreePosition("1001", "skill_tree", 3, 64, 0),
                        new SkillTreePosition("1002", "skill_tree", 6, 64, 0)
                ),
                List.of(new SkillTreeEdge("1000", "1001"))
        ));
        when(stateRepository.load(accountId)).thenReturn(validState);

        SkillTreePlayerState result = service.loadInitialPlayerState(accountId, userId);

        assertEquals(Set.of("1000", "1001"), result.unlockedNodeIds());
        verify(stateRepository, never()).repairInvalidState(any(), any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: master置換時にstate未loadのonline playerへ派生効果/status refreshを適用しない。
     */
    @Test
    void replaceMasterDataSnapshotSkipsOnlineCacheWhenNoPlayerStateIsLoaded() {
        SkillTreeService service = newService(null);

        try (MockedStatic<AstPlayerCache> cache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            service.replaceMasterDataSnapshot(emptyMasterDataSnapshot());

            cache.verifyNoInteractions();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: lore未定義のnodeは、ワールド表示の説明行を追加しない。
     */
    @Test
    void nodeFieldLabelOmitsDescriptionWhenLoreIsUndefined() {
        SkillTreeNodeDefinition node = node("1000");
        SkillTreeService service = newService(node);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                node.nodeId(),
                List.of(node),
                List.of(),
                List.of()
        ));

        String label = PlainTextComponentSerializer.plainText().serialize(
                service.nodeFieldLabel(
                        node,
                        SkillTreeService.NodePresentationState.UNLOCKED,
                        SkillTreeService.NodeLabelDetail.DETAILED
                )
        );

        assertEquals("Test Node", label);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: classId付きCPノードはクラス名付きCostを表示し、コスト値だけを黄色で表示する。
     */
    @Test
    void nodeDisplaysClassPointConditionAndYellowCost() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition node = new SkillTreeNodeDefinition(
                "1000",
                "Test Node",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.CLASS_POINT,
                1,
                new SkillTreeUnlockCondition("adventurer", 0),
                List.of()
        );
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getDisplayName("adventurer")).thenReturn("&6冒険者");
        SkillTreeService service = newService(node);
        service.setPlayerClassService(playerClassService);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                node.nodeId(),
                List.of(node),
                List.of(),
                List.of()
        ));

        String label = PlainTextComponentSerializer.plainText().serialize(
                service.nodeFieldLabel(
                        node,
                        SkillTreeService.NodePresentationState.UNLOCKED,
                        SkillTreeService.NodeLabelDetail.DETAILED
                )
        );
        assertEquals("Test Node\nCost: CP[冒険者] 1", label);
        assertTrue(hasYellowText(
                service.nodeFieldLabel(
                        node,
                        SkillTreeService.NodePresentationState.UNLOCKED,
                        SkillTreeService.NodeLabelDetail.DETAILED
                ),
                "1"
        ));

        AstPlayer player = astPlayer(accountId);
        when(player.getClassId()).thenReturn("adventurer");
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));
        var itemMeta = Objects.requireNonNull(invokeNodeHotbarItem(service, player, node).getItemMeta());
        var itemLore = Objects.requireNonNull(itemMeta.lore());
        assertTrue(itemLore.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch("消費: CP[冒険者] 1"::equals));
        assertTrue(itemLore.stream().anyMatch(line -> hasYellowText(line, "1")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: classId付きでコスト0のノードも、ワールド詳細ラベルへクラス条件と黄色のコスト値を表示する。
     */
    @Test
    void nodeFieldLabelDisplaysClassConditionForZeroCost() {
        SkillTreeNodeDefinition node = new SkillTreeNodeDefinition(
                "1000",
                "Test Node",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.CLASS_POINT,
                0,
                new SkillTreeUnlockCondition("adventurer", 0),
                List.of()
        );
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        when(playerClassService.getDisplayName("adventurer")).thenReturn("&6冒険者");
        SkillTreeService service = newService(node);
        service.setPlayerClassService(playerClassService);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                node.nodeId(),
                List.of(node),
                List.of(),
                List.of()
        ));

        Component label = service.nodeFieldLabel(
                node,
                SkillTreeService.NodePresentationState.UNLOCKED,
                SkillTreeService.NodeLabelDetail.DETAILED
        );

        assertEquals("Test Node\nCost: CP[冒険者] 0", PlainTextComponentSerializer.plainText().serialize(label));
        assertTrue(hasYellowText(label, "0"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: クラスマスタ未解決時は内部classIdをプレイヤー向け表示へ出さない。
     */
    @Test
    void nodeUsesGenericClassLabelWhenClassMasterIsUnavailable() {
        SkillTreeNodeDefinition node = new SkillTreeNodeDefinition(
                "1000",
                "Test Node",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.CLASS_POINT,
                1,
                new SkillTreeUnlockCondition("adventurer", 0),
                List.of()
        );
        SkillTreeService service = newService(node);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                node.nodeId(),
                List.of(node),
                List.of(),
                List.of()
        ));

        String label = PlainTextComponentSerializer.plainText().serialize(
                service.nodeFieldLabel(
                        node,
                        SkillTreeService.NodePresentationState.UNLOCKED,
                        SkillTreeService.NodeLabelDetail.DETAILED
                )
        );

        assertEquals("Test Node\nCost: CP[未登録のクラス] 1", label);
        assertFalse(label.contains("adventurer"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: 条件付きPPノードは必要条件を表示し、条件成立時は白、未成立時は赤で表示する。
     */
    @Test
    void passiveNodeConditionIsDisplayedWithWhiteOrRedColor() {
        SkillTreeNodeDefinition node = new SkillTreeNodeDefinition(
                "1000",
                "Test Node",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.PASSIVE_POINT,
                0,
                new SkillTreeUnlockCondition(null, 7),
                List.of()
        );
        SkillTreeService service = newService(node);
        service.replaceMasterDataSnapshot(new SkillTreeService.SkillTreeMasterDataSnapshot(
                node.nodeId(),
                List.of(node),
                List.of(),
                List.of()
        ));

        Component metLabel = service.nodeFieldLabel(
                node,
                SkillTreeService.NodePresentationState.AVAILABLE,
                SkillTreeService.NodeLabelDetail.DETAILED
        );
        Component unmetLabel = service.nodeFieldLabel(
                node,
                SkillTreeService.NodePresentationState.CONDITION_BLOCKED,
                SkillTreeService.NodeLabelDetail.DETAILED
        );

        assertEquals("Test Node\n必要レベル: 7", PlainTextComponentSerializer.plainText().serialize(metLabel));
        assertEquals("Test Node\n必要レベル: 7", PlainTextComponentSerializer.plainText().serialize(unmetLabel));
        assertTrue(hasTextColor(metLabel, "必要レベル: 7", NamedTextColor.WHITE));
        assertTrue(hasTextColor(unmetLabel, "必要レベル: 7", NamedTextColor.RED));

        Component compactLabel = service.nodeFieldLabel(
                node,
                SkillTreeService.NodePresentationState.CONDITION_BLOCKED,
                SkillTreeService.NodeLabelDetail.COMPACT
        );
        assertEquals("Test Node\n必要レベル: 7", PlainTextComponentSerializer.plainText().serialize(compactLabel));
        assertTrue(hasTextColor(compactLabel, "必要レベル: 7", NamedTextColor.RED));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: lore未定義のnodeは、スキルツリーのホットバーItemStackへノード固有の説明行を追加しない。
     */
    @Test
    void nodeHotbarItemOmitsDescriptionWhenLoreIsUndefined() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition node = node("1000");
        SkillTreeService service = newService(node);
        AstPlayer player = astPlayer(accountId);
        when(player.getClassId()).thenReturn("adventurer");
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        var itemStack = invokeNodeHotbarItem(service, player, node);
        var itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        var itemLore = Objects.requireNonNull(itemMeta.lore());

        assertFalse(itemLore.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch("Lore"::equals));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: state load済みonline playerはpassive所有状態を調停してからstatus refreshする。
     */
    @Test
    void replaceMasterDataSnapshotReconcilesLoadedOnlinePlayerBeforeStatusRefresh() {
        UUID accountId = UUID.randomUUID();
        AstPlayer player = astPlayer(accountId);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        StatusService statusService = mock(StatusService.class);
        SkillTreeService service = newService(null);
        service.setPassiveSkillService(passiveSkillService);
        service.setStatusService(statusService);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("1000")));

        try (MockedStatic<AstPlayerCache> cache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(player));

            service.replaceMasterDataSnapshot(emptyMasterDataSnapshot());
        }

        InOrder order = inOrder(passiveSkillService, statusService);
        order.verify(passiveSkillService).reconcileNow(player, false);
        order.verify(statusService).refreshStatus(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: root unlock判定で永続state内のmaster未知node IDを既解放として数えない。
     */
    @Test
    void canUnlockRootIgnoresPersistedUnknownNodeIds() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreeService service = newService(root);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("9999")));

        assertTrue(service.canUnlockNode(player, root));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: relock/point判定でmaster未知node IDを現在nodeとして数えない。
     */
    @Test
    void canRelockNodeIgnoresPersistedUnknownNodeIds() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreeService service = newService(root);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("1000", "9999")));

        assertTrue(service.canRelockNode(player, root));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: passive effect node unlock時にpassive差分を調停してからstatus refreshする。
     */
    @Test
    void unlockNodeReconcilesPassiveDeltaBeforeStatusRefresh() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition passiveNode = new SkillTreeNodeDefinition(
                "1000",
                "Passive Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of(new SkillTreeSkillEffect("passive-test"))
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillTreeService service = newService(passiveNode);
        service.setPassiveSkillService(passiveSkillService);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        service.unlockNode(player, passiveNode);

        verify(passiveSkillService).reconcileSkillPermissionDelta(
                eq(player),
                eq(Set.of("passive-test")),
                eq(Set.of()),
                eq(false)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: 直接status modifier node unlock後にstatus refreshする。
     */
    @Test
    void unlockNodeRefreshesStatusForDirectNodeModifier() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition statusNode = new SkillTreeNodeDefinition(
                "1000",
                "Status Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of(new SkillTreeStatusEffect(
                        StatusType.ATTACK,
                        StatusModifierType.FLAT,
                        5.0D
                ))
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        StatusService statusService = mock(StatusService.class);
        SkillTreeService service = newService(statusNode);
        service.setPassiveSkillService(passiveSkillService);
        service.setStatusService(statusService);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        service.unlockNode(player, statusNode);

        verify(passiveSkillService).reconcileNow(player, false);
        verify(statusService).refreshStatus(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: class conditionなしclass-point nodeは消費元classの明示選択を要求する。
     */
    @Test
    void classPointNodeWithoutClassConditionRequiresExplicitSourceClass() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition classNode = new SkillTreeNodeDefinition(
                "1000",
                "Shared CP Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.CLASS_POINT,
                1,
                SkillTreeUnlockCondition.NONE,
                List.of()
        );
        SkillTreeService service = newService(classNode);
        AstPlayer player = astPlayer(accountId);
        when(player.getClassId()).thenReturn("hunter");
        when(player.getAllClassProgresses()).thenReturn(List.of(
                new ClassProgressModel("adventurer", 5, 0L),
                new ClassProgressModel("hunter", 2, 0L)
        ));
        SkillTreePlayerState state = new SkillTreePlayerState(accountId, Set.of());
        service.applyInitialPlayerState(state);

        assertTrue(service.requiresCpSourceSelection(classNode));
        assertTrue(service.canUnlockNode(player, classNode));
        assertFalse(service.unlockNode(player, classNode));
        assertTrue(service.unlockNode(player, classNode, "hunter"));
        assertEquals("hunter", state.unlockedNode("1000").consumedClassId());
        assertEquals(0, service.availableClassPoints(player));
        assertEquals(4, service.cpSourceOptions(player).stream()
                .filter(option -> option.classId().equals("adventurer"))
                .findFirst()
                .orElseThrow()
                .availablePoints());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: class condition付きnodeは条件に対応するancestor classを消費元として記録する。
     */
    @Test
    void conditionedClassPointAutomaticallyConsumesTheRequiredAncestorClass() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition classNode = new SkillTreeNodeDefinition(
                "1000",
                "Adventurer Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.CLASS_POINT,
                1,
                new SkillTreeUnlockCondition("adventurer", 0),
                List.of()
        );
        SkillTreeService service = newService(classNode);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        service.setPlayerClassService(playerClassService);
        AstPlayer player = astPlayer(accountId);
        when(player.getClassId()).thenReturn("hunter");
        when(player.getAllClassProgresses()).thenReturn(List.of(
                new ClassProgressModel("adventurer", 2, 0L),
                new ClassProgressModel("hunter", 10, 0L)
        ));
        when(playerClassService.matchesCurrentClassCondition(player, "adventurer")).thenReturn(true);
        SkillTreePlayerState state = new SkillTreePlayerState(accountId, Set.of());
        service.applyInitialPlayerState(state);

        assertTrue(service.unlockNode(player, classNode));
        assertEquals("adventurer", state.unlockedNode("1000").consumedClassId());
        assertEquals(9, service.availableClassPoints(player));
        assertEquals(0, service.cpSourceOptions(player).stream()
                .filter(option -> option.classId().equals("adventurer"))
                .findFirst()
                .orElseThrow()
                .availablePoints());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: 条件不成立の解放済みPP nodeは表示を維持し、effectを無効化する。
     */
    @Test
    void unmetPassiveNodeConditionKeepsNodeVisibleAndDisablesItsEffects() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition conditionedNode = new SkillTreeNodeDefinition(
                "1000",
                "Hunter Status",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                new SkillTreeUnlockCondition("hunter", 10),
                List.of(new SkillTreeStatusEffect(StatusType.ATTACK, StatusModifierType.FLAT, 5.0D))
        );
        SkillTreeService service = newService(conditionedNode);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        service.setPlayerClassService(playerClassService);
        AstPlayer player = astPlayer(accountId);
        AccountModel account = player.getAccount();
        when(account.getLevel()).thenReturn(12);
        AtomicBoolean classMatches = new AtomicBoolean(false);
        when(playerClassService.matchesCurrentClassCondition(player, "hunter"))
                .thenAnswer(ignored -> classMatches.get());
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("1000")));

        assertTrue(service.isNodeVisible(player, conditionedNode));
        assertEquals(
                SkillTreeService.NodePresentationState.INACTIVE_CONDITION,
                service.nodePresentationState(player, conditionedNode)
        );
        assertEquals(0.0D, service.getStatusBonus(player, StatusType.ATTACK, 100.0D));

        classMatches.set(true);
        when(account.getLevel()).thenReturn(9);
        service.refreshProgressDerivedState(player);
        assertTrue(service.isNodeVisible(player, conditionedNode));
        assertEquals(
                SkillTreeService.NodePresentationState.INACTIVE_CONDITION,
                service.nodePresentationState(player, conditionedNode)
        );
        assertEquals(0.0D, service.getStatusBonus(player, StatusType.ATTACK, 100.0D));

        when(account.getLevel()).thenReturn(12);
        service.refreshProgressDerivedState(player);

        assertTrue(service.isNodeVisible(player, conditionedNode));
        assertEquals(
                SkillTreeService.NodePresentationState.UNLOCKED,
                service.nodePresentationState(player, conditionedNode)
        );
        assertEquals(5.0D, service.getStatusBonus(player, StatusType.ATTACK, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: 条件不成立のCP nodeは従来どおり非表示とする。
     */
    @Test
    void unmetClassPointNodeRemainsHidden() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition conditionedNode = new SkillTreeNodeDefinition(
                "1000",
                "Hunter Class Status",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.CLASS_POINT,
                0,
                new SkillTreeUnlockCondition("hunter", 10),
                List.of()
        );
        SkillTreeService service = newService(conditionedNode);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        service.setPlayerClassService(playerClassService);
        AstPlayer player = astPlayer(accountId);
        when(player.getAccount().getLevel()).thenReturn(12);
        when(playerClassService.matchesCurrentClassCondition(player, "hunter")).thenReturn(false);

        assertFalse(service.isNodeVisible(player, conditionedNode));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 11. skill tree unlock・relock・派生効果
     * 検証契約: condition失効で無効になったpassive skillをremoval差分として調停する。
     */
    @Test
    void conditionChangeReconcilesRemovedPassiveSkillAsRemoval() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition conditionedNode = new SkillTreeNodeDefinition(
                "1000",
                "Hunter Passive",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                new SkillTreeUnlockCondition("hunter", 0),
                List.of(new SkillTreeSkillEffect("passive-test"))
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        PlayerClassService playerClassService = mock(PlayerClassService.class);
        AtomicBoolean classMatches = new AtomicBoolean(true);
        SkillTreeService service = newService(conditionedNode);
        service.setPassiveSkillService(passiveSkillService);
        service.setPlayerClassService(playerClassService);
        AstPlayer player = astPlayer(accountId);
        when(playerClassService.matchesCurrentClassCondition(player, "hunter"))
                .thenAnswer(ignored -> classMatches.get());
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("1000")));
        assertEquals(Set.of("passive-test"), service.getUnlockedSkillIds(player));

        classMatches.set(false);
        service.refreshProgressDerivedState(player);

        assertEquals(Set.of(), service.getUnlockedSkillIds(player));
        verify(passiveSkillService).reconcileSkillPermissionDelta(
                eq(player),
                eq(Set.of()),
                eq(Set.of("passive-test")),
                eq(false)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: Interaction entityのPDC node IDからbound positionを直接解決しray retargetしない。
     */
    @Test
    void directNodeInteractionResolvesItsBoundPositionWithoutRayRetargeting() {
        SkillTreeService service = newService(null);
        Player player = mock(Player.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("skill_tree");
        putPosition(service, new SkillTreePosition("1000", "skill_tree", 0, 64, 3));
        Interaction interaction = mock(Interaction.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(interaction.getScoreboardTags()).thenReturn(Set.of(SkillTreeService.NODE_INTERACTION_TAG));
        when(interaction.getPersistentDataContainer()).thenReturn(data);
        when(interaction.isValid()).thenReturn(true);
        when(interaction.getBoundingBox()).thenReturn(new BoundingBox(-0.9D, 64.0D, 2.1D, 0.9D, 65.8D, 3.9D));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn("1000");
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                null,
                interaction,
                null,
                null,
                false,
                PlayerInteractionRayTrace.create(
                        new Vector(0.0D, 65.62D, 0.0D),
                        new Vector(0.0D, 0.0D, 1.0D),
                        8.0D
                ),
                8.0D
        );

        SkillTreeService.SkillTreePositionHit hit = service
                .findTargetedPositionHit(snapshot)
                .orElseThrow();

        assertEquals("1000", hit.position().nodeId());
        assertTrue(hit.hitDistance() >= 0.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: 条件未達PP nodeは表示を維持するが、解放・解除の入力候補には含めない。
     */
    @Test
    void unmetPassiveNodeRemainsVisibleButIsNotAnInputTarget() {
        SkillTreeNodeDefinition conditionedNode = new SkillTreeNodeDefinition(
                "1000",
                "Conditional Passive",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.PASSIVE_POINT,
                0,
                new SkillTreeUnlockCondition(null, 7),
                List.of()
        );
        SkillTreeService service = newService(conditionedNode);
        Player player = mock(Player.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("skill_tree");
        putPosition(service, new SkillTreePosition("1000", "skill_tree", 0, 64, 3));
        Interaction interaction = mock(Interaction.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(interaction.getScoreboardTags()).thenReturn(Set.of(SkillTreeService.NODE_INTERACTION_TAG));
        when(interaction.getPersistentDataContainer()).thenReturn(data);
        when(interaction.isValid()).thenReturn(true);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn("1000");
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                null,
                interaction,
                null,
                null,
                false,
                PlayerInteractionRayTrace.create(
                        new Vector(0.0D, 65.62D, 0.0D),
                        new Vector(0.0D, 0.0D, 1.0D),
                        8.0D
                ),
                8.0D
        );
        AstPlayer astPlayer = astPlayer(UUID.randomUUID());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            assertTrue(service.isNodeVisible(astPlayer, conditionedNode));
            assertTrue(service.findTargetedPositionHit(snapshot).isEmpty());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 12. skill tree 入力候補・node 実行
     * 検証契約: 意図的なbarrier block越しでもnode hitbox交差をtarget候補として扱う。
     */
    @Test
    void skillTreePositionRemainsTargetableThroughBlockingBarrier() {
        SkillTreeService service = newService(null);
        Player player = mock(Player.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 65, 3);
        putPosition(service, position);
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                null,
                null,
                null,
                null,
                false,
                PlayerInteractionRayTrace.create(
                        new Vector(0.0D, 65.62D, 0.0D),
                        new Vector(0.0D, 0.0D, 1.0D),
                        8.0D
                ),
                0.5D
        );

        SkillTreeService.SkillTreePositionHit hit;
        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("skill_tree")).thenReturn(world);
            hit = service.findTargetedPositionHit(snapshot).orElseThrow();
        }

        assertEquals("1000", hit.position().nodeId());
        assertTrue(hit.hitDistance() > snapshot.blockingDistance());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 8. skill tree player state
     * 検証契約: 旧join callbackのdiscardはstate instance一致時だけ行い新session stateを消さない。
     */
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
        return newService(node, mock(SkillTreePlayerStateRepository.class));
    }

    private SkillTreeService newService(
            SkillTreeNodeDefinition node,
            SkillTreePlayerStateRepository stateRepository
    ) {
        SkillTreeNodeRepository nodeRepository = mock(SkillTreeNodeRepository.class);
        SkillTreeStructureRepository structureRepository = mock(SkillTreeStructureRepository.class);

        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("AstralRecord");
        when(plugin.namespace()).thenReturn("astralrecord");

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
            putRootNodeId(service, node.nodeId());
        }
        return service;
    }

    private SkillTreeService.SkillTreeMasterDataSnapshot emptyMasterDataSnapshot() {
        return new SkillTreeService.SkillTreeMasterDataSnapshot(
                "1000",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private SkillTreeNodeDefinition node(String nodeId) {
        return new SkillTreeNodeDefinition(
                nodeId,
                "Test Node",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of()
        );
    }

    private org.bukkit.inventory.ItemStack invokeNodeHotbarItem(
            SkillTreeService service,
            AstPlayer player,
            SkillTreeNodeDefinition node
    ) {
        try {
            Method method = SkillTreeService.class.getDeclaredMethod(
                    "createNodeHotbarItem",
                    AstPlayer.class,
                    SkillTreeNodeDefinition.class
            );
            method.setAccessible(true);
            return (org.bukkit.inventory.ItemStack) method.invoke(service, player, node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void putNode(SkillTreeService service, SkillTreeNodeDefinition node) {
        try {
            Field nodesById = SkillTreeService.class.getDeclaredField("nodesById");
            nodesById.setAccessible(true);
            ((Map<String, SkillTreeNodeDefinition>) nodesById.get(service)).put(node.nodeId(), node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void putRootNodeId(SkillTreeService service, String nodeId) {
        try {
            Field rootNodeId = SkillTreeService.class.getDeclaredField("rootNodeId");
            rootNodeId.setAccessible(true);
            rootNodeId.set(service, nodeId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void putPosition(SkillTreeService service, SkillTreePosition position) {
        try {
            Field positionsByNodeId = SkillTreeService.class.getDeclaredField("positionsByNodeId");
            positionsByNodeId.setAccessible(true);
            ((Map<String, SkillTreePosition>) positionsByNodeId.get(service)).put(
                    position.nodeId(),
                    position
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private AstPlayer astPlayer(UUID accountId) {
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(mock(Player.class));
        when(player.getClassLevel()).thenReturn(1);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getLevel()).thenReturn(2);
        return player;
    }

    private boolean hasYellowText(Component component, String text) {
        return hasTextColor(component, text, NamedTextColor.YELLOW);
    }

    private boolean hasTextColor(Component component, String text, NamedTextColor color) {
        if (component instanceof TextComponent textComponent
                && textComponent.content().equals(text)
                && color.equals(component.color())) {
            return true;
        }
        return component.children().stream().anyMatch(child -> hasTextColor(child, text, color));
    }
}
