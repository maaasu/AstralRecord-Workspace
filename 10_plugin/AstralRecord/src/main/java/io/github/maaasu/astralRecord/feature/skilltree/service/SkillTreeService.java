package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeStatusDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
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
import org.bukkit.entity.Item;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.title.Title;

/**
 * スキルツリーのノード状態管理、GUI 更新、ホットバー制御、
 * およびノード由来のスキル・ステータス反映を担当するサービスです。
 */
public class SkillTreeService {
    public static final String SKILL_TREE_WORLD_ID = "skill_tree";
    public static final long RELOCK_GOLD_COST = 100L;
    private static final String ROOT_TAG = "root";
    private static final double TARGET_DISTANCE = 8.0D;
    private static final double TARGET_RADIUS_SQ = 0.9D * 0.9D;
    private static final long SAVE_INTERVAL_TICKS = 20L;
    private static final long FEEDBACK_INTERVAL_TICKS = 5L;
    private static final long VISUAL_DELAY_MILLIS = 1_500L;
    private static final long SAVE_DEBOUNCE_MILLIS = 5_000L;

    private final Plugin plugin;
    private final WorldService worldService;
    private InventoryService inventoryService;
    private PlayerHudService playerHudService;
    private StatusService statusService;
    private SkillService skillService;
    private PassiveSkillService passiveSkillService;
    private final SkillTreeNodeRepository nodeRepository;
    private final SkillTreeStructureRepository structureRepository;
    private final SkillTreePlayerStateRepository playerStateRepository;
    private final NamespacedKey positionItemKey;
    private final NamespacedKey connectorItemKey;
    private final Map<String, SkillTreeNodeDefinition> nodesById = new LinkedHashMap<>();
    private final Map<String, SkillTreeNodeDefinition> nodesByPositionId = new LinkedHashMap<>();
    private final Map<String, SkillTreePosition> positionsById = new LinkedHashMap<>();
    private final Map<String, SkillTreeEdge> edgesByKey = new LinkedHashMap<>();
    private final Map<String, ItemStack> lockedNodeDisplayItems = new LinkedHashMap<>();
    private final Map<String, ItemStack> unlockedNodeDisplayItems = new LinkedHashMap<>();
    private final Map<String, Component> blockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, Component> availableNodeFieldLabels = new LinkedHashMap<>();
    private final Map<String, Component> unlockedNodeFieldLabels = new LinkedHashMap<>();
    private final Map<UUID, SkillTreePlayerState> playerStates = new HashMap<>();
    private final Map<UUID, DerivedPlayerState> derivedPlayerStates = new HashMap<>();
    private final Set<UUID> dirtyPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> loadingPlayerStates = new LinkedHashSet<>();
    private final Set<UUID> failedPlayerStateLoads = new LinkedHashSet<>();
    private final Map<UUID, Long> dirtyPlayerStateDueAtMillis = new HashMap<>();
    private final Map<UUID, String> connectorLeftSelections = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, Long> visualReadyAtMillis = new HashMap<>();
    private final Map<UUID, BossBar> loadingBossBars = new HashMap<>();

