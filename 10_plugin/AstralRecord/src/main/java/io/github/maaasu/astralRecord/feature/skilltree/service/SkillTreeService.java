package io.github.maaasu.astralRecord.feature.skilltree.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skilltree.config.SkillTreePluginConfig;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeStatusEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeUnlockedNode;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeRuntimeRepository;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.title.Title;

/**
 * スキルツリーのノード状態管理、表示更新、
 * およびノード由来のスキル・ステータス反映を担当するサービスです。
 */
public class SkillTreeService {
    public static final String SKILL_TREE_WORLD_ID = "skill_tree";
    public static final long RELOCK_GOLD_COST = 100L;
    private static final double TARGET_DISTANCE = 8.0D;
    public static final double NODE_BEACON_TARGET_DISTANCE = 80.0D;
    private static final double TARGET_RADIUS = 0.9D;
    static final String NODE_INTERACTION_TAG = "astralrecord:skilltree:node-interaction";
    private static final long SAVE_INTERVAL_TICKS = 20L;
    private static final long FEEDBACK_INTERVAL_TICKS = 5L;
    private static final long VISUAL_DELAY_MILLIS = 1_500L;
    private static final long SAVE_DEBOUNCE_MILLIS = 5_000L;
    private static final int DEFAULT_VIEW_DISTANCE = 27;
    private static final double DETAILED_LABEL_DISTANCE = 14.0D;
    private static final double COMPACT_LABEL_DISTANCE = 36.0D;

    /**
     * 視線上で命中したスキルツリー位置とhitbox入口距離です。
     *
     * @param position 命中したスキルツリー位置
     * @param hitDistance プレイヤー視点からhitbox入口までの有限な非負距離
     */
    public record SkillTreePositionHit(@NotNull SkillTreePosition position, double hitDistance) {
        /**
         * 命中結果を生成し、距離契約を検証します。
         *
         * @throws NullPointerException スキルツリー位置がnullの場合
         * @throws IllegalArgumentException 距離が非有限または負数の場合
         */
        public SkillTreePositionHit {
            Objects.requireNonNull(position, "position");
            if (!Double.isFinite(hitDistance) || hitDistance < 0.0D) {
                throw new IllegalArgumentException("hitDistance must be finite and zero or greater");
            }
        }
    }

    /** CP 消費元クラス選択 GUI に表示するクラス別残高です。 */
    public record CpSourceOption(
            @NotNull String classId,
            @NotNull String displayName,
            int classLevel,
            int availablePoints
    ) {
    }

    /**
     * JSONから準備し、メインスレッドで一括公開するスキルツリーマスタです。
     *
     * @param rootNodeId 構造上のrootノードID
     * @param nodes ノード定義
     * @param positions nodeIdに直接対応する絶対座標
     * @param edges nodeId同士の無向接続
     */
    public record SkillTreeMasterDataSnapshot(
            @NotNull String rootNodeId,
            @NotNull List<SkillTreeNodeDefinition> nodes,
            @NotNull List<SkillTreePosition> positions,
            @NotNull List<SkillTreeEdge> edges
    ) {
        public SkillTreeMasterDataSnapshot {
            nodes = List.copyOf(nodes);
            positions = List.copyOf(positions);
            edges = List.copyOf(edges);
        }
    }

    /** APIへ登録する正規化済み定義スナップショットとそのSHA-256世代IDです。 */
    private record DefinitionGeneration(@NotNull String id, @NotNull String canonicalSnapshotJson) {
    }


