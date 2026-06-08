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
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.InventoryType;
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

    private static final int HOTBAR_TARGET_SLOT = 0;
    private static final int HOTBAR_ACTION_BAR_TOGGLE_SLOT = 7;
    private static final int HOTBAR_RETURN_SLOT = 8;
    private static final double TARGET_DISTANCE = 8.0D;
    private static final double TARGET_RADIUS_SQ = 0.9D * 0.9D;
    private static final double TARGET_HIGHLIGHT_RADIUS = 0.30D;
    private static final double TARGET_HIGHLIGHT_Y = 0.46D;
    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;
    private static final long VISUAL_DELAY_MILLIS = 3_000L;

    private final Plugin plugin;
    private final WorldService worldService;
    private InventoryService inventoryService;
    private PlayerHudService playerHudService;
    private StatusService statusService;
    private SkillService skillService;
    private PassiveSkillService passiveSkillService;
    private final ParticleDisplayService particleDisplayService = new ParticleDisplayService();
    private final SkillTreeNodeRepository nodeRepository;
    private final SkillTreeStructureRepository structureRepository;
    private final SkillTreePlayerStateRepository playerStateRepository;
    private final NamespacedKey positionItemKey;
    private final NamespacedKey connectorItemKey;
    private final Map<String, SkillTreeNodeDefinition> nodesById = new LinkedHashMap<>();
    private final Map<String, SkillTreeNodeDefinition> nodesByPositionId = new LinkedHashMap<>();
    private final Map<String, SkillTreePosition> positionsById = new LinkedHashMap<>();
    private final Map<String, SkillTreeEdge> edgesByKey = new LinkedHashMap<>();
    private final Map<UUID, SkillTreePlayerState> playerStates = new HashMap<>();
    private final Set<UUID> dirtyPlayerStates = new LinkedHashSet<>();
    private final Map<UUID, String> connectorLeftSelections = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedHotbars = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, SkillTreeActionBarMode> actionBarModes = new HashMap<>();
    private final Map<UUID, Long> visualReadyAtMillis = new HashMap<>();
    private final Map<UUID, BossBar> loadingBossBars = new HashMap<>();

    private BukkitTask saveTask;
    private BukkitTask hotbarTask;
    private SkillTreeVisualizer visualizer;
    private boolean structureDirty;

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
        for (SkillTreeNodeDefinition node : nodeRepository.findAll()) {
            nodesById.put(node.id(), node);
            nodesByPositionId.put(node.positionId(), node);
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
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
        }
        if (hotbarTask == null) {
            hotbarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayerHotbars, 1L, 5L);
        }
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
        if (hotbarTask != null) {
            hotbarTask.cancel();
            hotbarTask = null;
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
        Player player = astPlayer.getBukkit();
        returnLocations.put(player.getUniqueId(), player.getLocation().clone());
        visualReadyAtMillis.put(player.getUniqueId(), System.currentTimeMillis() + VISUAL_DELAY_MILLIS);
        return worldService.teleportPlayerAsync(player, spawn.get(), () -> applySkillTreeHotbar(player));
    }

    @NotNull
    public CompletableFuture<Boolean> returnToBase(@NotNull Player player) {
        visualReadyAtMillis.remove(player.getUniqueId());
        Location saved = returnLocations.remove(player.getUniqueId());
        if (saved != null && saved.getWorld() != null) {
            return worldService.teleportPlayerAsync(player, saved, () -> restoreHotbar(player));
        }
        for (WorldMasterData data : worldService.getAll()) {
            if (data.worldType() != WorldType.BASE) {
                continue;
            }
            Location spawn = worldService.resolveSpawnLocation(data);
            if (spawn != null) {
                return worldService.teleportPlayerAsync(player, spawn, () -> restoreHotbar(player));
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

    /**
     * ノード解除に必要な Gold を支払える状態かを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 解除コストを支払えるなら {@code true}
     */
    public boolean canAffordRelock(@NotNull AstPlayer astPlayer) {
        return inventoryService != null
                && inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), io.github.maaasu.astralRecord.feature.item.service.ItemService.DEFAULT_CURRENCY_ITEM_ID)
                + inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), io.github.maaasu.astralRecord.feature.item.service.ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID)
                >= RELOCK_GOLD_COST;
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
        return true;
    }

    public boolean removePosition(@NotNull String positionId) {
        boolean removed = positionsById.remove(positionId) != null;
        edgesByKey.entrySet().removeIf(entry -> entry.getValue().contains(positionId));
        if (removed) {
            structureDirty = true;
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
        return playerStates.computeIfAbsent(accountId, playerStateRepository::load);
    }

    public void markDirty(@NotNull SkillTreePlayerState state) {
        dirtyPlayerStates.add(state.accountId());
    }

    /**
     * 解放済みノードに紐づくスキル ID 一覧を返します。
     */
    public @NotNull Set<String> getUnlockedSkillIds(@NotNull AstPlayer astPlayer) {
        SkillTreePlayerState state = state(astPlayer);
        Set<String> skillIds = new LinkedHashSet<>();
        for (String nodeId : state.unlockedNodeIds()) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node == null) {
                continue;
            }
            for (String skillId : node.skillIds()) {
                if (skillId != null && !skillId.isBlank()) {
                    skillIds.add(skillId.trim());
                }
            }
        }
        return skillIds;
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
        double flat = 0.0D;
        double scalar = 0.0D;
        for (String nodeId : state.unlockedNodeIds()) {
            SkillTreeNodeDefinition node = nodesById.get(nodeId);
            if (node == null) {
                continue;
            }
            for (SkillTreeNodeStatusDefinition status : node.statuses()) {
                if (status.statusType() != statusType) {
                    continue;
                }
                if (status.type() == StatusModifierType.SCALAR) {
                    scalar += status.value();
                } else {
                    flat += status.value();
                }
            }
        }
        return flat + (baseValue * scalar);
    }

    public boolean unlockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        boolean changed = state.unlock(node.id());
        if (changed) {
            markDirty(state);
            refreshDerivedState(astPlayer);
        }
        return changed;
    }

    public boolean relockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        if (!state.isUnlocked(node.id())) {
            return false;
        }
        if (inventoryService == null || !inventoryService.consumeGold(astPlayer.getAccount().getUuid(), RELOCK_GOLD_COST)) {
            return false;
        }
        boolean changed = state.relock(node.id());
        if (changed) {
            markDirty(state);
            refreshDerivedState(astPlayer);
        }
        return changed;
    }

    public void setSkillPoints(@NotNull AstPlayer astPlayer, int points) {
        SkillTreePlayerState state = state(astPlayer);
        state.setSkillPoints(points);
        markDirty(state);
    }

    public void addSkillPoints(@NotNull AstPlayer astPlayer, int points) {
        SkillTreePlayerState state = state(astPlayer);
        state.addSkillPoints(points);
        markDirty(state);
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
        if (!isPlayerModeSkillTree(player)) {
            return;
        }
        savedHotbars.computeIfAbsent(player.getUniqueId(), ignored -> player.getInventory().getContents().clone());
        registerSkillTreeHud(player);
        if (shouldUseSkillTreeHotbar(player)) {
            renderSkillTreeHotbar(player);
        }
    }

    public void renderSkillTreeHotbar(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !shouldUseSkillTreeHotbar(player)) {
            return;
        }
        player.getInventory().setHeldItemSlot(HOTBAR_TARGET_SLOT);
        Optional<SkillTreeNodeDefinition> targeted = findTargetedNode(player);
        player.getInventory().setItem(HOTBAR_TARGET_SLOT, targeted.map(node -> createNodeHotbarItem(astPlayer, node)).orElseGet(this::createEmptyTargetItem));
        for (int slot = 1; slot < HOTBAR_ACTION_BAR_TOGGLE_SLOT; slot++) {
            player.getInventory().setItem(slot, createDummyItem());
        }
        player.getInventory().setItem(HOTBAR_ACTION_BAR_TOGGLE_SLOT, createActionBarToggleItem(player));
        player.getInventory().setItem(HOTBAR_RETURN_SLOT, createReturnItem());
    }

    private void tickPlayerHotbars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPlayerModeSkillTree(player)) {
                applySkillTreeHotbar(player);
                updateLoadingPresentation(player);
                updateTargetHighlight(player);
            } else if (savedHotbars.containsKey(player.getUniqueId()) || actionBarModes.containsKey(player.getUniqueId())) {
                restoreHotbar(player);
            }
        }
    }

    public void restoreHotbar(@NotNull Player player) {
        visualReadyAtMillis.remove(player.getUniqueId());
        stopLoadingPresentation(player);
        ItemStack[] saved = savedHotbars.remove(player.getUniqueId());
        actionBarModes.remove(player.getUniqueId());
        if (playerHudService != null) {
            playerHudService.clearPrimaryActionBarRenderer(player.getUniqueId());
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                playerHudService.refreshActionBar(astPlayer);
            }
        }
        if (saved == null) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            player.getInventory().setItem(slot, saved[slot]);
        }
    }

    /**
     * スキルツリー中に専用 HOTBAR を使うか判定します。
     */
    public boolean shouldUseSkillTreeHotbar(@NotNull Player player) {
        return isPlayerModeSkillTree(player) && !hasInteractiveGuiOpen(player);
    }

    /**
     * 専用 HOTBAR の操作を処理します。
     */
    public boolean handleSkillTreeHotbarControl(@NotNull Player player, int slot) {
        if (!shouldUseSkillTreeHotbar(player)) {
            return false;
        }
        if (slot == HOTBAR_ACTION_BAR_TOGGLE_SLOT) {
            toggleActionBarMode(player);
            return true;
        }
        if (slot == HOTBAR_RETURN_SLOT) {
            returnToBase(player).thenAccept(success -> {
                if (!success) {
                    player.sendMessage(PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                }
            });
            return true;
        }
        return false;
    }

    @NotNull
    public ItemStack createNodeDisplayItem(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        ItemStack itemStack = new ItemStack(node.icon());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component((unlocked ? "&6&l" : "&7") + node.name()));
            meta.addItemFlags(ItemFlag.values());
            if (unlocked) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            }
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @NotNull
    public Component nodeName(@NotNull SkillTreeNodeDefinition node, boolean unlocked) {
        return component((unlocked ? "&6&l" : "&7") + node.name());
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
        }
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
        ItemStack itemStack = createNodeDisplayItem(node, unlocked);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            var lore = new java.util.ArrayList<Component>();
            lore.add(component("&8ID: &f" + node.id()));
            lore.add(component("&8位置ID: &f" + node.positionId()));
            lore.add(component("&8所持SP: &f" + state.skillPoints()));
            lore.add(component(unlocked ? "&6◆ 解放済みノード ◆" : "&7◆ 未解放ノード ◆"));
            lore.add(component("&e左クリック&7でノードを解放"));
            lore.add(component("&6右クリック&7でノードを解除 &8(100G)"));
            appendNodeSkillInfo(lore, node);
            appendNodeStatusInfo(lore, node);
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
        lore.add(component(""));
        lore.add(component("&aノードステータス"));
        for (SkillTreeNodeStatusDefinition status : node.statuses()) {
            boolean scalar = status.type() == StatusModifierType.SCALAR;
            double displayValue = scalar ? status.value() * 100.0D : status.value();
            lore.add(component("&7- &f" + status.statusType().name() + "&7: &a+" + formatStatusValue(displayValue) + (scalar ? "%" : "")));
        }
    }

    private @NotNull String stripLegacy(@NotNull String text) {
        return text.replaceAll("(?i)&[0-9A-FK-OR]", "");
    }

    private @NotNull String formatStatusValue(double value) {
        if (value == Math.rint(value)) {
            return String.format(java.util.Locale.ROOT, "%.0f", value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private void refreshDerivedState(@NotNull AstPlayer astPlayer) {
        if (passiveSkillService != null) {
            passiveSkillService.reconcileNow(astPlayer);
            return;
        }
        if (statusService != null) {
            statusService.refreshStatus(astPlayer);
        }
    }

    @NotNull
    private ItemStack createEmptyTargetItem() {
        ItemStack itemStack = new ItemStack(Material.GRAY_DYE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component("&7スキルノード情報"));
            meta.lore(List.of(
                    component("&8視線先にスキルノードがありません"),
                    component("&7ノードへ照準を合わせると詳細が表示されます"),
                    component(""),
                    component("&eslot7 &7: ActionBar表示切替"),
                    component("&cslot8 &7: 拠点へ戻る")
            ));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @NotNull
    private ItemStack createDummyItem() {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @NotNull
    private ItemStack createReturnItem() {
        ItemStack itemStack = new ItemStack(Material.RED_BED);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component("&c拠点へ戻る"));
            meta.lore(List.of(component("&7スキルツリーを離れて元の場所へ戻ります")));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @NotNull
    private ItemStack createActionBarToggleItem(@NotNull Player player) {
        SkillTreeActionBarMode mode = actionBarModes.getOrDefault(player.getUniqueId(), SkillTreeActionBarMode.RESOURCE_STATUS);
        boolean resourceStatus = mode == SkillTreeActionBarMode.RESOURCE_STATUS;
        ItemStack itemStack = new ItemStack(resourceStatus ? Material.COMPASS : Material.WRITABLE_BOOK);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(resourceStatus
                    ? "&bActionBar表示: &fリソースHUD"
                    : "&bActionBar表示: &fノードガイド"));
            meta.lore(List.of(
                    component("&7クリックで表示内容を切り替え"),
                    component(resourceStatus ? "&8現在: &fHP / MP / ENG" : "&8現在: &fノードガイド")
            ));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private void registerSkillTreeHud(@NotNull Player player) {
        actionBarModes.putIfAbsent(player.getUniqueId(), SkillTreeActionBarMode.RESOURCE_STATUS);
        if (playerHudService == null) {
            return;
        }
        playerHudService.setPrimaryActionBarRenderer(player.getUniqueId(), this::resolveSkillTreeActionBar);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            playerHudService.refreshActionBar(astPlayer);
        }
    }

    private @Nullable Component resolveSkillTreeActionBar(@NotNull AstPlayer astPlayer) {
        if (!isPlayerModeSkillTree(astPlayer.getBukkit())) {
            return null;
        }
        SkillTreeActionBarMode mode = actionBarModes.getOrDefault(astPlayer.getBukkit().getUniqueId(), SkillTreeActionBarMode.RESOURCE_STATUS);
        return mode == SkillTreeActionBarMode.NODE_GUIDE
                ? PlayerMsgResource.formatComponent(PlayerMsgId.P_5833.getId())
                : null;
    }

    private void toggleActionBarMode(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        SkillTreeActionBarMode next = actionBarModes.getOrDefault(playerId, SkillTreeActionBarMode.RESOURCE_STATUS).toggle();
        actionBarModes.put(playerId, next);
        renderSkillTreeHotbar(player);
        if (playerHudService != null) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                playerHudService.refreshActionBar(astPlayer);
            }
        }
    }

    private boolean hasInteractiveGuiOpen(@NotNull Player player) {
        InventoryType topType = player.getOpenInventory().getTopInventory().getType();
        return topType != InventoryType.CRAFTING && topType != InventoryType.CREATIVE;
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

    private void updateTargetHighlight(@NotNull Player player) {
        if (!shouldUseSkillTreeHotbar(player) || !isSkillTreeVisualReady(player) || particleDisplayService == null) {
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }

        SkillTreeNodeDefinition node = findTargetedNode(player).orElse(null);
        if (node == null) {
            return;
        }

        SkillTreePosition position = getPosition(node.positionId());
        if (position == null) {
            return;
        }

        Location base = position.toLocation();
        if (base == null || base.getWorld() == null) {
            return;
        }

        boolean unlocked = isNodeUnlocked(astPlayer, node);
        boolean canUnlock = !unlocked && hasSkillPoints(astPlayer);
        emitTargetHighlight(astPlayer, base, unlocked, canUnlock);
    }

    private void emitTargetHighlight(
            @NotNull AstPlayer astPlayer,
            @NotNull Location base,
            boolean unlocked,
            boolean canUnlock
    ) {
        long step = System.currentTimeMillis() / 150L;
        double baseAngle = (step % 360L) * (Math.PI / 18.0D);
        for (int i = 0; i < 2; i++) {
            double angle = baseAngle + (Math.PI * i);
            Location point = base.clone().add(
                    Math.cos(angle) * TARGET_HIGHLIGHT_RADIUS,
                    TARGET_HIGHLIGHT_Y + (i == 0 ? 0.05D : -0.01D),
                    Math.sin(angle) * TARGET_HIGHLIGHT_RADIUS
            );
            particleDisplayService.spawnForViewer(
                    astPlayer,
                    point,
                    unlocked
                            ? SharedParticleDefinitions.SKILLTREE_TARGET_UNLOCKED_DUST
                            : canUnlock
                            ? SharedParticleDefinitions.SKILLTREE_TARGET_LOCKED_DUST
                            : SharedParticleDefinitions.SKILLTREE_TARGET_DENIED_DUST
            );
        }
        particleDisplayService.spawnForViewer(astPlayer, base.clone().add(0.0D, 0.70D, 0.0D), SharedParticleDefinitions.SKILLTREE_TARGET_ENCHANT);
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private enum SkillTreeActionBarMode {
        RESOURCE_STATUS,
        NODE_GUIDE;

        private @NotNull SkillTreeActionBarMode toggle() {
            return this == RESOURCE_STATUS ? NODE_GUIDE : RESOURCE_STATUS;
        }
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
            if (entity instanceof Item || entity instanceof ItemDisplay || entity instanceof TextDisplay) {
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