    private BukkitTask saveTask;
    private BukkitTask feedbackTask;
    private SkillTreeVisualizer visualizer;
    private boolean structureDirty;
    private boolean playerStateSaveInProgress;

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
        this.positionItemKey = new NamespacedKey(plugin, "skilltree_position_id");
        this.connectorItemKey = new NamespacedKey(plugin, "skilltree_connector");
    }

    public void setInventoryService(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
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

    public int loadAll() {
        nodesById.clear();
        nodesByPositionId.clear();
        lockedNodeDisplayItems.clear();
        unlockedNodeDisplayItems.clear();
        blockedNodeFieldLabels.clear();
        availableNodeFieldLabels.clear();
        unlockedNodeFieldLabels.clear();
        for (SkillTreeNodeDefinition node : nodeRepository.findAll()) {
            nodesById.put(node.id(), node);
            nodesByPositionId.put(node.positionId(), node);
            cacheNodePresentation(node);
        }

        positionsById.clear();
        edgesByKey.clear();
        var snapshot = structureRepository.load();
        for (SkillTreePosition position : snapshot.positions()) {
            positionsById.put(position.positionId(), position);
        }
        for (SkillTreeEdge edge : snapshot.edges()) {
            if (positionsById.containsKey(edge.leftPositionId()) && positionsById.containsKey(edge.rightPositionId())) {
                edgesByKey.put(edge.key(), edge);
            }
        }
        structureDirty = false;
        derivedPlayerStates.replaceAll((accountId, ignored) -> rebuildDerivedState(playerStates.get(accountId)));
        if (visualizer != null) {
            visualizer.markStructureDirty();
        }
        Logger.log(LogId.I_9000, nodesById.size(), positionsById.size(), edgesByKey.size());
        return nodesById.size();
    }

    public void start() {
        purgeSkillTreeVisualEntities();
        WorldMasterData data = worldService.getById(SKILL_TREE_WORLD_ID);
        World resolvedWorld = data == null ? null : worldService.resolveLoadedWorld(data);
        Logger.log(
                LogId.I_9001,
                SKILL_TREE_WORLD_ID,
                resolvedWorld == null ? "null" : resolvedWorld.getName(),
                positionsById.size(),
                edgesByKey.size()
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
            restoreHotbar(player);
        }
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

    public boolean isPlayerModeSkillTree(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null
                && astPlayer.getAccount().getMode() == AccountMode.PLAYER
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
        return state(astPlayer).isUnlocked(node.id());
    }

    /**
     * プレイヤーがノード解放用の SP を持っているかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 1 以上の SP を持つなら {@code true}
     */
    public boolean hasSkillPoints(@NotNull AstPlayer astPlayer) {
        return state(astPlayer).skillPoints() > 0;
    }

    public boolean canUnlockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        if (state.isUnlocked(node.id()) || state.skillPoints() <= 0) {
            return false;
        }
        if (state.unlockedNodeIds().isEmpty()) {
            return hasTag(node, ROOT_TAG);
        }
        return isAdjacentToUnlockedNode(state, node.positionId());
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
     * ItemStack がスキルツリー設定用アイテムかを判定します。
     *
     * @param itemStack 判定対象の ItemStack
     * @return Position / Connector アイテムの場合は true
     */
    public boolean isSkillTreeSetupItem(@Nullable ItemStack itemStack) {
        return readPositionItemId(itemStack) != null || isConnectorItem(itemStack);
    }

    /**
     * スキルツリー設定中として通常戦闘系入力を抑止すべきかを判定します。
     *
     * @param player 判定対象プレイヤー
     * @return 設定アイテム操作中、または管理者が skill_tree ワールドにいる場合は true
     */
    public boolean shouldSuppressSkillTreeSetupControls(@NotNull Player player) {
        boolean inSkillTreeWorld = isSkillTreeWorld(player.getWorld());
        if (!inSkillTreeWorld) {
            return hasSetupItemInHands(player);
        }
        return hasSetupItemInHotbar(player);
    }

    /**
     * プレイヤーがスキルツリー編集中で、通常攻撃・特殊攻撃などの通常操作を抑止すべきか判定します。
     *
     * @param player 判定対象のプレイヤー
     * @return スキルツリー編集中の場合は true
     */
    public boolean isSkillTreeEditing(@NotNull Player player) {
        return shouldSuppressSkillTreeSetupControls(player) || isPlayerModeSkillTree(player);
    }

    private boolean hasSetupItemInHands(@NotNull Player player) {
        return isSkillTreeSetupItem(player.getInventory().getItemInMainHand())
                || isSkillTreeSetupItem(player.getInventory().getItemInOffHand());
    }

    private boolean hasSetupItemInHotbar(@NotNull Player player) {
        for (int slot = 0; slot <= 8; slot++) {
            if (isSkillTreeSetupItem(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return isSkillTreeSetupItem(player.getInventory().getItemInOffHand());
    }

    @Nullable
    public ItemStack createPositionItem(@NotNull String positionId, int amount) {
        if (positionId.isBlank()) {
            return null;
        }
        ItemStack itemStack = new ItemStack(Material.ARMOR_STAND, Math.max(1, amount));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component("&dSkillTree Position [&f" + positionId + "&d]"));
            meta.lore(List.of(
                    component("&7Right click block: register / Left click: remove"),
                    component("&7positionId: &f" + positionId)
            ));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(positionItemKey, PersistentDataType.STRING, positionId);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
    @NotNull
    public ItemStack createConnectorItem(int amount) {
        ItemStack itemStack = new ItemStack(Material.LEAD, Math.max(1, amount));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component("&bSkillTree Connector"));
            meta.lore(List.of(
                    component("&7Left click: select left node"),
                    component("&7Right click: toggle connection with targeted node")
            ));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(connectorItemKey, PersistentDataType.BOOLEAN, true);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @Nullable
    public String readPositionItemId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(positionItemKey, PersistentDataType.STRING);
    }

    public boolean isConnectorItem(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return false;
        }
        Boolean value = itemStack.getItemMeta().getPersistentDataContainer().get(connectorItemKey, PersistentDataType.BOOLEAN);
        return Boolean.TRUE.equals(value);
    }

    public boolean registerPosition(@NotNull String positionId, @NotNull Location location) {
        positionsById.put(positionId, SkillTreePosition.from(positionId, location));
        structureDirty = true;
        if (visualizer != null) {
            visualizer.markStructureDirty();
        }
        return true;
    }

    public boolean removePosition(@NotNull String positionId) {
        boolean removed = positionsById.remove(positionId) != null;
        edgesByKey.entrySet().removeIf(entry -> entry.getValue().contains(positionId));
        if (removed) {
            structureDirty = true;
            if (visualizer != null) {
                visualizer.markStructureDirty();
            }
        }
        return removed;
    }

    public boolean toggleConnection(@NotNull String leftPositionId, @NotNull String rightPositionId) {
        if (leftPositionId.equals(rightPositionId)
                || !positionsById.containsKey(leftPositionId)
                || !positionsById.containsKey(rightPositionId)) {
            return false;
        }
        SkillTreeEdge edge = new SkillTreeEdge(leftPositionId, rightPositionId);
        if (edgesByKey.remove(edge.key()) == null) {
            edgesByKey.put(edge.key(), edge);
        }
        structureDirty = true;
        if (visualizer != null) {
            visualizer.markStructureDirty();
        }
        return true;
    }

    public void selectConnectorLeft(@NotNull UUID playerId, @NotNull String positionId) {
        connectorLeftSelections.put(playerId, positionId);
    }

    @Nullable
    public String consumeConnectorLeft(@NotNull UUID playerId) {
        return connectorLeftSelections.remove(playerId);
    }

    @NotNull
    public Collection<SkillTreePosition> getPositions() {
        return List.copyOf(positionsById.values());
    }

    @NotNull
    public Collection<SkillTreeEdge> getEdges() {
        return List.copyOf(edgesByKey.values());
    }

    @Nullable
    public SkillTreePosition getPosition(@NotNull String positionId) {
        return positionsById.get(positionId);
    }

    @Nullable
    public SkillTreeNodeDefinition getNodeByPositionId(@NotNull String positionId) {
        return nodesByPositionId.get(positionId);
    }

    @NotNull
    public Collection<String> getNodeIds() {
        return List.copyOf(nodesById.keySet());
    }

    @NotNull
    public Collection<String> getPositionIds() {
        return List.copyOf(positionsById.keySet());
    }

    @NotNull
    public Collection<String> getDefinedPositionIds() {
        return List.copyOf(nodesByPositionId.keySet());
    }

    public boolean hasDefinedPosition(@NotNull String positionId) {
        return nodesByPositionId.containsKey(positionId);
    }

    @NotNull
    public SkillTreePlayerState state(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        SkillTreePlayerState state = playerStates.get(accountId);
        if (state != null && !failedPlayerStateLoads.contains(accountId)) {
            return state;
        }
        failedPlayerStateLoads.remove(accountId);
        SkillTreePlayerState fallback = new SkillTreePlayerState(accountId, 0, Set.of());
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
        playerStates.put(accountId, new SkillTreePlayerState(accountId, 0, Set.of()));
        loadStateAsync(accountId);
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
                    derivedPlayerStates.put(accountId, rebuildDerivedState(loadedState));
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

    private @NotNull DerivedPlayerState derivedState(@NotNull UUID accountId, @NotNull SkillTreePlayerState state) {
        return derivedPlayerStates.computeIfAbsent(accountId, ignored -> rebuildDerivedState(state));
    }

    private @NotNull DerivedPlayerState rebuildDerivedState(@Nullable SkillTreePlayerState state) {
        if (state == null) {
            return DerivedPlayerState.EMPTY;
        }
        Set<String> unlockedSkillIds = new LinkedHashSet<>();
        Map<StatusType, StatusBonusTotals> statusBonuses = new java.util.EnumMap<>(StatusType.class);
        for (String nodeId : state.unlockedNodeIds()) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node == null) {
                continue;
            }
            for (String rawSkillId : node.skillIds()) {
                if (rawSkillId != null && !rawSkillId.isBlank()) {
                    unlockedSkillIds.add(rawSkillId.trim());
                }
            }
            for (SkillTreeNodeStatusDefinition status : node.statuses()) {
                StatusBonusTotals current = statusBonuses.getOrDefault(status.statusType(), StatusBonusTotals.ZERO);
                statusBonuses.put(
                        status.statusType(),
                        status.type() == StatusModifierType.SCALAR
                                ? new StatusBonusTotals(current.flat(), current.scalar() + status.value())
                                : new StatusBonusTotals(current.flat() + status.value(), current.scalar())
                );
            }
        }
        return new DerivedPlayerState(Set.copyOf(unlockedSkillIds), Map.copyOf(statusBonuses));
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
        refreshDerivedState(astPlayer, addedSkillIds, removedSkillIds, !changedNode.statuses().isEmpty());
        if (visualizer != null) {
            visualizer.markNodeStateDirty(
                    astPlayer.getBukkit().getUniqueId(),
                    affectedPositionIds(changedNode.positionId())
            );
        }
    }

    public @NotNull Set<String> affectedPositionIds(@NotNull String positionId) {
        Set<String> affected = new LinkedHashSet<>();
        affected.add(positionId);
        affected.addAll(adjacentPositionIds(positionId));
        return affected;
    }

    private long availableRelockGold(@NotNull AstPlayer astPlayer) {
        if (inventoryService == null) {
            return 0L;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        return inventoryService.getCurrencyAmount(accountId, io.github.maaasu.astralRecord.feature.item.service.ItemService.DEFAULT_CURRENCY_ITEM_ID)
                + inventoryService.getCurrencyAmount(accountId, io.github.maaasu.astralRecord.feature.item.service.ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
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
        return derivedState(state.accountId(), state).unlockedSkillIds();
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
        StatusBonusTotals totals = derivedState(state.accountId(), state).statusBonuses()
                .getOrDefault(statusType, StatusBonusTotals.ZERO);
        return totals.flat() + (baseValue * totals.scalar());
    }

    public boolean unlockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        if (!canUnlockNode(astPlayer, node)) {
            return false;
        }
        SkillTreePlayerState state = state(astPlayer);
        Set<String> previousSkillIds = derivedState(state.accountId(), state).unlockedSkillIds();
        boolean changed = state.unlock(node.id());
        if (changed) {
            DerivedPlayerState nextDerivedState = rebuildDerivedState(state);
            derivedPlayerStates.put(state.accountId(), nextDerivedState);
            markDirty(state);
            markNodeStateChanged(astPlayer, node, previousSkillIds, nextDerivedState.unlockedSkillIds());
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
        Set<String> previousSkillIds = derivedState(state.accountId(), state).unlockedSkillIds();
        boolean changed = state.relock(node.id());
        if (changed) {
            DerivedPlayerState nextDerivedState = rebuildDerivedState(state);
            derivedPlayerStates.put(state.accountId(), nextDerivedState);
            markDirty(state);
            markNodeStateChanged(astPlayer, node, previousSkillIds, nextDerivedState.unlockedSkillIds());
        }
        return changed;
    }

    public void setSkillPoints(@NotNull AstPlayer astPlayer, int points) {
        SkillTreePlayerState state = state(astPlayer);
        state.setSkillPoints(points);
        markDirty(state);
        markViewerContextDirty(astPlayer.getBukkit());
    }

    public void addSkillPoints(@NotNull AstPlayer astPlayer, int points) {
        SkillTreePlayerState state = state(astPlayer);
        state.addSkillPoints(points);
        markDirty(state);
        markViewerContextDirty(astPlayer.getBukkit());
    }

    public boolean canRelockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        if (!state.isUnlocked(node.id())) {
            return false;
        }

        Set<String> remainingUnlocked = new LinkedHashSet<>(state.unlockedNodeIds());
        remainingUnlocked.remove(node.id());
        if (remainingUnlocked.isEmpty()) {
            return true;
        }

        Set<String> rootPositions = new LinkedHashSet<>();
        for (String nodeId : remainingUnlocked) {
            SkillTreeNodeDefinition unlockedNode = nodesById.get(nodeId);
            if (unlockedNode != null && hasTag(unlockedNode, ROOT_TAG)) {
                rootPositions.add(unlockedNode.positionId());
            }
        }
        if (rootPositions.isEmpty()) {
            return false;
        }

        Set<String> reachableUnlocked = new LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(rootPositions);
        Set<String> visitedPositions = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String positionId = queue.removeFirst();
            if (!visitedPositions.add(positionId)) {
                continue;
            }
            SkillTreeNodeDefinition current = nodesByPositionId.get(positionId);
            if (current == null || !remainingUnlocked.contains(current.id())) {
                continue;
            }
            reachableUnlocked.add(current.id());
            for (String adjacentPositionId : adjacentPositionIds(positionId)) {
                SkillTreeNodeDefinition adjacent = nodesByPositionId.get(adjacentPositionId);
                if (adjacent != null && remainingUnlocked.contains(adjacent.id())) {
                    queue.addLast(adjacentPositionId);
                }
            }
        }
        return reachableUnlocked.containsAll(remainingUnlocked);
    }

    @NotNull
    public Optional<SkillTreePosition> findTargetedPosition(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection().normalize();
        return positionsById.values().stream()
                .map(position -> Map.entry(position, position.toLocation()))
                .filter(entry -> entry.getValue() != null && entry.getValue().getWorld() == player.getWorld())
                .filter(entry -> isTargeted(origin, direction, entry.getValue().clone().add(0.0D, 0.6D, 0.0D)))
                .min((left, right) -> Double.compare(left.getValue().distanceSquared(eye), right.getValue().distanceSquared(eye)))
                .map(Map.Entry::getKey);
    }

    @NotNull
    public Optional<SkillTreeNodeDefinition> findTargetedNode(@NotNull Player player) {
        return findTargetedPosition(player).map(position -> nodesByPositionId.get(position.positionId()));
    }

    public void applySkillTreeHotbar(@NotNull Player player) {
    }

    public void renderSkillTreeHotbar(@NotNull Player player) {
    }

    public void restoreHotbar(@NotNull Player player) {
        visualReadyAtMillis.remove(player.getUniqueId());
        stopLoadingPresentation(player);
    }

    private void tickPlayerFeedbacks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPlayerModeSkillTree(player)) {
                updateLoadingPresentation(player);
                if (isSkillTreeVisualReady(player)) {
                    markViewerContextDirty(player);
                }
            } else {
                visualReadyAtMillis.remove(player.getUniqueId());
                stopLoadingPresentation(player);
            }
        }
    }

    /**
     * スキルツリー中に専用 HOTBAR を使うか判定します。
     */
    public boolean shouldUseSkillTreeHotbar(@NotNull Player player) {
        return false;
    }

    /**
     * 専用 HOTBAR の操作を処理します。
     */
    public boolean handleSkillTreeHotbarControl(@NotNull Player player, int slot) {
        return false;
    }

    @NotNull
    public ItemStack createNodeDisplayItem(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        ItemStack cached = unlocked ? unlockedNodeDisplayItems.get(node.id()) : lockedNodeDisplayItems.get(node.id());
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
        return switch (presentationState) {
            case BLOCKED -> blockedNodeFieldLabels.getOrDefault(node.id(), Component.empty());
            case AVAILABLE -> availableNodeFieldLabels.getOrDefault(node.id(), Component.empty());
            case UNLOCKED -> unlockedNodeFieldLabels.getOrDefault(node.id(), Component.empty());
        };
    }

    public int edgeState(@NotNull Player player, @NotNull SkillTreeEdge edge) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return 0;
        }
        SkillTreeNodeDefinition left = nodesByPositionId.get(edge.leftPositionId());
        SkillTreeNodeDefinition right = nodesByPositionId.get(edge.rightPositionId());
        SkillTreePlayerState state = state(astPlayer);
        boolean leftUnlocked = left != null && state.isUnlocked(left.id());
        boolean rightUnlocked = right != null && state.isUnlocked(right.id());
        if (leftUnlocked && rightUnlocked) {
            return 2;
        }
        if (leftUnlocked || rightUnlocked) {
            return 1;
        }
        return 0;
    }

    public void saveDirty() {
        if (structureDirty) {
            structureRepository.save(positionsById.values(), edgesByKey.values());
            structureDirty = false;
        }
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
        if (structureDirty) {
            structureRepository.save(positionsById.values(), edgesByKey.values());
            structureDirty = false;
        }
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
                snapshots.add(new SkillTreePlayerState(state.accountId(), state.skillPoints(), state.unlockedNodeIds()));
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

    private boolean isTargeted(@NotNull Vector origin, @NotNull Vector direction, @NotNull Location target) {
        Vector toTarget = target.toVector().subtract(origin);
        double projection = toTarget.dot(direction);
        if (projection < 0.0D || projection > TARGET_DISTANCE) {
            return false;
        }
        Vector closest = origin.clone().add(direction.clone().multiply(projection));
        return closest.distanceSquared(target.toVector()) <= TARGET_RADIUS_SQ;
    }

    @NotNull
    private ItemStack createNodeHotbarItem(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        boolean unlocked = state.isUnlocked(node.id());
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
                    ? "&8State: &fUnlocked"
                    : canUnlock
                    ? "&8State: &aConnected"
                    : "&8State: &cNeed adjacent unlocked node"));
            lore.add(component("&8ID: &f" + node.id()));
            lore.add(component("&8位置ID: &f" + node.positionId()));
            lore.add(component("&8所持SP: &f" + state.skillPoints()));
            lore.add(component(unlocked ? "&6◆ 解放済みノード ◆" : "&7◆ 未解放ノード ◆"));
            lore.add(component("&e左クリック&7でノードを解放"));
            lore.add(component("&6右クリック&7でノードを解除 &8(100G)"));
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
        if (node.skillIds().isEmpty()) {
            return;
        }
        lore.add(component(""));
        lore.add(component("&b紐づくスキル"));
        for (String rawSkillId : node.skillIds()) {
            if (rawSkillId == null || rawSkillId.isBlank()) {
                continue;
            }
            String skillId = rawSkillId.trim();
            if (skillService == null) {
                lore.add(component("&7- &f" + skillId));
                continue;
            }
            var definition = skillService.registry().getDefinition(skillId);
            if (definition == null) {
                lore.add(component("&7- &f" + skillId + " &8(未読込)"));
                continue;
            }
            String kindLabel = definition.getKind().isPassive() ? "パッシブ" : "発動";
            String triggerLabel = definition.getKind().isPassive()
                    ? (definition.getPassiveBindRequired() ? "要バインド" : "所持のみ")
                    : "アクティブ";
            lore.add(component("&7- &f" + stripLegacy(definition.getName()) + " &8[" + kindLabel + " / " + triggerLabel + "]"));
            lore.add(component("&8  ID: " + definition.getId()));
        }
    }

    private void appendNodeStatusInfo(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        if (node.statuses().isEmpty()) {
            return;
        }
        lore.add(component("&8--- &dステータス &8---"));
        for (SkillTreeNodeStatusDefinition status : node.statuses()) {
            lore.add(component("&7- &f" + status.statusType().getDisplayName() + " &a" + formatNodeStatusModifier(status)));
        }
    }

    private void appendNodePassiveInfo(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        if (node.skillIds().isEmpty()) {
            return;
        }
        if (!lore.isEmpty()) {
            lore.add(component(""));
        }
        lore.add(component("&8--- &bパッシブ &8---"));
        appendNodePassiveSkillLines(lore, node);
    }

    private void appendNodePassiveSkillLines(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        for (String rawSkillId : node.skillIds()) {
            if (rawSkillId == null || rawSkillId.isBlank()) {
                continue;
            }
            String skillId = rawSkillId.trim();
            var definition = skillService == null ? null : skillService.registry().getDefinition(skillId);
            if (definition == null) {
                lore.add(component("&7- &f" + skillId + " &8(未読込)"));
                continue;
            }
            lore.add(component("&7- &f" + stripLegacy(definition.getName())));
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
        if (node.statuses().isEmpty()) {
            return;
        }
        lines.add(unlocked ? "&8--- &dステータス &8---" : "&8--- ステータス ---");
        for (SkillTreeNodeStatusDefinition status : node.statuses()) {
            lines.add((unlocked ? "&7- &f" : "&8- &7")
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
        if (node.skillIds().isEmpty()) {
            return;
        }
        lines.add(unlocked ? "&8--- &bパッシブ &8---" : "&8--- パッシブ ---");
        for (String rawSkillId : node.skillIds()) {
            if (rawSkillId == null || rawSkillId.isBlank()) {
                continue;
            }
            String skillId = rawSkillId.trim();
            var definition = skillService == null ? null : skillService.registry().getDefinition(skillId);
            if (definition == null) {
                lines.add((unlocked ? "&7- &f" : "&8- &7") + skillId);
                continue;
            }
            lines.add((unlocked ? "&7- &f" : "&8- &7") + stripLegacy(definition.getName()));
            String description = firstSkillDescription(definition);
            if (!description.isBlank()) {
                lines.add("&8  " + stripLegacy(description));
            }
        }
    }

    private @NotNull String firstSkillDescription(@NotNull io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition definition) {
        if (definition.getDescription() != null && !definition.getDescription().isBlank()) {
            return definition.getDescription();
        }
        for (String line : definition.getLore()) {
            if (line != null && !line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private @NotNull String formatNodeStatusModifier(@NotNull SkillTreeNodeStatusDefinition status) {
        if (status.type() == StatusModifierType.SCALAR) {
            double displayValue = status.value() * 100.0D;
            String sign = displayValue > 0.0D ? "+" : "";
            return sign + formatStatusValue(displayValue) + "%";
        }
        return status.statusType().formatSignedValue(status.value());
    }

    private @NotNull String stripLegacy(@NotNull String text) {
        return text.replaceAll("(?i)&[0-9A-FK-OR]", "");
    }

    private @NotNull String resolveNodeDisplayName(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        return unlocked ? node.name() : "&7" + stripLegacy(node.name());
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
        boolean refreshedByPassiveService = false;
        if (passiveSkillService != null) {
            if (addedSkillIds.isEmpty() && removedSkillIds.isEmpty()) {
                passiveSkillService.reconcileNow(astPlayer);
            } else {
                passiveSkillService.reconcileSkillOwnershipDelta(astPlayer, addedSkillIds, removedSkillIds, statusAffected);
            }
            refreshedByPassiveService = true;
        }
        if (statusAffected && statusService != null && !refreshedByPassiveService) {
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

    private void cacheNodePresentation(@NotNull SkillTreeNodeDefinition node) {
        lockedNodeDisplayItems.put(node.id(), createCachedNodeDisplayItem(node, false));
        unlockedNodeDisplayItems.put(node.id(), createCachedNodeDisplayItem(node, true));
        blockedNodeFieldLabels.put(node.id(), createNodeFieldLabel(node, NodePresentationState.BLOCKED));
        availableNodeFieldLabels.put(node.id(), createNodeFieldLabel(node, NodePresentationState.AVAILABLE));
        unlockedNodeFieldLabels.put(node.id(), createNodeFieldLabel(node, NodePresentationState.UNLOCKED));
    }

    private @NotNull ItemStack createCachedNodeDisplayItem(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        ItemStack itemStack = new ItemStack(node.icon());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(resolveNodeDisplayName(node, unlocked)));
            meta.addItemFlags(ItemFlag.values());
            if (unlocked) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            }
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull Component createNodeFieldLabel(
            @NotNull SkillTreeNodeDefinition node,
            @NotNull NodePresentationState presentationState
    ) {
        List<String> lines = new ArrayList<>();
        boolean emphasized = presentationState != NodePresentationState.BLOCKED;
        lines.add(resolveNodeDisplayName(node, presentationState == NodePresentationState.UNLOCKED));
        lines.add(switch (presentationState) {
            case BLOCKED -> "&8State: &7Blocked";
            case AVAILABLE -> "&8State: &aUnlockable";
            case UNLOCKED -> "&8State: &bUnlocked";
        });
        lines.add("&8Left click: &fUnlock");
        lines.add("&8Right click: &fRelock &7(100G)");
        appendNodeFieldStatusLines(lines, node, emphasized);
        appendNodeFieldPassiveLines(lines, node, emphasized);
        if (!node.lore().isEmpty()) {
            lines.add("&8--- Description ---");
            for (String loreLine : node.lore()) {
                lines.add((emphasized ? "&7" : "&8") + stripLegacy(loreLine));
            }
        }
        lines.add("&8nodeId: &7" + node.id());
        lines.add("&8positionId: &7" + node.positionId());
        return component(String.join("\n", lines));
    }

    private boolean canUnlockWithoutState(@NotNull SkillTreeNodeDefinition node) {
        return hasTag(node, ROOT_TAG);
    }

    private void updateLoadingPresentation(@NotNull Player player) {
        Long readyAt = visualReadyAtMillis.get(player.getUniqueId());
        if (readyAt == null) {
            stopLoadingPresentation(player);
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= readyAt) {
            visualReadyAtMillis.remove(player.getUniqueId());
            stopLoadingPresentation(player);
            return;
        }

        BossBar bossBar = loadingBossBars.computeIfAbsent(player.getUniqueId(), ignored -> createLoadingBossBar(player));
        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
        double progress = 1.0D - ((double) (readyAt - now) / (double) VISUAL_DELAY_MILLIS);
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, progress)));
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

    private boolean isAdjacentToUnlockedNode(@NotNull SkillTreePlayerState state, @NotNull String positionId) {
        for (String adjacentPositionId : adjacentPositionIds(positionId)) {
            SkillTreeNodeDefinition adjacent = nodesByPositionId.get(adjacentPositionId);
            if (adjacent != null && state.isUnlocked(adjacent.id())) {
                return true;
            }
        }
        return false;
    }

    private @NotNull Set<String> adjacentPositionIds(@NotNull String positionId) {
        Set<String> result = new LinkedHashSet<>();
        for (SkillTreeEdge edge : edgesByKey.values()) {
            if (positionId.equals(edge.leftPositionId())) {
                result.add(edge.rightPositionId());
            } else if (positionId.equals(edge.rightPositionId())) {
                result.add(edge.leftPositionId());
            }
        }
        return result;
    }

    private boolean hasTag(@NotNull SkillTreeNodeDefinition node, @NotNull String tag) {
        for (String nodeTag : node.tags()) {
            if (nodeTag != null && tag.equalsIgnoreCase(nodeTag.trim())) {
                return true;
            }
        }
        return false;
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
        if (state.isUnlocked(node.id())) {
            return NodePresentationState.UNLOCKED;
        }
        return canUnlockNode(astPlayer, node) ? NodePresentationState.AVAILABLE : NodePresentationState.BLOCKED;
    }

    public enum NodePresentationState {
        BLOCKED,
        AVAILABLE,
        UNLOCKED
    }

    private record DerivedPlayerState(
            @NotNull Set<String> unlockedSkillIds,
            @NotNull Map<StatusType, StatusBonusTotals> statusBonuses
    ) {
        private static final DerivedPlayerState EMPTY = new DerivedPlayerState(Set.of(), Map.of());
    }

    private record StatusBonusTotals(double flat, double scalar) {
        private static final StatusBonusTotals ZERO = new StatusBonusTotals(0.0D, 0.0D);
    }

    /**
     * 譌ｧ螳溯｣・〒菫晏ｭ倥＆繧後※縺励∪縺｣縺溘せ繧ｭ繝ｫ繝・Μ繝ｼ蜿ｯ隕門喧 entity 繧呈祉髯､縺励∪縺吶・     */
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
            if (entity instanceof Item || entity instanceof ItemDisplay || entity instanceof TextDisplay || entity instanceof BlockDisplay) {
                entity.remove();
                removedCount++;
            }
        }
        Logger.log(LogId.W_9001, world.getName(), removedCount);

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
