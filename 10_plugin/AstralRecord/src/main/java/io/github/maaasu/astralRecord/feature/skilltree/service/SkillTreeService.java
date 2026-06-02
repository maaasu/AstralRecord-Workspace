package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * スキルツリーのマスタ、構造、プレイヤー状態を統合して扱うサービスです。
 */
public class SkillTreeService {
    public static final String SKILL_TREE_WORLD_ID = "skill_tree";
    public static final long RELOCK_GOLD_COST = 100L;

    private static final double TARGET_DISTANCE = 8.0D;
    private static final double TARGET_RADIUS_SQ = 0.9D * 0.9D;
    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;

    private final Plugin plugin;
    private final WorldService worldService;
    private InventoryService inventoryService;
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

    public boolean teleportToSkillTree(@NotNull AstPlayer astPlayer) {
        Optional<Location> spawn = resolveSkillTreeSpawn();
        if (spawn.isEmpty()) {
            return false;
        }
        returnLocations.put(astPlayer.getBukkit().getUniqueId(), astPlayer.getBukkit().getLocation().clone());
        boolean teleported = astPlayer.getBukkit().teleport(spawn.get());
        if (teleported) {
            applySkillTreeHotbar(astPlayer.getBukkit());
        }
        return teleported;
    }

    public boolean returnToBase(@NotNull Player player) {
        Location saved = returnLocations.remove(player.getUniqueId());
        if (saved != null && saved.getWorld() != null) {
            restoreHotbar(player);
            return player.teleport(saved);
        }
        for (WorldMasterData data : worldService.getAll()) {
            if (data.worldType() != WorldType.BASE) {
                continue;
            }
            Location spawn = worldService.resolveSpawnLocation(data);
            if (spawn != null) {
                restoreHotbar(player);
                return player.teleport(spawn);
            }
        }
        return false;
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
            meta.displayName(component("&dスキルノードポジション設定&7[&f" + positionId + "&7]"));
            meta.lore(List.of(component("&7右クリックで設置 / 左クリックで削除"), component("&7positionId: &f" + positionId)));
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
            meta.displayName(component("&bスキルノード接続"));
            meta.lore(List.of(component("&7左クリックでノード1"), component("&7右クリックでノード2を選択して接続/解除")));
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
        renderSkillTreeHotbar(player);
    }

    public void renderSkillTreeHotbar(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !isPlayerModeSkillTree(player)) {
            return;
        }
        player.getInventory().setHeldItemSlot(0);
        Optional<SkillTreeNodeDefinition> targeted = findTargetedNode(player);
        player.getInventory().setItem(0, targeted.map(node -> createNodeHotbarItem(astPlayer, node)).orElseGet(this::createEmptyTargetItem));
        for (int slot = 1; slot <= 7; slot++) {
            player.getInventory().setItem(slot, createDummyItem());
        }
        player.getInventory().setItem(8, createReturnItem());
    }

    private void tickPlayerHotbars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPlayerModeSkillTree(player)) {
                renderSkillTreeHotbar(player);
                player.sendActionBar(PlayerMsgResource.formatComponent(PlayerMsgId.P_5833.getId()));
            } else if (savedHotbars.containsKey(player.getUniqueId())) {
                restoreHotbar(player);
            }
        }
    }

    public void restoreHotbar(@NotNull Player player) {
        ItemStack[] saved = savedHotbars.remove(player.getUniqueId());
        if (saved == null) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            player.getInventory().setItem(slot, saved[slot]);
        }
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
            lore.add(component("&8Position: &f" + node.positionId()));
            lore.add(component("&8SkillPoint: &f" + state.skillPoints()));
            lore.add(component(unlocked ? "&6解放済み" : "&7未解放"));
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
            meta.displayName(component("&7視線先にスキルノードがありません"));
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
            meta.displayName(component("&c拠点に戻る"));
            meta.lore(List.of(component("&7スキルツリーから退出します")));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }
}
