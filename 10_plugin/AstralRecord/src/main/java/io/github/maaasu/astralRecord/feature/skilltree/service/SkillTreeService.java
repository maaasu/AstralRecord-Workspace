package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
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
 * 郢ｧ・ｹ郢ｧ・ｭ郢晢ｽｫ郢昴・ﾎ懃ｹ晢ｽｼ邵ｺ・ｮ郢晄ｧｭ縺帷ｹｧ・ｿ邵ｲ竏ｵ・ｧ遏ｩﾂ・ｰ邵ｲ竏壹・郢晢ｽｬ郢ｧ・､郢晢ｽ､郢晢ｽｼ霑･・ｶ隲ｷ荵晢ｽ帝お・ｱ陷ｷ蛹ｻ・邵ｺ・ｦ隰・ｽｱ邵ｺ繝ｻ縺礼ｹ晢ｽｼ郢晁侭縺帷ｸｺ・ｧ邵ｺ蜷ｶﾂ繝ｻ */
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
    }

    /**
     * 郢ｧ・ｹ郢ｧ・ｭ郢晢ｽｫ郢昴・ﾎ懃ｹ晢ｽｼ騾包ｽｨ HUD 鬨ｾ・｣隰ｳ・ｺ郢ｧ・ｵ郢晢ｽｼ郢晁侭縺帷ｹｧ螳夲ｽｨ・ｭ陞ｳ螢ｹ・邵ｺ・ｾ邵ｺ蜷ｶﾂ繝ｻ     *
     * @param playerHudService 郢晏干ﾎ樒ｹｧ・､郢晢ｽ､郢晢ｽｼ HUD 郢ｧ・ｵ郢晢ｽｼ郢晁侭縺・
     */
    public void setPlayerHudService(@NotNull PlayerHudService playerHudService) {
        this.playerHudService = playerHudService;
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

    public boolean unlockNode(@NotNull AstPlayer astPlayer, @NotNull SkillTreeNodeDefinition node) {
        SkillTreePlayerState state = state(astPlayer);
        boolean changed = state.unlock(node.id());
        if (changed) {
            markDirty(state);
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
        }
    }

    /**
     * 郢ｧ・ｹ郢ｧ・ｭ郢晢ｽｫ郢昴・ﾎ懃ｹ晢ｽｼ陝・ｉ逡・HOTBAR 郢ｧ雋樞煤陷磯メ・｡・ｨ驕会ｽｺ郢晢ｽｻ隰ｫ蝣ｺ・ｽ諛岩・邵ｺ・ｹ邵ｺ蜥ｲ諞ｾ隲ｷ荵敖ｰ陋ｻ・､陞ｳ螢ｹ・邵ｺ・ｾ邵ｺ蜷ｶﾂ繝ｻ     *
     * @param player 陝・ｽｾ髮趣ｽ｡郢晏干ﾎ樒ｹｧ・､郢晢ｽ､郢晢ｽｼ
     * @return 郢ｧ・ｹ郢ｧ・ｭ郢晢ｽｫ郢昴・ﾎ懃ｹ晢ｽｼ陝・ｉ逡・HOTBAR 郢ｧ蜑・ｽｽ・ｿ邵ｺ繝ｻ・ｰ・ｴ陷ｷ繝ｻtrue
     */
    public boolean shouldUseSkillTreeHotbar(@NotNull Player player) {
        return isPlayerModeSkillTree(player) && !hasInteractiveGuiOpen(player);
    }

    /**
     * 郢ｧ・ｹ郢ｧ・ｭ郢晢ｽｫ郢昴・ﾎ懃ｹ晢ｽｼ陝・ｉ逡・HOTBAR 邵ｺ・ｮ陋ｻ・ｶ陟包ｽ｡郢ｧ・ｹ郢晢ｽｭ郢昴・繝ｨ郢ｧ雋槭・騾・・・邵ｺ・ｾ邵ｺ蜷ｶﾂ繝ｻ     *
     * @param player 陝・ｽｾ髮趣ｽ｡郢晏干ﾎ樒ｹｧ・､郢晢ｽ､郢晢ｽｼ
     * @param slot HOTBAR 郢ｧ・ｹ郢晢ｽｭ郢昴・繝ｨ騾｡・ｪ陷ｿ・ｷ
     * @return 陋ｻ・ｶ陟包ｽ｡郢ｧ・ｹ郢晢ｽｭ郢昴・繝ｨ邵ｺ・ｨ邵ｺ蜉ｱ窶ｻ陷・ｽｦ騾・・・邵ｺ貅ｷ・ｰ・ｴ陷ｷ繝ｻtrue
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
            if (!node.lore().isEmpty()) {
                lore.add(component(""));
                node.lore().forEach(line -> lore.add(component("&7" + line)));
            }
            meta.lore(lore);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
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
     * 旧実装で保存されてしまったスキルツリー可視化 entity を掃除します。
     */
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
