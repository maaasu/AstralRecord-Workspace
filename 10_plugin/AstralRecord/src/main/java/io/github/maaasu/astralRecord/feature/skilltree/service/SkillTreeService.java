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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
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
    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;

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
    private final Map<UUID, SkillTreePlayerState> playerStates = new HashMap<>();
    private final Set<UUID> dirtyPlayerStates = new LinkedHashSet<>();
    private final Map<UUID, String> connectorLeftSelections = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedHotbars = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, SkillTreeActionBarMode> actionBarModes = new HashMap<>();

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
    /**
     * スキルツリー操作後に HUD を更新するための HUD サービスを設定します。
     *
     * @param playerHudService プレイヤー HUD サービス
     */
     * @param playerHudService 驛｢譎丞ｹｲ・取ｨ抵ｽｹ・ｧ繝ｻ・､驛｢譎｢・ｽ・､驛｢譎｢・ｽ・ｼ HUD 驛｢・ｧ繝ｻ・ｵ驛｢譎｢・ｽ・ｼ驛｢譎∽ｾｭ邵ｺ繝ｻ
     */
    public void setPlayerHudService(@NotNull PlayerHudService playerHudService) {
        this.playerHudService = playerHudService;
    }

    /**
     * 繧ｹ繝・・繧ｿ繧ｹ譖ｴ譁ｰ騾｣謳ｺ蜈医ｒ險ｭ螳壹＠縺ｾ縺吶・     *
     * @param statusService 繧ｹ繝・・繧ｿ繧ｹ繧ｵ繝ｼ繝薙せ
     */
    public void setStatusService(@NotNull StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * 繧ｹ繧ｭ繝ｫ螳夂ｾｩ蜿ら・繧ｵ繝ｼ繝薙せ繧定ｨｭ螳壹＠縺ｾ縺吶・     *
     * @param skillService 繧ｹ繧ｭ繝ｫ繧ｵ繝ｼ繝薙せ
     */
    public void setSkillService(@NotNull SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 繝代ャ繧ｷ繝悶せ繧ｭ繝ｫ蜀崎ｩ穂ｾ｡騾｣謳ｺ蜈医ｒ險ｭ螳壹＠縺ｾ縺吶・     *
     * @param passiveSkillService 繝代ャ繧ｷ繝悶せ繧ｭ繝ｫ繧ｵ繝ｼ繝薙せ
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
        return nodesById.size();
    }

    public void start() {
        purgeSkillTreeVisualEntities();
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
        return worldService.teleportPlayerAsync(player, spawn.get(), () -> applySkillTreeHotbar(player));
    }

    @NotNull
    public CompletableFuture<Boolean> returnToBase(@NotNull Player player) {
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
        return isPlayerModeSkillTree(player) && player.getWorld() == location.getWorld();
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
     * 隗｣謾ｾ貂医∩繝弱・繝臥罰譚･縺ｮ繧ｹ繧ｭ繝ｫ ID 荳隕ｧ繧貞叙蠕励＠縺ｾ縺吶・     *
     * @param astPlayer 繝励Ξ繧､繝､繝ｼ
     * @return 繧ｹ繧ｭ繝ｫ ID 荳隕ｧ
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
     * 隗｣謾ｾ貂医∩繝弱・繝臥罰譚･縺ｮ逶ｴ謗･繧ｹ繝・・繧ｿ繧ｹ陬懈ｭ｣繧貞叙蠕励＠縺ｾ縺吶・     *
     * @param astPlayer 繝励Ξ繧､繝､繝ｼ
     * @param statusType 蟇ｾ雎｡繧ｹ繝・・繧ｿ繧ｹ
     * @param baseValue FLAT 驕ｩ逕ｨ蠕後・蝓ｺ貅門､
     * @return 邱剰｣懈ｭ｣蛟､
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
            } else if (savedHotbars.containsKey(player.getUniqueId()) || actionBarModes.containsKey(player.getUniqueId())) {
                restoreHotbar(player);
            }
        }
    }

    public void restoreHotbar(@NotNull Player player) {
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
    /**
     * プレイヤーがスキルツリー用 HOTBAR 制御を使うべき状態かを返します。
     *
     * @param player 対象プレイヤー
     * @return スキルツリー用 HOTBAR 制御を使う場合は true
     */
     * @param player 髯昴・・ｽ・ｾ鬮ｮ雜｣・ｽ・｡驛｢譎丞ｹｲ・取ｨ抵ｽｹ・ｧ繝ｻ・､驛｢譎｢・ｽ・､驛｢譎｢・ｽ・ｼ
     * @return 驛｢・ｧ繝ｻ・ｹ驛｢・ｧ繝ｻ・ｭ驛｢譎｢・ｽ・ｫ驛｢譏ｴ繝ｻ・取㏍・ｹ譎｢・ｽ・ｼ髯昴・・蛾｡繝ｻHOTBAR 驛｢・ｧ陷代・・ｽ・ｽ繝ｻ・ｿ驍ｵ・ｺ郢晢ｽｻ繝ｻ・ｰ繝ｻ・ｴ髯ｷ・ｷ郢晢ｽｻtrue
     */
    /**
     * スキルツリー用 HOTBAR 入力を処理します。
     *
     * @param player 対象プレイヤー
     * @param slot HOTBAR スロット番号
     * @return 入力を処理した場合は true
     */
     * 驛｢・ｧ繝ｻ・ｹ驛｢・ｧ繝ｻ・ｭ驛｢譎｢・ｽ・ｫ驛｢譏ｴ繝ｻ・取㏍・ｹ譎｢・ｽ・ｼ髯昴・・蛾｡繝ｻHOTBAR 驍ｵ・ｺ繝ｻ・ｮ髯具ｽｻ繝ｻ・ｶ髯溷桁・ｽ・｡驛｢・ｧ繝ｻ・ｹ驛｢譎｢・ｽ・ｭ驛｢譏ｴ繝ｻ郢晢ｽｨ驛｢・ｧ髮区ｧｭ繝ｻ鬨ｾ繝ｻ繝ｻ繝ｻ・ｰ驍ｵ・ｺ繝ｻ・ｾ驍ｵ・ｺ陷ｷ・ｶ・つ郢晢ｽｻ     *
     * @param player 髯昴・・ｽ・ｾ鬮ｮ雜｣・ｽ・｡驛｢譎丞ｹｲ・取ｨ抵ｽｹ・ｧ繝ｻ・､驛｢譎｢・ｽ・､驛｢譎｢・ｽ・ｼ
     * @param slot HOTBAR 驛｢・ｧ繝ｻ・ｹ驛｢譎｢・ｽ・ｭ驛｢譏ｴ繝ｻ郢晢ｽｨ鬨ｾ・｡繝ｻ・ｪ髯ｷ・ｿ繝ｻ・ｷ
     * @return 髯具ｽｻ繝ｻ・ｶ髯溷桁・ｽ・｡驛｢・ｧ繝ｻ・ｹ驛｢譎｢・ｽ・ｭ驛｢譏ｴ繝ｻ郢晢ｽｨ驍ｵ・ｺ繝ｻ・ｨ驍ｵ・ｺ陷会ｽｱ遯ｶ・ｻ髯ｷ繝ｻ・ｽ・ｦ鬨ｾ繝ｻ繝ｻ繝ｻ・ｰ驍ｵ・ｺ雋・ｽｷ繝ｻ・ｰ繝ｻ・ｴ髯ｷ・ｷ郢晢ｽｻtrue
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
            lore.add(component("&8菴咲ｽｮID: &f" + node.positionId()));
            lore.add(component("&8謇謖ヾP: &f" + state.skillPoints()));
            lore.add(component(unlocked ? "&6笳・隗｣謾ｾ貂医∩繝弱・繝・笳・ : "&7笳・譛ｪ隗｣謾ｾ繝弱・繝・笳・));
            lore.add(component("&e蟾ｦ繧ｯ繝ｪ繝・け&7縺ｧ繝弱・繝峨ｒ隗｣謾ｾ"));
            lore.add(component("&6蜿ｳ繧ｯ繝ｪ繝・け&7縺ｧ繝弱・繝峨ｒ隗｣髯､ &8(100G)"));
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
        lore.add(component("&b邏舌▼縺上せ繧ｭ繝ｫ"));
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
                lore.add(component("&7- &f" + skillId + " &8(譛ｪ隱ｭ霎ｼ)"));
                continue;
            }
            String kindLabel = definition.getKind().isPassive() ? "繝代ャ繧ｷ繝・ : "逋ｺ蜍・;
            String triggerLabel = definition.getKind().isPassive()
                    ? (definition.getPassiveBindRequired() ? "隕√ヰ繧､繝ｳ繝・ : "謇謖√・縺ｿ")
                    : "繧｢繧ｯ繝・ぅ繝・;
            lore.add(component("&7- &f" + stripLegacy(definition.getName()) + " &8[" + kindLabel + " / " + triggerLabel + "]"));
            lore.add(component("&8  ID: " + definition.getId()));
        }
    }

    private void appendNodeStatusInfo(@NotNull List<Component> lore, @NotNull SkillTreeNodeDefinition node) {
        if (node.statuses().isEmpty()) {
            return;
        }
        lore.add(component(""));
        lore.add(component("&a繝弱・繝峨せ繝・・繧ｿ繧ｹ"));
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
            meta.displayName(component("&7繧ｹ繧ｭ繝ｫ繝弱・繝画ュ蝣ｱ"));
            meta.lore(List.of(
                    component("&8隕也ｷ壼・縺ｫ繧ｹ繧ｭ繝ｫ繝弱・繝峨′縺ゅｊ縺ｾ縺帙ｓ"),
                    component("&7繝弱・繝峨∈辣ｧ貅悶ｒ蜷医ｏ縺帙ｋ縺ｨ隧ｳ邏ｰ縺瑚｡ｨ遉ｺ縺輔ｌ縺ｾ縺・),
                    component(""),
                    component("&eslot7 &7: ActionBar陦ｨ遉ｺ蛻・崛"),
                    component("&cslot8 &7: 諡轤ｹ縺ｸ謌ｻ繧・)
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
            meta.displayName(component("&c諡轤ｹ縺ｸ謌ｻ繧・));
            meta.lore(List.of(component("&7繧ｹ繧ｭ繝ｫ繝・Μ繝ｼ繧帝屬繧後※蜈・・蝣ｴ謇縺ｸ謌ｻ繧翫∪縺・)));
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
                    ? "&bActionBar陦ｨ遉ｺ: &f繝ｪ繧ｽ繝ｼ繧ｹHUD"
                    : "&bActionBar陦ｨ遉ｺ: &f繝弱・繝峨ぎ繧､繝・));
            meta.lore(List.of(
                    component("&7繧ｯ繝ｪ繝・け縺ｧ陦ｨ遉ｺ蜀・ｮｹ繧貞・繧頑崛縺・),
                    component(resourceStatus ? "&8迴ｾ蝨ｨ: &fHP / MP / ENG" : "&8迴ｾ蝨ｨ: &f繝弱・繝峨ぎ繧､繝・)
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

        for (Entity entity : List.copyOf(world.getEntities())) {
            if (entity instanceof Item || entity instanceof TextDisplay) {
                entity.remove();
            }
        }

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