    /**
     * 非同期のプレイヤー状態ロードで参照する、不変の構造検証スナップショットです。
     *
     * @param ready マスタ公開後なら {@code true}
     * @param rootNodeId 現在構造のrootノードID
     * @param definedNodeIds 現在のノード定義に存在するID
     * @param placedNodeIds 現在の構造へ配置されているID
     * @param adjacentNodeIdsByNodeId 現在構造における無向隣接ノード
     * @param repairKey 同一構造に対する補修・補償メールの冪等キー
     */
    private record PlayerStateValidationSnapshot(
            boolean ready,
            @NotNull String rootNodeId,
            @NotNull Set<String> definedNodeIds,
            @NotNull Set<String> placedNodeIds,
            @NotNull Map<String, Set<String>> adjacentNodeIdsByNodeId,
            @NotNull String repairKey
    ) {
        private static @NotNull PlayerStateValidationSnapshot unavailable() {
            return new PlayerStateValidationSnapshot(false, "", Set.of(), Set.of(), Map.of(), "");
        }

        private static @NotNull PlayerStateValidationSnapshot from(
                @NotNull SkillTreeMasterDataSnapshot snapshot
        ) {
            Set<String> definedNodeIds = snapshot.nodes().stream()
                    .map(SkillTreeNodeDefinition::nodeId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> placedNodeIds = snapshot.positions().stream()
                    .map(SkillTreePosition::nodeId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Map<String, Set<String>> adjacentNodeIdsByNodeId = new LinkedHashMap<>();
            for (String nodeId : placedNodeIds) {
                adjacentNodeIdsByNodeId.put(nodeId, new LinkedHashSet<>());
            }
            for (SkillTreeEdge edge : snapshot.edges()) {
                adjacentNodeIdsByNodeId.computeIfAbsent(edge.sourceNodeId(), ignored -> new LinkedHashSet<>())
                        .add(edge.targetNodeId());
                adjacentNodeIdsByNodeId.computeIfAbsent(edge.targetNodeId(), ignored -> new LinkedHashSet<>())
                        .add(edge.sourceNodeId());
            }
            Map<String, Set<String>> immutableAdjacency = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : adjacentNodeIdsByNodeId.entrySet()) {
                immutableAdjacency.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return new PlayerStateValidationSnapshot(
                    true,
                    snapshot.rootNodeId(),
                    definedNodeIds,
                    placedNodeIds,
                    Map.copyOf(immutableAdjacency),
                    createRepairKey(snapshot.rootNodeId(), placedNodeIds, snapshot.edges())
            );
        }

        private boolean isStructurallyValid(@NotNull SkillTreePlayerState state) {
            if (!ready || state.unlockedNodeIds().isEmpty()) {
                return true;
            }
            Set<String> unlockedNodeIds = state.unlockedNodeIds();
            if (!definedNodeIds.containsAll(unlockedNodeIds)
                    || !placedNodeIds.containsAll(unlockedNodeIds)
                    || !unlockedNodeIds.contains(rootNodeId)) {
                return false;
            }

            Set<String> reachableNodeIds = new LinkedHashSet<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(rootNodeId));
            while (!queue.isEmpty()) {
                String nodeId = queue.removeFirst();
                if (!unlockedNodeIds.contains(nodeId) || !reachableNodeIds.add(nodeId)) {
                    continue;
                }
                for (String adjacentNodeId : adjacentNodeIdsByNodeId.getOrDefault(nodeId, Set.of())) {
                    if (unlockedNodeIds.contains(adjacentNodeId)) {
                        queue.addLast(adjacentNodeId);
                    }
                }
            }
            return reachableNodeIds.containsAll(unlockedNodeIds);
        }
    }

    private static @NotNull String createRepairKey(
            @NotNull String rootNodeId,
            @NotNull Set<String> placedNodeIds,
            @NotNull List<SkillTreeEdge> edges
    ) {
        StringBuilder canonicalStructure = new StringBuilder("root:").append(rootNodeId).append('\n');
        placedNodeIds.stream()
                .sorted()
                .forEach(nodeId -> canonicalStructure.append("node:").append(nodeId).append('\n'));
        edges.stream()
                .map(SkillTreeEdge::key)
                .sorted()
                .forEach(edgeKey -> canonicalStructure.append("edge:").append(edgeKey).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalStructure.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hexadecimal.append(String.format(java.util.Locale.ROOT, "%02x", value));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private final Plugin plugin;
    private final WorldService worldService;
    private InventoryService inventoryService;
    private @Nullable SkillTreeOperationReceiptService runtimeOperationReceiptService;
    private PlayerHudService playerHudService;
    private @Nullable PlayerSettingService playerSettingService;
    private StatusService statusService;
    private SkillService skillService;
    private PassiveSkillService passiveSkillService;
    private PlayerClassService playerClassService;
    private final SkillTreeNodeRepository nodeRepository;
    private final SkillTreeStructureRepository structureRepository;
    private final SkillTreePlayerStateRepository playerStateRepository;
    private final SkillTreeRuntimeRepository runtimeRepository = new SkillTreeRuntimeRepository();
    private final UUID runtimeServerSessionId = UUID.randomUUID();
    private final java.time.Instant runtimeServerStartedAtUtc = java.time.Instant.now();
    private final NamespacedKey nodeInteractionKey;
    private final Map<String, SkillTreeNodeDefinition> nodesById = new LinkedHashMap<>();
    private final Map<String, SkillTreePosition> positionsByNodeId = new LinkedHashMap<>();
    private final Map<String, SkillTreeEdge> edgesByKey = new LinkedHashMap<>();
    /** 構造公開時に作る無向隣接表。表示・入力中にedge全件を再走査しません。 */
    private final Map<String, Set<String>> adjacentNodeIdsByNodeId = new LinkedHashMap<>();
    /** 構造公開時に作るnode IDの数値順キー。 */
    private final Map<String, Long> nodeIdSortValues = new LinkedHashMap<>();
    private final Map<String, ItemStack> lockedNodeDisplayItems = new LinkedHashMap<>();
    private final Map<String, ItemStack> unlockedNodeDisplayItems = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> blockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> conditionBlockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> availableNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> unlockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> inactiveNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> inactiveConditionNodeFieldLabels = new LinkedHashMap<>();
    private final Map<UUID, SkillTreePlayerState> playerStates = new HashMap<>();
    private final Map<UUID, DerivedPlayerState> derivedPlayerStates = new HashMap<>();
    private final Set<UUID> dirtyPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> loadingPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> failedPlayerStateLoads = new LinkedHashSet<>();
    private final Map<UUID, Long> dirtyPlayerStateDueAtMillis = new HashMap<>();
    private final Map<UUID, Long> playerStateRevisions = new HashMap<>();
    private final Map<UUID, Integer> persistedPlayerStateVersions = new HashMap<>();
    private final Set<UUID> retainedInitialPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> releasedPlayerStates = new LinkedHashSet<>();
    /** Runtime API の同一 account 操作を重複して claim しないための処理中集合です。 */
    private final Set<UUID> runtimeOperationAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, SkillTreeRuntimeRepository.AccountSession> runtimeAccountSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> runtimeAccountDefinitionGenerations = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, JsonObject> runtimeLastViews = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> runtimeViewSequences = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, SkillTreePlayerState> runtimeInitialLoadStates = new java.util.concurrent.ConcurrentHashMap<>();
    /** 移動・world change・quit ごとに更新し、claim 後に古くなったWeb操作を確定させません。 */
    private final Map<UUID, Long> runtimePlayerContextRevisions = new java.util.concurrent.ConcurrentHashMap<>();
    /** 次の skillTree snapshot にだけ同梱する、claim済みWeb操作の原子確定receiptです。 */
    private final Map<UUID, PendingRuntimeOperationReceipt> pendingRuntimeOperationReceipts = new HashMap<>();
    /** 空のlegacy stateを現在世代へ初回bindしたことを、同じsnapshotでAPIへ明示します。 */
    private final Set<UUID> pendingLegacyDefinitionGenerationMigrations = new LinkedHashSet<>();
    private final Map<UUID, Long> acknowledgedPlayerStateRevisions = new HashMap<>();
    private final Map<UUID, UUID> playerStateEpochs = new HashMap<>();
    private final Map<UUID, SkillTreePlayerState> initialPlayerStatePublications = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, Long> visualReadyAtMillis = new HashMap<>();
    private final Map<UUID, BossBar> loadingBossBars = new HashMap<>();

    private BukkitTask saveTask;
    private BukkitTask feedbackTask;
    private BukkitTask runtimeHeartbeatTask;
    private BukkitTask runtimePlayerViewTask;
    private SkillTreeVisualizer visualizer;
    private @Nullable ParticleDisplayService particleDisplayService;
    private boolean playerStateSaveInProgress;
    private BiConsumer<AstPlayer, String> nodeUnlockListener = (player, nodeId) -> { };
    private volatile PlayerStateValidationSnapshot playerStateValidationSnapshot = PlayerStateValidationSnapshot.unavailable();
    private String rootNodeId = "";
    /** 現在公開済みの実ロード定義世代。公開に失敗した場合は前回値を維持します。 */
    private volatile String definitionGenerationId = "";
    private volatile String definitionCanonicalSnapshotJson = "";
    private volatile String registeredRuntimeGenerationId = "";
    private long runtimePublicationRevision;
    private volatile boolean masterPublicationInProgress;

    public SkillTreeService(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService,
            @Nullable InventoryService inventoryService,
            @NotNull SkillTreeNodeRepository nodeRepository,
            @NotNull SkillTreeStructureRepository structureRepository,
            @NotNull SkillTreePlayerStateRepository playerStateRepository
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.inventoryService = inventoryService;
        this.nodeRepository = nodeRepository;
        this.structureRepository = structureRepository;
        this.playerStateRepository = playerStateRepository;
        this.nodeInteractionKey = new NamespacedKey(plugin, "skilltree_node_id");
    }

    /** 全player-state保存へ同じ参加時点の権限を添付する。I/Oやservice monitor取得は行わない。 */
    private JsonObject captureRuntimeAuthority(UUID accountId) {
        SkillTreeRuntimeRepository.AccountSession session = runtimeAccountSessions.get(accountId);
        String generation = runtimeAccountDefinitionGenerations.get(accountId);
        if (session == null || generation == null) return null;
        JsonObject proof = new JsonObject();
        proof.addProperty("serverId", ConfigProperties.getInstance().getApiServerId());
        proof.addProperty("serverSessionId", runtimeServerSessionId.toString());
        proof.addProperty("accountSessionId", session.id().toString());
        proof.addProperty("accountLeaseToken", session.token());
        proof.addProperty("definitionGenerationId", generation);
        return proof;
    }

    public void setInventoryService(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
        inventoryService.getPersistence().setSnapshotAuthorityProvider(this::captureRuntimeAuthority);
        this.runtimeOperationReceiptService = new SkillTreeOperationReceiptService(inventoryService);
    }

    /**
     * スキルツリーのローカル確定と player-state 保存を接続します。
     *
     * @param localStatePersistence account state lock と保存キューを提供するサービス
     */
    public void setLocalStatePersistence(@NotNull InventoryService localStatePersistence) {
        setInventoryService(localStatePersistence);
    }

    /**
     * Bedrock Edition 向けの表示フォールバックに使うパーティクルサービスを設定します。
     */
    public void setParticleDisplayService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * ノード解放成功時の通知先を設定します。
     *
     * @param nodeUnlockListener 解放者とノードIDを受け取る通知先
     */
    public void setNodeUnlockListener(@NotNull BiConsumer<AstPlayer, String> nodeUnlockListener) {
        this.nodeUnlockListener = nodeUnlockListener;
    }

    /**
     * プレイヤー HUD サービスを設定します。
     */
    public void setPlayerHudService(@NotNull PlayerHudService playerHudService) {
        this.playerHudService = playerHudService;
    }

    /**
     * スキルツリーノードのプレイヤー別表示設定を参照するサービスを設定します。
     *
     * @param playerSettingService ユーザー設定の cache 参照サービス
     */
    public void setPlayerSettingService(@NotNull PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    /**
     * ステータスサービスを設定します。
     */
    public void setStatusService(@NotNull StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * スキルサービスを設定します。
     */
    public void setSkillService(@NotNull SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * パッシブスキルサービスを設定します。
     */
    public void setPassiveSkillService(@NotNull PassiveSkillService passiveSkillService) {
        this.passiveSkillService = passiveSkillService;
    }

    /** クラス条件とクラス別 CP の解決元を設定します。 */
    public void setPlayerClassService(@NotNull PlayerClassService playerClassService) {
        this.playerClassService = playerClassService;
    }

    /**
     * JSONファイルを読み、共有キャッシュを変更しない検証済みスナップショットを構築します。
     *
     * @return 公開前のスキルツリーマスタ
     * @throws IllegalStateException JSONまたはノード参照が不正な場合
     */
    public @NotNull SkillTreeMasterDataSnapshot loadMasterDataSnapshot() {
        List<SkillTreeNodeDefinition> nodes = nodeRepository.findAll();
        Map<String, SkillTreeNodeDefinition> definitions = new LinkedHashMap<>();
        for (SkillTreeNodeDefinition node : nodes) {
            if (definitions.putIfAbsent(node.nodeId(), node) != null) {
                throw new IllegalStateException("Duplicate skilltree nodeId: " + node.nodeId());
            }
        }
        SkillTreePluginConfig config = SkillTreePluginConfig.loadFile(
                new File(plugin.getDataFolder(), "config.yml")
        );
        var structure = structureRepository.load(config);
        for (SkillTreePosition position : structure.positions()) {
            if (!definitions.containsKey(position.nodeId())) {
                throw new IllegalStateException("Skilltree structure references unknown nodeId: " + position.nodeId());
            }
        }
        if (!definitions.containsKey(structure.rootNodeId())) {
            throw new IllegalStateException("Skilltree rootNodeId has no node definition: " + structure.rootNodeId());
        }
        return new SkillTreeMasterDataSnapshot(
                structure.rootNodeId(),
                nodes,
                structure.positions(),
                structure.edges()
        );
    }

    /**
     * 検証済みスキルツリーマスタを実行時キャッシュへ一括公開します。
     *
     * @param snapshot 公開するスナップショット
     */
    public synchronized void replaceMasterDataSnapshot(@NotNull SkillTreeMasterDataSnapshot snapshot) {
        if (!pendingRuntimeOperationReceipts.isEmpty()) {
            throw new IllegalStateException("Skill tree operation acknowledgement is pending");
        }
        DefinitionGeneration nextDefinitionGeneration = createDefinitionGeneration(snapshot);
        String nextDefinitionGenerationId = nextDefinitionGeneration.id();
        nodesById.clear();
        lockedNodeDisplayItems.clear();
        unlockedNodeDisplayItems.clear();
        blockedNodeFieldLabels.clear();
        conditionBlockedNodeFieldLabels.clear();
        availableNodeFieldLabels.clear();
        unlockedNodeFieldLabels.clear();
        inactiveNodeFieldLabels.clear();
        inactiveConditionNodeFieldLabels.clear();
        for (SkillTreeNodeDefinition node : snapshot.nodes()) {
            nodesById.put(node.nodeId(), node);
            cacheNodePresentation(node);
        }

        positionsByNodeId.clear();
        edgesByKey.clear();
        adjacentNodeIdsByNodeId.clear();
        nodeIdSortValues.clear();
        for (SkillTreePosition position : snapshot.positions()) {
            positionsByNodeId.put(position.nodeId(), position);
        }
        for (String nodeId : nodesById.keySet()) {
            adjacentNodeIdsByNodeId.put(nodeId, new LinkedHashSet<>());
            nodeIdSortValues.put(nodeId, parseNodeIdSortValue(nodeId));
        }
        for (SkillTreeEdge edge : snapshot.edges()) {
            edgesByKey.put(edge.key(), edge);
            adjacentNodeIdsByNodeId.computeIfAbsent(edge.sourceNodeId(), ignored -> new LinkedHashSet<>())
                    .add(edge.targetNodeId());
            adjacentNodeIdsByNodeId.computeIfAbsent(edge.targetNodeId(), ignored -> new LinkedHashSet<>())
                    .add(edge.sourceNodeId());
        }
        rootNodeId = snapshot.rootNodeId();
        definitionGenerationId = nextDefinitionGenerationId;
        definitionCanonicalSnapshotJson = nextDefinitionGeneration.canonicalSnapshotJson();
        runtimePublicationRevision++;
        playerStateValidationSnapshot = PlayerStateValidationSnapshot.from(snapshot);
        derivedPlayerStates.clear();
        refreshLoadedOnlinePlayerDerivedStates();
        if (visualizer != null) {
            visualizer.markStructureDirty();
        }
        Logger.log(LogId.I_9000, nodesById.size(), positionsByNodeId.size(), edgesByKey.size());
    }

    /** 現在公開済みの実ロード定義世代を返します。 */
    public @NotNull String definitionGenerationId() {
        return definitionGenerationId;
    }

    /** APIへ登録する、現在公開済み世代を生成した正規化JSONを返します。 */
    public @NotNull String definitionCanonicalSnapshotJson() {
        return definitionCanonicalSnapshotJson;
    }

    /** 公開中の構造を取得する。関連マスターと同じロックで退避・復元するために使用する。 */
    public synchronized @NotNull SkillTreeMasterDataSnapshot snapshotPublishedMasterData() {
        if (!pendingRuntimeOperationReceipts.isEmpty()) throw new IllegalStateException("Skill tree operation acknowledgement is pending");
        return new SkillTreeMasterDataSnapshot(rootNodeId, List.copyOf(nodesById.values()),
                List.copyOf(positionsByNodeId.values()), List.copyOf(edgesByKey.values()));
    }

    /** 関連マスターの一括公開前に新しいローカル変更とRuntime公開を保留する。 */
    public synchronized void beginMasterDataPublication() {
        if (!pendingRuntimeOperationReceipts.isEmpty()) throw new IllegalStateException("Skill tree operation acknowledgement is pending");
        masterPublicationInProgress = true;
    }

    /** 一括公開または旧スナップショットへの復元が完了した後に保留を解除する。 */
    public synchronized void endMasterDataPublication() { masterPublicationInProgress = false; }

    /** 定義の公開成功後、不一致の旧状態を保持して再参加まで操作を止める。メインスレッド専用。 */
    public synchronized void finishMasterDataPublication() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            SkillTreePlayerState state = playerStates.get(astPlayer.getAccount().getUuid());
            if (state != null && state.definitionGenerationId() != null && !definitionGenerationId.equals(state.definitionGenerationId())) {
                astPlayer.getBukkit().kick(PlayerMsgResource.formatComponent(PlayerMsgId.P_9050.getId()));
            }
        }
    }

    /**
     * スキルツリーの判定へ影響する構造・ノード・効果・解除規則を正規化して世代IDを生成します。
     * ファイル名、配置時刻、Seeder実行IDには依存しません。
     */
    private @NotNull DefinitionGeneration createDefinitionGeneration(@NotNull SkillTreeMasterDataSnapshot snapshot) {
        if (playerClassService == null || playerClassService.snapshotLoadedClasses().isEmpty()) {
            throw new IllegalStateException("Class definitions are not ready for skill tree publication");
        }
        for (SkillTreeNodeDefinition node : snapshot.nodes()) {
            if (node.unlockCondition().classId() != null && playerClassService.getLoadedClass(node.unlockCondition().classId()) == null) {
                throw new IllegalStateException("A referenced class definition is not loaded");
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("rulesRevision", "skilltree-operation-v1");
        root.addProperty("relockGoldCost", RELOCK_GOLD_COST);
        root.addProperty("rootNodeId", snapshot.rootNodeId());
        JsonArray nodes = new JsonArray();
        snapshot.nodes().stream().sorted(java.util.Comparator.comparing(SkillTreeNodeDefinition::nodeId)).forEach(node -> {
            JsonObject value = new JsonObject();
            value.addProperty("nodeId", node.nodeId());
            value.addProperty("name", ColorCodeUtil.toPlainText(node.name(), node.nodeId()));
            value.addProperty("pointType", node.pointType().name());
            value.addProperty("pointCost", node.pointCost());
            JsonObject condition = new JsonObject();
            if (node.unlockCondition().classId() == null) condition.add("classId", com.google.gson.JsonNull.INSTANCE);
            else {
                condition.addProperty("classId", node.unlockCondition().classId());
                String classDisplayName = playerClassService == null ? node.unlockCondition().classId()
                        : playerClassService.getDisplayName(node.unlockCondition().classId());
                condition.addProperty("classDisplayName", ColorCodeUtil.toPlainText(
                        classDisplayName, node.unlockCondition().classId()));
            }
            condition.addProperty("playerLevel", node.unlockCondition().playerLevel());
            value.add("unlockCondition", condition);
            JsonArray effects = new JsonArray();
            for (SkillTreeNodeEffect effect : node.effects()) {
                JsonObject effectValue = new JsonObject();
                if (effect instanceof SkillTreeSkillEffect skill) {
                    effectValue.addProperty("type", "skill");
                    effectValue.addProperty("skillId", skill.skillId());
                    var definition = skillService == null ? null : skillService.registry().getDefinition(skill.skillId());
                    effectValue.addProperty("displayName", definition == null ? "未読込スキル"
                            : SkillPresentationUtil.plainName(definition, "未定義スキル"));
                    if (definition != null) {
                        effectValue.addProperty("description", firstSkillDescription(definition));
                    }
                } else if (effect instanceof SkillTreeStatusEffect status) {
                    effectValue.addProperty("type", "status");
                    effectValue.addProperty("status", status.statusType().name());
                    effectValue.addProperty("modifierType", status.modifierType().name());
                    effectValue.addProperty("value", status.value());
                    effectValue.addProperty("displayName", status.statusType().getDisplayName());
                    effectValue.addProperty("suffix", status.statusType().getSuffix());
                    effectValue.addProperty("decimalPlaces", status.statusType().getDecimalPlaces());
                }
                effects.add(effectValue);
            }
            value.add("effects", effects);
            nodes.add(value);
        });
        root.add("nodes", nodes);
        JsonArray positions = new JsonArray();
        snapshot.positions().stream().sorted(java.util.Comparator.comparing(SkillTreePosition::nodeId)).forEach(position -> {
            JsonObject value = new JsonObject();
            value.addProperty("nodeId", position.nodeId());
            value.addProperty("world", position.worldName());
            value.addProperty("x", position.x());
            value.addProperty("y", position.y());
            value.addProperty("z", position.z());
            positions.add(value);
        });
        root.add("positions", positions);
        JsonArray edges = new JsonArray();
        snapshot.edges().stream().map(SkillTreeEdge::key).sorted().forEach(edges::add);
        root.add("edges", edges);
        com.google.gson.Gson serializer = new com.google.gson.Gson();
        root.add("classes", serializer.toJsonTree(playerClassService == null ? Map.of() : playerClassService.snapshotLoadedClasses()));
        JsonObject skillDefinitions = new JsonObject();
        for (SkillTreeNodeDefinition node : snapshot.nodes()) {
            for (SkillTreeSkillEffect effect : node.skillEffects()) {
                var definition = skillService == null ? null : skillService.registry().getDefinition(effect.skillId());
                if (definition == null) throw new IllegalStateException("A referenced skill definition is not loaded");
                skillDefinitions.add(effect.skillId(), serializer.toJsonTree(definition));
            }
        }
        root.add("skills", skillDefinitions);
        try {
            String canonicalSnapshotJson = canonicalJson(root).toString();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalSnapshotJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return new DefinitionGeneration(result.toString(), canonicalSnapshotJson);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** オブジェクトのキー順だけを正規化し、意味を持つ配列順序を維持する。 */
    private static JsonElement canonicalJson(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> result.add(key, canonicalJson(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(canonicalJson(item)));
            return result;
        }
        return value.deepCopy();
    }

    /**
     * スキルツリーマスタを同期読込して公開します。
     *
     * @return 読み込んだノード件数
     */
    public int loadAll() {
        SkillTreeMasterDataSnapshot snapshot = loadMasterDataSnapshot();
        replaceMasterDataSnapshot(snapshot);
        return nodesById.size();
    }

    public void start() {
        purgeSkillTreeVisualEntities();
        WorldMasterData data = worldService.getById(SKILL_TREE_WORLD_ID);
        World resolvedWorld = data == null ? null : worldService.resolveLoadedWorld(data);
        Logger.log(
                LogId.I_9001,
                SKILL_TREE_WORLD_ID,
                resolvedWorld == null ? "null" : resolvedWorld.getName()
        );
        if (visualizer == null) {
            visualizer = new SkillTreeVisualizer(plugin, this, particleDisplayService);
            visualizer.start();
        }
        if (saveTask == null) {
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirtyAsync, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
        }
        if (feedbackTask == null) {
            feedbackTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayerFeedbacks, 1L, FEEDBACK_INTERVAL_TICKS);
        }
        if (runtimeHeartbeatTask == null && runtimeRepository.isConfigured()) {
            runtimeHeartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::publishRuntimeHeartbeat, 1L, 20L * 10L
            );
        }
        if (runtimePlayerViewTask == null && runtimeRepository.isConfigured()) {
            runtimePlayerViewTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::publishOnlineRuntimePlayerViews, 1L, 20L * 5L
            );
        }
        refreshAllPlayerVisibility();
        markAllViewerContextsDirty();
    }

    public void stop() {
        if (visualizer != null) {
            visualizer.stop();
            visualizer = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (feedbackTask != null) {
            feedbackTask.cancel();
            feedbackTask = null;
        }
        if (runtimeHeartbeatTask != null) {
            runtimeHeartbeatTask.cancel();
            runtimeHeartbeatTask = null;
        }
        if (runtimePlayerViewTask != null) {
            runtimePlayerViewTask.cancel();
            runtimePlayerViewTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayerPresentation(player);
        }
        restoreAllPlayerVisibility();
        clearAllLoadingBossBars();
        saveDirty();
    }

    /**
     * APIへ公開済みの実ロード世代を登録またはheartbeatします。
     * 通信失敗は現在のローカル世代を変更せず、次周期で再試行します。
     */
    private void publishRuntimeHeartbeat() {
        final String generation;
        final String canonicalSnapshotJson;
        final long publicationRevision;
        synchronized (this) {
            if (masterPublicationInProgress) return;
            generation = definitionGenerationId;
            canonicalSnapshotJson = definitionCanonicalSnapshotJson;
            publicationRevision = runtimePublicationRevision;
        }
        if (generation.isBlank() || canonicalSnapshotJson.isBlank() || !runtimeRepository.isConfigured()) {
            return;
        }
        String serverId = ConfigProperties.getInstance().getApiServerId();
        try {
            if (generation.equals(registeredRuntimeGenerationId)) {
                runtimeRepository.heartbeat(serverId, runtimeServerSessionId, generation);
            } else {
                runtimeRepository.register(
                        serverId,
                        runtimeServerSessionId,
                        runtimeServerStartedAtUtc,
                        publicationRevision,
                        plugin.getPluginMeta().getVersion(),
                        "skilltree-operation-v2",
                        generation,
                        canonicalSnapshotJson
                );
                registeredRuntimeGenerationId = generation;
            }
        } catch (RuntimeException ignored) {
            // runtime APIの到達不能時はreadyを主張せず、次周期の再登録までWeb即時適用を停止させる。
        }
    }

    /**
     * 現在sessionのWeb操作取得に使うサーバーIDを返します。
     * Runtimeキー未設定の場合はnullで、Web操作を安全に受け付けません。
     */
    public @Nullable String runtimeServerId() {
        return runtimeRepository.isConfigured() && !definitionGenerationId.isBlank()
                ? ConfigProperties.getInstance().getApiServerId()
                : null;
    }

    /**
     * Web からのスキルツリー編集を現在地で確定できるか判定します。
     *
     * <p>オフライン時の変更は案として保持し、オンライン中は拠点またはスキルツリー
     * ワールドでのみ Plugin が確定します。Bukkit メインスレッドから呼び出します。</p>
     *
     * @param player 判定対象プレイヤー
     * @return 編集を確定できるワールドなら {@code true}
     */
    public boolean isRuntimeEditingAllowed(@NotNull Player player) {
        WorldMasterData current = worldService.findByBukkitWorld(player.getWorld());
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && !isAdminMode(astPlayer) && current != null
                && (current.worldType() == WorldType.BASE || SKILL_TREE_WORLD_ID.equals(current.id()));
    }

    /**
     * Web 表示用に、現在接続中のプレイヤーを Plugin のルールで評価したビューを生成します。
     *
     * <p>この値は raw master の代替ではなく、現在の世代・保存版数・クラス進行・Gold・
     * 現在地を束ねた短命な表示用スナップショットです。Bukkit メインスレッドから呼び出します。</p>
     *
     * @param astPlayer 評価対象プレイヤー
     * @return API へ公開する評価済みビュー
     */
    public synchronized @NotNull JsonObject createRuntimePlayerView(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        JsonObject location = new JsonObject();
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        location.addProperty("worldId", worldData == null ? null : worldData.id());
        location.addProperty("worldDisplayName", worldData == null ? "現在地を確認中" : ColorCodeUtil.toPlainText(worldData.displayName(), "現在地を確認中"));
        location.addProperty("x", player.getLocation().getX());
        location.addProperty("y", player.getLocation().getY());
        location.addProperty("z", player.getLocation().getZ());
        JsonObject view = createRuntimePlayerDataView(astPlayer, location, player.isOnline(), player.isOnline() && isRuntimeEditingAllowed(player));
        runtimeLastViews.put(astPlayer.getAccount().getUuid(), view.deepCopy());
        return view;
    }

    /** 保存境界でも利用できるデータ評価。Bukkitの位置はメインスレッドで取得済みの値だけを使用する。 */
    private synchronized JsonObject createRuntimePlayerDataView(AstPlayer astPlayer, JsonObject location, boolean online, boolean eligible) {
        SkillTreePlayerState state = state(astPlayer);
        JsonObject view = new JsonObject();
        view.addProperty("accountId", state.accountId().toString());
        view.addProperty("definitionGenerationId", definitionGenerationId);
        view.addProperty("playerStateVersion", state.persistedVersion());
        view.addProperty("playerStateRevision", playerStateRevisions.getOrDefault(state.accountId(), 0L));
        view.addProperty("evaluationFingerprint", createRuntimeEvaluationFingerprint(astPlayer, state));
        view.addProperty("online", online);
        view.addProperty("channelName", ConfigProperties.getInstance().getNetworkChannelName());
        view.addProperty("editEligible", eligible);
        view.addProperty("relockGoldCost", RELOCK_GOLD_COST);

        view.add("location", location.deepCopy());

        JsonObject points = new JsonObject();
        points.addProperty("pp", availablePassivePoints(astPlayer));
        points.addProperty("earnedPp", earnedPassivePoints(astPlayer));
        points.addProperty("spentPp", spentPassivePoints(knownUnlockedNodeIds(state)));
        points.addProperty("gold", availableRelockGold(astPlayer));
        JsonArray classPoints = new JsonArray();
        for (CpSourceOption option : cpSourceOptions(astPlayer)) {
            JsonObject value = new JsonObject();
            value.addProperty("classId", option.classId());
            value.addProperty("className", runtimeClassName(option.classId()));
            value.addProperty("availableCp", option.availablePoints());
            value.addProperty("earnedCp", Math.max(0, option.classLevel() - 1));
            value.addProperty("spentCp", spentClassPoints(state, option.classId()));
            classPoints.add(value);
        }
        points.add("classes", classPoints);
        view.add("points", points);

        JsonObject tree = new JsonObject();
        tree.addProperty("structureId", "main");
        tree.addProperty("name", "スキルツリー");
        tree.addProperty("rootNodeId", rootNodeId);
        JsonArray treeNodes = new JsonArray();
        Set<String> visibleNodes = new LinkedHashSet<>();
        for (SkillTreeNodeDefinition node : nodesById.values()) {
            SkillTreePosition position = positionsByNodeId.get(node.nodeId());
            if (position == null || !isNodeVisible(astPlayer, node)) continue;
            visibleNodes.add(node.nodeId());
            JsonObject nodeView = new JsonObject();
            nodeView.addProperty("nodeId", node.nodeId());
            nodeView.addProperty("name", ColorCodeUtil.toPlainText(node.name(), "未登録のノード"));
            nodeView.addProperty("icon", node.icon().name());
            nodeView.addProperty("pointType", node.pointType() == SkillTreePointType.CLASS_POINT ? "CP" : "PP");
            nodeView.addProperty("pointCost", node.pointCost());
            nodeView.addProperty("x", position.x());
            nodeView.addProperty("y", position.y());
            nodeView.addProperty("z", position.z());
            JsonArray nodeLore = new JsonArray();
            node.lore().forEach(line -> nodeLore.add(ColorCodeUtil.toPlainText(line, "")));
            nodeView.add("lore", nodeLore);
            boolean unlocked = state.isUnlocked(node.nodeId());
            boolean canUnlock = canUnlockNode(astPlayer, node);
            boolean canRelock = unlocked && canRelockNode(astPlayer, node) && canAffordRelock(astPlayer);
            boolean conditionMet = isNodeUnlockConditionMet(astPlayer, node);
            boolean inactive = unlocked && derivedState(astPlayer, state).inactiveUnlockedNodeIds().contains(node.nodeId());
            nodeView.addProperty("isUnlocked", unlocked);
            nodeView.addProperty("isEffectiveUnlocked", activeUnlockedNodeIds(astPlayer, state).contains(node.nodeId()));
            nodeView.addProperty("isConditionMet", isNodeUnlockConditionMet(astPlayer, node));
            nodeView.addProperty("isConditionMet", conditionMet);
            nodeView.addProperty("stateText", unlocked
                    ? !conditionMet ? "解放済み・条件未達のため無効" : inactive ? "解放済み・ポイント不足のため無効" : "解放済み"
                    : canUnlock ? "解放可能" : !conditionMet ? "未解放・必要条件未達" : "未解放");
            List<String> requirements = new ArrayList<>();
            if (node.unlockCondition().hasPlayerLevelCondition()) requirements.add("必要レベル: " + node.unlockCondition().playerLevel());
            if (node.unlockCondition().hasClassCondition()) requirements.add("必要クラス: " + runtimeClassName(node.unlockCondition().classId()));
            nodeView.addProperty("requirementText", String.join("\n", requirements));
            nodeView.addProperty("canUnlock", canUnlock);
            nodeView.addProperty("canRelock", canRelock);
            nodeView.addProperty("requiresCpSourceSelection", requiresCpSourceSelection(node));
            String fixedCpSourceId = node.pointType() == SkillTreePointType.CLASS_POINT
                    && node.unlockCondition().classId() != null ? normalizeClassId(node.unlockCondition().classId()) : null;
            if (fixedCpSourceId == null) {
                nodeView.add("cpSourceClassId", com.google.gson.JsonNull.INSTANCE);
                nodeView.add("cpSourceClassName", com.google.gson.JsonNull.INSTANCE);
            } else {
                nodeView.addProperty("cpSourceClassId", fixedCpSourceId);
                String fixedCpSourceName = playerClassService == null ? fixedCpSourceId : playerClassService.getDisplayName(fixedCpSourceId);
                nodeView.addProperty("cpSourceClassName", ColorCodeUtil.toPlainText(fixedCpSourceName, fixedCpSourceId));
            }
            nodeView.addProperty("costText", runtimeCostText(node));
            JsonArray displayEffects = new JsonArray();
            for (SkillTreeStatusEffect effect : node.statusEffects()) {
                displayEffects.add(effect.statusType().getDisplayName() + " " + formatNodeStatusModifier(effect));
            }
            List<Component> skillLines = new ArrayList<>();
            appendNodePassiveSkillLines(skillLines, node);
            for (Component line : skillLines) {
                displayEffects.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line));
            }
            nodeView.add("displayEffects", displayEffects);
            SkillTreeUnlockedNode unlockedNode = state.unlockedNode(node.nodeId());
            String actualSource = unlockedNode != null && node.pointType() == SkillTreePointType.CLASS_POINT
                    ? resolvedConsumedClassId(unlockedNode, node) : null;
            if (actualSource == null) {
                nodeView.add("consumedClassName", com.google.gson.JsonNull.INSTANCE);
            } else {
                String classId = actualSource;
                nodeView.addProperty("consumedClassId", classId);
                nodeView.addProperty("consumedClassName", runtimeClassName(classId));
            }
            JsonArray cpSources = new JsonArray();
            if (node.pointType() == SkillTreePointType.CLASS_POINT) {
                for (CpSourceOption option : cpSourceOptions(astPlayer)) {
                    JsonObject source = new JsonObject();
                    source.addProperty("classId", option.classId());
                    source.addProperty("className", runtimeClassName(option.classId()));
                    source.addProperty("availableCp", option.availablePoints());
                    cpSources.add(source);
                }
            }
            nodeView.add("cpSources", cpSources);
            if (canUnlock || canRelock) {
                nodeView.add("blockedReason", com.google.gson.JsonNull.INSTANCE);
            } else {
                nodeView.addProperty("blockedReason", runtimeBlockedReason(astPlayer, node, unlocked));
            }
            treeNodes.add(nodeView);
        }
        tree.add("nodes", treeNodes);
        JsonArray visibleEdges = new JsonArray();
        for (SkillTreeEdge edge : edgesByKey.values()) {
            if (!visibleNodes.contains(edge.sourceNodeId()) || !visibleNodes.contains(edge.targetNodeId())) continue;
            JsonObject value = new JsonObject();
            value.addProperty("sourceNodeId", edge.sourceNodeId());
            value.addProperty("targetNodeId", edge.targetNodeId());
            visibleEdges.add(value);
        }
        tree.add("edges", visibleEdges);
        view.add("tree", tree);
        return view;
    }

    /** API 到達可能時に、評価済みの接続情報を非同期で公開します。 */
    public void publishRuntimePlayerViewAsync(@NotNull AstPlayer astPlayer) {
        String serverId = runtimeServerId();
        if (serverId == null || !astPlayer.getBukkit().isOnline()) {
            return;
        }
        JsonObject view = createRuntimePlayerView(astPlayer);
        UUID accountId = astPlayer.getAccount().getUuid();
        SkillTreeRuntimeRepository.AccountSession session = runtimeAccountSessions.get(accountId);
        if (session == null) return;
        long sequence = runtimeViewSequences.merge(accountId, 1L, Long::sum);
        final boolean settled;
        synchronized (this) { settled = !dirtyPlayerStates.contains(accountId) && !masterPublicationInProgress; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!runtimeRepository.acquireAccount(serverId, runtimeServerSessionId, accountId, session, definitionGenerationId)) return;
                if (settled) runtimeRepository.publishPlayerView(serverId, runtimeServerSessionId, accountId, view, session, sequence);
            } catch (RuntimeException ignored) {
                // 次回 heartbeat / 再公開で回復する。接続中プレイヤーへ失敗を通知しない。
            }
        });
    }

    /** Bukkit メインスレッドでオンライン状態を再評価し、Web表示用に非同期公開します。 */
    private void publishOnlineRuntimePlayerViews() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && isStateReady(astPlayer)) {
                publishRuntimePlayerViewAsync(astPlayer);
                pollRuntimeOperationsAsync(astPlayer);
            }
        }
    }

    /**
     * 現在の backend session に配送された Web 操作を一件だけ取得して処理します。
     *
     * <p>claim と API 完了通知は非同期、現在地・世代・state版数・実際のポイント判定は
     * Bukkit メインスレッドと既存の critical mutation lane で行います。</p>
     */
    private void pollRuntimeOperationsAsync(@NotNull AstPlayer astPlayer) {
        String serverId = runtimeServerId();
        UUID accountId = astPlayer.getAccount().getUuid();
        SkillTreeRuntimeRepository.AccountSession accountSession = runtimeAccountSessions.get(accountId);
        if (serverId == null || accountSession == null || !runtimeOperationAccounts.add(accountId)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<SkillTreeRuntimeRepository.Operation> operations = runtimeRepository.findPending(
                        serverId, runtimeServerSessionId, accountId
                );
                if (operations.isEmpty()) {
                    runtimeOperationAccounts.remove(accountId);
                    return;
                }
                SkillTreeRuntimeRepository.Operation operation = operations.getFirst();
                    SkillTreeRuntimeRepository.ClaimedOperation claimed = runtimeRepository.claim(
                            serverId, runtimeServerSessionId, operation, accountSession
                );
                if (claimed == null) {
                    runtimeOperationAccounts.remove(accountId);
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> dispatchClaimedRuntimeOperation(serverId, astPlayer, claimed));
            } catch (RuntimeException ignored) {
                runtimeOperationAccounts.remove(accountId);
            }
        });
    }

    /** メインスレッドで claim 済みの Web 操作を再検証し、既存の保存 lane へ渡します。 */
    private void dispatchClaimedRuntimeOperation(
            @NotNull String serverId,
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeRuntimeRepository.ClaimedOperation claimed
    ) {
        SkillTreeRuntimeRepository.Operation operation = claimed.operation();
        UUID accountId = operation.accountId();
        Player player = astPlayer.getBukkit();
        if (!accountId.equals(astPlayer.getAccount().getUuid())
                || !player.isOnline()
                || !isRuntimeEditingAllowed(player)
                || !definitionGenerationId.equals(operation.expectedDefinitionGenerationId())
                || !isStateReady(astPlayer)
                || dirtyPlayerStates.contains(accountId)
                || state(astPlayer).persistedVersion() != operation.expectedPlayerStateVersion()
                || operation.expectedEvaluationFingerprint() != null
                && !operation.expectedEvaluationFingerprint().equals(
                        createRuntimeEvaluationFingerprint(astPlayer, state(astPlayer)))) {
            rejectRuntimeOperation(claimed, astPlayer, "RECONFIRMATION_REQUIRED");
            return;
        }

        if ("BATCH".equalsIgnoreCase(operation.action())) {
            if (operation.changes().isEmpty() || operation.changes().size() > 512) {
                rejectRuntimeOperation(claimed, astPlayer, "FAILED");
                return;
            }
            RuntimeMutationGuard batchGuard = new RuntimeMutationGuard(accountId, definitionGenerationId,
                    operation.expectedPlayerStateVersion(), runtimePlayerContextRevisions.getOrDefault(player.getUniqueId(), 0L),
                    operation.operationId(), claimed.leaseToken(), serverId, operation.expectedEvaluationFingerprint());
            applyRuntimeBatchAsync(astPlayer, operation.changes(), batchGuard).whenComplete((batch, failure) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (failure == null && batch != null) {
                            acknowledgeRuntimeBatch(astPlayer, batch);
                            runtimeOperationAccounts.remove(accountId);
                        } else {
                            rejectRuntimeOperation(claimed, astPlayer, "RECONFIRMATION_REQUIRED");
                        }
                    }));
            return;
        }

        SkillTreeNodeDefinition node = nodesById.get(operation.nodeId());
        if (node == null) {
            rejectRuntimeOperation(claimed, astPlayer, "FAILED");
            return;
        }
        RuntimeMutationGuard runtimeGuard = new RuntimeMutationGuard(
                accountId,
                definitionGenerationId,
                operation.expectedPlayerStateVersion(),
                runtimePlayerContextRevisions.getOrDefault(player.getUniqueId(), 0L),
                operation.operationId(),
                claimed.leaseToken(),
                serverId,
                operation.expectedEvaluationFingerprint()
        );
        CompletableFuture<SkillTreeMutationResult> mutation;
        if ("UNLOCK".equalsIgnoreCase(operation.action())) {
            if (requiresCpSourceSelection(node)
                    && (operation.sourceClassId() == null || operation.sourceClassId().isBlank())) {
                rejectRuntimeOperation(claimed, astPlayer, "RECONFIRMATION_REQUIRED");
                return;
            }
            mutation = unlockNodeAsync(astPlayer, node, operation.sourceClassId(), runtimeGuard);
        } else if ("RELOCK".equalsIgnoreCase(operation.action())) {
            mutation = relockNodeAsync(astPlayer, node, runtimeGuard);
        } else {
            rejectRuntimeOperation(claimed, astPlayer, "FAILED");
            return;
        }
        mutation.whenComplete((result, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure == null && result != null && result.changed()) {
                acknowledgeCommittedNodeMutation(astPlayer, node, result, "UNLOCK".equalsIgnoreCase(operation.action()));
                runtimeOperationAccounts.remove(accountId);
                return;
            }
            rejectRuntimeOperation(claimed, astPlayer, "RECONFIRMATION_REQUIRED");
        }));
    }

    /** 状態変更なしの拒否結果をroot skillTreeOperation sectionへ保存し、leaseを未確定で残しません。 */
    private void rejectRuntimeOperation(
            @NotNull SkillTreeRuntimeRepository.ClaimedOperation claimed,
            @NotNull AstPlayer astPlayer,
            @NotNull String finalStatus
    ) {
        UUID accountId = claimed.operation().accountId();
        SkillTreeOperationReceiptService receiptService = runtimeOperationReceiptService;
        if (receiptService == null) {
            runtimeOperationAccounts.remove(accountId);
            return;
        }
        SkillTreePlayerState state = state(astPlayer);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("operationId", claimed.operation().operationId().toString());
        receipt.addProperty("serverId", ConfigProperties.getInstance().getApiServerId());
        receipt.addProperty("serverSessionId", runtimeServerSessionId.toString());
        receipt.addProperty("leaseToken", claimed.leaseToken());
        receipt.addProperty("finalStatus", finalStatus);
        receipt.addProperty("failureReason", finalStatus);
        receipt.addProperty("definitionGenerationId", definitionGenerationId);
        receipt.addProperty("finalPlayerStateVersion", state.persistedVersion());
        receipt.addProperty("finalEvaluationFingerprint", createRuntimeEvaluationFingerprint(astPlayer, state));
        receipt.addProperty("migrateLegacyState", false);
        receipt.add("evaluatedView", createRuntimePlayerView(astPlayer));
        receiptService.recordResult(accountId, receipt).whenComplete((ignored, failure) ->
                runtimeOperationAccounts.remove(accountId));
    }

    private @NotNull String runtimeCostText(@NotNull SkillTreeNodeDefinition node) {
        return node.pointType() == SkillTreePointType.PASSIVE_POINT
                ? node.pointCost() + " PP"
                : requiresCpSourceSelection(node) ? "消費元を選択 · " + node.pointCost() + " CP"
                : runtimeClassName(node.unlockCondition().classId()) + " · " + node.pointCost() + " CP";
    }

    private @NotNull String runtimeClassName(@Nullable String classId) {
        if (classId == null) return "クラス共通";
        String name = playerClassService == null ? null : playerClassService.getDisplayName(classId);
        return playerClassService == null || playerClassService.getLoadedClass(classId) == null ? "未登録のクラス" : ColorCodeUtil.toPlainText(name, "未登録のクラス");
    }

    /** 提案時点と確定直前のGold・CP・条件を比較する、表示に依存しない評価指紋を生成します。 */
    private @NotNull String createRuntimeEvaluationFingerprint(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreePlayerState state
    ) {
        StringBuilder canonical = new StringBuilder()
                .append(definitionGenerationId).append('\n')
                .append(astPlayer.getAccount().getLevel()).append('\n')
                .append(astPlayer.getAccount().getHighestLevel()).append('\n')
                .append(astPlayer.getAccount().getClassId()).append('\n')
                .append(astPlayer.getAccount().getMode()).append('\n')
                .append(availablePassivePoints(astPlayer)).append('\n')
                .append(availableRelockGold(astPlayer)).append('\n');
        cpSourceOptions(astPlayer).forEach(option -> canonical
                .append(option.classId()).append(':')
                .append(option.classLevel()).append(':')
                .append(option.availablePoints()).append('\n'));
        nodesById.values().stream().sorted(java.util.Comparator.comparing(SkillTreeNodeDefinition::nodeId))
                .forEach(node -> canonical.append(node.nodeId()).append(':')
                        .append(state.isUnlocked(node.nodeId())).append(':')
                        .append(isNodeUnlockConditionMet(astPlayer, node)).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private @NotNull String runtimeBlockedReason(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            boolean unlocked
    ) {
        if (unlocked) {
            return canAffordRelock(astPlayer) ? "RELOCK_DISCONNECTS_TREE" : "INSUFFICIENT_GOLD";
        }
        if (!isNodeUnlockConditionMet(astPlayer, node)) {
            return "UNLOCK_CONDITION_NOT_MET";
        }
        boolean hasPoints = node.pointType() == SkillTreePointType.CLASS_POINT
                && requiresCpSourceSelection(node)
                ? cpSourceOptions(astPlayer).stream()
                        .anyMatch(option -> hasRequiredPoints(astPlayer, node, option.classId()))
                : hasRequiredPoints(astPlayer, node, node.pointType() == SkillTreePointType.CLASS_POINT
                        ? node.unlockCondition().classId() : null);
        if (!hasPoints) {
            return "INSUFFICIENT_POINTS";
        }
        return "NOT_CONNECTED";
    }

    @NotNull
    public Optional<Location> resolveSkillTreeSpawn() {
        WorldMasterData data = worldService.getById(SKILL_TREE_WORLD_ID);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(worldService.resolveSpawnLocation(data));
    }

    public boolean canTeleportFrom(@NotNull World world) {
        WorldMasterData current = worldService.findByBukkitWorld(world);
        return current != null && current.worldType() == WorldType.BASE;
    }

    @NotNull
    public CompletableFuture<Boolean> teleportToSkillTree(@NotNull AstPlayer astPlayer) {
        Optional<Location> spawn = resolveSkillTreeSpawn();
        if (spawn.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        preloadState(astPlayer);
        Player player = astPlayer.getBukkit();
        returnLocations.put(player.getUniqueId(), player.getLocation().clone());
        visualReadyAtMillis.put(player.getUniqueId(), System.currentTimeMillis() + VISUAL_DELAY_MILLIS);
        return worldService.teleportPlayerAsync(player, spawn.get(), () -> markViewerContextDirty(player));
    }

    @NotNull
    public CompletableFuture<Boolean> returnToBase(@NotNull Player player) {
        visualReadyAtMillis.remove(player.getUniqueId());
        stopLoadingPresentation(player);
        Location saved = returnLocations.remove(player.getUniqueId());
        if (saved != null && saved.getWorld() != null) {
            return worldService.teleportPlayerAsync(player, saved, () -> markViewerContextDirty(player));
        }
        for (WorldMasterData data : worldService.getAll()) {
            if (data.worldType() != WorldType.BASE) {
                continue;
            }
            Location spawn = worldService.resolveSpawnLocation(data);
            if (spawn != null) {
                return worldService.teleportPlayerAsync(player, spawn, () -> markViewerContextDirty(player));
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    public boolean isSkillTreeWorld(@NotNull World world) {
        WorldMasterData current = worldService.findByBukkitWorld(world);
        return current != null && SKILL_TREE_WORLD_ID.equals(current.id());
    }

    /**
     * 指定プレイヤー視点の他プレイヤー可視状態を、現在ワールドに応じて同期します。
     *
     * @param player 可視状態を同期するプレイヤー
     */
    public void refreshPlayerVisibility(@NotNull Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (player.equals(other)) {
                continue;
            }
            updateVisibility(player, other);
            updateVisibility(other, player);
        }
    }

    /**
     * 全オンラインプレイヤー間の可視状態を同期します。
     */
    public void refreshAllPlayerVisibility() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerVisibility(player);
        }
    }

    /**
     * 指定プレイヤーに関する可視制御を解除します。
     *
     * @param player 可視制御を解除するプレイヤー
     */
    public void restorePlayerVisibility(@NotNull Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (player.equals(other)) {
                continue;
            }
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
    }

    /**
     * 全オンラインプレイヤー間の可視制御を解除します。
     */
    public void restoreAllPlayerVisibility() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            restorePlayerVisibility(player);
        }
    }

    public boolean isPlayerModeSkillTree(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null
                && !isAdminMode(astPlayer)
                && isSkillTreeWorld(player.getWorld());
    }

    public boolean isAdminMode(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.ADMIN;
    }

    public boolean shouldShowAdminPosition(@NotNull Player player, @NotNull Location location) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return isAdminMode(astPlayer) && player.getWorld() == location.getWorld();
    }

    public boolean shouldShowPlayerNode(@NotNull Player player, @NotNull Location location) {
        return isSkillTreeVisualReady(player)
                && isPlayerModeSkillTree(player)
                && player.getWorld() == location.getWorld();
    }

    public boolean isSkillTreeVisualReady(@NotNull Player player) {
        Long readyAt = visualReadyAtMillis.get(player.getUniqueId());
        return readyAt == null || System.currentTimeMillis() >= readyAt;
    }

    /**
     * ノードが解放済みかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param node 判定対象ノード
     * @return 解放済みなら {@code true}
     */
    public boolean isNodeUnlocked(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        return state(astPlayer).isUnlocked(node.nodeId());
    }

    /**
     * プレイヤーがノード解放用の CP または PP を持っているかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 1 以上の CP または PP を持つなら {@code true}
     */
    public boolean hasAvailableUnlockPoint(@NotNull AstPlayer astPlayer) {
        return availablePassivePoints(astPlayer) > 0
                || cpSourceOptions(astPlayer).stream().anyMatch(option -> option.availablePoints() > 0);
    }

    public int availableClassPoints(@NotNull AstPlayer astPlayer) {
        return availableClassPoints(astPlayer, astPlayer.getClassId());
    }

    public int availablePassivePoints(@NotNull AstPlayer astPlayer) {
        SkillTreePlayerState state = state(astPlayer);
        return Math.max(0, earnedPassivePoints(astPlayer) - spentPassivePoints(knownUnlockedNodeIds(state)));
    }

    /** 現在職の CP 表示名を {@code CP[{職業名}]} 形式で返します。 */
    public @NotNull String currentClassPointLabel(@NotNull AstPlayer astPlayer) {
        String className = playerClassService == null
                ? astPlayer.getClassId()
                : playerClassService.getDisplayName(astPlayer.getClassId());
        return "CP[" + ColorCodeUtil.toPlainText(className, astPlayer.getClassId()) + "]";
    }

    /**
     * 保持済みクラス進行度を、CP 消費元候補として返します。
     * 残高 0 のクラスも GUI 上で理由を確認できるように含めます。
     */
    public @NotNull List<CpSourceOption> cpSourceOptions(@NotNull AstPlayer astPlayer) {
        List<CpSourceOption> options = new ArrayList<>();
        for (var progress : astPlayer.getAllClassProgresses()) {
            String classId = progress.getClassId();
            String displayName = playerClassService == null
                    ? classId
                    : playerClassService.getDisplayName(classId);
            options.add(new CpSourceOption(
                    classId,
                    displayName,
                    Math.max(1, progress.getLevel()),
                    availableClassPoints(astPlayer, classId)
            ));
        }
        options.sort(java.util.Comparator.comparing(CpSourceOption::classId));
        return List.copyOf(options);
    }

    public boolean requiresCpSourceSelection(@NotNull SkillTreeNodeDefinition node) {
        return node.pointType() == SkillTreePointType.CLASS_POINT
                && node.pointCost() > 0
                && !node.unlockCondition().hasClassCondition();
    }

    public boolean canUnlockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        if (requiresCpSourceSelection(node)) {
            return cpSourceOptions(astPlayer).stream()
                    .anyMatch(option -> canUnlockNode(astPlayer, node, option.classId()));
        }
        String consumedClassId = node.pointType() == SkillTreePointType.CLASS_POINT
                ? node.unlockCondition().classId()
                : null;
        return canUnlockNode(astPlayer, node, consumedClassId);
    }

    public boolean canUnlockNode(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable String consumedClassId
    ) {
        SkillTreePlayerState state = state(astPlayer);
        if (state.isUnlocked(node.nodeId()) || !isNodeUnlockConditionMet(astPlayer, node)) {
            return false;
        }
        if (!hasRequiredPoints(astPlayer, node, consumedClassId)) {
            return false;
        }
        if (knownUnlockedNodeIds(state).isEmpty()) {
            return rootNodeId.equals(node.nodeId());
        }
        return isAdjacentToActiveNode(astPlayer, state, node.nodeId());
    }

    /**
     * ノード解除に必要な Gold を支払える状態かを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 解除コストを支払えるなら {@code true}
     */
    public boolean canAffordRelock(@NotNull AstPlayer astPlayer) {
        return availableRelockGold(astPlayer) >= RELOCK_GOLD_COST;
    }

    /**
     * プレイヤーがスキルツリー操作中で、通常攻撃・特殊攻撃などを抑止すべきか判定します。
     *
     * @param player 判定対象のプレイヤー
     * @return 通常プレイヤーとしてスキルツリーワールドにいる場合は true
     */
    public boolean isSkillTreeEditing(@NotNull Player player) {
        return isPlayerModeSkillTree(player);
    }

    private void updateVisibility(@NotNull Player viewer, @NotNull Player target) {
        if (isSkillTreeWorld(viewer.getWorld())) {
            viewer.hidePlayer(plugin, target);
            return;
        }
        viewer.showPlayer(plugin, target);
    }

    @NotNull
    public Collection<SkillTreePosition> getPositions() {
        return List.copyOf(positionsByNodeId.values());
    }

    @NotNull
    public Collection<SkillTreeEdge> getEdges() {
        return List.copyOf(edgesByKey.values());
    }

    @Nullable
    public SkillTreePosition getPosition(@NotNull String nodeId) {
        return positionsByNodeId.get(nodeId);
    }

    @Nullable
    public SkillTreeNodeDefinition getNode(@NotNull String nodeId) {
        return nodesById.get(nodeId);
    }

    @NotNull
    public Collection<String> getNodeIds() {
        return List.copyOf(nodesById.keySet());
    }

    @NotNull
    public SkillTreePlayerState state(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        SkillTreePlayerState state = playerStates.get(accountId);
        if (state != null && !failedPlayerStateLoads.contains(accountId)) {
            return state;
        }
        failedPlayerStateLoads.remove(accountId);
        SkillTreePlayerState fallback = new SkillTreePlayerState(accountId, Set.<String>of());
        playerStates.put(accountId, fallback);
        loadStateAsync(accountId);
        return fallback;
    }

    /**
     * プレイヤー状態の非同期ロードを開始します。既にロード済み、またはロード中の場合は何もしません。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void preloadState(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        if (loadingPlayerStates.contains(accountId)) {
            return;
        }
        if (playerStates.containsKey(accountId) && !failedPlayerStateLoads.contains(accountId)) {
            return;
        }
        failedPlayerStateLoads.remove(accountId);
        playerStates.put(accountId, new SkillTreePlayerState(accountId, Set.<String>of()));
        loadStateAsync(accountId);
    }

    /**
     * 初回ログイン処理で使用するスキルツリー状態を読み込みます。
     * <p>
     * 呼び出し元は Bukkit メインスレッド外で実行し、戻り値は
     * {@link #applyInitialPlayerState(SkillTreePlayerState)} でメインスレッドから反映してください。
     *
     * 現在のマスター構造と不整合な状態は、API側で空状態への置換と補償メール配信を
     * 同一トランザクションとして確定する。
     *
     * @param accountId 読み込み対象アカウント UUID
     * @param userId 補償メール配信対象ユーザー UUID
     * @return 保持中の未保存状態、または検証済みのAPI / DB読込状態
     * @throws RuntimeException 読み込みまたは補修APIの呼び出しに失敗した場合
     */
    public @NotNull SkillTreePlayerState loadInitialPlayerState(
            @NotNull UUID accountId,
            @NotNull UUID userId
    ) {
        SkillTreePlayerState retained = retainInitialPlayerState(accountId);
        if (retained != null) return retained;
        try {
        acquireRuntimeAccount(accountId);
        SkillTreePlayerState loadedState = playerStateRepository.load(accountId, ConfigProperties.getInstance().getApiServerId(), runtimeServerSessionId, runtimeAccountSessions.get(accountId), definitionGenerationId);
        if (!loadedState.unlockedNodeIds().isEmpty()
                && (loadedState.definitionGenerationId() == null || loadedState.definitionGenerationId().isBlank())) {
            throw new io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeCompatibilityException("Skill tree legacy state has no definition generation.");
        }
        if (loadedState.definitionGenerationId() != null
                && !loadedState.definitionGenerationId().equals(definitionGenerationId)) {
            throw new io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeCompatibilityException("Skill tree definition generation does not match this server.");
        }
        PlayerStateValidationSnapshot validationSnapshot = playerStateValidationSnapshot;
        if (validationSnapshot.isStructurallyValid(loadedState)) {
            runtimeInitialLoadStates.put(accountId, loadedState);
            return loadedState;
        }
        SkillTreePlayerState repaired = playerStateRepository.repairInvalidState(
                accountId,
                userId,
                validationSnapshot.repairKey(),
                loadedState.persistedVersion(),
                loadedState.definitionGenerationId(),
                ConfigProperties.getInstance().getApiServerId(), runtimeServerSessionId, runtimeAccountSessions.get(accountId)
        );
        runtimeInitialLoadStates.put(accountId, repaired);
        return repaired;
        } catch (RuntimeException failure) {
            releaseRuntimeAccount(accountId);
            throw failure;
        }
    }

    /** 初期ロードと同じ非同期スレッドで、前の参加先が終了するまで短時間だけ待機する。 */
    private void acquireRuntimeAccount(UUID accountId) {
        if (!runtimeRepository.isConfigured()) throw new io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeCompatibilityException("Skill tree runtime is not configured");
        publishRuntimeHeartbeat();
        SkillTreeRuntimeRepository.AccountSession session = runtimeAccountSessions.computeIfAbsent(accountId, ignored -> SkillTreeRuntimeRepository.AccountSession.create());
        String generation = definitionGenerationId;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            if (runtimeRepository.acquireAccount(ConfigProperties.getInstance().getApiServerId(), runtimeServerSessionId, accountId, session, generation)) {
                runtimeAccountDefinitionGenerations.put(accountId, generation);
                return;
            }
            try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Previous skill tree player session has not finished");
    }

    /** 退出保存後に処理権限を終了する。古い通知は終了したsessionだけを指し、新しい参加先へ影響しない。 */
    private void releaseRuntimeAccount(UUID accountId) {
        SkillTreeRuntimeRepository.AccountSession session = runtimeAccountSessions.remove(accountId);
        if (session == null) return;
        runtimeAccountDefinitionGenerations.remove(accountId);
        runtimeLastViews.remove(accountId);
        JsonObject captured = new JsonObject();
        long sequence = runtimeViewSequences.getOrDefault(accountId, 0L) + 1;
        runtimeViewSequences.remove(accountId);
        CompletableFuture.runAsync(() -> {
            try { runtimeRepository.closeAccount(ConfigProperties.getInstance().getApiServerId(), runtimeServerSessionId, accountId, session, captured, sequence); }
            catch (RuntimeException ignored) { /* 終了を確認できない場合はlease期限で失効し、オフライン案は許可しない。 */ }
        });
    }

    /**
     * 全退出保存ACK後かつinventory解放前に、保存済み残高の最終viewと処理権限終了を確定する。
     * @param astPlayer 退出済みのデータ参照。Bukkitの現在位置をここから参照しない
     */
    public void finishRuntimeLogout(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        SkillTreeRuntimeRepository.AccountSession session;
        JsonObject view;
        synchronized (this) {
            session = runtimeAccountSessions.get(accountId);
            if (session == null) return;
            JsonObject previous = runtimeLastViews.get(accountId);
            JsonObject location = previous == null ? new JsonObject() : previous.getAsJsonObject("location");
            view = masterPublicationInProgress ? new JsonObject() : createRuntimePlayerDataView(astPlayer, location, false, false);
        }
        try {
            runtimeRepository.closeAccount(ConfigProperties.getInstance().getApiServerId(), runtimeServerSessionId,
                    accountId, session, view, runtimeViewSequences.getOrDefault(accountId, 0L) + 1);
        } catch (RuntimeException ignored) {
            // ACK済みのデータは保持済み。終了確認不能時はlease失効まで新たな所有権を与えない。
        } finally {
            synchronized (this) {
                runtimeAccountSessions.remove(accountId, session);
                runtimeAccountDefinitionGenerations.remove(accountId);
                runtimeLastViews.remove(accountId);
                runtimeViewSequences.remove(accountId);
                discardAccountState(accountId);
            }
        }
    }

    /**
     * 初期ロード状態を公開します。未保存状態またはload時の保持状態は旧API値で上書きしません。
     * <p>
     * {@link AstPlayer} 登録前に呼び出すことで、初回ステータス計算が空のスキルツリー状態を参照しないようにします。
     *
     * @param state 反映するスキルツリー状態
     */
    public synchronized void applyInitialPlayerState(@NotNull SkillTreePlayerState state) {
        if (masterPublicationInProgress || !definitionGenerationId.equals(runtimeAccountDefinitionGenerations.get(state.accountId())))
            throw new io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeCompatibilityException("Definition changed during player admission");
        runtimeInitialLoadStates.remove(state.accountId(), state);
        loadingPlayerStates.remove(state.accountId());
        failedPlayerStateLoads.remove(state.accountId());
        releasedPlayerStates.remove(state.accountId());
        initialPlayerStatePublications.put(state.accountId(), state);
        if (retainedInitialPlayerStates.remove(state.accountId()) || dirtyPlayerStates.contains(state.accountId())) {
            derivedPlayerStates.remove(state.accountId());
            return;
        }
        playerStates.put(state.accountId(), state);
        playerStateEpochs.put(state.accountId(), UUID.randomUUID());
        acknowledgedPlayerStateRevisions.remove(state.accountId());
        playerStateRevisions.remove(state.accountId());
        if (state.persistedVersion() > 0) {
            persistedPlayerStateVersions.put(state.accountId(), state.persistedVersion());
        } else {
            persistedPlayerStateVersions.remove(state.accountId());
        }
        derivedPlayerStates.remove(state.accountId());
        if (state.definitionGenerationId() == null && state.unlockedNodeIds().isEmpty()) {
            SkillTreePlayerState bound = bindCurrentDefinitionGeneration(state);
            playerStates.put(state.accountId(), bound);
            pendingLegacyDefinitionGenerationMigrations.add(state.accountId());
            markDirty(bound);
        }
    }

    /** 未保存状態を初期ロードへ引き継ぎ、applyまでACK後の破棄を抑止します。 */
    private synchronized @Nullable SkillTreePlayerState retainInitialPlayerState(UUID accountId) {
        if (!dirtyPlayerStates.contains(accountId) && !retainedInitialPlayerStates.contains(accountId)) return null;
        retainedInitialPlayerStates.add(accountId);
        releasedPlayerStates.remove(accountId);
        SkillTreePlayerState current = playerStates.get(accountId);
        return current == null ? null : new SkillTreePlayerState(
            accountId, current.unlockedNodes(), current.persistedVersion(), current.definitionGenerationId());
    }

    /**
     * ログイン反映が中断された場合に、当該反映で公開したスキルツリー状態だけを破棄します。
     * 後続セッションが同じアカウントへ別の状態を反映済みの場合は削除しません。
     * 未保存状態は完全ACKまで保持し、その後に破棄します。
     *
     * @param state {@link #applyInitialPlayerState(SkillTreePlayerState)} へ渡した初期状態
     */
    public synchronized void discardInitialPlayerState(@NotNull SkillTreePlayerState state) {
        if (initialPlayerStatePublications.get(state.accountId()) != state) {
            if (runtimeInitialLoadStates.remove(state.accountId(), state)) releaseRuntimeAccount(state.accountId());
            return;
        }
        retainedInitialPlayerStates.remove(state.accountId());
        initialPlayerStatePublications.remove(state.accountId());
        releasedPlayerStates.add(state.accountId());
        if (dirtyPlayerStates.contains(state.accountId())) return;
        evictReleasedPlayerState(state.accountId());
    }

    /** 保存不能の復旧時に、指定 account の未保存スキルツリー状態と表示を保存せず破棄します。 */
    public synchronized void discardAccountState(@NotNull UUID accountId) {
        releaseRuntimeAccount(accountId);
        playerStates.remove(accountId);
        derivedPlayerStates.remove(accountId);
        dirtyPlayerStates.remove(accountId);
        loadingPlayerStates.remove(accountId);
        failedPlayerStateLoads.remove(accountId);
        dirtyPlayerStateDueAtMillis.remove(accountId);
        playerStateRevisions.remove(accountId);
        persistedPlayerStateVersions.remove(accountId);
        retainedInitialPlayerStates.remove(accountId);
        releasedPlayerStates.remove(accountId);
        acknowledgedPlayerStateRevisions.remove(accountId);
        playerStateEpochs.remove(accountId);
        initialPlayerStatePublications.remove(accountId);
        pendingRuntimeOperationReceipts.remove(accountId);
        pendingLegacyDefinitionGenerationMigrations.remove(accountId);
        returnLocations.remove(accountId);
        visualReadyAtMillis.remove(accountId);
        BossBar bossBar = loadingBossBars.remove(accountId);
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    /** 保存済みかつ初期反映取消済みの状態を破棄します。serviceのmonitor内から呼びます。 */
    private void evictReleasedPlayerState(UUID accountId) {
        if (!releasedPlayerStates.remove(accountId)) return;
        releaseRuntimeAccount(accountId);
        playerStates.remove(accountId);
        initialPlayerStatePublications.remove(accountId);
        playerStateEpochs.remove(accountId);
        playerStateRevisions.remove(accountId);
        acknowledgedPlayerStateRevisions.remove(accountId);
        persistedPlayerStateVersions.remove(accountId);
        pendingRuntimeOperationReceipts.remove(accountId);
        pendingLegacyDefinitionGenerationMigrations.remove(accountId);
        derivedPlayerStates.remove(accountId);
    }

    /**
     * スキルツリー状態を通信待ちなしで利用できるかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return API ロードが完了している場合は true
     */
    public boolean isStateReady(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        SkillTreePlayerState loaded = playerStates.get(accountId);
        return !masterPublicationInProgress && loaded != null
                && (loaded.definitionGenerationId() == null && loaded.unlockedNodeIds().isEmpty() || definitionGenerationId.equals(loaded.definitionGenerationId()))
                && runtimeAccountSessions.containsKey(accountId)
                && !loadingPlayerStates.contains(accountId)
                && !failedPlayerStateLoads.contains(accountId);
    }

    private void loadStateAsync(@NotNull UUID accountId) {
        if (!loadingPlayerStates.add(accountId)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SkillTreePlayerState loaded = null;
            RuntimeException failure = null;
            try {
                loaded = playerStateRepository.load(accountId, ConfigProperties.getInstance().getApiServerId(), runtimeServerSessionId, runtimeAccountSessions.get(accountId), definitionGenerationId);
                if (loaded.definitionGenerationId() == null && !loaded.unlockedNodeIds().isEmpty()
                        || loaded.definitionGenerationId() != null && !definitionGenerationId.equals(loaded.definitionGenerationId())) {
                    throw new IllegalStateException("Skill tree definition generation is incompatible");
                }
            } catch (RuntimeException e) {
                failure = e;
            }

            SkillTreePlayerState loadedState = loaded;
            RuntimeException loadFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loadingPlayerStates.remove(accountId);
                if (loadFailure != null) {
                    failedPlayerStateLoads.add(accountId);
                    Logger.log(LogId.W_9002, accountId, loadFailure.getMessage());
                    return;
                }
                failedPlayerStateLoads.remove(accountId);
                if (!dirtyPlayerStates.contains(accountId)) {
                    playerStates.put(accountId, loadedState);
                    refreshDerivedState(accountId);
                    markViewerContextDirty(accountId);
                }
            });
        });
    }

    /**
     * ローカル変更の保存世代を進め、完全ACKまで未保存として保持します。
     * @param state キャッシュへ反映済みのアカウント状態
     */
    public synchronized void markDirty(@NotNull SkillTreePlayerState state) {
        playerStateEpochs.computeIfAbsent(state.accountId(), ignored -> UUID.randomUUID());
        dirtyPlayerStates.add(state.accountId());
        dirtyPlayerStateDueAtMillis.remove(state.accountId());
        playerStateRevisions.merge(state.accountId(), 1L, Long::sum);
    }

    /**
     * レベル由来ポイントの変化に合わせてスキルツリー効果と表示を再計算します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void refreshProgressDerivedState(@NotNull AstPlayer astPlayer) {
        SkillTreePlayerState state = state(astPlayer);
        Set<String> previousSkillIds = derivedState(astPlayer, state).unlockedSkillIds();
        DerivedPlayerState nextDerivedState = rebuildDerivedState(astPlayer, state);
        derivedPlayerStates.put(state.accountId(), nextDerivedState);
        Set<String> addedSkillIds = new LinkedHashSet<>(nextDerivedState.unlockedSkillIds());
        addedSkillIds.removeAll(previousSkillIds);
        Set<String> removedSkillIds = new LinkedHashSet<>(previousSkillIds);
        removedSkillIds.removeAll(nextDerivedState.unlockedSkillIds());
        refreshDerivedState(astPlayer, addedSkillIds, removedSkillIds, true);
        markViewerContextDirty(astPlayer.getBukkit());
    }

    private @NotNull DerivedPlayerState derivedState(@NotNull AstPlayer astPlayer, @NotNull SkillTreePlayerState state) {
        return derivedPlayerStates.computeIfAbsent(state.accountId(), ignored -> rebuildDerivedState(astPlayer, state));
    }

    private @NotNull DerivedPlayerState rebuildDerivedState(@NotNull AstPlayer astPlayer, @Nullable SkillTreePlayerState state) {
        if (state == null) {
            return DerivedPlayerState.EMPTY;
        }
        Set<String> activeNodeIds = activeUnlockedNodeIds(astPlayer, state);
        Set<String> inactiveNodeIds = new LinkedHashSet<>(state.unlockedNodeIds());
        inactiveNodeIds.removeAll(activeNodeIds);
        Set<String> unlockedSkillIds = new LinkedHashSet<>();
        Map<StatusType, StatusBonusTotals> statusBonuses = new java.util.EnumMap<>(StatusType.class);
        for (String nodeId : activeNodeIds) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node == null) {
                continue;
            }
            for (SkillTreeSkillEffect skill : node.skillEffects()) {
                if (!skill.skillId().isBlank()) {
                    unlockedSkillIds.add(skill.skillId());
                }
            }
            for (SkillTreeStatusEffect status : node.statusEffects()) {
                StatusBonusTotals current = statusBonuses.getOrDefault(status.statusType(), StatusBonusTotals.ZERO);
                statusBonuses.put(
                        status.statusType(),
                        status.modifierType() == StatusModifierType.SCALAR
                                ? new StatusBonusTotals(current.flat(), current.scalar() + status.value())
                                : new StatusBonusTotals(current.flat() + status.value(), current.scalar())
                );
            }
        }
        return new DerivedPlayerState(Set.copyOf(unlockedSkillIds), Map.copyOf(statusBonuses), Set.copyOf(inactiveNodeIds));
    }

    private @NotNull Set<String> activeUnlockedNodeIds(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreePlayerState state
    ) {
        Set<String> activeNodeIds = knownUnlockedNodeIds(state);

        List<String> passiveNodeIds = activeNodeIds.stream()
                .filter(nodeId -> nodesById.get(nodeId).pointType() == SkillTreePointType.PASSIVE_POINT)
                .sorted(this::compareNodeIdDescending)
                .toList();
        int passiveSpent = spentPassivePoints(activeNodeIds);
        for (String nodeId : passiveNodeIds) {
            if (passiveSpent <= earnedPassivePoints(astPlayer)) {
                break;
            }
            activeNodeIds.remove(nodeId);
            passiveSpent -= nodesById.get(nodeId).pointCost();
        }

        Map<String, List<String>> classNodeIds = new LinkedHashMap<>();
        Map<String, Integer> classSpent = new LinkedHashMap<>();
        for (String nodeId : knownUnlockedNodeIds(state)) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node.pointType() != SkillTreePointType.CLASS_POINT || node.pointCost() <= 0) {
                continue;
            }
            String consumedClassId = resolvedConsumedClassId(state.unlockedNode(nodeId), node);
            if (consumedClassId == null) {
                activeNodeIds.remove(nodeId);
                continue;
            }
            classNodeIds.computeIfAbsent(consumedClassId, ignored -> new ArrayList<>()).add(nodeId);
            classSpent.merge(consumedClassId, node.pointCost(), Integer::sum);
        }
        for (Map.Entry<String, List<String>> entry : classNodeIds.entrySet()) {
            String classId = entry.getKey();
            int spent = classSpent.getOrDefault(classId, 0);
            List<String> descendingNodeIds = entry.getValue();
            descendingNodeIds.sort(this::compareNodeIdDescending);
            for (String nodeId : descendingNodeIds) {
                if (spent <= earnedClassPoints(astPlayer, classId)) {
                    break;
                }
                activeNodeIds.remove(nodeId);
                spent -= nodesById.get(nodeId).pointCost();
            }
        }

        activeNodeIds.removeIf(nodeId -> !isNodeUnlockConditionMet(astPlayer, nodesById.get(nodeId)));
        return activeNodeIds;
    }

    /**
     * API 状態に残る削除済み ID を除外し、現行ノードマスタに存在する解放済み ID だけを返します。
     *
     * @param state プレイヤーの永続化済み解放状態
     * @return 現行ノードマスタに存在する解放済み ID
     */
    private @NotNull Set<String> knownUnlockedNodeIds(@NotNull SkillTreePlayerState state) {
        Set<String> knownNodeIds = new LinkedHashSet<>();
        for (String nodeId : state.unlockedNodeIds()) {
            if (nodesById.containsKey(nodeId)) {
                knownNodeIds.add(nodeId);
            }
        }
        return knownNodeIds;
    }

    private int availablePoints(@NotNull AstPlayer astPlayer, @NotNull SkillTreePointType pointType) {
        return pointType == SkillTreePointType.CLASS_POINT
                ? availableClassPoints(astPlayer)
                : availablePassivePoints(astPlayer);
    }

    /**
     * 指定クラスのスキルツリー解放に使用できるCP残高を返します。
     *
     * @param astPlayer 対象プレイヤー
     * @param classId CP消費元クラスID
     * @return 指定クラスの未消費CP。残高が負数になる場合は0
     */
    public int availableClassPoints(@NotNull AstPlayer astPlayer, @NotNull String classId) {
        SkillTreePlayerState state = state(astPlayer);
        return Math.max(0, earnedClassPoints(astPlayer, classId) - spentClassPoints(state, classId));
    }

    private int earnedClassPoints(@NotNull AstPlayer astPlayer, @NotNull String classId) {
        String normalizedClassId = normalizeClassId(classId);
        for (var progress : astPlayer.getAllClassProgresses()) {
            if (normalizeClassId(progress.getClassId()).equals(normalizedClassId)) {
                return Math.max(0, progress.getLevel() - 1);
            }
        }
        return 0;
    }

    private int earnedPassivePoints(@NotNull AstPlayer astPlayer) {
        return Math.max(0,
            Math.max(astPlayer.getAccount().getLevel(), astPlayer.getAccount().getHighestLevel()) - 1);
    }

    private int spentClassPoints(@NotNull SkillTreePlayerState state, @NotNull String classId) {
        String normalizedClassId = normalizeClassId(classId);
        int spent = 0;
        for (String nodeId : knownUnlockedNodeIds(state)) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node.pointType() != SkillTreePointType.CLASS_POINT || node.pointCost() <= 0) {
                continue;
            }
            String consumedClassId = resolvedConsumedClassId(state.unlockedNode(nodeId), node);
            if (normalizedClassId.equals(consumedClassId)) {
                spent += node.pointCost();
            }
        }
        return spent;
    }

    private int spentPassivePoints(@NotNull Set<String> nodeIds) {
        int spent = 0;
        for (String nodeId : nodeIds) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node == null || node.pointType() != SkillTreePointType.PASSIVE_POINT || node.pointCost() <= 0) {
                continue;
            }
            spent += node.pointCost();
        }
        return spent;
    }

    private @Nullable String resolvedConsumedClassId(
            @Nullable SkillTreeUnlockedNode unlockedNode,
            @NotNull SkillTreeNodeDefinition node
    ) {
        if (unlockedNode != null && unlockedNode.consumedClassId() != null) {
            return normalizeClassId(unlockedNode.consumedClassId());
        }
        return node.unlockCondition().hasClassCondition()
                ? normalizeClassId(node.unlockCondition().classId())
                : null;
    }

    private @NotNull String normalizeClassId(@NotNull String classId) {
        return classId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean hasRequiredPoints(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable String consumedClassId
    ) {
        if (node.pointType() == SkillTreePointType.PASSIVE_POINT) {
            return consumedClassId == null && availablePassivePoints(astPlayer) >= node.pointCost();
        }

        String requiredClassId = node.unlockCondition().classId();
        String normalizedSource = consumedClassId == null ? null : normalizeClassId(consumedClassId);
        if (requiredClassId != null) {
            String normalizedRequired = normalizeClassId(requiredClassId);
            if (normalizedSource == null) {
                normalizedSource = normalizedRequired;
            }
            return normalizedRequired.equals(normalizedSource)
                    && availableClassPoints(astPlayer, normalizedRequired) >= node.pointCost();
        }
        if (node.pointCost() == 0) {
            return normalizedSource == null || earnedClassPoints(astPlayer, normalizedSource) >= 0;
        }
        return normalizedSource != null
                && availableClassPoints(astPlayer, normalizedSource) >= node.pointCost();
    }

    /** ノードの現在職・プレイヤーレベル条件が成立しているかを返します。 */
    public boolean isNodeUnlockConditionMet(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        if (node.unlockCondition().hasPlayerLevelCondition()
                && astPlayer.getAccount().getLevel() < node.unlockCondition().playerLevel()) {
            return false;
        }
        String requiredClassId = node.unlockCondition().classId();
        return requiredClassId == null
                || playerClassService != null
                && playerClassService.matchesCurrentClassCondition(astPlayer, requiredClassId);
    }

    /**
     * 通常プレイヤーにノードを表示してよいかを返します。
     *
     * <p>CPノードは解放条件未達時に非表示とし、PPノードは条件表示のため表示を維持します。</p>
     */
    public boolean isNodeVisible(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        return node.pointType() == SkillTreePointType.PASSIVE_POINT
                || isNodeUnlockConditionMet(astPlayer, node);
    }

    /**
     * 指定ノードの強調ビームがプレイヤーに表示される条件を満たすかを返します。
     *
     * @param player 判定対象プレイヤー
     * @param node 判定対象ノード
     * @param position ノードのワールド位置
     * @return 強調ビームが表示対象なら {@code true}
     */
    public boolean isNodeBeaconVisible(
            @NotNull Player player,
            @NotNull SkillTreeNodeDefinition node,
            @NotNull SkillTreePosition position
    ) {
        Location location = position.toLocation();
        return location != null
                && visualizer != null
                && visualizer.isNodeBeaconVisible(player, node, location);
    }

    private int compareNodeIdDescending(@NotNull String left, @NotNull String right) {
        int numeric = Long.compare(nodeIdSortValue(right), nodeIdSortValue(left));
        return numeric != 0 ? numeric : right.compareTo(left);
    }

    private long nodeIdSortValue(@NotNull String nodeId) {
        return nodeIdSortValues.computeIfAbsent(nodeId, this::parseNodeIdSortValue);
    }

    private long parseNodeIdSortValue(@NotNull String nodeId) {
        StringBuilder digits = new StringBuilder(nodeId.length());
        for (int index = 0; index < nodeId.length(); index++) {
            char character = nodeId.charAt(index);
            if (Character.isDigit(character)) {
                digits.append(character);
            }
        }
        if (digits.isEmpty()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void markNodeStateChanged(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition changedNode,
            @NotNull Set<String> previousSkillIds,
            @NotNull Set<String> currentSkillIds
    ) {
        Set<String> addedSkillIds = new LinkedHashSet<>(currentSkillIds);
        addedSkillIds.removeAll(previousSkillIds);
        Set<String> removedSkillIds = new LinkedHashSet<>(previousSkillIds);
        removedSkillIds.removeAll(currentSkillIds);
        boolean statusAffected = !changedNode.statusEffects().isEmpty()
                || !addedSkillIds.isEmpty()
                || !removedSkillIds.isEmpty();
        refreshDerivedState(astPlayer, addedSkillIds, removedSkillIds, statusAffected);
        if (visualizer != null) {
            // point残高の変化は離れた未解放nodeのAVAILABLE状態にも影響するため全体更新にする。
            visualizer.markViewerDirty(astPlayer.getBukkit().getUniqueId());
        }
    }

    public @NotNull Set<String> affectedNodeIds(@NotNull String nodeId) {
        Set<String> affected = new LinkedHashSet<>();
        affected.add(nodeId);
        affected.addAll(adjacentNodeIds(nodeId));
        return affected;
    }

    private long availableRelockGold(@NotNull AstPlayer astPlayer) {
        if (inventoryService == null) {
            return 0L;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        return inventoryService.getGoldAmount(accountId);
    }

    public void markViewerContextDirty(@NotNull Player player) {
        markRuntimePlayerContextChanged(player);
        if (visualizer != null) {
            visualizer.markViewerDirty(player.getUniqueId());
        }
    }

    /** Web操作の現在地確認に使うプレイヤー文脈世代を更新します。 */
    public void markRuntimePlayerContextChanged(@NotNull Player player) {
        runtimePlayerContextRevisions.merge(player.getUniqueId(), 1L, Long::sum);
    }

    /**
     * 移動による表示更新を、前回描画位置からの累積距離で要求します。
     * teleportや状態変更は {@link #markViewerContextDirty(Player)} を使用します。
     *
     * @param player 移動したviewer
     * @param current 移動イベントで確定した移動先
     */
    public void markViewerMoved(@NotNull Player player, @NotNull Location current) {
        markRuntimePlayerContextChanged(player);
        if (visualizer != null) {
            visualizer.markViewerMoved(player, current);
        }
    }

    /** chunk load/unload等で構造packetの再同期が必要になったことを通知します。 */
    public void markStructureDirty() {
        if (visualizer != null) {
            visualizer.markStructureDirty();
        }
    }

    /** 退出したviewerに紐づく一時表示状態を破棄します。 */
    public void removeViewerPresentation(@NotNull Player player) {
        if (visualizer != null) {
            visualizer.removeViewer(player.getUniqueId());
        }
    }

    /**
     * 指定プレイヤーのスキル使用許可ノード強調表示を設定します。
     * 設定はスキルツリー表示用のプロセス内状態だけへ保持します。
     *
     * @param player 表示設定を変更するプレイヤー
     * @param enabled 有効にする場合は {@code true}
     */
    public void setSkillNodeHighlightEnabled(@NotNull Player player, boolean enabled) {
        if (visualizer != null) {
            visualizer.setSkillNodeHighlightEnabled(player.getUniqueId(), enabled);
        }
    }

    /**
     * 指定プレイヤーのスキル使用許可ノード強調表示を反転します。
     *
     * @param player 表示設定を変更するプレイヤー
     * @return 反転後に有効なら {@code true}
     */
    public boolean toggleSkillNodeHighlight(@NotNull Player player) {
        boolean enabled = !isSkillNodeHighlightEnabled(player);
        setSkillNodeHighlightEnabled(player, enabled);
        return enabled;
    }

    /**
     * 指定プレイヤーのステータス絞り込み対象を設定します。
     * 空集合は絞り込みを解除します。設定はプロセス内状態だけへ保持します。
     *
     * @param player 表示設定を変更するプレイヤー
     * @param statusFilter 絞り込むステータス種別
     */
    public void setStatusFilter(@NotNull Player player, @NotNull Set<StatusType> statusFilter) {
        if (visualizer != null) {
            visualizer.setStatusFilter(player.getUniqueId(), statusFilter);
        }
    }

    /**
     * 指定プレイヤーに設定されたステータス絞り込み対象を返します。
     *
     * @param player 確認対象プレイヤー
     * @return 不変なステータス種別集合。絞り込みなしの場合は空集合
     */
    public @NotNull Set<StatusType> statusFilter(@NotNull Player player) {
        return visualizer == null ? Set.of() : visualizer.statusFilter(player.getUniqueId());
    }

    /**
     * 指定プレイヤーのスキル使用許可ノード強調表示が有効かを返します。
     *
     * @param player 確認対象プレイヤー
     * @return 有効なら {@code true}
     */
    public boolean isSkillNodeHighlightEnabled(@NotNull Player player) {
        return visualizer == null || visualizer.isSkillNodeHighlightEnabled(player.getUniqueId());
    }

    private void markViewerContextDirty(@NotNull UUID accountId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && accountId.equals(astPlayer.getAccount().getUuid())) {
                markViewerContextDirty(player);
                return;
            }
        }
    }

    private void markAllViewerContextsDirty() {
        if (visualizer == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            visualizer.markViewerDirty(player.getUniqueId());
        }
    }

    /**
     * 解放済みノードに紐づくスキル ID 一覧を返します。
     */
    public @NotNull Set<String> getUnlockedSkillIds(@NotNull AstPlayer astPlayer) {
        SkillTreePlayerState state = state(astPlayer);
        return derivedState(astPlayer, state).unlockedSkillIds();
    }

    /**
     * 解放済みノードから指定ステータスへの補正値を返します。
     */
    public double getStatusBonus(
        @NotNull AstPlayer astPlayer,
        @NotNull StatusType statusType,
        double baseValue
    ) {
        SkillTreePlayerState state = state(astPlayer);
        StatusBonusTotals totals = derivedState(astPlayer, state).statusBonuses()
                .getOrDefault(statusType, StatusBonusTotals.ZERO);
        return totals.flat() + (baseValue * totals.scalar());
    }

    public @NotNull CompletableFuture<SkillTreeMutationResult> unlockNodeAsync(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        String consumedClassId = node.pointType() == SkillTreePointType.CLASS_POINT
                ? node.unlockCondition().classId()
                : null;
        if (requiresCpSourceSelection(node)) {
            return CompletableFuture.completedFuture(SkillTreeMutationResult.rejected());
        }
        return unlockNodeAsync(astPlayer, node, consumedClassId);
    }

    public @NotNull CompletableFuture<SkillTreeMutationResult> unlockNodeAsync(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable String consumedClassId
    ) {
        return unlockNodeAsync(astPlayer, node, consumedClassId, null);
    }

    /** Web claim の世代・現在地文脈を保存lane内でも再検証して解放します。 */
    private @NotNull CompletableFuture<SkillTreeMutationResult> unlockNodeAsync(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable String consumedClassId,
            @Nullable RuntimeMutationGuard runtimeGuard
    ) {
        InventoryService persistence = inventoryService;
        if (persistence == null) {
            return CompletableFuture.completedFuture(SkillTreeMutationResult.rejected());
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        return persistence.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = persistence.snapshotState(accountId);
            SkillTreeMutationCheckpoint checkpoint = null;
            try {
                synchronized (this) {
                    if (masterPublicationInProgress || !isStateReady(astPlayer) || nodesById.get(node.nodeId()) != node) {
                        throw new IllegalStateException("Skill tree definition is not ready");
                    }
                    if (runtimeGuard != null && !matchesRuntimeMutationGuard(astPlayer, runtimeGuard)) {
                        throw new IllegalStateException("Skill tree runtime operation is stale.");
                    }
                    if (!canUnlockNode(astPlayer, node, consumedClassId)) {
                        throw new IllegalStateException("Skill tree node is no longer unlockable.");
                    }
                    SkillTreePlayerState state = state(astPlayer);
                    Set<String> previousSkillIds = derivedState(astPlayer, state).unlockedSkillIds();
                    checkpoint = captureMutationCheckpoint(accountId, state);
                    String normalizedSource = node.pointType() == SkillTreePointType.CLASS_POINT && consumedClassId != null
                            ? normalizeClassId(consumedClassId)
                            : null;
                    if (!applyNodeMutationLocked(persistence, accountId, state, node, "UNLOCK", normalizedSource)) {
                        throw new IllegalStateException("Skill tree node is already unlocked.");
                    }
                    boolean migrateLegacyState = state.definitionGenerationId() == null;
                    SkillTreePlayerState boundState = bindCurrentDefinitionGeneration(state);
                    playerStates.put(accountId, boundState);
                    markLegacyDefinitionGenerationMigration(accountId, migrateLegacyState);
                    registerPendingRuntimeOperation(astPlayer, runtimeGuard, boundState);
                    markDirty(boundState);
                    SkillTreeMutationCheckpoint committedCheckpoint = checkpoint;
                    return new InventorySaveCoordinator.CriticalMutation<>(
                        new SkillTreeMutationResult(true, previousSkillIds),
                        () -> restoreMutationCheckpoint(committedCheckpoint, inventoryBefore, persistence)
                    );
                }
            } catch (RuntimeException | Error failure) {
                // validation failure happens before CriticalMutation is returned, so the coordinator
                // cannot invoke its rollback callback.
                if (checkpoint != null) restoreMutationCheckpoint(checkpoint, inventoryBefore, persistence);
                else if (inventoryBefore != null) persistence.restoreState(inventoryBefore);
                throw failure;
            }
        });
    }

    public @NotNull CompletableFuture<SkillTreeMutationResult> relockNodeAsync(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        return relockNodeAsync(astPlayer, node, null);
    }

    /** Web BATCHを一回のcritical snapshotへまとめ、途中失敗時は全node・Goldを復元します。 */
    private @NotNull CompletableFuture<BatchMutationResult> applyRuntimeBatchAsync(
            @NotNull AstPlayer astPlayer,
            @NotNull List<SkillTreeRuntimeRepository.Change> changes,
            @NotNull RuntimeMutationGuard runtimeGuard
    ) {
        InventoryService persistence = inventoryService;
        if (persistence == null || changes.isEmpty() || changes.size() > 512) {
            return CompletableFuture.failedFuture(new IllegalStateException("Invalid skill tree batch."));
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        return persistence.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = persistence.snapshotState(accountId);
            SkillTreeMutationCheckpoint checkpoint = null;
            try {
                synchronized (this) {
                    if (masterPublicationInProgress || !isStateReady(astPlayer)
                            || !matchesRuntimeMutationGuard(astPlayer, runtimeGuard)) {
                        throw new IllegalStateException("Skill tree batch is stale.");
                    }
                    SkillTreePlayerState state = state(astPlayer);
                    Set<String> previousSkillIds = derivedState(astPlayer, state).unlockedSkillIds();
                    checkpoint = captureMutationCheckpoint(accountId, state);
                    List<SkillTreeNodeDefinition> unlockedNodes = new ArrayList<>();
                    List<SkillTreeNodeDefinition> changedNodes = new ArrayList<>();
                    Set<String> requestedNodeIds = new LinkedHashSet<>();
                    for (SkillTreeRuntimeRepository.Change change : changes) {
                        if (!requestedNodeIds.add(change.nodeId())) {
                            throw new IllegalStateException("Skill tree batch has duplicate node IDs.");
                        }
                        SkillTreeNodeDefinition node = nodesById.get(change.nodeId());
                        if (node == null || masterPublicationInProgress
                                || !runtimeGuard.definitionGenerationId().equals(definitionGenerationId)
                                || runtimePlayerContextRevisions.getOrDefault(astPlayer.getBukkit().getUniqueId(), 0L)
                                != runtimeGuard.playerContextRevision()) {
                            throw new IllegalStateException("Skill tree batch node is stale.");
                        }
                        changedNodes.add(node);
                        String action = change.action().trim().toUpperCase(java.util.Locale.ROOT);
                        if ("UNLOCK".equals(action)) {
                            if (requiresCpSourceSelection(node)
                                    && (change.sourceClassId() == null || change.sourceClassId().isBlank())) {
                                throw new IllegalStateException("Skill tree CP source is missing.");
                            }
                            if (!canUnlockNode(astPlayer, node, change.sourceClassId())) {
                                throw new IllegalStateException("Skill tree node is no longer unlockable.");
                            }
                            String source = node.pointType() == SkillTreePointType.CLASS_POINT && change.sourceClassId() != null
                                    ? normalizeClassId(change.sourceClassId()) : null;
                            if (!applyNodeMutationLocked(persistence, accountId, state, node, action, source)) {
                                throw new IllegalStateException("Skill tree unlock failed.");
                            }
                            unlockedNodes.add(node);
                        } else if ("RELOCK".equals(action)) {
                            if (!canRelockNode(astPlayer, node)) {
                                throw new IllegalStateException("Skill tree node cannot be relocked.");
                            }
                            if (!applyNodeMutationLocked(persistence, accountId, state, node, action, null)) {
                                throw new IllegalStateException("Skill tree relock failed.");
                            }
                        } else {
                            throw new IllegalStateException("Unsupported skill tree batch action.");
                        }
                    }
                    if (!runtimeGuard.definitionGenerationId().equals(definitionGenerationId)
                            || runtimePlayerContextRevisions.getOrDefault(astPlayer.getBukkit().getUniqueId(), 0L)
                            != runtimeGuard.playerContextRevision()) {
                        throw new IllegalStateException("Skill tree batch became stale.");
                    }
                    boolean migrateLegacyState = state.definitionGenerationId() == null;
                    SkillTreePlayerState boundState = bindCurrentDefinitionGeneration(state);
                    playerStates.put(accountId, boundState);
                    markLegacyDefinitionGenerationMigration(accountId, migrateLegacyState);
                    registerPendingRuntimeOperation(astPlayer, runtimeGuard, boundState);
                    markDirty(boundState);
                    SkillTreeMutationCheckpoint committedCheckpoint = checkpoint;
                    return new InventorySaveCoordinator.CriticalMutation<>(
                            new BatchMutationResult(previousSkillIds, List.copyOf(unlockedNodes), List.copyOf(changedNodes)),
                            () -> restoreMutationCheckpoint(committedCheckpoint, inventoryBefore, persistence));
                }
            } catch (RuntimeException | Error failure) {
                if (checkpoint != null) restoreMutationCheckpoint(checkpoint, inventoryBefore, persistence);
                else if (inventoryBefore != null) persistence.restoreState(inventoryBefore);
                throw failure;
            }
        });
    }

    /** batch ACK後に派生効果を一回だけ再計算し、全UNLOCKのlistenerを通知します。 */
    private synchronized void acknowledgeRuntimeBatch(
            @NotNull AstPlayer astPlayer,
            @NotNull BatchMutationResult batch
    ) {
        SkillTreePlayerState state = playerStates.get(astPlayer.getAccount().getUuid());
        if (state == null) return;
        DerivedPlayerState next = rebuildDerivedState(astPlayer, state);
        derivedPlayerStates.put(state.accountId(), next);
        Set<String> added = new LinkedHashSet<>(next.unlockedSkillIds());
        added.removeAll(batch.previousSkillIds());
        Set<String> removed = new LinkedHashSet<>(batch.previousSkillIds());
        removed.removeAll(next.unlockedSkillIds());
        refreshDerivedState(astPlayer, added, removed, !added.isEmpty() || !removed.isEmpty()
                || batch.changedNodes().stream().anyMatch(node -> !node.statusEffects().isEmpty()));
        if (visualizer != null) visualizer.markViewerDirty(astPlayer.getBukkit().getUniqueId());
        for (SkillTreeNodeDefinition node : batch.unlockedNodes()) nodeUnlockListener.accept(astPlayer, node.nodeId());
    }

    /** Web claim の世代・現在地文脈を保存lane内でも再検証して再ロックします。 */
    private @NotNull CompletableFuture<SkillTreeMutationResult> relockNodeAsync(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable RuntimeMutationGuard runtimeGuard
    ) {
        InventoryService persistence = inventoryService;
        if (persistence == null) {
            return CompletableFuture.completedFuture(SkillTreeMutationResult.rejected());
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        return persistence.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = persistence.snapshotState(accountId);
            SkillTreeMutationCheckpoint checkpoint = null;
            try {
                synchronized (this) {
                    if (masterPublicationInProgress || !isStateReady(astPlayer) || nodesById.get(node.nodeId()) != node) {
                        throw new IllegalStateException("Skill tree definition is not ready");
                    }
                    if (runtimeGuard != null && !matchesRuntimeMutationGuard(astPlayer, runtimeGuard)) {
                        throw new IllegalStateException("Skill tree runtime operation is stale.");
                    }
                    if (!canRelockNode(astPlayer, node)) {
                        throw new IllegalStateException("Skill tree node cannot be relocked.");
                    }
                    SkillTreePlayerState state = state(astPlayer);
                    Set<String> previousSkillIds = derivedState(astPlayer, state).unlockedSkillIds();
                    checkpoint = captureMutationCheckpoint(accountId, state);
                    if (!applyNodeMutationLocked(persistence, accountId, state, node, "RELOCK", null)) {
                        throw new IllegalStateException("Skill tree node cannot be relocked.");
                    }
                    boolean migrateLegacyState = state.definitionGenerationId() == null;
                    SkillTreePlayerState boundState = bindCurrentDefinitionGeneration(state);
                    playerStates.put(accountId, boundState);
                    markLegacyDefinitionGenerationMigration(accountId, migrateLegacyState);
                    registerPendingRuntimeOperation(astPlayer, runtimeGuard, boundState);
                    markDirty(boundState);
                    SkillTreeMutationCheckpoint committedCheckpoint = checkpoint;
                    return new InventorySaveCoordinator.CriticalMutation<>(
                        new SkillTreeMutationResult(true, previousSkillIds),
                        () -> restoreMutationCheckpoint(committedCheckpoint, inventoryBefore, persistence)
                    );
                }
            } catch (RuntimeException | Error failure) {
                if (checkpoint != null) restoreMutationCheckpoint(checkpoint, inventoryBefore, persistence);
                else if (inventoryBefore != null) persistence.restoreState(inventoryBefore);
                throw failure;
            }
        });
    }

    /** SQL ACK 後、Bukkit メインスレッドで派生効果と表示を反映します。 */
    public synchronized void acknowledgeCommittedNodeMutation(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @NotNull SkillTreeMutationResult mutation,
            boolean notifyUnlockListener
    ) {
        if (!mutation.changed()) {
            return;
        }
        SkillTreePlayerState state = playerStates.get(astPlayer.getAccount().getUuid());
        if (state == null) {
            return;
        }
        DerivedPlayerState next = rebuildDerivedState(astPlayer, state);
        derivedPlayerStates.put(state.accountId(), next);
        markNodeStateChanged(astPlayer, node, mutation.previousSkillIds(), next.unlockedSkillIds());
        if (notifyUnlockListener) {
            nodeUnlockListener.accept(astPlayer, node.nodeId());
        }
    }

    /** 現在公開中の定義世代を、確定予定の状態へだけ関連付けます。 */
    private @NotNull SkillTreePlayerState bindCurrentDefinitionGeneration(@NotNull SkillTreePlayerState state) {
        if (definitionGenerationId.isBlank()) {
            throw new IllegalStateException("Skill tree definition generation is not ready.");
        }
        return new SkillTreePlayerState(
                state.accountId(), state.unlockedNodes(), state.persistedVersion(), definitionGenerationId
        );
    }

    /** 既存の単発・Web batchで共有する、検証済みノード変更とGold消費の最小状態変更です。 */
    private boolean applyNodeMutationLocked(
            @NotNull InventoryService persistence,
            @NotNull UUID accountId,
            @NotNull SkillTreePlayerState state,
            @NotNull SkillTreeNodeDefinition node,
            @NotNull String action,
            @Nullable String consumedClassId
    ) {
        if ("UNLOCK".equals(action)) {
            String source = node.pointType() == SkillTreePointType.CLASS_POINT
                    ? consumedClassId != null ? consumedClassId : node.unlockCondition().classId() : null;
            return state.unlock(node.nodeId(), source == null ? null : normalizeClassId(source));
        }
        return "RELOCK".equals(action)
                && persistence.consumeGold(accountId, RELOCK_GOLD_COST)
                && state.relock(node.nodeId());
    }

    /** 初回legacy bindだけを保存ACKまで保持します。 */
    private void markLegacyDefinitionGenerationMigration(@NotNull UUID accountId, boolean migrateLegacyState) {
        if (migrateLegacyState) {
            pendingLegacyDefinitionGenerationMigrations.add(accountId);
        }
    }

    /** 保存laneでBukkit APIへ触れずに照合できる、Web操作受付時点の安全境界です。 */
    private boolean matchesRuntimeMutationGuard(
            @NotNull AstPlayer astPlayer,
            @NotNull RuntimeMutationGuard guard
    ) {
        SkillTreePlayerState current = playerStates.get(guard.accountId());
        return current != null
                && guard.accountId().equals(astPlayer.getAccount().getUuid())
                && guard.definitionGenerationId().equals(definitionGenerationId)
                && current.persistedVersion() == guard.expectedPlayerStateVersion()
                && !dirtyPlayerStates.contains(guard.accountId())
                && runtimePlayerContextRevisions.getOrDefault(astPlayer.getBukkit().getUniqueId(), 0L)
                == guard.playerContextRevision()
                && guard.expectedEvaluationFingerprint() != null
                && guard.expectedEvaluationFingerprint().equals(createRuntimeEvaluationFingerprint(astPlayer, current));
    }

    /** APPLIED を状態更新と同じ player-state snapshot transaction に同梱するためのreceiptを登録します。 */
    private void registerPendingRuntimeOperation(
            @NotNull AstPlayer astPlayer,
            @Nullable RuntimeMutationGuard guard,
            @NotNull SkillTreePlayerState finalState
    ) {
        if (guard == null || guard.operationId() == null) {
            return;
        }
        pendingRuntimeOperationReceipts.put(guard.accountId(), new PendingRuntimeOperationReceipt(
                guard.operationId(),
                guard.serverId(),
                runtimeServerSessionId,
                guard.leaseToken(),
                definitionGenerationId,
                Math.addExact(finalState.persistedVersion(), 1),
                createRuntimeEvaluationFingerprint(astPlayer, finalState)
        ));
    }

    private @NotNull SkillTreeMutationCheckpoint captureMutationCheckpoint(
            @NotNull UUID accountId,
            @NotNull SkillTreePlayerState state
    ) {
        return new SkillTreeMutationCheckpoint(
            new SkillTreePlayerState(accountId, state.unlockedNodes(), state.persistedVersion(), state.definitionGenerationId()),
            derivedPlayerStates.get(accountId),
            dirtyPlayerStates.contains(accountId),
            dirtyPlayerStateDueAtMillis.get(accountId),
            playerStateRevisions.get(accountId),
            persistedPlayerStateVersions.get(accountId),
            acknowledgedPlayerStateRevisions.get(accountId),
            playerStateEpochs.get(accountId),
            pendingRuntimeOperationReceipts.get(accountId),
            pendingLegacyDefinitionGenerationMigrations.contains(accountId)
        );
    }

    private synchronized void restoreMutationCheckpoint(
            @NotNull SkillTreeMutationCheckpoint checkpoint,
            @Nullable InventoryService.InventoryStateSnapshot inventoryBefore,
            @NotNull InventoryService persistence
    ) {
        UUID accountId = checkpoint.state().accountId();
        playerStates.put(accountId, checkpoint.state());
        restoreMapValue(derivedPlayerStates, accountId, checkpoint.derivedState());
        restoreSetValue(dirtyPlayerStates, accountId, checkpoint.dirty());
        restoreMapValue(dirtyPlayerStateDueAtMillis, accountId, checkpoint.dueAtMillis());
        restoreMapValue(playerStateRevisions, accountId, checkpoint.revision());
        restoreMapValue(persistedPlayerStateVersions, accountId, checkpoint.persistedVersion());
        restoreMapValue(acknowledgedPlayerStateRevisions, accountId, checkpoint.acknowledgedRevision());
        restoreMapValue(playerStateEpochs, accountId, checkpoint.epoch());
        restoreMapValue(pendingRuntimeOperationReceipts, accountId, checkpoint.pendingRuntimeOperationReceipt());
        restoreSetValue(
                pendingLegacyDefinitionGenerationMigrations,
                accountId,
                checkpoint.legacyDefinitionGenerationMigrationPending());
        if (inventoryBefore != null) {
            persistence.restoreState(inventoryBefore);
        }
    }

    private static <K, V> void restoreMapValue(@NotNull Map<K, V> map, @NotNull K key, @Nullable V value) {
        if (value == null) map.remove(key);
        else map.put(key, value);
    }

    private static <T> void restoreSetValue(@NotNull Set<T> set, @NotNull T value, boolean present) {
        if (present) set.add(value);
        else set.remove(value);
    }

    public boolean canRelockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        if (!state.isUnlocked(node.nodeId()) || !isNodeUnlockConditionMet(astPlayer, node)) {
            return false;
        }

        Set<String> remainingUnlocked = knownUnlockedNodeIds(state);
        remainingUnlocked.remove(node.nodeId());
        if (remainingUnlocked.isEmpty()) {
            return true;
        }

        if (!remainingUnlocked.contains(rootNodeId)) {
            return false;
        }

        Set<String> reachableUnlocked = new LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(rootNodeId));
        Set<String> visitedNodeIds = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String currentNodeId = queue.removeFirst();
            if (!visitedNodeIds.add(currentNodeId)) {
                continue;
            }
            SkillTreeNodeDefinition current = nodesById.get(currentNodeId);
            if (current == null || !remainingUnlocked.contains(current.nodeId())) {
                continue;
            }
            reachableUnlocked.add(current.nodeId());
            for (String adjacentNodeId : adjacentNodeIds(currentNodeId)) {
                SkillTreeNodeDefinition adjacent = nodesById.get(adjacentNodeId);
                if (adjacent != null && remainingUnlocked.contains(adjacent.nodeId())) {
                    queue.addLast(adjacentNodeId);
                }
            }
        }
        return reachableUnlocked.containsAll(remainingUnlocked);
    }

    /**
     * プレイヤー視線上で最も入口距離が近いスキルツリー位置を返します。
     *
     * @param player 判定対象プレイヤー
     * @return 命中したスキルツリー位置
     */
    @NotNull
    public Optional<SkillTreePosition> findTargetedPosition(@NotNull Player player) {
        return findTargetedPositionHit(player).map(SkillTreePositionHit::position);
    }

    /**
     * プレイヤー視線上で最も入口距離が近いスキルツリー位置を返します。
     * 候補解決だけを行い、ノード状態や表示状態を変更しません。
     *
     * @param player 判定対象プレイヤー
     * @return 命中したスキルツリー位置と入口距離
     */
    @NotNull
    public Optional<SkillTreePositionHit> findTargetedPositionHit(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                eye.toVector(),
                eye.getDirection(),
                TARGET_DISTANCE
        );
        if (ray == null) {
            return Optional.empty();
        }

        return findTargetedPositionHit(player, ray);
    }

    /**
     * 入力イベントが直接示すノード hitbox を優先し、視線上のスキルツリー位置を返します。
     * Interaction entity に紐づく位置は、イベント自体を命中根拠として再 ray trace せず解決します。
     * スキルツリーはバリア等の遮蔽ブロック越しに操作するため、遮蔽距離では候補を除外しません。
     *
     * @param snapshot 判定対象の入力 snapshot
     * @return 命中したスキルツリー位置と入口距離
     */
    @NotNull
    public Optional<SkillTreePositionHit> findTargetedPositionHit(
            @NotNull PlayerInteractionSnapshot snapshot
    ) {
        Entity targetEntity = snapshot.targetEntity();
        if (targetEntity instanceof Interaction
                && targetEntity.getScoreboardTags().contains(NODE_INTERACTION_TAG)) {
            String nodeId = targetEntity.getPersistentDataContainer().get(
                    nodeInteractionKey,
                    PersistentDataType.STRING
            );
            SkillTreePosition position = nodeId == null ? null : positionsByNodeId.get(nodeId);
            if (position == null || !targetEntity.isValid() || !isNodeVisibleToPlayer(snapshot.player(), nodeId)) {
                return Optional.empty();
            }
            Double hitDistance = snapshot.hitDistance(targetEntity);
            if (hitDistance == null) {
                hitDistance = Math.min(
                        snapshot.ray().maxDistance(),
                        snapshot.rayOrigin().distance(targetEntity.getBoundingBox().getCenter())
                );
            }
            return Optional.of(new SkillTreePositionHit(position, hitDistance));
        }

        return findTargetedPositionHit(snapshot.player(), snapshot.ray());
    }

    /**
     * 左クリック対象となる、表示中のノード強調ビームを視線から解決します。
     * 通常ノードの解放・解除対象とは異なり、本人に実際に表示されるビームだけを候補にします。
     *
     * @param snapshot 判定対象の入力snapshot
     * @return 命中した強調ビームのノード位置と入口距離
     */
    @NotNull
    public Optional<SkillTreePositionHit> findTargetedBeaconPositionHit(
            @NotNull PlayerInteractionSnapshot snapshot
    ) {
        PlayerInteractionRayTrace beaconRay = PlayerInteractionRayTrace.create(
                snapshot.rayOrigin(),
                snapshot.ray().direction(),
                NODE_BEACON_TARGET_DISTANCE
        );
        return beaconRay == null
                ? Optional.empty()
                : findTargetedBeaconPositionHit(snapshot.player(), beaconRay);
    }

    /**
     * 視線上で最も入口距離が近い、表示中のノード強調ビームを返します。
     *
     * @param player 判定対象プレイヤー
     * @param ray 視線ray
     * @return 命中した強調ビームのノード位置と入口距離
     */
    @NotNull
    private Optional<SkillTreePositionHit> findTargetedBeaconPositionHit(
            @NotNull Player player,
            @NotNull PlayerInteractionRayTrace ray
    ) {
        SkillTreePositionHit nearest = null;
        Location playerLocation = player.getLocation();
        for (SkillTreePosition position : positionsByNodeId.values()) {
            SkillTreeNodeDefinition node = nodesById.get(position.nodeId());
            Location location = position.toLocation();
            if (node == null || location == null || location.getWorld() != player.getWorld()
                    || !isNodeBeaconVisible(player, node, position)) {
                continue;
            }
            double deltaX = playerLocation.getX() - location.getX();
            double deltaZ = playerLocation.getZ() - location.getZ();
            if (!SkillTreeVisualizer.isNodeBeaconClickable(Math.sqrt(deltaX * deltaX + deltaZ * deltaZ))) {
                continue;
            }
            BoundingBox beaconHitbox = SkillTreeVisualizer.nodeBeaconHitbox(location);
            Double hitDistance = ray.aabbEntryDistance(beaconHitbox);
            if (hitDistance == null || (nearest != null
                    && (hitDistance > nearest.hitDistance()
                    || (Double.compare(hitDistance, nearest.hitDistance()) == 0
                    && position.nodeId().compareTo(nearest.position().nodeId()) >= 0)))) {
                continue;
            }
            nearest = new SkillTreePositionHit(position, hitDistance);
        }
        return Optional.ofNullable(nearest);
    }

    private Optional<SkillTreePositionHit> findTargetedPositionHit(
            @NotNull Player player,
            @NotNull PlayerInteractionRayTrace ray
    ) {

        SkillTreePositionHit nearest = null;
        for (SkillTreePosition position : positionsByNodeId.values()) {
            if (!isNodeVisibleToPlayer(player, position.nodeId())) {
                continue;
            }
            Location location = position.toLocation();
            if (location == null || location.getWorld() != player.getWorld()) {
                continue;
            }
            Location center = location.clone().add(0.0D, 0.6D, 0.0D);
            Double hitDistance = ray.sphereEntryDistance(center.toVector(), TARGET_RADIUS);
            if (hitDistance == null || (nearest != null
                    && (hitDistance > nearest.hitDistance()
                    || (Double.compare(hitDistance, nearest.hitDistance()) == 0
                    && position.nodeId().compareTo(nearest.position().nodeId()) >= 0)))) {
                continue;
            }
            nearest = new SkillTreePositionHit(position, hitDistance);
        }
        return Optional.ofNullable(nearest);
    }

    private boolean isNodeVisibleToPlayer(@NotNull Player player, @NotNull String nodeId) {
        SkillTreeNodeDefinition node = nodesById.get(nodeId);
        if (node == null) {
            return true;
        }
        if (!node.unlockCondition().hasClassCondition()
                && !node.unlockCondition().hasPlayerLevelCondition()) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        // PPノードは表示するが、条件未達中は解放・解除の入力対象にしない。
        return astPlayer != null && isNodeUnlockConditionMet(astPlayer, node);
    }

    /**
     * ノード hitbox に対応する nodeId を保存します。
     *
     * @param interaction 対象の Interaction entity
     * @param nodeId 対応する nodeId
     */
    void tagNodeInteraction(@NotNull Interaction interaction, @NotNull String nodeId) {
        interaction.getPersistentDataContainer().set(
                nodeInteractionKey,
                PersistentDataType.STRING,
                nodeId
        );
    }

    @NotNull
    public Optional<SkillTreeNodeDefinition> findTargetedNode(@NotNull Player player) {
        return findTargetedPosition(player).map(position -> nodesById.get(position.nodeId()));
    }

    /**
     * スキルツリー表示中に使う一時的なプレイヤー表示状態を解除します。
     *
     * @param player 表示状態を解除するプレイヤー
     */
    public void clearPlayerPresentation(@NotNull Player player) {
        markRuntimePlayerContextChanged(player);
        visualReadyAtMillis.remove(player.getUniqueId());
        stopLoadingPresentation(player);
    }

    private void tickPlayerFeedbacks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPlayerModeSkillTree(player)) {
                boolean becameReady = updateLoadingPresentation(player);
                if (becameReady) {
                    markViewerContextDirty(player);
                }
            } else {
                visualReadyAtMillis.remove(player.getUniqueId());
                stopLoadingPresentation(player);
            }
        }
    }

    /**
     * 固定されたスキルツリー表示距離を返します。
     *
     * @return 表示距離27ブロック
     */
    public int viewDistance() {
        return DEFAULT_VIEW_DISTANCE;
    }

    /**
     * ノード表示テキストの詳細段階を返します。
     *
     * @param player 表示対象プレイヤー
     * @param nodeLocation ノード位置
     * @return 距離に応じたテキスト段階
     */
    public @NotNull NodeLabelDetail nodeLabelDetail(@NotNull Player player, @Nullable Location nodeLocation) {
        if (nodeLocation == null || nodeLocation.getWorld() == null || player.getWorld() != nodeLocation.getWorld()) {
            return NodeLabelDetail.HIDDEN;
        }
        boolean compactDisplay = playerSettingService != null
                && playerSettingService.isSkillTreeCompactDisplayEnabled(player.getUniqueId());
        double distanceSquared = player.getLocation().distanceSquared(nodeLocation);
        double detailedThreshold = Math.min(DETAILED_LABEL_DISTANCE, DEFAULT_VIEW_DISTANCE);
        if (distanceSquared <= detailedThreshold * detailedThreshold) {
            return compactDisplay ? NodeLabelDetail.SIMPLE : NodeLabelDetail.DETAILED;
        }
        double compactThreshold = Math.min(COMPACT_LABEL_DISTANCE, DEFAULT_VIEW_DISTANCE);
        if (distanceSquared <= compactThreshold * compactThreshold) {
            return compactDisplay ? NodeLabelDetail.SIMPLE : NodeLabelDetail.COMPACT;
        }
        return NodeLabelDetail.HIDDEN;
    }

    @NotNull
    public ItemStack createNodeDisplayItem(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        ItemStack cached = unlocked ? unlockedNodeDisplayItems.get(node.nodeId()) : lockedNodeDisplayItems.get(node.nodeId());
        return cached == null ? new ItemStack(node.icon()) : cached.clone();
    }

    /**
     * スキルツリーのワールド表示用ラベルを組み立てます。
     *
     * @param node 表示対象ノード定義
     * @param unlocked 解放済み表示にする場合は {@code true}
     * @return ノード名と詳細行を含むラベル
     */
    @NotNull
    public Component nodeFieldLabel(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        return nodeFieldLabel(node, unlocked ? NodePresentationState.UNLOCKED : canUnlockWithoutState(node) ? NodePresentationState.AVAILABLE : NodePresentationState.BLOCKED);
    }

    @NotNull
    public Component nodeFieldLabel(@NotNull SkillTreeNodeDefinition node, @NotNull NodePresentationState presentationState) {
        return nodeFieldLabel(node, presentationState, NodeLabelDetail.DETAILED);
    }

    /**
     * スキルツリーノードのワールド表示用ラベルを返します。
     *
     * @param node 表示対象ノード
     * @param presentationState 解放状態
     * @param labelDetail 表示情報量
     * @return ラベル
     */
    @NotNull
    public Component nodeFieldLabel(
            @NotNull SkillTreeNodeDefinition node,
            @NotNull NodePresentationState presentationState,
            @NotNull NodeLabelDetail labelDetail
    ) {
        return switch (presentationState) {
            case BLOCKED -> blockedNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
            case CONDITION_BLOCKED -> conditionBlockedNodeFieldLabels
                    .getOrDefault(node.nodeId(), NodeLabelSet.EMPTY)
                    .component(labelDetail);
            case AVAILABLE -> availableNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
            case UNLOCKED -> unlockedNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
            case INACTIVE -> inactiveNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
            case INACTIVE_CONDITION -> inactiveConditionNodeFieldLabels
                    .getOrDefault(node.nodeId(), NodeLabelSet.EMPTY)
                    .component(labelDetail);
        };
    }

    public int edgeState(@NotNull Player player, @NotNull SkillTreeEdge edge) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return 0;
        }
        SkillTreeNodeDefinition left = nodesById.get(edge.sourceNodeId());
        SkillTreeNodeDefinition right = nodesById.get(edge.targetNodeId());
        SkillTreePlayerState state = state(astPlayer);
        Set<String> activeNodeIds = activeUnlockedNodeIds(astPlayer, state);
        boolean leftUnlocked = left != null && activeNodeIds.contains(left.nodeId());
        boolean rightUnlocked = right != null && activeNodeIds.contains(right.nodeId());
        if (leftUnlocked && rightUnlocked) {
            return 2;
        }
        if (leftUnlocked || rightUnlocked) {
            return 1;
        }
        return 0;
    }

    public void saveDirty() {
        InventoryService persistence = inventoryService;
        if (persistence == null) {
            return;
        }
        List.copyOf(dirtyPlayerStates).forEach(persistence::queueLocalPlayerSave);
    }

    /**
     * dirty なスキルツリー状態を非同期で API へ保存します。
     * <p>
     * Bukkit API には触れず、メインスレッドでは保存対象のスナップショット作成だけを行います。
     */
    public void saveDirtyAsync() {
        saveDirty();
    }

    /**
     * 現在のローカル確定済み解放状態を player-state section として返します。
     * 呼出元は account の state lock を保持している必要があります。
     *
     * @param accountId 対象アカウントID
     * @return dirty state がない場合は {@code null}
     */
    public synchronized @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        if (!dirtyPlayerStates.contains(accountId)) {
            return null;
        }
        SkillTreePlayerState state = playerStates.get(accountId);
        if (state == null) {
            return null;
        }
        long capturedRevision = playerStateRevisions.getOrDefault(accountId, 0L);
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("clientRevision", capturedRevision);
        Integer expectedVersion = persistedPlayerStateVersions.get(accountId);
        payload.add("expectedVersion", expectedVersion == null
            ? com.google.gson.JsonNull.INSTANCE
            : new com.google.gson.JsonPrimitive(expectedVersion));
        long targetVersion = (expectedVersion == null ? 0 : expectedVersion)
            + capturedRevision - acknowledgedPlayerStateRevisions.getOrDefault(accountId, 0L);
        payload.addProperty("targetVersion", targetVersion);
        if (state.definitionGenerationId() == null) {
            payload.add("definitionGenerationId", com.google.gson.JsonNull.INSTANCE);
        } else {
            payload.addProperty("definitionGenerationId", state.definitionGenerationId());
        }
        payload.addProperty("serverId", ConfigProperties.getInstance().getApiServerId());
        payload.addProperty("serverSessionId", runtimeServerSessionId.toString());
        SkillTreeRuntimeRepository.AccountSession accountSession = runtimeAccountSessions.get(accountId);
        if (accountSession == null) throw new IllegalStateException("Skill tree account session is missing");
        payload.addProperty("accountSessionId", accountSession.id().toString());
        payload.addProperty("accountLeaseToken", accountSession.token());
        payload.addProperty("migrateLegacyState", pendingLegacyDefinitionGenerationMigrations.contains(accountId));
        PendingRuntimeOperationReceipt pendingOperation = pendingRuntimeOperationReceipts.get(accountId);
        if (pendingOperation != null) {
            payload.add("operation", pendingOperation.toJson());
        }
        JsonArray nodes = new JsonArray();
        for (SkillTreeUnlockedNode node : state.unlockedNodes()) {
            JsonObject value = new JsonObject();
            value.addProperty("nodeId", node.nodeId());
            if (node.consumedClassId() == null) {
                value.add("consumedClassId", com.google.gson.JsonNull.INSTANCE);
            } else {
                value.addProperty("consumedClassId", node.consumedClassId());
            }
            nodes.add(value);
        }
        payload.add("unlockedNodes", nodes);
        UUID capturedEpoch = playerStateEpochs.get(accountId);
        UUID pendingOperationId = pendingOperation == null ? null : pendingOperation.operationId();
        boolean capturedLegacyMigration = pendingLegacyDefinitionGenerationMigrations.contains(accountId);
        return new PlayerStateSection("skillTree", payload,
            acknowledged -> acknowledgeSnapshot(
                    accountId, capturedEpoch, capturedRevision, pendingOperationId, capturedLegacyMigration, acknowledged));
    }

    private synchronized void acknowledgeSnapshot(@NotNull UUID accountId, UUID capturedEpoch,
        long capturedRevision, @Nullable UUID pendingOperationId, boolean capturedLegacyMigration,
        @NotNull JsonElement acknowledged) {
        if (!Objects.equals(capturedEpoch, playerStateEpochs.get(accountId))
            || capturedRevision <= acknowledgedPlayerStateRevisions.getOrDefault(accountId, -1L)) return;
        if (!acknowledged.isJsonObject()) {
            throw new IllegalStateException("skillTree acknowledgement must be an object");
        }
        final int version;
        try {
            JsonObject metadata = acknowledged.getAsJsonObject();
            if (!metadata.has("clientRevision") || metadata.get("clientRevision").getAsLong() != capturedRevision
                || !metadata.has("version") || metadata.get("version").isJsonNull()) {
                throw new IllegalStateException("skillTree acknowledgement metadata is incomplete");
            }
            version = metadata.get("version").getAsInt();
        } catch (RuntimeException malformedAck) {
            throw new IllegalStateException("Invalid skillTree acknowledgement", malformedAck);
        }
        persistedPlayerStateVersions.put(accountId, version);
        SkillTreePlayerState currentState = playerStates.get(accountId);
        if (currentState != null) {
            playerStates.put(accountId, new SkillTreePlayerState(accountId, currentState.unlockedNodes(),
                    version, currentState.definitionGenerationId()));
        }
        if (pendingOperationId != null) {
            PendingRuntimeOperationReceipt pending = pendingRuntimeOperationReceipts.get(accountId);
            if (pending != null && pendingOperationId.equals(pending.operationId())) {
                pendingRuntimeOperationReceipts.remove(accountId);
            }
        }
        if (capturedLegacyMigration) {
            pendingLegacyDefinitionGenerationMigrations.remove(accountId);
        }
        acknowledgedPlayerStateRevisions.put(accountId, capturedRevision);
        if (playerStateRevisions.getOrDefault(accountId, 0L) == capturedRevision) {
            dirtyPlayerStates.remove(accountId);
            dirtyPlayerStateDueAtMillis.remove(accountId);
            evictReleasedPlayerState(accountId);
        }
    }

    @NotNull
    private ItemStack createNodeHotbarItem(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        boolean unlocked = state.isUnlocked(node.nodeId());
        boolean inactive = unlocked && derivedState(astPlayer, state).inactiveUnlockedNodeIds().contains(node.nodeId());
        boolean canUnlock = canUnlockNode(astPlayer, node);
        ItemStack itemStack = createNodeDisplayItem(node, unlocked);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            var lore = new java.util.ArrayList<Component>();
            appendNodeStatusInfo(lore, node);
            appendNodePassiveInfo(lore, node);
            if (!lore.isEmpty()) {
                lore.add(component(""));
            }
            lore.add(component(unlocked
                    ? inactive ? "&8状態: &c解放済み / 無効" : "&8状態: &f解放済み"
                    : canUnlock
                    ? "&8状態: &a解放可能"
                    : "&8状態: &c隣接ノードの解放が必要"));
            lore.add(component("&8消費: &f" + nodePointDisplayName(node) + " &e" + node.pointCost()));
            lore.add(component("&8" + currentClassPointLabel(astPlayer) + ": &f" + availableClassPoints(astPlayer)
                    + " &8/ PP: &f" + availablePoints(astPlayer, SkillTreePointType.PASSIVE_POINT)));
            if (inactive) {
                lore.add(component("&cCP/PP 不足により効果停止中"));
            }
            lore.add(component(unlocked ? "&6◆ 解放済みノード ◆" : "&7◆ 未解放ノード ◆"));
            lore.add(component("&e左クリック&7でノードを解放"));
            lore.add(component("&6右クリック&7でノードを解除 &8（100ゴールド）"));
            if (!node.lore().isEmpty()) {
                lore.add(component(""));
                node.lore().forEach(line -> lore.add(component("&7" + line)));
            }
            meta.lore(lore);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private void appendNodeSkillInfo(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        if (node.skillEffects().isEmpty()) {
            return;
        }
        lore.add(component(""));
        lore.add(component("&b紐づくスキル"));
        for (SkillTreeSkillEffect effect : node.skillEffects()) {
            String skillId = effect.skillId();
            if (skillService == null) {
                lore.add(component("&7- &f未読込スキル"));
                continue;
            }
            var definition = skillService.registry().getDefinition(skillId);
            if (definition == null) {
                lore.add(component("&7- &f未読込スキル &8(未読込)"));
                continue;
            }
            String kindLabel = definition.getKind().isPassive() ? "パッシブ" : "発動";
            String triggerLabel = definition.getKind().isPassive()
                    ? (definition.getPassiveBindRequired() ? "要バインド" : "所持のみ")
                    : "アクティブ";
            lore.add(component("&7- &f" + SkillPresentationUtil.plainName(definition, "未定義スキル")
                    + " &8[" + kindLabel + " / " + triggerLabel + "]"));
        }
    }

    private void appendNodeStatusInfo(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        if (node.statusEffects().isEmpty()) {
            return;
        }
        lore.add(component("&8--- &dステータス &8---"));
        for (SkillTreeStatusEffect status : node.statusEffects()) {
            lore.add(component("&7- " + status.statusType().legacyColor() + status.statusType().getDisplayName()
                    + " &a" + formatNodeStatusModifier(status)));
        }
    }

    private void appendNodePassiveInfo(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        if (node.skillEffects().isEmpty()) {
            return;
        }
        if (!lore.isEmpty()) {
            lore.add(component(""));
        }
        lore.add(component("&8--- &bスキル &8---"));
        appendNodePassiveSkillLines(lore, node);
    }

    private void appendNodePassiveSkillLines(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        for (SkillTreeSkillEffect effect : node.skillEffects()) {
            String skillId = effect.skillId();
            var definition = skillService == null ? null : skillService.registry().getDefinition(skillId);
            if (definition == null) {
                lore.add(component("&7- &f未読込スキル &8(未読込)"));
                continue;
            }
            lore.add(component("&7- &f" + SkillPresentationUtil.plainName(definition, "未定義スキル")));
            String description = firstSkillDescription(definition);
            if (!description.isBlank()) {
                lore.add(component("&8  " + stripLegacy(description)));
            }
        }
    }

    private void appendNodeFieldStatusLines(
            @NotNull List<String> lines,
            @NotNull SkillTreeNodeDefinition node,
            boolean unlocked
    ) {
        if (node.statusEffects().isEmpty()) {
            return;
        }
        lines.add(unlocked ? "&8--- &dステータス &8---" : "&8--- ステータス ---");
        for (SkillTreeStatusEffect status : node.statusEffects()) {
            lines.add((unlocked ? "&7- " + status.statusType().legacyColor() : "&8- &7")
                    + status.statusType().getDisplayName()
                    + " "
                    + (unlocked ? "&a" : "&7")
                    + formatNodeStatusModifier(status));
        }
    }

    private void appendNodeFieldPassiveLines(
            @NotNull List<String> lines,
            @NotNull SkillTreeNodeDefinition node,
            boolean unlocked
    ) {
        if (node.skillEffects().isEmpty()) {
            return;
        }
        lines.add(unlocked ? "&8--- &bスキル &8---" : "&8--- スキル ---");
        for (SkillTreeSkillEffect effect : node.skillEffects()) {
            String skillId = effect.skillId();
            var definition = skillService == null ? null : skillService.registry().getDefinition(skillId);
            if (definition == null) {
                lines.add((unlocked ? "&7- &f" : "&8- &7") + "未読込スキル");
                continue;
            }
            lines.add((unlocked ? "&7- &f" : "&8- &7") + SkillPresentationUtil.plainName(definition, "未定義スキル"));
            String description = firstSkillDescription(definition);
            if (!description.isBlank()) {
                lines.add("&8  " + stripLegacy(description));
            }
        }
    }

    private @NotNull String firstSkillDescription(@NotNull io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition definition) {
        if (definition.getDescription() != null && !definition.getDescription().isBlank()) {
            return SkillPresentationUtil.renderSkillTemplate(definition, definition.getDescription());
        }
        for (String line : definition.getLore()) {
            if (line != null && !line.isBlank()) {
                return SkillPresentationUtil.renderSkillTemplate(definition, line);
            }
        }
        return "";
    }

    private @NotNull String formatNodeStatusModifier(@NotNull SkillTreeStatusEffect status) {
        if (status.modifierType() == StatusModifierType.SCALAR) {
            double displayValue = status.value() * 100.0D;
            String sign = displayValue > 0.0D ? "+" : "";
            return sign + formatStatusValue(displayValue) + "%";
        }
        return status.statusType().formatSignedValue(status.value());
    }

    private @NotNull String stripLegacy(@NotNull String text) {
        return ColorCodeUtil.toPlainText(text, text);
    }

    private @NotNull String resolveNodeDisplayName(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        return unlocked
                ? ColorCodeUtil.toLegacyText(node.name(), node.nodeId())
                : "&7" + stripLegacy(node.name());
    }

    private @NotNull String formatStatusValue(double value) {
        if (value == Math.rint(value)) {
            return String.format(java.util.Locale.ROOT, "%.0f", value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private void refreshDerivedState(@NotNull AstPlayer astPlayer) {
        refreshDerivedState(astPlayer, Set.of(), Set.of(), true);
    }

    private void refreshDerivedState(
            @NotNull AstPlayer astPlayer,
            @NotNull Set<String> addedSkillIds,
            @NotNull Set<String> removedSkillIds,
            boolean statusAffected
    ) {
        if (passiveSkillService != null) {
            if (addedSkillIds.isEmpty() && removedSkillIds.isEmpty()) {
                passiveSkillService.reconcileNow(astPlayer, false);
            } else {
                passiveSkillService.reconcileSkillPermissionDelta(astPlayer, addedSkillIds, removedSkillIds, false);
            }
        }
        if (statusAffected && statusService != null) {
            statusService.refreshStatus(astPlayer);
        }
    }

    private void refreshDerivedState(@NotNull UUID accountId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && accountId.equals(astPlayer.getAccount().getUuid())) {
                refreshDerivedState(astPlayer);
                return;
            }
        }
    }

    /**
     * マスタ交換後、進行状態をロード済みのオンラインプレイヤーへ新しい効果を即時反映します。
     * プレイヤー状態が一件もない起動直後や単体テストではオンラインキャッシュへアクセスしません。
     */
    private void refreshLoadedOnlinePlayerDerivedStates() {
        if (playerStates.isEmpty()) {
            return;
        }
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            UUID accountId = astPlayer.getAccount().getUuid();
            if (!playerStates.containsKey(accountId)
                    || loadingPlayerStates.contains(accountId)
                    || failedPlayerStateLoads.contains(accountId)) {
                continue;
            }
            refreshDerivedState(astPlayer);
        }
    }

    private void cacheNodePresentation(@NotNull SkillTreeNodeDefinition node) {
        lockedNodeDisplayItems.put(node.nodeId(), createCachedNodeDisplayItem(node, false));
        unlockedNodeDisplayItems.put(node.nodeId(), createCachedNodeDisplayItem(node, true));
        blockedNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.BLOCKED));
        conditionBlockedNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.CONDITION_BLOCKED));
        availableNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.AVAILABLE));
        unlockedNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.UNLOCKED));
        inactiveNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.INACTIVE));
        inactiveConditionNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.INACTIVE_CONDITION));
    }

    private @NotNull ItemStack createCachedNodeDisplayItem(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        ItemStack itemStack = new ItemStack(node.icon());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(resolveNodeDisplayName(node, unlocked)));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull NodeLabelSet createNodeLabelSet(
            @NotNull SkillTreeNodeDefinition node,
            @NotNull NodePresentationState presentationState
    ) {
        return new NodeLabelSet(
                createNodeFieldLabel(node, presentationState, NodeLabelDetail.DETAILED),
                createNodeFieldLabel(node, presentationState, NodeLabelDetail.COMPACT),
                createNodeFieldLabel(node, presentationState, NodeLabelDetail.SIMPLE)
        );
    }

    /**
     * ノードのポイント種別を表示します。
     * classId条件がある場合は解決済みのクラス表示名を角括弧へ付加し、
     * 未登録または未初期化の場合は内部IDを表示せず汎用名へ置き換えます。
     *
     * @param node 表示対象ノード
     * @return ポイント種別の表示名
     */
    private @NotNull String nodePointDisplayName(@NotNull SkillTreeNodeDefinition node) {
        String pointType = node.pointType().displayName();
        if (!node.unlockCondition().hasClassCondition()) {
            return pointType;
        }
        String classId = node.unlockCondition().classId();
        if (classId == null || classId.isBlank()) {
            return pointType;
        }
        String className = playerClassService == null ? "" : playerClassService.getDisplayName(classId);
        String plainClassName = ColorCodeUtil.toPlainText(className, "");
        if (plainClassName.isBlank() || plainClassName.equalsIgnoreCase(classId)) {
            plainClassName = "未登録のクラス";
        }
        return pointType + "[" + plainClassName + "]";
    }

    private @NotNull Component createNodeFieldLabel(
            @NotNull SkillTreeNodeDefinition node,
            @NotNull NodePresentationState presentationState,
            @NotNull NodeLabelDetail labelDetail
    ) {
        List<String> lines = new ArrayList<>();
        boolean emphasized = presentationState == NodePresentationState.AVAILABLE || presentationState == NodePresentationState.UNLOCKED;
        boolean simple = labelDetail == NodeLabelDetail.SIMPLE;
        if (!simple) {
            lines.add(resolveNodeDisplayName(node, presentationState == NodePresentationState.UNLOCKED));
        }
        if (simple) {
            lines.add("&8Cost: &f" + nodePointDisplayName(node) + " &e" + node.pointCost());
            appendNodeFieldSimpleStatusLines(lines, node, emphasized);
            appendNodeFieldSimpleSkillLines(lines, node, emphasized);
            return component(String.join("\n", lines));
        }
        if (labelDetail == NodeLabelDetail.DETAILED) {
            if (presentationState == NodePresentationState.INACTIVE_CONDITION) {
                lines.add("&c効果停止中: 解放条件未達");
            } else if (presentationState == NodePresentationState.INACTIVE) {
                lines.add("&c効果停止中: CP/PP 不足");
            }
        }
        if (labelDetail == NodeLabelDetail.DETAILED
                && (node.pointCost() > 0 || node.unlockCondition().hasClassCondition())) {
            lines.add("&8Cost: &f" + nodePointDisplayName(node) + " &e" + node.pointCost());
        }
        if (labelDetail != NodeLabelDetail.HIDDEN) {
            appendNodeFieldConditionLines(lines, node, presentationState);
        }
        if (labelDetail == NodeLabelDetail.DETAILED) {
            appendNodeFieldStatusLines(lines, node, emphasized);
            appendNodeFieldPassiveLines(lines, node, emphasized);
        }
        if (labelDetail == NodeLabelDetail.DETAILED && !node.lore().isEmpty()) {
            lines.add((emphasized ? "&7" : "&8") + stripLegacy(node.lore().getFirst()));
        }
        return component(String.join("\n", lines));
    }

    private void appendNodeFieldSimpleStatusLines(
            @NotNull List<String> lines,
            @NotNull SkillTreeNodeDefinition node,
            boolean emphasized
    ) {
        for (SkillTreeStatusEffect status : node.statusEffects()) {
            lines.add((emphasized ? "&7- " + status.statusType().legacyColor() : "&8- &7")
                    + status.statusType().getDisplayName()
                    + " "
                    + (emphasized ? "&a" : "&7")
                    + formatNodeStatusModifier(status));
        }
    }

    private void appendNodeFieldSimpleSkillLines(
            @NotNull List<String> lines,
            @NotNull SkillTreeNodeDefinition node,
            boolean emphasized
    ) {
        for (SkillTreeSkillEffect effect : node.skillEffects()) {
            var definition = skillService == null ? null : skillService.registry().getDefinition(effect.skillId());
            String skillName = definition == null
                    ? "未読込スキル"
                    : SkillPresentationUtil.plainName(definition, "未定義スキル");
            lines.add((emphasized ? "&7- &f" : "&8- &7") + skillName);
        }
    }

    private void appendNodeFieldConditionLines(
            @NotNull List<String> lines,
            @NotNull SkillTreeNodeDefinition node,
            @NotNull NodePresentationState presentationState
    ) {
        if (node.pointType() != SkillTreePointType.PASSIVE_POINT
                || (!node.unlockCondition().hasClassCondition()
                && !node.unlockCondition().hasPlayerLevelCondition())) {
            return;
        }
        String color = presentationState == NodePresentationState.CONDITION_BLOCKED
                || presentationState == NodePresentationState.INACTIVE_CONDITION
                ? "&c"
                : "&f";
        if (node.unlockCondition().hasPlayerLevelCondition()) {
            lines.add(color + "必要レベル: " + node.unlockCondition().playerLevel());
        }
        if (node.unlockCondition().hasClassCondition()) {
            lines.add(color + "必要クラス: " + nodeConditionClassDisplayName(node));
        }
    }

    private @NotNull String nodeConditionClassDisplayName(@NotNull SkillTreeNodeDefinition node) {
        String classId = node.unlockCondition().classId();
        if (classId == null || classId.isBlank()) {
            return "未登録のクラス";
        }
        String className = playerClassService == null ? "" : playerClassService.getDisplayName(classId);
        String plainClassName = ColorCodeUtil.toPlainText(className, "");
        return plainClassName.isBlank() || plainClassName.equalsIgnoreCase(classId)
                ? "未登録のクラス"
                : plainClassName;
    }

    private boolean canUnlockWithoutState(@NotNull SkillTreeNodeDefinition node) {
        return rootNodeId.equals(node.nodeId());
    }

    private boolean updateLoadingPresentation(@NotNull Player player) {
        Long readyAt = visualReadyAtMillis.get(player.getUniqueId());
        if (readyAt == null) {
            stopLoadingPresentation(player);
            return false;
        }

        long now = System.currentTimeMillis();
        if (now >= readyAt) {
            visualReadyAtMillis.remove(player.getUniqueId());
            stopLoadingPresentation(player);
            return true;
        }

        BossBar bossBar = loadingBossBars.computeIfAbsent(player.getUniqueId(), ignored -> createLoadingBossBar(player));
        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
        double progress = 1.0D - ((double) (readyAt - now) / (double) VISUAL_DELAY_MILLIS);
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, progress)));
        return false;
    }

    private @NotNull BossBar createLoadingBossBar(@NotNull Player player) {
        BossBar bossBar = Bukkit.createBossBar(
                PlayerMsgResource.getMessage(PlayerMsgId.P_5837.getId()),
                BarColor.BLUE,
                BarStyle.SEGMENTED_12
        );
        bossBar.setVisible(true);
        bossBar.setProgress(0.0D);
        bossBar.addPlayer(player);
        player.showTitle(Title.title(
                Component.empty(),
                PlayerMsgResource.formatComponent(PlayerMsgId.P_5836.getId()),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(VISUAL_DELAY_MILLIS), Duration.ofMillis(200))
        ));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.35F, 1.45F);
        return bossBar;
    }

    private void stopLoadingPresentation(@NotNull Player player) {
        BossBar bossBar = loadingBossBars.remove(player.getUniqueId());
        if (bossBar == null) {
            return;
        }
        bossBar.removeAll();
        bossBar.setVisible(false);
    }

    private void clearAllLoadingBossBars() {
        for (BossBar bossBar : loadingBossBars.values()) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
        loadingBossBars.clear();
    }

    private boolean isAdjacentToActiveNode(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreePlayerState state,
            @NotNull String nodeId
    ) {
        Set<String> activeNodeIds = activeUnlockedNodeIds(astPlayer, state);
        for (String adjacentNodeId : adjacentNodeIds(nodeId)) {
            SkillTreeNodeDefinition adjacent = nodesById.get(adjacentNodeId);
            if (adjacent != null && activeNodeIds.contains(adjacent.nodeId())) {
                return true;
            }
        }
        return false;
    }

    private @NotNull Set<String> adjacentNodeIds(@NotNull String nodeId) {
        return adjacentNodeIdsByNodeId.getOrDefault(nodeId, Set.of());
    }

    /**
     * 一回のviewer再描画で共有するノード表示判定値を作ります。
     * 解放済み有効ノード・PP/CP残高・隣接表をこの時点で確定し、edgeごとの全体走査を避けます。
     *
     * @param astPlayer 表示対象プレイヤー
     * @return 当該再描画だけで使用する不変スナップショット
     */
    @NotNull NodePresentationSnapshot createNodePresentationSnapshot(@NotNull AstPlayer astPlayer) {
        SkillTreePlayerState state = state(astPlayer);
        Set<String> knownUnlockedNodeIds = knownUnlockedNodeIds(state);
        Set<String> activeUnlockedNodeIds = activeUnlockedNodeIds(astPlayer, state);
        Set<String> inactiveUnlockedNodeIds = new LinkedHashSet<>(state.unlockedNodeIds());
        inactiveUnlockedNodeIds.removeAll(activeUnlockedNodeIds);
        Map<String, Integer> availableClassPointsByClassId = new LinkedHashMap<>();
        for (var progress : astPlayer.getAllClassProgresses()) {
            String classId = normalizeClassId(progress.getClassId());
            availableClassPointsByClassId.put(classId, availableClassPoints(astPlayer, classId));
        }
        for (SkillTreeNodeDefinition node : nodesById.values()) {
            String requiredClassId = node.unlockCondition().classId();
            if (requiredClassId != null) {
                String classId = normalizeClassId(requiredClassId);
                availableClassPointsByClassId.computeIfAbsent(
                        classId,
                        ignored -> availableClassPoints(astPlayer, classId)
                );
            }
        }
        return new NodePresentationSnapshot(
                astPlayer,
                state,
                knownUnlockedNodeIds.isEmpty(),
                Set.copyOf(activeUnlockedNodeIds),
                Set.copyOf(inactiveUnlockedNodeIds),
                availablePassivePoints(astPlayer),
                Map.copyOf(availableClassPointsByClassId)
        );
    }

    @NotNull NodePresentationState nodePresentationState(
            @NotNull NodePresentationSnapshot snapshot,
            @NotNull SkillTreeNodeDefinition node
    ) {
        if (snapshot.state().isUnlocked(node.nodeId())) {
            if (!isNodeUnlockConditionMet(snapshot.astPlayer(), node)) {
                return NodePresentationState.INACTIVE_CONDITION;
            }
            return snapshot.inactiveUnlockedNodeIds().contains(node.nodeId())
                    ? NodePresentationState.INACTIVE
                    : NodePresentationState.UNLOCKED;
        }
        if (!isNodeUnlockConditionMet(snapshot.astPlayer(), node)) {
            return NodePresentationState.CONDITION_BLOCKED;
        }
        return canUnlockNode(snapshot, node) ? NodePresentationState.AVAILABLE : NodePresentationState.BLOCKED;
    }

    private boolean canUnlockNode(
            @NotNull NodePresentationSnapshot snapshot,
            @NotNull SkillTreeNodeDefinition node
    ) {
        if (snapshot.state().isUnlocked(node.nodeId())) {
            return false;
        }
        if (requiresCpSourceSelection(node)) {
            for (Map.Entry<String, Integer> entry : snapshot.availableClassPointsByClassId().entrySet()) {
                if (entry.getValue() >= node.pointCost()
                        && (snapshot.hasNoKnownUnlockedNodeIds()
                        ? rootNodeId.equals(node.nodeId())
                        : isAdjacentToActiveNode(snapshot.activeUnlockedNodeIds(), node.nodeId()))) {
                    return true;
                }
            }
            return false;
        }
        if (!hasRequiredPoints(snapshot, node, node.pointType() == SkillTreePointType.CLASS_POINT
                ? node.unlockCondition().classId()
                : null)) {
            return false;
        }
        return snapshot.hasNoKnownUnlockedNodeIds()
                ? rootNodeId.equals(node.nodeId())
                : isAdjacentToActiveNode(snapshot.activeUnlockedNodeIds(), node.nodeId());
    }

    private boolean hasRequiredPoints(
            @NotNull NodePresentationSnapshot snapshot,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable String consumedClassId
    ) {
        if (node.pointType() == SkillTreePointType.PASSIVE_POINT) {
            return consumedClassId == null && snapshot.availablePassivePoints() >= node.pointCost();
        }
        String requiredClassId = node.unlockCondition().classId();
        String normalizedSource = consumedClassId == null ? null : normalizeClassId(consumedClassId);
        if (requiredClassId != null) {
            String normalizedRequired = normalizeClassId(requiredClassId);
            if (normalizedSource == null) {
                normalizedSource = normalizedRequired;
            }
            return normalizedRequired.equals(normalizedSource)
                    && snapshot.availableClassPointsByClassId().getOrDefault(normalizedRequired, 0) >= node.pointCost();
        }
        if (node.pointCost() == 0) {
            return normalizedSource == null || snapshot.availableClassPointsByClassId().containsKey(normalizedSource);
        }
        return normalizedSource != null
                && snapshot.availableClassPointsByClassId().getOrDefault(normalizedSource, 0) >= node.pointCost();
    }

    private boolean isAdjacentToActiveNode(@NotNull Set<String> activeNodeIds, @NotNull String nodeId) {
        for (String adjacentNodeId : adjacentNodeIds(nodeId)) {
            if (activeNodeIds.contains(adjacentNodeId)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    /**
     * プレイヤー視点のノード表示状態を返します。
     * 条件未達PPノードは、未解放なら {@link NodePresentationState#CONDITION_BLOCKED}、
     * 解放済みなら {@link NodePresentationState#INACTIVE_CONDITION} になります。
     *
     * @param astPlayer 状態を判定するプレイヤー
     * @param node 判定対象ノード
     * @return ノード表示状態
     */
    public @NotNull NodePresentationState nodePresentationState(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        SkillTreePlayerState state = state(astPlayer);
        if (state.isUnlocked(node.nodeId())) {
            if (!isNodeUnlockConditionMet(astPlayer, node)) {
                return NodePresentationState.INACTIVE_CONDITION;
            }
            return derivedState(astPlayer, state).inactiveUnlockedNodeIds().contains(node.nodeId())
                    ? NodePresentationState.INACTIVE
                    : NodePresentationState.UNLOCKED;
        }
        if (!isNodeUnlockConditionMet(astPlayer, node)) {
            return NodePresentationState.CONDITION_BLOCKED;
        }
        return canUnlockNode(astPlayer, node) ? NodePresentationState.AVAILABLE : NodePresentationState.BLOCKED;
    }

    public enum NodePresentationState {
        BLOCKED,
        CONDITION_BLOCKED,
        AVAILABLE,
        UNLOCKED,
        INACTIVE,
        INACTIVE_CONDITION
    }

    /** viewer再描画中だけ共有する、プレイヤー由来のノード表示状態です。 */
    record NodePresentationSnapshot(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreePlayerState state,
            boolean hasNoKnownUnlockedNodeIds,
            @NotNull Set<String> activeUnlockedNodeIds,
            @NotNull Set<String> inactiveUnlockedNodeIds,
            int availablePassivePoints,
            @NotNull Map<String, Integer> availableClassPointsByClassId
    ) {
    }

    public enum NodeLabelDetail {
        HIDDEN,
        COMPACT,
        SIMPLE,
        DETAILED
    }

    private record NodeLabelSet(@NotNull Component detailed, @NotNull Component compact, @NotNull Component simple) {
        private static final NodeLabelSet EMPTY = new NodeLabelSet(Component.empty(), Component.empty(), Component.empty());

        private @NotNull Component component(@NotNull NodeLabelDetail detail) {
            return switch (detail) {
                case HIDDEN -> Component.empty();
                case COMPACT -> compact;
                case SIMPLE -> simple;
                case DETAILED -> detailed;
            };
        }
    }

    private record DerivedPlayerState(
            @NotNull Set<String> unlockedSkillIds,
            @NotNull Map<StatusType, StatusBonusTotals> statusBonuses,
            @NotNull Set<String> inactiveUnlockedNodeIds
    ) {
        private static final DerivedPlayerState EMPTY = new DerivedPlayerState(Set.of(), Map.of(), Set.of());
    }

    private record StatusBonusTotals(double flat, double scalar) {
        private static final StatusBonusTotals ZERO = new StatusBonusTotals(0.0D, 0.0D);
    }

    /** SQL ACK 後の派生状態反映に必要な、確定済みノード操作の結果です。 */
    public record SkillTreeMutationResult(boolean changed, @NotNull Set<String> previousSkillIds) {
        public SkillTreeMutationResult {
            previousSkillIds = Set.copyOf(previousSkillIds);
        }

        private static @NotNull SkillTreeMutationResult rejected() {
            return new SkillTreeMutationResult(false, Set.of());
        }
    }

    /** 一回のWeb batchで確定した解放通知と、確定前の派生スキル集合です。 */
    private record BatchMutationResult(
            @NotNull Set<String> previousSkillIds,
            @NotNull List<SkillTreeNodeDefinition> unlockedNodes,
            @NotNull List<SkillTreeNodeDefinition> changedNodes
    ) {
        private BatchMutationResult {
            previousSkillIds = Set.copyOf(previousSkillIds);
            unlockedNodes = List.copyOf(unlockedNodes);
            changedNodes = List.copyOf(changedNodes);
        }
    }

    /** 重要操作が SQL ACK 前に失敗した場合に復元するスキルツリー保存 lane の状態です。 */
    private record SkillTreeMutationCheckpoint(
            @NotNull SkillTreePlayerState state,
            @Nullable DerivedPlayerState derivedState,
            boolean dirty,
            @Nullable Long dueAtMillis,
            @Nullable Long revision,
            @Nullable Integer persistedVersion,
            @Nullable Long acknowledgedRevision,
            @Nullable UUID epoch,
            @Nullable PendingRuntimeOperationReceipt pendingRuntimeOperationReceipt,
            boolean legacyDefinitionGenerationMigrationPending
    ) {
    }

    /** Runtime operation をclaimした時点の定義・保存版数・現在地文脈です。 */
    private record RuntimeMutationGuard(
            @NotNull UUID accountId,
            @NotNull String definitionGenerationId,
            int expectedPlayerStateVersion,
            long playerContextRevision,
            @NotNull UUID operationId,
            @NotNull String leaseToken,
            @NotNull String serverId,
            @Nullable String expectedEvaluationFingerprint
    ) {
    }

    /** state変更と同じAPI transactionで確定する Web操作 receipt の保存用値です。 */
    private record PendingRuntimeOperationReceipt(
            @NotNull UUID operationId,
            @NotNull String serverId,
            @NotNull UUID serverSessionId,
            @NotNull String leaseToken,
            @NotNull String definitionGenerationId,
            int finalPlayerStateVersion,
            @NotNull String finalEvaluationFingerprint
    ) {
        private @NotNull JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("operationId", operationId.toString());
            value.addProperty("serverId", serverId);
            value.addProperty("serverSessionId", serverSessionId.toString());
            value.addProperty("leaseToken", leaseToken);
            value.addProperty("finalStatus", "APPLIED");
            value.addProperty("definitionGenerationId", definitionGenerationId);
            value.addProperty("finalPlayerStateVersion", finalPlayerStateVersion);
            value.addProperty("finalEvaluationFingerprint", finalEvaluationFingerprint);
            value.addProperty("migrateLegacyState", false);
            return value;
        }
    }

    /**
     * 旧実装で保存されてしまったスキルツリー可視化 entity を掃除します。 */
    private void purgeSkillTreeVisualEntities() {
        WorldMasterData data = worldService.getById(SKILL_TREE_WORLD_ID);
        if (data == null) {
            return;
        }

        World world = worldService.resolveLoadedWorld(data);
        if (world == null) {
            return;
        }

        int removedCount = 0;
        for (Entity entity : List.copyOf(world.getEntities())) {
            if (entity instanceof Item
                    || entity instanceof ItemDisplay
                    || entity instanceof TextDisplay
                    || entity instanceof BlockDisplay
                    || entity.getScoreboardTags().contains(NODE_INTERACTION_TAG)) {
                entity.remove();
                removedCount++;
            }
        }
        Logger.log(LogId.I_9003, world.getName(), removedCount);

        File entitiesDirectory = new File(world.getWorldFolder(), "entities");
        File[] files = entitiesDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                file.delete();
            }
        }
    }
}
