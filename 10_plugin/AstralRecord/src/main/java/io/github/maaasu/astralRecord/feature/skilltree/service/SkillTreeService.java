package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skilltree.config.SkillTreePluginConfig;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
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
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
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
    private static final double TARGET_RADIUS = 0.9D;
    static final String NODE_INTERACTION_TAG = "astralrecord:skilltree:node-interaction";
    private static final long SAVE_INTERVAL_TICKS = 20L;
    private static final long FEEDBACK_INTERVAL_TICKS = 5L;
    private static final long VISUAL_DELAY_MILLIS = 1_500L;
    private static final long SAVE_DEBOUNCE_MILLIS = 5_000L;
    private static final int DEFAULT_VIEW_DISTANCE = 48;
    private static final double DETAILED_LABEL_DISTANCE = 14.0D;
    private static final double COMPACT_LABEL_DISTANCE = 28.0D;

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

    private final Plugin plugin;
    private final WorldService worldService;
    private InventoryService inventoryService;
    private PlayerHudService playerHudService;
    private StatusService statusService;
    private SkillService skillService;
    private PassiveSkillService passiveSkillService;
    private PlayerClassService playerClassService;
    private final SkillTreeNodeRepository nodeRepository;
    private final SkillTreeStructureRepository structureRepository;
    private final SkillTreePlayerStateRepository playerStateRepository;
    private final NamespacedKey nodeInteractionKey;
    private final Map<String, SkillTreeNodeDefinition> nodesById = new LinkedHashMap<>();
    private final Map<String, SkillTreePosition> positionsByNodeId = new LinkedHashMap<>();
    private final Map<String, SkillTreeEdge> edgesByKey = new LinkedHashMap<>();
    private final Map<String, ItemStack> lockedNodeDisplayItems = new LinkedHashMap<>();
    private final Map<String, ItemStack> unlockedNodeDisplayItems = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> blockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> availableNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> unlockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, NodeLabelSet> inactiveNodeFieldLabels = new LinkedHashMap<>();
    private final Map<UUID, SkillTreePlayerState> playerStates = new HashMap<>();
    private final Map<UUID, DerivedPlayerState> derivedPlayerStates = new HashMap<>();
    private final Set<UUID> dirtyPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> loadingPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> failedPlayerStateLoads = new LinkedHashSet<>();
    private final Map<UUID, Long> dirtyPlayerStateDueAtMillis = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, Long> visualReadyAtMillis = new HashMap<>();
    private final Map<UUID, BossBar> loadingBossBars = new HashMap<>();

    private BukkitTask saveTask;
    private BukkitTask feedbackTask;
    private SkillTreeVisualizer visualizer;
    private boolean playerStateSaveInProgress;
    private BiConsumer<AstPlayer, String> nodeUnlockListener = (player, nodeId) -> { };
    private String rootNodeId = "";

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

    public void setInventoryService(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
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
    public void replaceMasterDataSnapshot(@NotNull SkillTreeMasterDataSnapshot snapshot) {
        nodesById.clear();
        lockedNodeDisplayItems.clear();
        unlockedNodeDisplayItems.clear();
        blockedNodeFieldLabels.clear();
        availableNodeFieldLabels.clear();
        unlockedNodeFieldLabels.clear();
        inactiveNodeFieldLabels.clear();
        for (SkillTreeNodeDefinition node : snapshot.nodes()) {
            nodesById.put(node.nodeId(), node);
            cacheNodePresentation(node);
        }

        positionsByNodeId.clear();
        edgesByKey.clear();
        for (SkillTreePosition position : snapshot.positions()) {
            positionsByNodeId.put(position.nodeId(), position);
        }
        for (SkillTreeEdge edge : snapshot.edges()) {
            edgesByKey.put(edge.key(), edge);
        }
        rootNodeId = snapshot.rootNodeId();
        derivedPlayerStates.clear();
        refreshLoadedOnlinePlayerDerivedStates();
        if (visualizer != null) {
            visualizer.markStructureDirty();
        }
        Logger.log(LogId.I_9000, nodesById.size(), positionsByNodeId.size(), edgesByKey.size());
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
            visualizer = new SkillTreeVisualizer(plugin, this);
            visualizer.start();
        }
        if (saveTask == null) {
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirtyAsync, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
        }
        if (feedbackTask == null) {
            feedbackTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayerFeedbacks, 1L, FEEDBACK_INTERVAL_TICKS);
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayerPresentation(player);
        }
        restoreAllPlayerVisibility();
        clearAllLoadingBossBars();
        saveDirty();
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
     * @param accountId 読み込み対象アカウント UUID
     * @return API / DB から読み込んだスキルツリー状態
     * @throws RuntimeException 読み込みに失敗した場合
     */
    public @NotNull SkillTreePlayerState loadInitialPlayerState(@NotNull UUID accountId) {
        return playerStateRepository.load(accountId);
    }

    /**
     * 初回ログイン処理で読み込んだスキルツリー状態をサービス内キャッシュへ反映します。
     * <p>
     * {@link AstPlayer} 登録前に呼び出すことで、初回ステータス計算が空のスキルツリー状態を参照しないようにします。
     *
     * @param state 反映するスキルツリー状態
     */
    public void applyInitialPlayerState(@NotNull SkillTreePlayerState state) {
        loadingPlayerStates.remove(state.accountId());
        failedPlayerStateLoads.remove(state.accountId());
        playerStates.put(state.accountId(), state);
        derivedPlayerStates.remove(state.accountId());
    }

    /**
     * ログイン反映が中断された場合に、当該反映で公開したスキルツリー状態だけを破棄します。
     * 後続セッションが同じアカウントへ別の状態を反映済みの場合は削除しません。
     *
     * @param state {@link #applyInitialPlayerState(SkillTreePlayerState)} へ渡した初期状態
     */
    public void discardInitialPlayerState(@NotNull SkillTreePlayerState state) {
        if (playerStates.get(state.accountId()) != state) {
            return;
        }
        playerStates.remove(state.accountId());
        derivedPlayerStates.remove(state.accountId());
    }

    /**
     * スキルツリー状態を通信待ちなしで利用できるかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return API ロードが完了している場合は true
     */
    public boolean isStateReady(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        return playerStates.containsKey(accountId)
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
                loaded = playerStateRepository.load(accountId);
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

    public void markDirty(@NotNull SkillTreePlayerState state) {
        dirtyPlayerStates.add(state.accountId());
        dirtyPlayerStateDueAtMillis.put(state.accountId(), System.currentTimeMillis() + SAVE_DEBOUNCE_MILLIS);
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
        return Math.max(0, astPlayer.getAccount().getLevel() - 1);
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

    /** 通常プレイヤーにノードを表示してよいかを返します。 */
    public boolean isNodeVisible(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        return isNodeUnlockConditionMet(astPlayer, node);
    }

    private int compareNodeIdDescending(@NotNull String left, @NotNull String right) {
        int numeric = Long.compare(nodeIdSortValue(right), nodeIdSortValue(left));
        return numeric != 0 ? numeric : right.compareTo(left);
    }

    private long nodeIdSortValue(@NotNull String nodeId) {
        String digits = nodeId.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(digits);
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
            visualizer.markNodeStateDirty(
                    astPlayer.getBukkit().getUniqueId(),
                    affectedNodeIds(changedNode.nodeId())
            );
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
        if (visualizer != null) {
            visualizer.markViewerDirty(player.getUniqueId());
        }
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

    public boolean unlockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        String consumedClassId = node.pointType() == SkillTreePointType.CLASS_POINT
                ? node.unlockCondition().classId()
                : null;
        if (requiresCpSourceSelection(node)) {
            return false;
        }
        return unlockNode(astPlayer, node, consumedClassId);
    }

    public boolean unlockNode(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            @Nullable String consumedClassId
    ) {
        if (!canUnlockNode(astPlayer, node, consumedClassId)) {
            return false;
        }
        SkillTreePlayerState state = state(astPlayer);
        Set<String> previousSkillIds = derivedState(astPlayer, state).unlockedSkillIds();
        String normalizedSource = node.pointType() == SkillTreePointType.CLASS_POINT && consumedClassId != null
                ? normalizeClassId(consumedClassId)
                : null;
        boolean changed = state.unlock(node.nodeId(), normalizedSource);
        if (changed) {
            DerivedPlayerState nextDerivedState = rebuildDerivedState(astPlayer, state);
            derivedPlayerStates.put(state.accountId(), nextDerivedState);
            markDirty(state);
            markNodeStateChanged(astPlayer, node, previousSkillIds, nextDerivedState.unlockedSkillIds());
            nodeUnlockListener.accept(astPlayer, node.nodeId());
        }
        return changed;
    }

    public boolean relockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        if (!canRelockNode(astPlayer, node)) {
            return false;
        }
        if (inventoryService == null || !inventoryService.consumeGold(astPlayer.getAccount().getUuid(), RELOCK_GOLD_COST)) {
            return false;
        }
        SkillTreePlayerState state = state(astPlayer);
        Set<String> previousSkillIds = derivedState(astPlayer, state).unlockedSkillIds();
        boolean changed = state.relock(node.nodeId());
        if (changed) {
            DerivedPlayerState nextDerivedState = rebuildDerivedState(astPlayer, state);
            derivedPlayerStates.put(state.accountId(), nextDerivedState);
            markDirty(state);
            markNodeStateChanged(astPlayer, node, previousSkillIds, nextDerivedState.unlockedSkillIds());
        }
        return changed;
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
     * @return 表示距離48ブロック
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
        double distanceSquared = player.getLocation().distanceSquared(nodeLocation);
        double detailedThreshold = Math.min(DETAILED_LABEL_DISTANCE, DEFAULT_VIEW_DISTANCE);
        if (distanceSquared <= detailedThreshold * detailedThreshold) {
            return NodeLabelDetail.DETAILED;
        }
        double compactThreshold = Math.min(COMPACT_LABEL_DISTANCE, DEFAULT_VIEW_DISTANCE);
        if (distanceSquared <= compactThreshold * compactThreshold) {
            return NodeLabelDetail.COMPACT;
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
            case AVAILABLE -> availableNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
            case UNLOCKED -> unlockedNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
            case INACTIVE -> inactiveNodeFieldLabels.getOrDefault(node.nodeId(), NodeLabelSet.EMPTY).component(labelDetail);
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
        for (UUID accountId : List.copyOf(dirtyPlayerStates)) {
            SkillTreePlayerState state = playerStates.get(accountId);
            if (state != null) {
                playerStateRepository.save(state);
            }
            dirtyPlayerStates.remove(accountId);
            dirtyPlayerStateDueAtMillis.remove(accountId);
        }
    }

    /**
     * dirty なスキルツリー状態を非同期で API へ保存します。
     * <p>
     * Bukkit API には触れず、メインスレッドでは保存対象のスナップショット作成だけを行います。
     */
    public void saveDirtyAsync() {
        flushDirtyPlayerStatesAsync();
    }

    private void flushDirtyPlayerStatesAsync() {
        if (playerStateSaveInProgress || dirtyPlayerStates.isEmpty()) {
            return;
        }

        List<SkillTreePlayerState> snapshots = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (UUID accountId : List.copyOf(dirtyPlayerStates)) {
            Long dueAt = dirtyPlayerStateDueAtMillis.get(accountId);
            if (dueAt != null && dueAt > now) {
                continue;
            }
            SkillTreePlayerState state = playerStates.get(accountId);
            if (state != null) {
                snapshots.add(new SkillTreePlayerState(state.accountId(), state.unlockedNodes()));
            }
            dirtyPlayerStates.remove(accountId);
            dirtyPlayerStateDueAtMillis.remove(accountId);
        }
        if (snapshots.isEmpty()) {
            return;
        }

        playerStateSaveInProgress = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<UUID> failedAccountIds = new LinkedHashSet<>();
            for (SkillTreePlayerState snapshot : snapshots) {
                try {
                    playerStateRepository.save(snapshot);
                } catch (RuntimeException e) {
                    failedAccountIds.add(snapshot.accountId());
                    Logger.log(LogId.W_9003, snapshot.accountId(), e.getMessage());
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                long retryDueAt = System.currentTimeMillis() + SAVE_DEBOUNCE_MILLIS;
                dirtyPlayerStates.addAll(failedAccountIds);
                failedAccountIds.forEach(accountId -> dirtyPlayerStateDueAtMillis.put(accountId, retryDueAt));
                playerStateSaveInProgress = false;
            });
        });
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
        lore.add(component("&8--- &bパッシブ &8---"));
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
        lines.add(unlocked ? "&8--- &bパッシブ &8---" : "&8--- パッシブ ---");
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
        availableNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.AVAILABLE));
        unlockedNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.UNLOCKED));
        inactiveNodeFieldLabels.put(node.nodeId(), createNodeLabelSet(node, NodePresentationState.INACTIVE));
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
                createNodeFieldLabel(node, presentationState, NodeLabelDetail.COMPACT)
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
        lines.add(resolveNodeDisplayName(node, presentationState == NodePresentationState.UNLOCKED));
        if (presentationState == NodePresentationState.INACTIVE && labelDetail == NodeLabelDetail.DETAILED) {
            lines.add("&c効果停止中: CP/PP 不足");
        }
        if (labelDetail == NodeLabelDetail.DETAILED
                && (node.pointCost() > 0 || node.unlockCondition().hasClassCondition())) {
            lines.add("&8Cost: &f" + nodePointDisplayName(node) + " &e" + node.pointCost());
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
        Set<String> result = new LinkedHashSet<>();
        for (SkillTreeEdge edge : edgesByKey.values()) {
            if (nodeId.equals(edge.sourceNodeId())) {
                result.add(edge.targetNodeId());
            } else if (nodeId.equals(edge.targetNodeId())) {
                result.add(edge.sourceNodeId());
            }
        }
        return result;
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    public @NotNull NodePresentationState nodePresentationState(
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        SkillTreePlayerState state = state(astPlayer);
        if (state.isUnlocked(node.nodeId())) {
            return derivedState(astPlayer, state).inactiveUnlockedNodeIds().contains(node.nodeId())
                    ? NodePresentationState.INACTIVE
                    : NodePresentationState.UNLOCKED;
        }
        return canUnlockNode(astPlayer, node) ? NodePresentationState.AVAILABLE : NodePresentationState.BLOCKED;
    }

    public enum NodePresentationState {
        BLOCKED,
        AVAILABLE,
        UNLOCKED,
        INACTIVE
    }

    public enum NodeLabelDetail {
        HIDDEN,
        COMPACT,
        DETAILED
    }

    private record NodeLabelSet(@NotNull Component detailed, @NotNull Component compact) {
        private static final NodeLabelSet EMPTY = new NodeLabelSet(Component.empty(), Component.empty());

        private @NotNull Component component(@NotNull NodeLabelDetail detail) {
            return switch (detail) {
                case HIDDEN -> Component.empty();
                case COMPACT -> compact;
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
