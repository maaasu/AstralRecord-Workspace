package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketDisplayHandle;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketDisplayService;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketItemDisplayOptions;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketTextDisplayOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SkillTreeVisualizer {
    private static final long INTERVAL_TICKS = 10L;
    private static final double VIEW_DISTANCE = 96.0D;
    private static final double VIEW_DISTANCE_SQ = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final double EDGE_STEP = 0.45D;
    private static final double ITEM_Y_OFFSET = 0.15D;
    private static final double EDGE_Y_OFFSET = 0.65D;
    private static final double TEXT_Y_OFFSET = 1.2D;
    private static final float NODE_TEXT_SCALE = 0.85F;
    private static final float ADMIN_TEXT_SCALE = 0.72F;
    private static final float EDGE_TEXT_SCALE = 0.42F;

    private final Plugin plugin;
    private final SkillTreeService service;
    private final Map<UUID, ViewerScene> viewerScenes = new HashMap<>();
    private BukkitTask task;

    SkillTreeVisualizer(@NotNull Plugin plugin, @NotNull SkillTreeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, INTERVAL_TICKS);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        viewerScenes.values().forEach(ViewerScene::destroy);
        viewerScenes.clear();
    }

    private void tick() {
        Set<UUID> onlineViewerIds = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            onlineViewerIds.add(playerId);

            RenderMode mode = resolveMode(player);
            if (mode == RenderMode.HIDDEN) {
                removeScene(playerId);
                continue;
            }

            ViewerScene scene = viewerScenes.computeIfAbsent(playerId, ignored -> new ViewerScene(player));
            scene.resetIfWorldChanged(player);
            scene.begin();
            renderPositions(player, scene, mode);
            renderEdges(player, scene, mode);
            scene.end();
            if (scene.isEmpty()) {
                removeScene(playerId);
            }
        }

        viewerScenes.entrySet().removeIf(entry -> {
            if (onlineViewerIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().destroy();
            return true;
        });
    }

    private void removeScene(@NotNull UUID playerId) {
        ViewerScene scene = viewerScenes.remove(playerId);
        if (scene != null) {
            scene.destroy();
        }
    }

    private @NotNull RenderMode resolveMode(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (service.isAdminMode(astPlayer)) {
            return RenderMode.ADMIN;
        }
        if (service.isPlayerModeSkillTree(player) && service.isSkillTreeVisualReady(player)) {
            return RenderMode.PLAYER;
        }
        return RenderMode.HIDDEN;
    }

    private void renderPositions(
            @NotNull Player player,
            @NotNull ViewerScene scene,
            @NotNull RenderMode mode
    ) {
        for (SkillTreePosition position : service.getPositions()) {
            Location location = position.toLocation();
            if (!isVisibleTo(player, location)) {
                continue;
            }

            if (mode == RenderMode.ADMIN) {
                renderAdminPosition(scene, position.positionId(), location);
                continue;
            }

            SkillTreeNodeDefinition node = service.getNodeByPositionId(position.positionId());
            if (node != null) {
                renderPlayerNode(player, scene, position.positionId(), location, node);
            }
        }
    }

    private void renderAdminPosition(
            @NotNull ViewerScene scene,
            @NotNull String positionId,
            @NotNull Location location
    ) {
        String key = "admin:position:" + positionId;
        scene.upsertItem(key + ":item", itemLocation(location), new ItemStack(Material.ARMOR_STAND));
        scene.upsertText(key + ":marker", textLocation(location), component("&d*"), ADMIN_TEXT_SCALE);
        scene.upsertText(key + ":label", location.clone().add(0.0D, TEXT_Y_OFFSET + 0.45D, 0.0D), component("&d" + positionId), ADMIN_TEXT_SCALE);
    }

    private void renderPlayerNode(
            @NotNull Player player,
            @NotNull ViewerScene scene,
            @NotNull String positionId,
            @NotNull Location location,
            @NotNull SkillTreeNodeDefinition node
    ) {
        boolean unlocked = isUnlocked(player, node);
        String key = "node:" + positionId;
        scene.upsertItem(key + ":item", itemLocation(location), service.createNodeDisplayItem(node, unlocked));
        scene.upsertText(key + ":label", textLocation(location), service.nodeName(node, unlocked), NODE_TEXT_SCALE);
    }

    private void renderEdges(
            @NotNull Player player,
            @NotNull ViewerScene scene,
            @NotNull RenderMode mode
    ) {
        for (SkillTreeEdge edge : service.getEdges()) {
            SkillTreePosition left = service.getPosition(edge.leftPositionId());
            SkillTreePosition right = service.getPosition(edge.rightPositionId());
            if (left == null || right == null) {
                continue;
            }

            Location leftLocation = left.toLocation();
            Location rightLocation = right.toLocation();
            if (leftLocation == null
                    || rightLocation == null
                    || leftLocation.getWorld() == null
                    || leftLocation.getWorld() != rightLocation.getWorld()) {
                continue;
            }

            Location midpoint = interpolate(leftLocation, rightLocation, 0.5D);
            if (!isVisibleTo(player, midpoint)) {
                continue;
            }

            EdgeColor color = resolveEdgeColor(player, edge, mode);
            if (color == EdgeColor.HIDDEN) {
                continue;
            }
            renderEdge(scene, edge, leftLocation, rightLocation, color);
        }
    }

    private @NotNull EdgeColor resolveEdgeColor(
            @NotNull Player player,
            @NotNull SkillTreeEdge edge,
            @NotNull RenderMode mode
    ) {
        if (mode == RenderMode.ADMIN) {
            return EdgeColor.ADMIN;
        }

        SkillTreeNodeDefinition leftNode = service.getNodeByPositionId(edge.leftPositionId());
        SkillTreeNodeDefinition rightNode = service.getNodeByPositionId(edge.rightPositionId());
        if (leftNode == null || rightNode == null) {
            return EdgeColor.HIDDEN;
        }

        boolean leftUnlocked = isUnlocked(player, leftNode);
        boolean rightUnlocked = isUnlocked(player, rightNode);
        if (leftUnlocked && rightUnlocked) {
            return EdgeColor.YELLOW;
        }
        if (leftUnlocked || rightUnlocked) {
            return EdgeColor.WHITE;
        }
        return EdgeColor.GRAY;
    }

    private void renderEdge(
            @NotNull ViewerScene scene,
            @NotNull SkillTreeEdge edge,
            @NotNull Location left,
            @NotNull Location right,
            @NotNull EdgeColor color
    ) {
        int count = pointCount(left, right);
        Component text = component(color.colorCode + "*");
        for (int i = 1; i < count; i++) {
            Location location = interpolate(left, right, (double) i / count);
            scene.upsertText("edge:" + edge.key() + ":" + i, location, text, EDGE_TEXT_SCALE);
        }
    }

    private boolean isUnlocked(@NotNull Player player, @NotNull SkillTreeNodeDefinition node) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }
        SkillTreePlayerState state = service.state(astPlayer);
        return state.isUnlocked(node.id());
    }

    private boolean isVisibleTo(@NotNull Player player, @Nullable Location location) {
        return location != null
                && location.getWorld() != null
                && player.getWorld() == location.getWorld()
                && player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQ;
    }

    private @NotNull Location itemLocation(@NotNull Location location) {
        return location.clone().add(0.0D, ITEM_Y_OFFSET, 0.0D);
    }

    private @NotNull Location textLocation(@NotNull Location location) {
        return location.clone().add(0.0D, TEXT_Y_OFFSET, 0.0D);
    }

    private @NotNull Location interpolate(@NotNull Location left, @NotNull Location right, double t) {
        Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
        return vector.toLocation(left.getWorld()).add(0.0D, EDGE_Y_OFFSET, 0.0D);
    }

    private int pointCount(@NotNull Location left, @NotNull Location right) {
        return Math.max(1, (int) Math.ceil(left.distance(right) / EDGE_STEP));
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private enum RenderMode {
        HIDDEN,
        ADMIN,
        PLAYER
    }

    private enum EdgeColor {
        HIDDEN(""),
        ADMIN("&d"),
        GRAY("&7"),
        WHITE("&f"),
        YELLOW("&e");

        private final String colorCode;

        EdgeColor(@NotNull String colorCode) {
            this.colorCode = colorCode;
        }
    }

    private static final class ViewerScene {
        private final PacketDisplayService displayService;
        private final Map<String, SceneEntry> entries = new HashMap<>();
        private final Set<String> activeKeys = new HashSet<>();
        private UUID worldId;

        private ViewerScene(@NotNull Player viewer) {
            this.displayService = new PacketDisplayService(viewer);
            this.worldId = viewer.getWorld().getUID();
        }

        private void resetIfWorldChanged(@NotNull Player viewer) {
            UUID currentWorldId = viewer.getWorld().getUID();
            if (worldId.equals(currentWorldId)) {
                return;
            }
            destroy();
            worldId = currentWorldId;
        }

        private void begin() {
            activeKeys.clear();
        }

        private void upsertText(
                @NotNull String key,
                @NotNull Location location,
                @NotNull Component text,
                float scale
        ) {
            activeKeys.add(key);
            SceneEntry entry = entries.get(key);
            if (entry == null || entry.type != SceneEntryType.TEXT) {
                destroy(entry);
                PacketDisplayHandle handle = displayService.spawnText(location, PacketTextDisplayOptions.skillTree(text, scale));
                entries.put(key, new SceneEntry(SceneEntryType.TEXT, handle));
                return;
            }
            entry.handle.teleport(location);
            entry.handle.updateText(text);
        }

        private void upsertItem(@NotNull String key, @NotNull Location location, @NotNull ItemStack itemStack) {
            activeKeys.add(key);
            SceneEntry entry = entries.get(key);
            if (entry == null || entry.type != SceneEntryType.ITEM) {
                destroy(entry);
                PacketDisplayHandle handle = displayService.spawnItem(location, PacketItemDisplayOptions.skillTree(itemStack));
                entries.put(key, new SceneEntry(SceneEntryType.ITEM, handle));
                return;
            }
            entry.handle.teleport(location);
            entry.handle.updateItem(itemStack);
        }

        private void end() {
            entries.entrySet().removeIf(entry -> {
                if (activeKeys.contains(entry.getKey())) {
                    return false;
                }
                entry.getValue().handle.destroy();
                return true;
            });
        }

        private boolean isEmpty() {
            return entries.isEmpty();
        }

        private void destroy() {
            for (SceneEntry entry : entries.values()) {
                entry.handle.destroy();
            }
            entries.clear();
            activeKeys.clear();
        }

        private void destroy(@Nullable SceneEntry entry) {
            if (entry != null) {
                entry.handle.destroy();
            }
        }
    }

    private enum SceneEntryType {
        TEXT,
        ITEM
    }

    private record SceneEntry(@NotNull SceneEntryType type, @NotNull PacketDisplayHandle handle) {
    }
}
