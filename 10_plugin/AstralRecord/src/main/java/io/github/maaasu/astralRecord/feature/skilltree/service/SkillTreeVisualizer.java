package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
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

/**
 * スキルツリーのノードと接続線を、サーバ実体を作らず viewer ごとのパケット表示で管理します。
 */
final class SkillTreeVisualizer {
    private static final long INTERVAL_TICKS = 10L;
    private static final double VIEW_DISTANCE_SQ = 96.0D * 96.0D;
    private static final double EDGE_STEP = 0.45D;

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
            onlineViewerIds.add(player.getUniqueId());
            ViewerScene scene = viewerScenes.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new ViewerScene(player)
            );
            scene.resetIfWorldChanged(player);
            scene.begin();
            updatePositions(player, scene);
            updateEdges(player, scene);
            scene.end();
            if (scene.isEmpty()) {
                scene.destroy();
                viewerScenes.remove(player.getUniqueId());
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

    private void updatePositions(@NotNull Player player, @NotNull ViewerScene scene) {
        for (SkillTreePosition position : service.getPositions()) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }

            SkillTreeNodeDefinition node = service.getNodeByPositionId(position.positionId());
            PositionViewerState state = resolvePositionState(player, location, node);
            if (state == PositionViewerState.HIDDEN) {
                continue;
            }

            if (state == PositionViewerState.ADMIN_ONLY || state == PositionViewerState.ADMIN_PREVIEW) {
                renderAdminPosition(scene, position.positionId(), location);
                if (state == PositionViewerState.ADMIN_PREVIEW && node != null) {
                    renderNodePosition(scene, position.positionId(), location, node, false);
                }
                continue;
            }

            if (node != null) {
                renderNodePosition(scene, position.positionId(), location, node, state == PositionViewerState.PLAYER_UNLOCKED);
            }
        }
    }

    private @NotNull PositionViewerState resolvePositionState(
            @NotNull Player player,
            @NotNull Location location,
            @Nullable SkillTreeNodeDefinition node
    ) {
        boolean near = player.getWorld() == location.getWorld()
                && player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQ;
        boolean adminVisible = near && service.shouldShowAdminPosition(player, location);
        boolean playerVisible = near && node != null && service.shouldShowPlayerNode(player, location);
        if (adminVisible) {
            return node == null ? PositionViewerState.ADMIN_ONLY : PositionViewerState.ADMIN_PREVIEW;
        }
        if (!playerVisible || node == null) {
            return PositionViewerState.HIDDEN;
        }

        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return PositionViewerState.HIDDEN;
        }
        SkillTreePlayerState state = service.state(astPlayer);
        return state.isUnlocked(node.id()) ? PositionViewerState.PLAYER_UNLOCKED : PositionViewerState.PLAYER_LOCKED;
    }

    private void renderAdminPosition(
            @NotNull ViewerScene scene,
            @NotNull String positionId,
            @NotNull Location location
    ) {
        scene.upsertItem("p:" + positionId + ":admin:item", itemLocation(location), new ItemStack(Material.ARMOR_STAND));
        scene.upsertText("p:" + positionId + ":admin:marker", textLocation(location), component("&d*"));
        scene.upsertText("p:" + positionId + ":admin:text", location.clone().add(0.0D, 1.2D, 0.0D), component("&d" + positionId));
    }

    private void renderNodePosition(
            @NotNull ViewerScene scene,
            @NotNull String positionId,
            @NotNull Location location,
            @NotNull SkillTreeNodeDefinition node,
            boolean unlocked
    ) {
        String stateKey = unlocked ? "unlocked" : "locked";
        scene.upsertItem(
                "p:" + positionId + ":" + stateKey + ":item",
                itemLocation(location),
                service.createNodeDisplayItem(node, unlocked)
        );
        scene.upsertText(
                "p:" + positionId + ":" + stateKey + ":text",
                textLocation(location),
                service.nodeName(node, unlocked)
        );
    }

    private void updateEdges(@NotNull Player player, @NotNull ViewerScene scene) {
        for (SkillTreeEdge edge : service.getEdges()) {
            SkillTreePosition left = service.getPosition(edge.leftPositionId());
            SkillTreePosition right = service.getPosition(edge.rightPositionId());
            if (left == null || right == null) {
                continue;
            }
            Location leftLocation = left.toLocation();
            Location rightLocation = right.toLocation();
            if (leftLocation == null || rightLocation == null || leftLocation.getWorld() != rightLocation.getWorld()) {
                continue;
            }

            EdgeViewerState state = resolveEdgeState(player, edge, leftLocation, rightLocation);
            if (state == EdgeViewerState.HIDDEN) {
                continue;
            }
            renderEdge(scene, edge, leftLocation, rightLocation, state);
        }
    }

    private @NotNull EdgeViewerState resolveEdgeState(
            @NotNull Player player,
            @NotNull SkillTreeEdge edge,
            @NotNull Location left,
            @NotNull Location right
    ) {
        Location midpoint = interpolate(left, right, 0.5D);
        boolean near = player.getWorld() == midpoint.getWorld()
                && player.getLocation().distanceSquared(midpoint) <= VIEW_DISTANCE_SQ;
        if (!near) {
            return EdgeViewerState.HIDDEN;
        }
        if (service.shouldShowAdminPosition(player, midpoint)) {
            return EdgeViewerState.ADMIN;
        }

        SkillTreeNodeDefinition leftNode = service.getNodeByPositionId(edge.leftPositionId());
        SkillTreeNodeDefinition rightNode = service.getNodeByPositionId(edge.rightPositionId());
        if (leftNode == null || rightNode == null || !service.shouldShowPlayerNode(player, midpoint)) {
            return EdgeViewerState.HIDDEN;
        }
        return switch (service.edgeState(player, edge)) {
            case 2 -> EdgeViewerState.YELLOW;
            case 1 -> EdgeViewerState.WHITE;
            default -> EdgeViewerState.GRAY;
        };
    }

    private void renderEdge(
            @NotNull ViewerScene scene,
            @NotNull SkillTreeEdge edge,
            @NotNull Location left,
            @NotNull Location right,
            @NotNull EdgeViewerState state
    ) {
        int count = pointCount(left, right);
        Component text = component(state.colorCode + "*");
        for (int i = 1; i < count; i++) {
            Location location = interpolate(left, right, (double) i / count);
            scene.upsertText("e:" + edge.key() + ":" + i, location, text);
        }
    }

    @NotNull
    private Location itemLocation(@NotNull Location location) {
        return location.clone().add(0.0D, 0.15D, 0.0D);
    }

    @NotNull
    private Location textLocation(@NotNull Location location) {
        return location.clone().add(0.0D, 1.2D, 0.0D);
    }

    @NotNull
    private Location interpolate(@NotNull Location left, @NotNull Location right, double t) {
        Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
        return vector.toLocation(left.getWorld()).add(0.0D, 0.65D, 0.0D);
    }

    private int pointCount(@NotNull Location left, @NotNull Location right) {
        return Math.max(1, (int) Math.ceil(left.distance(right) / EDGE_STEP));
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private enum PositionViewerState {
        HIDDEN,
        ADMIN_ONLY,
        ADMIN_PREVIEW,
        PLAYER_LOCKED,
        PLAYER_UNLOCKED
    }

    private enum EdgeViewerState {
        HIDDEN(""),
        ADMIN("&d"),
        GRAY("&7"),
        WHITE("&f"),
        YELLOW("&e");

        private final String colorCode;

        EdgeViewerState(@NotNull String colorCode) {
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

        private void upsertText(@NotNull String key, @NotNull Location location, @NotNull Component text) {
            activeKeys.add(key);
            SceneEntry entry = entries.get(key);
            if (entry == null || entry.type != SceneEntryType.TEXT) {
                destroy(entry);
                PacketDisplayHandle handle = displayService.spawnText(location, PacketTextDisplayOptions.skillTree(text));
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
